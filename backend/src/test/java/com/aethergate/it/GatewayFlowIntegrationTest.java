package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aethergate.service.tenant.ApiKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** End-to-end through HTTP: API-key auth → rate limit → cache → reserve → route → settle → log. */
@AutoConfigureMockMvc
class GatewayFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String newKey(UUID tenant, int rpm, int tpm) {
        String raw = ApiKeys.generate();
        jdbc.update("""
                insert into api_keys (tenant_id, name, key_prefix, key_hash, rate_limit_rpm, rate_limit_tpm)
                values (?, 'test', ?, ?, ?, ?)""", tenant, ApiKeys.displayPrefix(raw), ApiKeys.sha256Hex(raw), rpm, tpm);
        return raw;
    }

    /** Non-streaming responses are also written asynchronously; follow the async dispatch when started. */
    private ResultActions call(MockHttpServletRequestBuilder builder) throws Exception {
        ResultActions actions = mvc.perform(builder);
        MvcResult result = actions.andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)) : actions;
    }

    private static String body(String prompt, boolean stream) {
        return """
                {"model":"auto","stream":%s,"max_tokens":200,"messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"%s"}]}
                """.formatted(stream, prompt);
    }

    @Test
    @DisplayName("missing or unknown API key → 401 problem+json")
    void rejectsUnauthenticated() throws Exception {
        call(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body("hi", false)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/unauthorized"));
        call(post("/v1/chat/completions").header("X-API-Key", "ag_live_doesnotexist000000000000")
                        .contentType(MediaType.APPLICATION_JSON).content(body("hi", false)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MISS settles RESERVE = SETTLE + RELEASE; identical prompt is then a $0 cache HIT")
    void completionSettlesThenCacheHits() throws Exception {
        UUID tenant = newTenant("Flow Co", new BigDecimal("10"));
        String key = newKey(tenant, 60, 100_000);

        MvcResult first = call(post("/v1/chat/completions").header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("How does the metering ledger reserve budget?", false)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(header().exists("X-RateLimit-Remaining-Requests"))
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value(containsString("RESERVE")))
                .andExpect(jsonPath("$.aethergate.cache_status").value("MISS"))
                .andExpect(jsonPath("$.aethergate.provider").value("OPENAI"))
                .andReturn();
        JsonNode telemetry = json.readTree(first.getResponse().getContentAsString()).get("aethergate");
        String requestId = telemetry.get("request_id").asText();
        BigDecimal reservedUsd = new BigDecimal(telemetry.get("reserved_usd").asText());
        BigDecimal settledUsd = new BigDecimal(telemetry.get("settled_usd").asText());
        BigDecimal releasedUsd = new BigDecimal(telemetry.get("released_usd").asText());
        assertThat(reservedUsd).isEqualByComparingTo(settledUsd.add(releasedUsd));
        assertThat(settledUsd).isPositive();
        assertThat(count("select count(*) from ledger_entries where request_id = ?", requestId)).isEqualTo(3);
        assertThat(available(tenant)).isEqualByComparingTo(new BigDecimal("10").subtract(settledUsd));
        assertThat(reserved(tenant)).isEqualByComparingTo("0");

        call(post("/v1/chat/completions").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("How does the metering ledger reserve budget?", false)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andExpect(jsonPath("$.aethergate.provider").value("CACHE"))
                .andExpect(jsonPath("$.aethergate.settled_usd").value("0.00000000"))
                .andExpect(jsonPath("$.aethergate.saved_usd").value(settledUsd.toPlainString()));
        assertThat(count("select count(*) from ledger_entries where tenant_id = ?", tenant)).isEqualTo(4); // top-up + 3
        assertThat(count("select count(*) from llm_request_logs where tenant_id = ? and cache_status = 'HIT'", tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("stream=true emits OpenAI chunks, a telemetry event and [DONE]")
    void streamsServerSentEvents() throws Exception {
        UUID tenant = newTenant("Stream Co", new BigDecimal("10"));
        String key = newKey(tenant, 60, 100_000);
        MvcResult started = mvc.perform(post("/v1/chat/completions").header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Tell me about virtual threads", true)))
                .andExpect(request().asyncStarted())
                .andReturn();
        String sse = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(sse).contains("\"object\":\"chat.completion.chunk\"", "\"role\":\"assistant\"",
                "event: aethergate.telemetry", "\"total_tokens\"", "data: [DONE]");
        assertThat(started.getResponse().getContentType()).startsWith("text/event-stream");
    }

    @Test
    @DisplayName("X-Simulate-Provider-Failure fails the primary over to the fallback")
    void failsOverToFallback() throws Exception {
        UUID tenant = newTenant("Failover Co", new BigDecimal("10"));
        String key = newKey(tenant, 60, 100_000);
        call(post("/v1/chat/completions").header("X-API-Key", key).header("X-Simulate-Provider-Failure", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(body("circuit breaker failover please", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aethergate.provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.aethergate.failover_triggered").value(true))
                .andExpect(jsonPath("$.aethergate.attempts[0]").value(containsString("simulated HTTP 503")));
        assertThat(count("select count(*) from llm_request_logs where tenant_id = ? and failover_triggered", tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("no healthy provider → 503, the whole hold is released")
    void allProvidersDownReleasesHold() throws Exception {
        UUID tenant = newTenant("Single Co", new BigDecimal("3"));
        jdbc.update("update provider_configs set is_enabled = false where tenant_id = ? and priority_order = 2", tenant);
        String key = newKey(tenant, 60, 100_000);
        call(post("/v1/chat/completions").header("X-API-Key", key).header("X-Simulate-Provider-Failure", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(body("anything at all", false)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/providers-unavailable"));
        assertThat(available(tenant)).isEqualByComparingTo("3");
        assertThat(sum(tenant, "RESERVE")).isEqualByComparingTo(sum(tenant, "RELEASE"));

        MvcResult started = mvc.perform(post("/v1/chat/completions").header("X-API-Key", key).header("X-Simulate-Provider-Failure", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(body("anything else", true)))
                .andExpect(request().asyncStarted()).andReturn();
        String sse = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
        assertThat(sse).contains("event: error", "providers-unavailable", "[DONE]");
        assertThat(available(tenant)).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("balance below the pre-flight hold → 402 insufficient-quota")
    void insufficientQuota() throws Exception {
        UUID tenant = newTenant("Broke Co", new BigDecimal("0.00001"));
        String key = newKey(tenant, 60, 100_000);
        call(post("/v1/chat/completions").header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("hello", false)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/insufficient-quota"))
                .andExpect(jsonPath("$.required_usd").exists());
    }

    @Test
    @DisplayName("RPM bucket empty → 429 with Retry-After")
    void rateLimited() throws Exception {
        UUID tenant = newTenant("Limited Co", new BigDecimal("10"));
        String key = newKey(tenant, 1, 100_000);
        call(post("/v1/chat/completions").header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body("first", false))).andExpect(status().isOk());
        call(post("/v1/chat/completions").header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("second", false)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.limit_type").value("requests/min"));
    }

    @Test
    void validationErrorsAreProblemDetails() throws Exception {
        UUID tenant = newTenant("Invalid Co", new BigDecimal("1"));
        String key = newKey(tenant, 60, 100_000);
        call(post("/v1/chat/completions").header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        call(post("/v1/chat/completions").header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[{\"role\":\"wizard\",\"content\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsTenantModels() throws Exception {
        UUID tenant = newTenant("Models Co", new BigDecimal("1"));
        String key = newKey(tenant, 60, 100_000);
        mvc.perform(get("/v1/models").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.data[1].owned_by").value("ANTHROPIC"))
                .andExpect(content().string(not(containsString("key_hash"))));
    }
}
