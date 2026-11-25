package com.aethergate.service.guardrail;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.GuardrailAction;
import com.aethergate.domain.model.GuardrailRule;
import com.aethergate.domain.model.GuardrailType;
import com.aethergate.domain.repository.GuardrailRuleRepository;
import com.aethergate.service.guardrail.GuardrailEngine.CompiledRule;
import com.aethergate.service.guardrail.GuardrailEngine.Match;
import com.aethergate.service.guardrail.GuardrailEngine.Verdict;
import com.aethergate.service.guardrail.GuardrailViews.GuardrailDraft;
import com.aethergate.service.guardrail.GuardrailViews.GuardrailEventView;
import com.aethergate.service.guardrail.GuardrailViews.GuardrailList;
import com.aethergate.service.guardrail.GuardrailViews.GuardrailView;
import com.aethergate.service.guardrail.GuardrailViews.MatchView;
import com.aethergate.service.guardrail.GuardrailViews.TestResult;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.StaleVersionException;
import com.aethergate.service.webhook.WebhookEvents;
import com.aethergate.service.webhook.WebhookOutbox;
import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Guardrail rules of the current tenant: CRUD, dry run, and the two hooks the gateway uses (load the active
 * rules, record what fired). Every definition is compiled on save, so an invalid regex is a 400 at edit time,
 * never a failure on the request path.
 */
