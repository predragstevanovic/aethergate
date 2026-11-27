package com.aethergate.service.finops;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.ProviderConfig;
import com.aethergate.domain.model.Tenant;
import com.aethergate.domain.repository.ProviderConfigRepository;
import com.aethergate.domain.repository.TenantRepository;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.metering.SpendCapExceededException;
import com.aethergate.service.tenant.DemoReadOnlyException;
import com.aethergate.service.tenant.KeyPolicy;
import com.aethergate.service.webhook.WebhookEvents;
import com.aethergate.service.webhook.WebhookOutbox;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Workspace cost controls: end-user daily caps, agent-session budgets and step limits (enforced by the metering
 * ledger under the tenant lock), the budget-aware model downgrade (soft limit) and the record of blocked requests.
 */
@Service
public class CostControlService {

    public static final String DOWNGRADE = "DOWNGRADE";
    static final BigDecimal MAX_CAP = new BigDecimal("100000");

    private final TenantRepository tenants;
    private final ProviderConfigRepository providers;
    private final JdbcTemplate jdbc;
    private final WebhookOutbox outbox;
    private final Clock clock;
    /** Downgrade decision per tenant; a few seconds of staleness is fine for a soft limit and saves a query per request. */
    private final Cache<UUID, Optional<String>> downgradeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5)).maximumSize(10_000).build();

    public CostControlService(TenantRepository tenants, ProviderConfigRepository providers,
                              JdbcTemplate jdbc, WebhookOutbox outbox, Clock clock) {
        this.tenants = tenants;
        this.providers = providers;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    public record CostControls(BigDecimal endUserDailyCapUsd, BigDecimal sessionCapUsd, Integer sessionMaxRequests,
                               Integer downgradeAtPct, String downgradeModel, boolean downgradeActive,
                               BigDecimal monthToDateUsd, BigDecimal hardBudgetLimitUsd, double usedPct,
                               List<String> downgradeCandidates, boolean demo) {}

    public record ControlEvent(String kind, String scopeKey, String period, BigDecimal limitValue, BigDecimal observedValue,
                               int blockedCount, String lastRequestId, Instant firstAt, Instant lastAt) {}

    /** Routing after the soft limit: the downgrade model first, the rest of the chain behind it as fallback. */
    public record Routing(List<RouteTarget> chain, String downgradedTo) {}

    @TenantScoped(readOnly = true)
    public CostControls get() {
        Tenant t = tenants.findById(TenantContext.require()).orElseThrow();
        return view(t);
    }

    @TenantScoped
    @SuppressWarnings("java:S107")
    public CostControls update(BigDecimal userDailyCap, BigDecimal sessionCap, Integer sessionMaxRequests,
                               Integer downgradeAtPct, String downgradeModel) {
        Tenant t = tenants.findByIdForUpdate(TenantContext.require()).orElseThrow();
        if (t.isDemo()) {
            throw new DemoReadOnlyException();
        }
        requireCap(userDailyCap, "End-user daily cap");
        requireCap(sessionCap, "Session budget");
        if (sessionMaxRequests != null && (sessionMaxRequests < 1 || sessionMaxRequests > 100_000)) {
            throw new IllegalArgumentException("Session step limit must be 1-100,000 model calls (empty = no limit)");
        }
        String model = downgradeModel == null || downgradeModel.isBlank() ? null : downgradeModel.strip();
        if ((downgradeAtPct == null) != (model == null)) {
            throw new IllegalArgumentException("Set both the downgrade threshold and the downgrade model, or neither");
        }
        if (downgradeAtPct != null && (downgradeAtPct < 1 || downgradeAtPct > 100)) {
            throw new IllegalArgumentException("Downgrade threshold must be 1-100 % of the monthly hard limit");
        }
        if (model != null) {
            String m = model;
            model = providers.findByEnabledTrueOrderByPriorityOrderAsc().stream().map(ProviderConfig::getModelName)
                    .filter(n -> n.equalsIgnoreCase(m)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("'" + m + "' is not an enabled model of this workspace"));
        }
        t.updateCostControls(scale(userDailyCap), scale(sessionCap), sessionMaxRequests, downgradeAtPct, model);
        downgradeCache.invalidate(t.getId());
        return view(t);
    }

    /**
     * Soft limit: once month-to-date spend reaches {@code downgrade_at_pct} of the hard limit, requests are answered by the
     * cheaper downgrade model first. A key that may not use that model keeps its normal chain.
     */
    public Routing route(UUID tenantId, List<RouteTarget> chain, KeyPolicy policy) {
        Optional<String> target = downgradeCache.get(tenantId, id -> TenantContext.callAs(id, this::downgradeTarget));
        if (target.isEmpty() || !policy.allowsModel(target.get())) {
            return new Routing(chain, null);
        }
        Optional<RouteTarget> cheap = chain.stream().filter(t -> t.model().equalsIgnoreCase(target.get())).findFirst();
        if (cheap.isEmpty() || chain.getFirst() == cheap.get()) {
            return new Routing(chain, null);
        }
        List<RouteTarget> reordered = new ArrayList<>();
        reordered.add(cheap.get());
        chain.stream().filter(t -> t != cheap.get()).forEach(reordered::add);
        return new Routing(List.copyOf(reordered), cheap.get().model());
    }

    @TenantScoped(readOnly = true)
    public Optional<String> downgradeTarget() {
        Tenant t = tenants.findById(TenantContext.require()).orElseThrow();
        return downgradeActive(t, monthToDate(t)) ? Optional.of(t.getDowngradeModel()) : Optional.empty();
    }

    /**
     * A cap blocked a request: one row per (kind, scope, period) counts the blocks. The insert that creates the row is
     * the first block of the period and announces it as a webhook, exactly once (decided by the unique constraint).
     */
    @TenantScoped
    public boolean recordBlocked(SpendCapExceededException e, String requestId) {
        String period = switch (e.getKind()) {
            case KEY_MONTHLY_BUDGET -> YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
            case END_USER_DAILY_CAP -> LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
            case SESSION_BUDGET, SESSION_STEPS -> "session";
        };
        Boolean inserted = jdbc.queryForObject("""
                insert into cost_control_events (tenant_id, kind, scope_key, period, limit_value, observed_value, last_request_id)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, kind, scope_key, period) do update
                    set blocked_count = cost_control_events.blocked_count + 1, last_at = now(),
                        observed_value = excluded.observed_value, last_request_id = excluded.last_request_id
                returning (xmax = 0)""", Boolean.class, TenantContext.require(), e.getKind().name(), e.getScopeKey(), period,
                e.getLimit(), e.getObserved(), requestId);
        if (Boolean.TRUE.equals(inserted)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", e.getKind().name());
            data.put("scope", e.getScopeKey());
            data.put("period", period);
            data.put("limit", e.getLimit());
            data.put("observed", e.getObserved());
            data.put("request_id", requestId);
            outbox.publish(WebhookEvents.SPEND_CAP_REACHED, data);
            return true;
        }
        return false;
    }

    @TenantScoped(readOnly = true)
    public List<ControlEvent> recentEvents(int limit) {
        return jdbc.query("""
                select kind, scope_key, period, limit_value, observed_value, blocked_count, last_request_id, first_at, last_at
                from cost_control_events where tenant_id = aethergate.current_tenant_id()
                order by last_at desc limit ?""", (rs, i) -> new ControlEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9).toInstant()), Math.clamp(limit, 1, 200));
    }

    private CostControls view(Tenant t) {
        BigDecimal mtd = monthToDate(t);
        BigDecimal limit = CostCalculator.normalize(t.getHardBudgetLimitUsd());
        double used = limit.signum() == 0 ? 0
                : mtd.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP).doubleValue();
        List<String> candidates = providers.findByEnabledTrueOrderByPriorityOrderAsc().stream()
                .sorted(java.util.Comparator.comparing(ProviderConfig::getOutputCostPer1m))
                .map(ProviderConfig::getModelName).toList();
        return new CostControls(t.getEndUserDailyCapUsd(), t.getSessionCapUsd(), t.getSessionMaxRequests(),
                t.getDowngradeAtPct(), t.getDowngradeModel(), downgradeActive(t, mtd), mtd, limit, used, candidates, t.isDemo());
    }

    private static boolean downgradeActive(Tenant t, BigDecimal mtd) {
        if (t.getDowngradeAtPct() == null || t.getDowngradeModel() == null || t.getHardBudgetLimitUsd().signum() <= 0) {
            return false;
        }
        BigDecimal threshold = t.getHardBudgetLimitUsd().multiply(BigDecimal.valueOf(t.getDowngradeAtPct()))
                .divide(BigDecimal.valueOf(100));
        return mtd.compareTo(threshold) >= 0;
    }

    private BigDecimal monthToDate(Tenant t) {
        Instant monthStart = YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal mtd = jdbc.queryForObject("""
                select coalesce(sum(amount_usd), 0) from ledger_entries
                where tenant_id = ? and entry_type = 'SETTLE' and created_at >= ?""", BigDecimal.class, t.getId(),
                java.sql.Timestamp.from(monthStart));
        return CostCalculator.normalize(mtd == null ? BigDecimal.ZERO : mtd);
    }

    private static void requireCap(BigDecimal cap, String what) {
        if (cap != null && (cap.signum() <= 0 || cap.compareTo(MAX_CAP) > 0)) {
            throw new IllegalArgumentException(what + " must be between $0 and $100,000 (empty = no cap)");
        }
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : CostCalculator.normalize(v);
    }

    /** Test hook: forget cached downgrade decisions. */
    public void evictCaches() {
        downgradeCache.invalidateAll();
    }
}
