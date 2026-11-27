package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.ProviderType;
import com.aethergate.service.finops.AnalyticsService;
import com.aethergate.service.finops.AnomalyService;
import com.aethergate.service.finops.CostControlService;
import com.aethergate.service.finops.FinOpsViews.Forecast;
import com.aethergate.service.finops.FinOpsViews.Recommendations;
import com.aethergate.service.finops.FinOpsViews.Window;
import com.aethergate.service.finops.FocusExporter;
import com.aethergate.service.finops.ForecastService;
import com.aethergate.service.finops.SavingsAdvisor;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.tenant.DemoReadOnlyException;
import com.aethergate.service.tenant.KeyPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Service-level edge cases of the FinOps features (validation, routing corner cases, advisor and forecast branches). */
class FinOpsEdgeCasesIntegrationTest extends AbstractIntegrationTest {

    @Autowired CostControlService controls;
    @Autowired AnalyticsService analytics;
    @Autowired ForecastService forecasts;
    @Autowired SavingsAdvisor advisor;
    @Autowired FocusExporter focus;
    @Autowired AnomalyService anomalies;

    private <T> T as(UUID tenant, Supplier<T> s) {
        return TenantContext.callAs(tenant, s::get);
    }

    private UUID project(UUID tenant, String name, String budget) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into projects (id, tenant_id, name, monthly_budget_usd) values (?, ?, ?, ?)", id, tenant, name,
                budget == null ? null : new BigDecimal(budget));
        return id;
    }

    private void settle(UUID tenant, UUID project, String amount, Instant at) {
        jdbc.update("""
                insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description, created_at,
                                            project_id)
                values (?, ?, 'SETTLE', ?, 0, 'test', ?, ?)""", tenant, "r_" + UUID.randomUUID(), new BigDecimal(amount),
                Timestamp.from(at), project);
    }

    @SuppressWarnings("java:S107")
    private void log(UUID tenant, UUID key, UUID project, String provider, String model, int prompt, int completion, String cost,
                     String cache, boolean failover, String user, Instant at) {
        jdbc.update("""
                insert into llm_request_logs (tenant_id, request_id, api_key_id, project_id, provider_used, model_used, prompt_tokens,
                    completion_tokens, total_cost_usd, saved_cost_usd, latency_ms, ttft_ms, cache_status, failover_triggered,
                    status_code, created_at, end_user_id, tags)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 300, 100, ?, ?, 200, ?, ?, '{"feature":"x,\\"y"}'::jsonb)""",
                tenant, "req_e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24), key, project, provider, model,
                prompt, completion, new BigDecimal(cost), "HIT".equals(cache) ? new BigDecimal("0.002") : BigDecimal.ZERO, cache,
                failover, Timestamp.from(at), user);
    }

    @Test
    @DisplayName("cost controls: service validation, demo is read-only, routing corner cases")
    void costControlEdges() {
        UUID t = newTenant("Edge Co", new BigDecimal("10"));
        assertThatThrownBy(() -> as(t, () -> controls.update(new BigDecimal("-1"), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(t, () -> controls.update(null, new BigDecimal("100001"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(t, () -> controls.update(null, null, 0, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(t, () -> controls.update(null, null, 100_001, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(t, () -> controls.update(null, null, null, 0, "gpt-4o-mini"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(t, () -> controls.update(null, null, null, 101, "gpt-4o-mini"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(t, () -> controls.update(null, null, null, null, "gpt-4o-mini"))).isInstanceOf(IllegalArgumentException.class);

        // downgrade target already primary → chain unchanged; target missing from chain → unchanged
        as(t, () -> controls.update(new BigDecimal("1"), new BigDecimal("2"), 5, 1, "gpt-4o-mini"));
        settle(t, null, "100", Instant.now());
        controls.evictCaches();
        RouteTarget mini = new RouteTarget(ProviderType.OPENAI, "gpt-4o-mini", 1, new BigDecimal("0.15"), new BigDecimal("0.60"));
        RouteTarget haiku = new RouteTarget(ProviderType.ANTHROPIC, "claude-haiku-4-5", 2, BigDecimal.ONE, new BigDecimal("5"));
        assertThat(controls.route(t, List.of(mini, haiku), KeyPolicy.NONE).downgradedTo()).isNull();
        assertThat(controls.route(t, List.of(haiku), KeyPolicy.NONE).downgradedTo()).isNull();
        CostControlService.Routing r = controls.route(t, List.of(haiku, mini), KeyPolicy.NONE);
        assertThat(r.downgradedTo()).isEqualTo("gpt-4o-mini");
        assertThat(r.chain()).extracting(RouteTarget::model).containsExactly("gpt-4o-mini", "claude-haiku-4-5");
        assertThat(as(t, controls::get).downgradeActive()).isTrue();

        jdbc.update("update tenants set is_demo = true where id = ?", t);
        assertThatThrownBy(() -> as(t, () -> controls.update(null, null, null, null, null))).isInstanceOf(DemoReadOnlyException.class);
        jdbc.update("update tenants set is_demo = false, hard_budget_limit_usd = 0.00000001 where id = ?", t);
        assertThat(as(t, controls::get).usedPct()).isGreaterThan(100);
    }

    @Test
    @DisplayName("advisor: cache opportunity, unreliable primary, uncapped heavy keys and unbudgeted projects")
    void advisorBranches() {
        UUID t = newTenant("Advice Co", new BigDecimal("10"));
        UUID key = UUID.randomUUID();
        jdbc.update("""
                insert into api_keys (id, tenant_id, name, key_prefix, key_hash, rate_limit_rpm, rate_limit_tpm, last_used_at)
                values (?, ?, 'Backend', 'ag_live_bk01', ?, 60, 1000, now())""", key, t,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        UUID loose = project(t, "Loose Spend", null);
        settle(t, loose, "0.05", Instant.now().minus(Duration.ofDays(2)));
        Instant now = Instant.now();
        for (int i = 0; i < 110; i++) {
            log(t, key, loose, "OPENAI", "gpt-4o-mini", 300, 900, "0.0006", "MISS", i % 10 == 0, i % 3 == 0 ? "u" + (i % 5) : null,
                    now.minus(Duration.ofMinutes(i * 20L)));
        }
        jdbc.update("insert into alert_rules (tenant_id, threshold_pct) values (?, 80)", t);
        Recommendations recs = as(t, advisor::recommendations);
        assertThat(recs.items()).extracting(x -> x.kind())
                .contains("CACHE_OPPORTUNITY", "UNRELIABLE_PRIMARY", "UNCAPPED_KEYS", "UNBUDGETED_PROJECTS")
                .doesNotContain("NO_BUDGET_ALERTS", "IDLE_KEYS");

        // analytics branches over the same data: end users with a cap, attribution by an unused tag, search
        as(t, () -> controls.update(new BigDecimal("0.001"), null, null, null, null));
        assertThat(as(t, () -> analytics.endUsers(Window.D7)).users()).isNotEmpty()
                .allSatisfy(u -> assertThat(u.capUsedPct()).isNotNull());
        assertThat(as(t, () -> analytics.attribution("tag:nothing", Window.D7)).rows()).singleElement()
                .satisfies(row -> assertThat(row.label()).isEqualTo("Untagged"));
        assertThat(as(t, () -> analytics.attribution("tag:feature", Window.D7)).allocatedPct()).isEqualTo(100.0);
        assertThat(as(t, () -> analytics.sessions(Window.H24, "  ")).sessions()).isEmpty();
        assertThat(as(t, () -> analytics.requests(Window.D7, Map.of("project", loose.toString(), "q", ""), 0, 500)).size())
                .isEqualTo(100);

        // FOCUS: cache rows, projects, tags with quotes and commas stay one CSV cell
        log(t, null, null, "CACHE", "gpt-4o-mini", 300, 900, "0", "HIT", false, null, now.minusSeconds(30));
        FocusExporter.Export export = as(t, () -> focus.export(null));
        assertThat(export.csv()).contains("AetherGate Semantic Cache", "Cached Completion", "Loose Spend", "playground");
        assertThat(export.rows()).isGreaterThanOrEqualTo(2);

        // anomaly attribution names the end user when there is no session
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        for (int i = 0; i < 30; i++) {
            log(t, key, null, "OPENAI", "gpt-4o", 5000, 2000, "0.05", "MISS", false, "whale", hour.plusSeconds(i));
        }
        assertThat(as(t, () -> anomalies.scan(1))).isEqualTo(1);
        assertThat(as(t, anomalies::view).anomalies().getFirst().topDimension()).contains("user whale");
    }

    @Test
    @DisplayName("forecast: project outlook ladder and a tenant with no traffic")
    void forecastBranches() {
        UUID t = newTenant("Forecast Co", new BigDecimal("10"));
        Forecast empty = as(t, forecasts::forecast);
        assertThat(empty.balanceRunwayDays()).isNull();
        assertThat(empty.limitReachedOn()).isNull();
        assertThat(empty.status()).isEqualTo("ON_TRACK");

        UUID calm = project(t, "Calm", "1000");
        UUID tight = project(t, "Tight", "0.001");
        UUID edge = project(t, "Edge", null);
        Instant now = Instant.now();
        settle(t, calm, "0.01", now.minusSeconds(120));
        settle(t, tight, "0.01", now.minusSeconds(120));
        settle(t, edge, "0.01", now.minusSeconds(120));
        settle(t, null, "0.02", now.minus(Duration.ofDays(9)));
        Forecast f = as(t, forecasts::forecast);
        Map<String, String> status = new java.util.HashMap<>();
        f.projects().forEach(p -> status.put(p.name(), p.status()));
        assertThat(status).containsEntry("Calm", "ON_TRACK").containsEntry("Tight", "OVER_BUDGET").containsEntry("Edge", "NO_BUDGET");
        assertThat(f.balanceRunwayDays()).isNotNull();
        assertThat(f.trendPct()).isNotZero();
    }
}
