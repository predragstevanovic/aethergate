package com.aethergate.service.finops;

import com.aethergate.config.TenantScoped;
import com.aethergate.service.finops.FinOpsViews.Action;
import com.aethergate.service.finops.FinOpsViews.Forecast;
import com.aethergate.service.finops.FinOpsViews.Recommendation;
import com.aethergate.service.finops.FinOpsViews.Recommendations;
import com.aethergate.service.metering.CostCalculator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Savings advisor: turns the last 30 days of traffic into ranked, actionable recommendations. Each one states the
 * evidence and, where the saving can be computed from real token counts and catalog prices, a monthly dollar figure.
 *
 * <ul>
 *   <li>Model right-sizing: short-answer traffic on an expensive model, repriced at the cheapest enabled model.</li>
 *   <li>Prompt bloat: input tokens dominate cost; trimming context by 30 % is priced from input prices.</li>
 *   <li>Cache opportunity, unreliable primary, soft-limit downgrade when the forecast breaches the limit.</li>
 *   <li>Governance: idle keys, heavy keys without a cap, projects without a budget, no budget alerts.</li>
 * </ul>
 */
@Service
@TenantScoped(readOnly = true)
public class SavingsAdvisor {

    static final int SHORT_COMPLETION_TOKENS = 500;
    static final double PROMPT_TRIM = 0.30;
    static final BigDecimal MIN_SAVING = new BigDecimal("0.01");
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final JdbcTemplate jdbc;
    private final ForecastService forecasts;
    private final Clock clock;

    public SavingsAdvisor(JdbcTemplate jdbc, ForecastService forecasts, Clock clock) {
        this.jdbc = jdbc;
        this.forecasts = forecasts;
        this.clock = clock;
    }

    record ModelUsage(String provider, String model, long requests, long promptTokens, long completionTokens, BigDecimal cost) {}

    record Price(String model, BigDecimal in, BigDecimal out) {
        BigDecimal cost(long prompt, long completion) {
            return BigDecimal.valueOf(prompt).multiply(in).add(BigDecimal.valueOf(completion).multiply(out))
                    .divide(MILLION, MathContext.DECIMAL128);
        }
    }

    /** Cheapest enabled model for this token mix, if it is at least 30 % cheaper than what was paid. */
    static Recommendation rightSize(ModelUsage u, List<Price> catalog) {
        if (u.requests() == 0 || u.completionTokens() / u.requests() > SHORT_COMPLETION_TOKENS) {
            return null;
        }
        Price best = catalog.stream().filter(p -> !p.model().equalsIgnoreCase(u.model()))
                .min(Comparator.comparing(p -> p.cost(u.promptTokens(), u.completionTokens()))).orElse(null);
        if (best == null) {
            return null;
        }
        BigDecimal alternative = best.cost(u.promptTokens(), u.completionTokens());
        BigDecimal saving = CostCalculator.normalize(u.cost().subtract(alternative));
        if (saving.compareTo(MIN_SAVING) < 0 || alternative.compareTo(u.cost().multiply(new BigDecimal("0.7"))) > 0) {
            return null;
        }
        long avg = u.completionTokens() / u.requests();
        return new Recommendation("rightsize:" + u.model(), "MODEL_RIGHTSIZING", "COST", saving.compareTo(BigDecimal.ONE) >= 0 ? "HIGH" : "MEDIUM",
                "Route short answers from " + u.model() + " to " + best.model(),
                u.requests() + " requests on " + u.model() + " averaged " + avg + " completion tokens (classification / extraction"
                        + " sized). Repriced at " + best.model() + " the same tokens cost $" + CostCalculator.normalize(alternative)
                        .stripTrailingZeros().toPlainString() + " instead of $" + u.cost().stripTrailingZeros().toPlainString()
                        + ". Use a key model allow-list, a soft-limit downgrade or request model=" + best.model() + ".",
                saving, new Action("Open Settings", "/settings"));
    }

    static Recommendation promptBloat(ModelUsage u, Price price) {
        if (price == null || u.requests() < 20 || u.completionTokens() == 0
                || u.promptTokens() < 6 * u.completionTokens()) {
            return null;
        }
        BigDecimal inputCost = BigDecimal.valueOf(u.promptTokens()).multiply(price.in()).divide(MILLION, MathContext.DECIMAL128);
        BigDecimal saving = CostCalculator.normalize(inputCost.multiply(BigDecimal.valueOf(PROMPT_TRIM)));
        if (saving.compareTo(MIN_SAVING) < 0) {
            return null;
        }
        return new Recommendation("prompt-bloat:" + u.model(), "PROMPT_BLOAT", "COST", "MEDIUM",
                "Trim prompt context on " + u.model(),
                "Prompts are " + (u.promptTokens() / Math.max(1, u.completionTokens())) + "x longer than answers. Cutting 30 % of"
                        + " the context (shorter system prompts, fewer retrieved chunks, conversation summarisation) saves"
                        + " this much input cost. Versioned prompt templates make the change measurable per version.",
                saving, new Action("Open Prompts", "/prompts"));
    }

