package com.aethergate.service.webhook;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.webhook.WebhookViews.AlertFiringView;
import com.aethergate.service.webhook.WebhookViews.AlertList;
import com.aethergate.service.webhook.WebhookViews.AlertRuleView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Budget alerts: "tell me when the workspace (or a project) has spent N % of its monthly limit".
 *
 * <p>Evaluated after every settlement. Firing is exactly-once per rule and month because the database
 * decides it: {@code INSERT ... ON CONFLICT (rule_id, period) DO NOTHING} lets exactly one of any number of
 * concurrent settlements win, and only the winner writes the webhook to the outbox, in the same transaction.
 */
@Service
public class AlertService {

    public static final int MAX_RULES = 20;
    private static final UUID WORKSPACE = new UUID(0, 0);

    private final JdbcTemplate jdbc;
    private final WebhookOutbox outbox;
    private final Clock clock;

    public AlertService(JdbcTemplate jdbc, WebhookOutbox outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    private record Rule(UUID id, UUID projectId, String projectName, int threshold, boolean enabled) {}

    private record Position(BigDecimal spend, BigDecimal limit) {}

    @TenantScoped(readOnly = true)
    public AlertList list() {
        String period = period();
        Map<UUID, Position> positions = new LinkedHashMap<>();
        List<AlertRuleView> views = rules(false).stream().map(r -> {
            Position p = positions.computeIfAbsent(r.projectId() == null ? WORKSPACE : r.projectId(), k -> position(r.projectId()));
            List<Timestamp> fired = jdbc.queryForList("select fired_at from alert_firings where rule_id = ? and period = ?",
                    Timestamp.class, r.id(), period);
            double used = p.limit() == null || p.limit().signum() == 0 ? 0
                    : p.spend().multiply(BigDecimal.valueOf(100)).divide(p.limit(), 2, RoundingMode.HALF_UP).doubleValue();
            return new AlertRuleView(r.id(), r.projectId(), r.projectId() == null ? "Workspace" : r.projectName(), r.threshold(),
                    r.enabled(), p.spend(), p.limit(), used, fired.isEmpty() ? null : fired.getFirst().toInstant());
        }).toList();
        List<AlertFiringView> recent = jdbc.query("""
                select coalesce(p.name, 'Workspace') as scope, r.threshold_pct, f.spend_usd, f.limit_usd, f.fired_at
                from alert_firings f join alert_rules r on r.tenant_id = f.tenant_id and r.id = f.rule_id
                left join projects p on p.tenant_id = r.tenant_id and p.id = r.project_id
                where f.tenant_id = aethergate.current_tenant_id() order by f.fired_at desc limit 20""",
                (rs, i) -> new AlertFiringView(rs.getString("scope"), rs.getInt("threshold_pct"), rs.getBigDecimal("spend_usd"),
                        rs.getBigDecimal("limit_usd"), rs.getTimestamp("fired_at").toInstant()));
        return new AlertList(views, recent, period);
    }

    /** {@code projectId} null = the workspace's monthly hard limit; otherwise the project's monthly budget. */
    @TenantScoped
    public AlertList create(UUID projectId, int thresholdPct) {
        if (thresholdPct < 1 || thresholdPct > 100) {
            throw new IllegalArgumentException("Threshold must be between 1 and 100 percent");
        }
        if (projectId != null) {
            List<BigDecimal> budget = jdbc.query("select monthly_budget_usd from projects where id = ? and status = 'ACTIVE'",
                    (rs, i) -> rs.getBigDecimal(1), projectId);
            if (budget.isEmpty()) {
                throw new NoSuchElementException("Active project not found");
            }
            if (budget.getFirst() == null) {
                throw new IllegalArgumentException("That project has no monthly budget; set one before alerting on it");
            }
        }
        Integer n = jdbc.queryForObject("select count(*) from alert_rules where tenant_id = aethergate.current_tenant_id()",
                Integer.class);
        if (n != null && n >= MAX_RULES) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_RULES + " alert rules");
        }
        Integer dup = jdbc.queryForObject("""
                select count(*) from alert_rules where tenant_id = aethergate.current_tenant_id()
                and project_id is not distinct from ?::uuid and threshold_pct = ?""", Integer.class, projectId, thresholdPct);
        if (dup != null && dup > 0) {
            throw new ResourceConflictException("An alert at " + thresholdPct + " % already exists for that scope");
        }
        jdbc.update("insert into alert_rules (tenant_id, project_id, threshold_pct) values (?, ?, ?)",
                TenantContext.require(), projectId, thresholdPct);
        evaluate(projectId); // already past the threshold? fire now rather than on the next request
        return list();
    }

    @TenantScoped
    public AlertList setEnabled(UUID id, boolean enabled) {
        if (jdbc.update("update alert_rules set is_enabled = ? where id = ?", enabled, id) == 0) {
            throw new NoSuchElementException("Alert rule not found");
        }
        return list();
    }

    @TenantScoped
    public void delete(UUID id) {
        if (jdbc.update("delete from alert_rules where id = ?", id) == 0) {
            throw new NoSuchElementException("Alert rule not found");
        }
    }

    /**
     * Called after a settlement charged {@code projectId} (null = unassigned). Checks the workspace rules and that
     * project's rules; each crossed threshold fires once per month. Returns how many fired.
     */
    @TenantScoped
    public int evaluate(UUID projectId) {
        List<Rule> candidates = rules(true).stream()
                .filter(r -> r.projectId() == null || r.projectId().equals(projectId)).toList();
        if (candidates.isEmpty()) {
            return 0;
        }
        String period = period();
        Map<UUID, Position> positions = new LinkedHashMap<>();
        int fired = 0;
        for (Rule r : candidates) {
            Position p = positions.computeIfAbsent(r.projectId() == null ? WORKSPACE : r.projectId(), k -> position(r.projectId()));
            if (p.limit() == null || p.limit().signum() <= 0) {
                continue;
            }
            BigDecimal threshold = p.limit().multiply(BigDecimal.valueOf(r.threshold())).divide(BigDecimal.valueOf(100));
            if (p.spend().compareTo(threshold) < 0) {
                continue;
            }
            int inserted = jdbc.update("""
                    insert into alert_firings (tenant_id, rule_id, period, spend_usd, limit_usd) values (?, ?, ?, ?, ?)
                    on conflict (rule_id, period) do nothing""", TenantContext.require(), r.id(), period, p.spend(), p.limit());
            if (inserted == 1) {
                fired++;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("scope", r.projectId() == null ? "workspace" : "project");
                data.put("project_id", r.projectId() == null ? null : r.projectId().toString());
                data.put("project_name", r.projectName());
                data.put("threshold_pct", r.threshold());
                data.put("spend_usd", p.spend());
                data.put("limit_usd", p.limit());
                data.put("period", period);
                outbox.publish(WebhookEvents.BUDGET_THRESHOLD, data);
            }
        }
        return fired;
    }

    private List<Rule> rules(boolean enabledOnly) {
        return jdbc.query("""
                select r.id, r.project_id, p.name, r.threshold_pct, r.is_enabled from alert_rules r
                left join projects p on p.tenant_id = r.tenant_id and p.id = r.project_id
                where r.tenant_id = aethergate.current_tenant_id() and (? = false or r.is_enabled)
                order by r.project_id nulls first, r.threshold_pct""", (rs, i) -> new Rule(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("name"), rs.getInt("threshold_pct"),
                rs.getBoolean("is_enabled")), enabledOnly);
    }

    /** Month-to-date settled spend and the applicable limit (tenant hard limit or project budget). */
    private Position position(UUID projectId) {
        Timestamp since = Timestamp.from(monthStart());
        if (projectId == null) {
            BigDecimal spend = jdbc.queryForObject("""
                    select coalesce(sum(amount_usd), 0) from ledger_entries
                    where tenant_id = aethergate.current_tenant_id() and entry_type = 'SETTLE' and created_at >= ?""",
                    BigDecimal.class, since);
            BigDecimal limit = jdbc.queryForObject("select hard_budget_limit_usd from tenants where id = aethergate.current_tenant_id()",
                    BigDecimal.class);
            return new Position(CostCalculator.normalize(spend), limit == null ? null : CostCalculator.normalize(limit));
        }
        BigDecimal spend = jdbc.queryForObject("""
                select coalesce(sum(amount_usd), 0) from ledger_entries
                where tenant_id = aethergate.current_tenant_id() and project_id = ? and entry_type = 'SETTLE' and created_at >= ?""",
                BigDecimal.class, projectId, since);
        List<BigDecimal> budget = jdbc.query("select monthly_budget_usd from projects where id = ?", (rs, i) -> rs.getBigDecimal(1),
                projectId);
        BigDecimal limit = budget.isEmpty() ? null : budget.getFirst();
        return new Position(CostCalculator.normalize(spend), limit == null ? null : CostCalculator.normalize(limit));
    }

    private String period() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    private Instant monthStart() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
