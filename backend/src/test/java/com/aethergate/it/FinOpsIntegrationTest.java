package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aethergate.config.TenantContext;
import com.aethergate.service.finops.AnomalyService;
import com.aethergate.service.finops.CostControlService;
import com.aethergate.service.finops.FocusExporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FinOps features end to end: attribution (users, sessions, tags), spend caps (key, end user, session cost and
 * steps), budget-aware downgrade, request explorer, sessions, forecast, anomalies, savings advisor, FOCUS export.
 */
@AutoConfigureMockMvc
class FinOpsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AnomalyService anomalies;

    @Autowired
    CostControlService costControls;

    record Account(JwtRequestPostProcessor auth, UUID tenantId) {}

    private Account signUp() throws Exception {
        UUID user = UUID.randomUUID();
        JwtRequestPostProcessor auth = jwt().jwt(j -> j.subject(user.toString()).claim("email", "f" + user + "@example.com")
                .audience(List.of("authenticated")));
        MvcResult me = mvc.perform(get("/api/v1/me").with(auth)).andExpect(status().isOk()).andReturn();
        return new Account(auth, UUID.fromString(read(me).at("/tenant/id").asText()));
    }

    private JsonNode read(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private ResultActions call(MockHttpServletRequestBuilder builder) throws Exception {
        ResultActions actions = mvc.perform(builder);
        MvcResult result = actions.andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)) : actions;
    }

    private JsonNode createKey(Account a) throws Exception {
        return read(mvc.perform(post("/api/v1/tenant/api-keys").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"k\",\"rate_limit_rpm\":600,\"rate_limit_tpm\":1000000}")).andExpect(status().isCreated()).andReturn());
    }

    private static String completion(String prompt) {
        return "{\"model\":\"auto\",\"max_tokens\":40,\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + " "
                + UUID.randomUUID() + "\"}]}";
    }

    private MockHttpServletRequestBuilder v1(String key, String body) {
        return post("/v1/chat/completions").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void controls(Account a, String body) throws Exception {
        mvc.perform(put("/api/v1/tenant/cost-controls").with(a.auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("end user, session and cost tags flow from headers and OpenAI body fields into logs, ledger and showback")
    void attributionAndShowback() throws Exception {
        Account a = signUp();
        String key = createKey(a).get("raw_key").asText();
        call(v1(key, completion("tagged")).header("X-AetherGate-User", "alice").header("X-AetherGate-Session", "run-1")
                .header("X-AetherGate-Tags", "feature=search, env=prod")).andExpect(status().isOk());
        call(v1(key, "{\"max_tokens\":40,\"user\":\"bob\",\"metadata\":{\"session_id\":\"run-2\",\"feature\":\"chat\",\"team\":\"core\"},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"body attribution " + UUID.randomUUID() + "\"}]}"))
                .andExpect(status().isOk());
        call(v1(key, completion("untagged"))).andExpect(status().isOk());

        assertThat(count("select count(*) from llm_request_logs where tenant_id = ? and end_user_id = 'alice' and session_id = 'run-1'"
                + " and tags ->> 'feature' = 'search' and tags ->> 'env' = 'prod' and requested_model = 'auto'", a.tenantId())).isEqualTo(1);
        assertThat(count("select count(*) from llm_request_logs where tenant_id = ? and end_user_id = 'bob' and session_id = 'run-2'"
                + " and tags ->> 'team' = 'core' and not jsonb_exists(tags, 'session_id')", a.tenantId())).isEqualTo(1);
        // the ledger carries the same dimensions as the log (what the caps are enforced from)
        assertThat(count("select count(*) from ledger_entries where tenant_id = ? and end_user_id = 'alice' and session_id = 'run-1'"
                + " and api_key_id is not null", a.tenantId())).isEqualTo(3);

        mvc.perform(get("/api/v1/analytics/attribution?dimension=tag:feature&range=24h").with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.tag_keys", org.hamcrest.Matchers.hasItems("feature", "env", "team")))
                .andExpect(jsonPath("$.total_requests").value(3))
                .andExpect(jsonPath("$.allocated_pct").value(org.hamcrest.Matchers.lessThan(100.0)));
        for (String d : List.of("project", "model", "provider", "api_key", "end_user")) {
            mvc.perform(get("/api/v1/analytics/attribution?dimension=" + d).with(a.auth())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.dimension").value(d));
        }
        mvc.perform(get("/api/v1/analytics/attribution?dimension=tag:Bad Key").with(a.auth())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/attribution?range=1y").with(a.auth())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/end-users?range=7d").with(a.auth()))
                .andExpect(jsonPath("$.users.length()").value(2))
                .andExpect(jsonPath("$.anonymous_requests").value(1))
                .andExpect(jsonPath("$.daily_cap_usd").doesNotExist());

        // invalid attribution is a 400 before anything is reserved
        call(v1(key, completion("bad")).header("X-AetherGate-Tags", "noequals")).andExpect(status().isBadRequest());
        call(v1(key, completion("bad")).header("X-AetherGate-Tags", "user=x")).andExpect(status().isBadRequest());
        call(v1(key, completion("bad")).header("X-AetherGate-User", "has spaces")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("end-user daily cap → 402, counted once per user and day, announced once as spend_cap.reached")
    void endUserCap() throws Exception {
        Account a = signUp();
        String key = createKey(a).get("raw_key").asText();
        mvc.perform(post("/api/v1/tenant/webhooks").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ops\",\"url\":\"http://127.0.0.1:9/hook\",\"event_types\":[\"spend_cap.reached\"]}"))
                .andExpect(status().isCreated());
        controls(a, "{\"end_user_daily_cap_usd\":0.0001}");
        for (int i = 0; i < 2; i++) {
            call(v1(key, completion("cap")).header("X-AetherGate-User", "carol"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/end-user-cap-exceeded"))
                    .andExpect(jsonPath("$.cap").value("END_USER_DAILY_CAP"))
                    .andExpect(jsonPath("$.scope").value("carol"));
        }
        call(v1(key, completion("anon"))).andExpect(status().isOk()); // no end user, no end-user cap
        assertThat(count("select blocked_count from cost_control_events where tenant_id = ? and kind = 'END_USER_DAILY_CAP'",
                a.tenantId())).isEqualTo(2);
        assertThat(count("select count(*) from webhook_deliveries where tenant_id = ? and event_type = 'spend_cap.reached'",
                a.tenantId())).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries where tenant_id = ? and end_user_id = 'carol'", a.tenantId())).isZero();
        mvc.perform(get("/api/v1/tenant/cost-controls").with(a.auth()))
                .andExpect(jsonPath("$.controls.end_user_daily_cap_usd").value(0.0001))
                .andExpect(jsonPath("$.events[0].kind").value("END_USER_DAILY_CAP"))
                .andExpect(jsonPath("$.events[0].blocked_count").value(2));
    }

    @Test
    @DisplayName("runaway-agent guard: session step limit → 429, session budget → 402; sessions list and timeline")
    void sessionGuards() throws Exception {
        Account a = signUp();
        String key = createKey(a).get("raw_key").asText();
        controls(a, "{\"session_max_requests\":2}");
        for (int i = 0; i < 2; i++) {
            call(v1(key, completion("step " + i)).header("X-AetherGate-Session", "agent-1").header("X-AetherGate-User", "dana"))
                    .andExpect(status().isOk());
        }
        call(v1(key, completion("step 3")).header("X-AetherGate-Session", "agent-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/session-step-limit"));
        controls(a, "{\"session_cap_usd\":0.0001}");
        call(v1(key, completion("pricey")).header("X-AetherGate-Session", "agent-2"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/session-budget-exceeded"));

        mvc.perform(get("/api/v1/analytics/sessions?range=24h").with(a.auth()))
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].session_id").value("agent-1"))
                .andExpect(jsonPath("$.sessions[0].steps").value(2))
                .andExpect(jsonPath("$.sessions[0].end_user_id").value("dana"))
                .andExpect(jsonPath("$.sessions[0].blocked").value(1))
                .andExpect(jsonPath("$.session_cap_usd").value(0.0001));
        mvc.perform(get("/api/v1/analytics/sessions?q=agent_&range=24h").with(a.auth()))
                .andExpect(jsonPath("$.sessions.length()").value(0)); // _ is literal, not a wildcard
        mvc.perform(get("/api/v1/analytics/sessions?q=AGENT&range=24h").with(a.auth()))
                .andExpect(jsonPath("$.sessions.length()").value(1));
        mvc.perform(get("/api/v1/analytics/sessions/agent-1").with(a.auth()))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[1].step").value(2))
                .andExpect(jsonPath("$.blocks[0].kind").value("SESSION_STEPS"));
        mvc.perform(get("/api/v1/analytics/sessions/nope").with(a.auth())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("API key monthly budget → 402 key-budget-exceeded; the keys list shows cap and month-to-date spend")
    void keyBudget() throws Exception {
        Account a = signUp();
        JsonNode created = createKey(a);
        String key = created.get("raw_key").asText();
        String id = created.at("/key/id").asText();
        call(v1(key, completion("before cap"))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/tenant/api-keys/" + id + "/policy").with(a.auth()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"k\",\"rate_limit_rpm\":600,\"rate_limit_tpm\":1000000,\"monthly_budget_usd\":0.0001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthly_budget_usd").value(0.0001));
        call(v1(key, completion("after cap")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/key-budget-exceeded"));
        mvc.perform(get("/api/v1/tenant/api-keys").with(a.auth()))
                .andExpect(jsonPath("$[0].monthly_budget_usd").value(0.0001))
                .andExpect(jsonPath("$[0].month_to_date_usd").exists());
        mvc.perform(put("/api/v1/tenant/api-keys/" + id + "/policy").with(a.auth()).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"k\",\"rate_limit_rpm\":600,\"rate_limit_tpm\":1000000,\"monthly_budget_usd\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("soft limit: past the threshold the downgrade model answers first (header + log); restricted keys keep their chain")
    void budgetDowngrade() throws Exception {
        Account a = signUp();
        JsonNode created = createKey(a);
        String key = created.get("raw_key").asText();
        // $0.50 settled of a $1.00 limit = 50 %
        jdbc.update("update tenants set hard_budget_limit_usd = 1 where id = ?", a.tenantId());
        jdbc.update("""
                insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description)
                values (?, 'synthetic_spend', 'SETTLE', 0.5, 99.5, 'test spend')""", a.tenantId());
        mvc.perform(put("/api/v1/tenant/cost-controls").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"downgrade_at_pct\":40,\"downgrade_model\":\"CLAUDE-HAIKU-4-5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downgrade_model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.downgrade_active").value(true))
                .andExpect(jsonPath("$.used_pct").value(50.0));
        call(v1(key, completion("downgrade me")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-AetherGate-Downgraded-To", "claude-haiku-4-5"))
                .andExpect(jsonPath("$.aethergate.downgraded_to").value("claude-haiku-4-5"));
        assertThat(count("select count(*) from llm_request_logs where tenant_id = ? and routing_reason = 'DOWNGRADE'"
                + " and model_used = 'claude-haiku-4-5'", a.tenantId())).isEqualTo(1);
        mvc.perform(get("/api/v1/analytics/requests?routing=downgrade&range=24h").with(a.auth()))
                .andExpect(jsonPath("$.total").value(1));

        // a key restricted to gpt-4o-mini cannot be downgraded to a model it may not use
        String id = created.at("/key/id").asText();
        mvc.perform(put("/api/v1/tenant/api-keys/" + id + "/policy").with(a.auth()).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"k\",\"rate_limit_rpm\":600,\"rate_limit_tpm\":1000000,\"allowed_models\":[\"gpt-4o-mini\"]}"))
                .andExpect(status().isOk());
        call(v1(key, completion("restricted"))).andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-AetherGate-Downgraded-To"));

        // validation
        mvc.perform(put("/api/v1/tenant/cost-controls").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"downgrade_at_pct\":40}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/tenant/cost-controls").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"downgrade_at_pct\":40,\"downgrade_model\":\"gpt-9\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/tenant/cost-controls").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"downgrade_at_pct\":400,\"downgrade_model\":\"gpt-4o-mini\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/tenant/cost-controls").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_max_requests\":0}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/tenant/cost-controls").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.downgrade_active").value(false));
    }

    @Test
    @DisplayName("request explorer filters by user, session, tag, cache, status and pages; unknown filters are 400")
    void requestExplorer() throws Exception {
        Account a = signUp();
        String key = createKey(a).get("raw_key").asText();
        String prompt = "{\"max_tokens\":40,\"messages\":[{\"role\":\"user\",\"content\":\"repeat after me\"}]}";
        call(v1(key, prompt).header("X-AetherGate-User", "erin").header("X-AetherGate-Tags", "feature=faq")).andExpect(status().isOk());
        call(v1(key, prompt).header("X-AetherGate-User", "erin")).andExpect(status().isOk()); // cache HIT
        call(v1(key, completion("other")).header("X-AetherGate-Session", "s-9")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/analytics/requests?range=24h").with(a.auth()))
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.rows.length()").value(3));
        mvc.perform(get("/api/v1/analytics/requests?end_user=erin&cache=HIT").with(a.auth()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].provider").value("CACHE"))
                .andExpect(jsonPath("$.filters.end_user").value("erin"));
        mvc.perform(get("/api/v1/analytics/requests?tag=feature:faq").with(a.auth()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].tags.feature").value("faq"));
        mvc.perform(get("/api/v1/analytics/requests?session=s-9&status=ok&provider=openai&model=gpt-4o-mini").with(a.auth()))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/analytics/requests?status=error").with(a.auth())).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/analytics/requests?project=unassigned&size=2&page=1").with(a.auth()))
                .andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.page").value(1));
        String requestId = jdbc.queryForObject("select request_id from llm_request_logs where tenant_id = ? and session_id = 's-9'",
                String.class, a.tenantId());
        mvc.perform(get("/api/v1/analytics/requests?q=" + requestId.substring(0, 10)).with(a.auth()))
                .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(1)));
        String keyId = jdbc.queryForObject("select id::text from api_keys where tenant_id = ?", String.class, a.tenantId());
        mvc.perform(get("/api/v1/analytics/requests?api_key=" + keyId).with(a.auth())).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/v1/analytics/requests?project=" + UUID.randomUUID()).with(a.auth())).andExpect(jsonPath("$.total").value(0));
        for (String bad : List.of("colour=red", "tag=nocolon", "cache=MAYBE", "status=meh", "api_key=xyz", "page=abc")) {
            mvc.perform(get("/api/v1/analytics/requests?" + bad).with(a.auth())).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("playground runs carry end user, session and tags too")
    void playgroundAttribution() throws Exception {
        Account a = signUp();
        MvcResult r = mvc.perform(post("/api/v1/playground/completions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"hello " + UUID.randomUUID() + "\",\"end_user\":\"pg-user\",\"session_id\":\"pg-run\","
                        + "\"tags\":\"feature=playground\"}")).andReturn();
        mvc.perform(asyncDispatch(r)).andExpect(status().isOk());
        assertThat(count("select count(*) from llm_request_logs where tenant_id = ? and end_user_id = 'pg-user' and session_id = 'pg-run'"
                + " and tags ->> 'feature' = 'playground' and api_key_id is null", a.tenantId())).isEqualTo(1);
    }

    private void insertLog(UUID tenant, Instant at, String model, int prompt, int completion, BigDecimal cost, String session) {
        jdbc.update("""
                insert into llm_request_logs (tenant_id, request_id, provider_used, model_used, prompt_tokens, completion_tokens,
                    total_cost_usd, saved_cost_usd, latency_ms, ttft_ms, cache_status, failover_triggered, status_code, created_at,
                    session_id)
                values (?, ?, 'ANTHROPIC', ?, ?, ?, ?, 0, 500, 200, 'MISS', false, 200, ?, ?)""",
                tenant, "req_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), model, prompt, completion, cost,
                Timestamp.from(at), session);
    }

    @Test
    @DisplayName("cost anomaly: a spike far above the robust weekly baseline is stored and announced exactly once")
    void anomalies() throws Exception {
        Account a = signUp();
        mvc.perform(post("/api/v1/tenant/webhooks").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ops\",\"url\":\"http://127.0.0.1:9/hook\",\"event_types\":[\"cost.anomaly_detected\"]}"))
                .andExpect(status().isCreated());
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        for (int h = 2; h <= 170; h += 3) { // a quiet week: one cent-ish every three hours
            insertLog(a.tenantId(), hour.minus(Duration.ofHours(h)).plusSeconds(60), "claude-haiku-4-5", 1000, 100,
                    new BigDecimal("0.0015"), null);
        }
        for (int i = 0; i < 20; i++) { // the spike: one runaway session, one hour ago
            insertLog(a.tenantId(), hour.minus(Duration.ofHours(1)).plusSeconds(60L + i), "claude-haiku-4-5", 4000, 900,
                    new BigDecimal("0.0085"), "loop-1");
        }
        int first = TenantContext.callAs(a.tenantId(), () -> anomalies.scan(48));
        int again = TenantContext.callAs(a.tenantId(), () -> anomalies.scan(48));
        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(count("select count(*) from webhook_deliveries where tenant_id = ? and event_type = 'cost.anomaly_detected'",
                a.tenantId())).isEqualTo(1);
        mvc.perform(get("/api/v1/analytics/anomalies").with(a.auth()))
                .andExpect(jsonPath("$.hours.length()").value(AnomalyService.VIEW_HOURS))
                .andExpect(jsonPath("$.anomalies.length()").value(1))
                .andExpect(jsonPath("$.anomalies[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.anomalies[0].top_dimension").value(containsString("session loop-1")))
                .andExpect(jsonPath("$.hours[46].anomalous").value(true));
    }

    @Test
    @DisplayName("forecast projects month end from the 7-day run rate; recommendations price right-sizing from real tokens")
    void forecastAndRecommendations() throws Exception {
        Account a = signUp();
        String key = createKey(a).get("raw_key").asText();
        call(v1(key, completion("forecast"))).andExpect(status().isOk());
        jdbc.update("""
                insert into api_keys (tenant_id, name, key_prefix, key_hash, rate_limit_rpm, rate_limit_tpm, created_at)
                values (?, 'Old batch job', 'ag_live_old1', ?, 60, 1000, now() - interval '40 days')""",
                a.tenantId(), UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        Instant now = Instant.now();
        for (int i = 0; i < 40; i++) { // short answers on the pricier model: classic right-sizing candidate
            insertLog(a.tenantId(), now.minus(Duration.ofHours(i * 3L)), "claude-haiku-4-5", 1200, 80, new BigDecimal("0.0016"), null);
        }

        MvcResult f = mvc.perform(get("/api/v1/analytics/forecast").with(a.auth())).andExpect(status().isOk()).andReturn();
        JsonNode forecast = read(f);
        double mtd = forecast.get("month_to_date_usd").asDouble();
        double projected = forecast.get("projected_month_end_usd").asDouble();
        assertThat(projected).isGreaterThanOrEqualTo(mtd);
        assertThat(forecast.get("projected_low_usd").asDouble()).isLessThanOrEqualTo(projected);
        assertThat(forecast.get("projected_high_usd").asDouble()).isGreaterThanOrEqualTo(projected);
        assertThat(forecast.get("points").size()).isEqualTo(forecast.get("days_in_month").asInt());
        assertThat(forecast.get("status").asText()).isEqualTo("ON_TRACK");
        assertThat(forecast.get("available_balance_usd").asDouble()).isGreaterThan(99.0);

        MvcResult r = mvc.perform(get("/api/v1/analytics/recommendations").with(a.auth())).andExpect(status().isOk()).andReturn();
        JsonNode recs = read(r);
        List<String> kinds = new java.util.ArrayList<>();
        recs.get("items").forEach(n -> kinds.add(n.get("kind").asText()));
        assertThat(kinds).contains("MODEL_RIGHTSIZING", "PROMPT_BLOAT", "IDLE_KEYS", "NO_BUDGET_ALERTS");
        JsonNode rightsizing = recs.get("items").get(kinds.indexOf("MODEL_RIGHTSIZING"));
        assertThat(rightsizing.get("title").asText()).contains("claude-haiku-4-5", "gpt-4o-mini");
        assertThat(rightsizing.get("monthly_savings_usd").asDouble()).isGreaterThan(0.04);
        assertThat(recs.get("total_monthly_savings_usd").asDouble()).isGreaterThan(0.04);
    }

    @Test
    @DisplayName("forecast flags a month that will breach the limit, and the advisor then suggests the soft-limit downgrade")
    void forecastAtRisk() throws Exception {
        Account a = signUp();
        jdbc.update("update tenants set hard_budget_limit_usd = 0.5 where id = ?", a.tenantId());
        Instant now = Instant.now();
        jdbc.update("""
                insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description, created_at)
                values (?, 'synthetic_today', 'SETTLE', 0.49, 99.5, 'test spend', ?)""", a.tenantId(), Timestamp.from(now.minusSeconds(60)));
        mvc.perform(get("/api/v1/analytics/forecast").with(a.auth()))
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.oneOf("AT_RISK", "OVER_LIMIT")))
                .andExpect(jsonPath("$.balance_runway_days").isNumber());
        mvc.perform(get("/api/v1/analytics/recommendations").with(a.auth()))
                .andExpect(jsonPath("$.items[?(@.kind == 'ENABLE_SOFT_LIMIT')]").exists());
    }

    @Test
    @DisplayName("FOCUS export: spec columns, billed cost equals the ledger's settled spend, CSV-safe cells")
    void focusExport() throws Exception {
        Account a = signUp();
        String key = createKey(a).get("raw_key").asText();
        for (int i = 0; i < 3; i++) {
            call(v1(key, completion("focus " + i)).header("X-AetherGate-Tags", "feature=export,team=finops")).andExpect(status().isOk());
        }
        MvcResult r = mvc.perform(get("/api/v1/analytics/export/focus").with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andReturn();
        String csv = r.getResponse().getContentAsString();
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo(String.join(",", FocusExporter.COLUMNS));
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(csv).contains("\"{\"\"aethergate/api-key\"\"", "OpenAI API", "AI and Machine Learning", "\"\"feature\"\":\"\"export\"\"");
        BigDecimal settled = jdbc.queryForObject("""
                select coalesce(sum(amount_usd), 0) from ledger_entries where tenant_id = ? and entry_type = 'SETTLE'
                and created_at >= date_trunc('month', now() at time zone 'UTC') at time zone 'UTC'""", BigDecimal.class, a.tenantId());
        assertThat(new BigDecimal(r.getResponse().getHeader("X-AetherGate-Billed-Cost"))).isEqualByComparingTo(settled);
        mvc.perform(get("/api/v1/analytics/export/focus?month=2026-13").with(a.auth())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/export/focus?month=2001-01").with(a.auth()))
                .andExpect(header().string("X-AetherGate-Rows", "0"));
    }

    @Test
    @DisplayName("analytics are tenant-isolated and cost controls need ADMIN")
    void isolationAndRbac() throws Exception {
        Account a = signUp();
        Account b = signUp();
        String key = createKey(a).get("raw_key").asText();
        call(v1(key, completion("mine")).header("X-AetherGate-Session", "secret-run")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/analytics/sessions/secret-run").with(b.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/analytics/requests").with(b.auth())).andExpect(jsonPath("$.total").value(0));
        costControls.evictCaches();

        // invite a viewer into A, accept, then try to change the controls
        MvcResult invite = mvc.perform(post("/api/v1/tenant/members").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"viewer-" + b.tenantId() + "@example.com\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated())
                .andReturn();
        String token = read(invite).get("token").asText();
        UUID viewerId = UUID.randomUUID();
        JwtRequestPostProcessor viewer = jwt().jwt(j -> j.subject(viewerId.toString())
                .claim("email", "viewer-" + b.tenantId() + "@example.com").audience(List.of("authenticated")));
        mvc.perform(post("/api/v1/invitations/accept").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/tenant/cost-controls").with(viewer).header("X-Workspace-Id", a.tenantId().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/analytics/attribution").with(viewer).header("X-Workspace-Id", a.tenantId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_requests").value(1));
    }
}