    public Recommendations recommendations() {
        Instant now = clock.instant();
        Timestamp since = Timestamp.from(now.minus(Duration.ofDays(30)));
        List<Recommendation> out = new ArrayList<>();

        List<Price> catalog = jdbc.query("""
                select model_name, input_cost_per_1m, output_cost_per_1m from provider_configs
                where tenant_id = aethergate.current_tenant_id() and is_enabled""",
                (rs, i) -> new Price(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
        List<ModelUsage> usage = jdbc.query("""
                select provider_used, model_used, count(*), coalesce(sum(prompt_tokens), 0), coalesce(sum(completion_tokens), 0),
                       coalesce(sum(total_cost_usd), 0)
                from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and created_at >= ? and cache_status = 'MISS'
                  and status_code = 200 and total_cost_usd > 0
                group by 1, 2 order by 6 desc""", (rs, i) -> new ModelUsage(rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getBigDecimal(6)), since);
        for (ModelUsage u : usage) {
            add(out, rightSize(u, catalog));
            add(out, promptBloat(u, catalog.stream().filter(p -> p.model().equalsIgnoreCase(u.model())).findFirst().orElse(null)));
        }

        Map<String, Object> totals = jdbc.queryForMap("""
                select count(*) as n, count(*) filter (where cache_status = 'HIT') as hits,
                       coalesce(sum(total_cost_usd), 0) as cost, count(*) filter (where failover_triggered) as failovers
                from llm_request_logs where tenant_id = aethergate.current_tenant_id() and created_at >= ?""", since);
        long n = ((Number) totals.get("n")).longValue();
        long hits = ((Number) totals.get("hits")).longValue();
        long failovers = ((Number) totals.get("failovers")).longValue();
        BigDecimal spend = CostCalculator.normalize((BigDecimal) totals.get("cost"));
        cacheOpportunity(out, n, hits, spend);
        if (n >= 50 && failovers * 100 >= n * 5L) {
            out.add(new Recommendation("reliability:failover", "UNRELIABLE_PRIMARY", "RELIABILITY", "MEDIUM",
                    "Primary provider fails over on " + Math.round(failovers * 100.0 / n) + " % of requests",
                    "Each failover adds latency and the fallback is often the pricier model. Reorder the chain or add a healthier"
                            + " primary.", null, new Action("Reorder chain", "/settings")));
        }

        governance(out, now, spend);
        downgradeAdvice(out);

        out.sort(Comparator.comparing((Recommendation r) -> r.monthlySavingsUsd() == null ? BigDecimal.ZERO : r.monthlySavingsUsd())
                .reversed().thenComparing(r -> severityRank(r.severity())));
        BigDecimal total = out.stream().map(Recommendation::monthlySavingsUsd).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Recommendations(out, CostCalculator.normalize(total), spend, now);
    }

    private static void cacheOpportunity(List<Recommendation> out, long n, long hits, BigDecimal spend) {
        if (n < 100 || spend.signum() == 0) {
            return;
        }
        double ratio = (double) hits / n;
        if (ratio >= 0.15) {
            return;
        }
        // raising the hit ratio to 25 % removes that share of the paid (miss) requests
        double avoided = (0.25 - ratio) / (1 - ratio);
        BigDecimal saving = CostCalculator.normalize(spend.multiply(BigDecimal.valueOf(avoided)));
        out.add(new Recommendation("cache:hit-ratio", "CACHE_OPPORTUNITY", "COST", "LOW",
                "Semantic cache answers only " + Math.round(ratio * 100) + " % of requests",
                "Requests that embed one-off ids or timestamps never match. Move variable parts into prompt-template variables"
                        + " and keep the stable instruction first. Estimate assumes a 25 % hit ratio.",
                saving, new Action("Open Prompts", "/prompts")));
    }

    private void governance(List<Recommendation> out, Instant now, BigDecimal spend) {
        Timestamp idleSince = Timestamp.from(now.minus(Duration.ofDays(14)));
        List<String> idle = jdbc.queryForList("""
                select name || ' (' || key_prefix || ')' from api_keys
                where tenant_id = aethergate.current_tenant_id() and is_active and key_prefix <> 'ag_live_demo'
                  and created_at < ? and (last_used_at is null or last_used_at < ?)
                order by created_at limit 10""", String.class, idleSince, idleSince);
        if (!idle.isEmpty()) {
            out.add(new Recommendation("security:idle-keys", "IDLE_KEYS", "SECURITY", "MEDIUM",
                    idle.size() + " API key(s) unused for 14+ days",
                    "Unused live credentials are pure risk: " + String.join(", ", idle) + ". Revoke them or set an expiry.",
                    null, new Action("Review keys", "/settings")));
        }
        if (spend.signum() > 0) {
            List<String> heavy = jdbc.query("""
                    select k.name, k.key_prefix, sum(l.total_cost_usd) as cost from llm_request_logs l
                    join api_keys k on k.tenant_id = l.tenant_id and k.id = l.api_key_id
                    where l.tenant_id = aethergate.current_tenant_id() and l.created_at >= ? and k.is_active
                      and k.monthly_budget_usd is null
                    group by k.name, k.key_prefix having sum(l.total_cost_usd) * 5 >= ?
                    order by cost desc limit 5""", (rs, i) -> rs.getString(1) + " (" + rs.getString(2) + ")",
                    Timestamp.from(now.minus(Duration.ofDays(30))), spend);
            if (!heavy.isEmpty()) {
                out.add(new Recommendation("governance:uncapped-keys", "UNCAPPED_KEYS", "GOVERNANCE", "MEDIUM",
                        "Cap the keys that drive most of the spend",
                        String.join(", ", heavy) + " each account for 20 %+ of spend and have no monthly budget. A leaked or"
                                + " looping client would only stop at the workspace limit.",
                        null, new Action("Set key budgets", "/settings")));
            }
        }
        List<String> unbudgeted = jdbc.queryForList("""
                select p.name from projects p
                where p.tenant_id = aethergate.current_tenant_id() and p.status = 'ACTIVE' and p.monthly_budget_usd is null
                  and exists (select 1 from ledger_entries e where e.tenant_id = p.tenant_id and e.project_id = p.id
                              and e.entry_type = 'SETTLE' and e.created_at >= ?)
                order by p.name limit 10""", String.class, Timestamp.from(now.minus(Duration.ofDays(30))));
        if (!unbudgeted.isEmpty()) {
            out.add(new Recommendation("governance:unbudgeted-projects", "UNBUDGETED_PROJECTS", "GOVERNANCE", "LOW",
                    unbudgeted.size() + " project(s) spend without a budget",
                    String.join(", ", unbudgeted) + ": a monthly budget turns overspend into a 402 instead of a surprise.",
                    null, new Action("Open Projects", "/projects")));
        }
        Integer rules = jdbc.queryForObject("select count(*) from alert_rules where tenant_id = aethergate.current_tenant_id()",
                Integer.class);
        if (rules != null && rules == 0) {
            out.add(new Recommendation("governance:no-alerts", "NO_BUDGET_ALERTS", "GOVERNANCE", "LOW",
                    "No budget alerts configured",
                    "Add 50 / 80 / 100 % alerts on the workspace limit so someone hears about spend before the 402s start.",
                    null, new Action("Open Alerts", "/alerts")));
        }
    }

    private void downgradeAdvice(List<Recommendation> out) {
        Integer downgrade = jdbc.queryForObject("select downgrade_at_pct from tenants where id = aethergate.current_tenant_id()",
                Integer.class);
        if (downgrade != null) {
            return;
        }
        Forecast f = forecasts.forecast();
        if (f.projectedPctOfLimit() >= 90) {
            out.add(new Recommendation("forecast:soft-limit", "ENABLE_SOFT_LIMIT", "COST", "HIGH",
                    "Forecast reaches " + Math.round(f.projectedPctOfLimit()) + " % of the monthly limit",
                    "Enable the budget-aware downgrade: past e.g. 85 % of the limit requests go to a cheaper model first,"
                            + " instead of every request failing with 402 once the limit is hit"
                            + (f.limitReachedOn() == null ? "." : " (projected for " + f.limitReachedOn() + ")."),
                    null, new Action("Cost controls", "/settings")));
        }
    }

    private static void add(List<Recommendation> out, Recommendation r) {
        if (r != null) {
            out.add(r);
        }
    }

    private static int severityRank(String s) {
        return switch (s) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }
}
