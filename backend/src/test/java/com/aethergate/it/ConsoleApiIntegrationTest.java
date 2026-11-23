package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Management console API with Supabase-style JWTs: provisioning, dashboard, keys, routing, credit, playground. */
@AutoConfigureMockMvc
class ConsoleApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static JwtRequestPostProcessor user(UUID id, String email) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("email", email).audience(java.util.List.of("authenticated")));
    }

    @Test
    @DisplayName("first login provisions a workspace with $100 welcome credit and a two-provider chain")
    void firstLoginProvisionsWorkspace() throws Exception {
        UUID userId = UUID.randomUUID();
        MvcResult me = mvc.perform(get("/api/v1/me").with(user(userId, "ada.lovelace@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.name").value("Ada.lovelace's Workspace"))
                .andExpect(jsonPath("$.tenant.slug").value(startsWith("ada-lovelace-")))
                .andReturn();
        UUID tenantId = UUID.fromString(json.readTree(me.getResponse().getContentAsString()).at("/tenant/id").asText());
        assertThat(available(tenantId)).isEqualByComparingTo("100");
        assertThat(count("select count(*) from provider_configs where tenant_id = ?", tenantId)).isEqualTo(2);

        // second call resolves the same workspace
        mvc.perform(get("/api/v1/me").with(user(userId, "ada.lovelace@example.com")))
                .andExpect(jsonPath("$.tenant.id").value(tenantId.toString()));
    }

    @Test
    @DisplayName("overview: KPIs, series, breaker states and live RLS proof")
    void overview() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(post("/api/v1/tenant/top-up").with(user(userId, "ov@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount_usd\":\"50\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available_balance_usd").value("150.00000000"));

        for (String range : new String[]{"24h", "7d", "30d"}) {
            mvc.perform(get("/api/v1/dashboard/overview").param("range", range).with(user(userId, "ov@example.com")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.range").value(range))
                    .andExpect(jsonPath("$.kpis.available_balance_usd").value("150.00000000"))
                    .andExpect(jsonPath("$.providers[0].circuit_state").value("CLOSED"))
                    .andExpect(jsonPath("$.providers[0].live").value(false))
                    .andExpect(jsonPath("$.isolation.other_tenant_rows_visible").value(0));
        }
        mvc.perform(get("/api/v1/dashboard/overview").param("range", "1y").with(user(userId, "ov@example.com")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ledger page reports balanced invariants and filters by type")
    void ledgerPage() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(get("/api/v1/dashboard/ledger").with(user(userId, "ledger@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.entries[0].entry_type").value("TOP_UP"))
                .andExpect(jsonPath("$.totals_by_type.TOP_UP").value("100.00000000"));
        mvc.perform(get("/api/v1/dashboard/ledger").param("type", "SETTLE").with(user(userId, "ledger@example.com")))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    @DisplayName("API key lifecycle: created key works on the gateway, revoked key is rejected")
    void apiKeyLifecycle() throws Exception {
        UUID userId = UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/v1/tenant/api-keys").with(user(userId, "keys@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Production\",\"rate_limit_rpm\":120}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.raw_key").value(startsWith("ag_live_")))
                .andExpect(jsonPath("$.key.rate_limit_rpm").value(120))
                .andReturn();
        JsonNode node = json.readTree(created.getResponse().getContentAsString());
        String raw = node.get("raw_key").asText();
        String keyId = node.at("/key/id").asText();
        assertThat(count("select count(*) from api_keys where key_hash like 'ag_live%'")).isZero();

        mvc.perform(get("/api/v1/tenant/api-keys").with(user(userId, "keys@example.com")))
                .andExpect(jsonPath("$[0].name").value("Production"))
                .andExpect(jsonPath("$[0].raw_key").doesNotExist());
        mvc.perform(get("/v1/models").header("X-API-Key", raw)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/tenant/api-keys/" + keyId).with(user(userId, "keys@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/v1/models").header("X-API-Key", raw)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/tenant/api-keys/" + UUID.randomUUID()).with(user(userId, "keys@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("provider priority can be reordered and toggled, but never all disabled")
    void providerRouting() throws Exception {
        UUID userId = UUID.randomUUID();
        JsonNode providers = json.readTree(mvc.perform(get("/api/v1/tenant/providers").with(user(userId, "route@example.com")))
                .andReturn().getResponse().getContentAsString());
        String openai = providers.get(0).get("id").asText();
        String anthropic = providers.get(1).get("id").asText();

        mvc.perform(put("/api/v1/tenant/providers/order").with(user(userId, "route@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider_ids\":[\"" + anthropic + "\",\"" + openai + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$[0].priority_order").value(1));
        mvc.perform(get("/api/v1/tenant/providers").with(user(userId, "route@example.com")))
                .andExpect(jsonPath("$[0].provider").value("ANTHROPIC"));
        mvc.perform(put("/api/v1/tenant/providers/order").with(user(userId, "route@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider_ids\":[\"" + openai + "\"]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/v1/tenant/providers/" + openai).with(user(userId, "route@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(patch("/api/v1/tenant/providers/" + anthropic).with(user(userId, "route@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topUpValidation() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(post("/api/v1/tenant/top-up").with(user(userId, "tv@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount_usd\":\"5000\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("playground streams through the full pipeline, a re-send is a cache HIT")
    void playgroundStreamsAndCaches() throws Exception {
        UUID userId = UUID.randomUUID();
        String body = "{\"prompt\":\"What is a semantic cache?\",\"max_tokens\":300}";
        MvcResult first = mvc.perform(post("/api/v1/playground/completions").with(user(userId, "pg@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        String sse = mvc.perform(asyncDispatch(first)).andReturn().getResponse().getContentAsString();
        assertThat(sse).contains("\"cache_status\":\"MISS\"", "\"reserved_usd\"", "\"released_usd\"", "[DONE]");

        MvcResult second = mvc.perform(post("/api/v1/playground/completions").with(user(userId, "pg@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        String cached = mvc.perform(asyncDispatch(second)).andReturn().getResponse().getContentAsString();
        assertThat(cached).contains("\"cache_status\":\"HIT\"", "\"provider\":\"CACHE\"");

        MvcResult chaos = mvc.perform(post("/api/v1/playground/completions").with(user(userId, "pg@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"Explain failover\",\"system\":\"terse\",\"simulate_failure\":true}"))
                .andExpect(request().asyncStarted()).andReturn();
        String failover = mvc.perform(asyncDispatch(chaos)).andReturn().getResponse().getContentAsString();
        assertThat(failover).contains("\"failover_triggered\":true", "\"provider\":\"ANTHROPIC\"");
    }

    @Test
    void consoleRequiresJwtAndDemoIsOffWithoutConfig() throws Exception {
        mvc.perform(get("/api/v1/dashboard/overview")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/unauthorized"));
        mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.demo_available").value(false));
        mvc.perform(post("/api/v1/auth/demo-session")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://aethergate.dev/problems/demo-unavailable"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