@Service
public class GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailService.class);
    public static final int MAX_RULES = 20;

    private final GuardrailRuleRepository rules;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final WebhookOutbox outbox;

    public GuardrailService(GuardrailRuleRepository rules, JdbcTemplate jdbc, ApplicationEventPublisher events, Clock clock,
                            WebhookOutbox outbox) {
        this.outbox = outbox;
        this.rules = rules;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @TenantScoped(readOnly = true)
    public GuardrailList list() {
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(30)));
        List<GuardrailView> views = jdbc.query("""
                select r.id, r.name, r.rule_type, r.pattern, r.action, r.is_enabled, r.version, r.created_at, r.updated_at,
                       coalesce(e.hits, 0) as hits, e.last_hit
                from guardrail_rules r
                left join lateral (select count(*) as hits, max(created_at) as last_hit from guardrail_events g
                                   where g.tenant_id = r.tenant_id and g.rule_id = r.id and g.created_at >= ?) e on true
                where r.tenant_id = aethergate.current_tenant_id()
                order by r.created_at
                """, (rs, i) -> {
            Timestamp last = rs.getTimestamp("last_hit");
            return new GuardrailView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("rule_type"),
                    rs.getString("pattern"), rs.getString("action"), rs.getBoolean("is_enabled"), rs.getLong("hits"),
                    last == null ? null : last.toInstant(), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }, since);
        List<GuardrailEventView> recent = jdbc.query("""
                select rule_name, action, request_id, matches, created_at from guardrail_events
                where tenant_id = aethergate.current_tenant_id() order by created_at desc limit 20
                """, (rs, i) -> new GuardrailEventView(rs.getString("rule_name"), rs.getString("action"),
                rs.getString("request_id"), rs.getInt("matches"), rs.getTimestamp("created_at").toInstant()));
        return new GuardrailList(views, recent, MAX_RULES);
    }

    @TenantScoped
    public GuardrailView create(GuardrailDraft draft) {
        Definition d = validate(draft);
        if (rules.count() >= MAX_RULES) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_RULES + " guardrail rules");
        }
        if (rules.existsByNameIgnoreCase(d.name())) {
            throw duplicate(d.name());
        }
        GuardrailRule rule = rules.saveAndFlush(new GuardrailRule(TenantContext.require(), d.name(), d.type(), d.pattern(), d.action()));
        changed();
        return view(rule.getId());
    }

    @TenantScoped
    public GuardrailView update(UUID id, long expectedVersion, GuardrailDraft draft) {
        Definition d = validate(draft);
        GuardrailRule rule = find(id);
        if (rule.getVersion() != expectedVersion) {
            throw new StaleVersionException(expectedVersion, rule.getVersion());
        }
        if (rules.existsByNameIgnoreCaseAndIdNot(d.name(), id)) {
            throw duplicate(d.name());
        }
        rule.redefine(d.name(), d.type(), d.pattern(), d.action());
        rules.saveAndFlush(rule);
        changed();
        return view(id);
    }

    @TenantScoped
    public GuardrailView setEnabled(UUID id, boolean enabled) {
        GuardrailRule rule = find(id);
        rule.setEnabled(enabled);
        rules.saveAndFlush(rule);
        changed();
        return view(id);
    }

    /** Events keep a snapshot of the rule name, so the audit trail survives the delete. */
    @TenantScoped
    public void delete(UUID id) {
        rules.delete(find(id));
        rules.flush();
        changed();
    }

    /** What would the gateway do with this text under the tenant's enabled rules? Nothing is recorded. */
    @TenantScoped(readOnly = true)
    public TestResult test(String text) {
        Verdict v = GuardrailEngine.evaluate(activeRules(), List.of(new ChatMessage("user", text)));
        List<MatchView> matches = v.matches().stream().map(m -> new MatchView(m.ruleName(), m.action().name(), m.count())).toList();
        return new TestResult(v.blocked(), v.blocked() ? v.blockedBy().ruleName() : null,
                v.blocked() ? text : v.messages().getFirst().content(), matches);
    }

    /** Enabled rules, compiled. Called by the enforcer through its cache. */
    @TenantScoped(readOnly = true)
    public List<CompiledRule> activeRules() {
        List<CompiledRule> compiled = new ArrayList<>();
        for (GuardrailRule r : rules.findByEnabledTrueOrderByCreatedAtAsc()) {
            try {
                compiled.add(GuardrailEngine.compile(r.getId(), r.getName(), r.getType(), r.getAction(), r.getPattern()));
            } catch (IllegalArgumentException e) { // validated on save; only a manual DB edit could get here
                log.warn("Skipping invalid guardrail {}: {}", r.getId(), e.getMessage());
            }
        }
        return List.copyOf(compiled);
    }

    @TenantScoped
    public void recordEvents(String requestId, List<Match> matches) {
        UUID tenant = TenantContext.require();
        for (Match m : matches) {
            jdbc.update("""
                    insert into guardrail_events (tenant_id, rule_id, rule_name, action, request_id, matches)
                    values (?, ?, ?, ?, ?, ?)""", tenant, m.ruleId(), m.ruleName(), m.action().name(), requestId, m.count());
            if (m.action() == GuardrailAction.BLOCK) {
                // same transaction as the event row; the prompt itself is never put in a webhook
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("rule", m.ruleName());
                data.put("matches", m.count());
                data.put("request_id", requestId);
                outbox.publish(WebhookEvents.GUARDRAIL_BLOCKED, data);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private record Definition(String name, GuardrailType type, String pattern, GuardrailAction action) {}

    static Definition validate(GuardrailDraft draft) {
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.length() < 2 || name.length() > 80) {
            throw new IllegalArgumentException("Rule name must be 2-80 characters");
        }
        GuardrailType type = parse(GuardrailType.class, draft.type(), "type");
        GuardrailAction action = parse(GuardrailAction.class, draft.action(), "action");
        String pattern = type.needsPattern() && draft.pattern() != null ? draft.pattern().strip() : null;
        GuardrailEngine.compile(null, name, type, action, pattern); // throws a 400-worthy message if invalid
        return new Definition(name, type, pattern, action);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + field + " '" + value + "'");
        }
    }

    private GuardrailRule find(UUID id) {
        return rules.findById(id).orElseThrow(() -> new NoSuchElementException("Guardrail rule not found"));
    }

    private GuardrailView view(UUID id) {
        return list().rules().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Guardrail rule not found"));
    }

    private void changed() {
        events.publishEvent(new GuardrailsChanged(TenantContext.require()));
    }

    private static ResourceConflictException duplicate(String name) {
        return new ResourceConflictException("A guardrail called '" + name + "' already exists");
    }
}
