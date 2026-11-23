package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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

/** Tenant self-service CRUD beyond projects: guardrails, the model catalog, workspace settings. */
@AutoConfigureMockMvc
class TenantCrudIntegrationTest extends AbstractIntegrationTest {

    private static final String GUARDRAILS = "/api/v1/tenant/guardrails";
    private static final String PROVIDERS = "/api/v1/tenant/providers";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private record Account(JwtRequestPostProcessor auth, UUID tenantId) {}

    private Account signUp() throws Exception {
        UUID user = UUID.randomUUID();
        JwtRequestPostProcessor auth = jwt().jwt(j -> j.subject(user.toString()).claim("email", "c" + user + "@example.com")
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

    private String key(Account a) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tenant/api-keys").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"k\",\"rate_limit_rpm\":600,\"rate_limit_tpm\":1000000}")).andExpect(status().isCreated()).andReturn();
        return read(r).get("raw_key").asText();
    }

    private String rule(Account a, String name, String type, String pattern, String action) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("name", name, "type", type, "action", action,
                "pattern", pattern == null ? "" : pattern));
        MvcResult r = mvc.perform(post(GUARDRAILS).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return read(r).get("id").asText();
    }

    private static String completion(String prompt) {
        return "{\"model\":\"auto\",\"max_tokens\":60,\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}";
    }

    // ── guardrails ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("guardrails CRUD: 201/ETag, 409 duplicate, 400 invalid regex, PUT If-Match 428/412/200, toggle, 204")
    void guardrailCrud() throws Exception {
        Account a = signUp();
        MvcResult created = mvc.perform(post(GUARDRAILS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No secrets\",\"type\":\"regex\",\"pattern\":\"(?i)api[_-]?key\\\\s*=\\\\s*\\\\S+\",\"action\":\"block\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.type").value("REGEX"))
                .andExpect(jsonPath("$.action").value("BLOCK"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();
        String id = read(created).get("id").asText();

        mvc.perform(post(GUARDRAILS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"no SECRETS\",\"type\":\"PII_EMAIL\",\"action\":\"REDACT\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(GUARDRAILS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Broken\",\"type\":\"REGEX\",\"pattern\":\"([a-z\",\"action\":\"BLOCK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Invalid regular expression")));
        mvc.perform(post(GUARDRAILS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Odd\",\"type\":\"VIBES\",\"action\":\"BLOCK\"}"))
                .andExpect(status().isBadRequest());

        String update = "{\"name\":\"No secrets v2\",\"type\":\"KEYWORDS\",\"pattern\":\"password, api key\",\"action\":\"REDACT\"}";
        mvc.perform(put(GUARDRAILS + "/" + id).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(GUARDRAILS + "/" + id).with(a.auth()).header("If-Match", "\"3\"").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(put(GUARDRAILS + "/" + id).with(a.auth()).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.type").value("KEYWORDS"));

        mvc.perform(patch(GUARDRAILS + "/" + id).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get(GUARDRAILS).with(a.auth()))
                .andExpect(jsonPath("$.rules.length()").value(1))
                .andExpect(jsonPath("$.max_rules").value(20));
        mvc.perform(delete(GUARDRAILS + "/" + id).with(a.auth())).andExpect(status().isNoContent());
        mvc.perform(delete(GUARDRAILS + "/" + id).with(a.auth())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("gateway: BLOCK → 422 before any hold (ledger untouched); REDACT → providers and cache only see masked text")
    void guardrailsAtGateway() throws Exception {
        Account a = signUp();
        String raw = key(a);
        rule(a, "Confidential", "KEYWORDS", "project falcon", "BLOCK");
        rule(a, "Emails", "PII_EMAIL", null, "REDACT");
        int ledgerBefore = count("select count(*) from ledger_entries where tenant_id = ?", a.tenantId());

        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content(completion("Summarise Project Falcon for the board " + UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(endsWith("/guardrail-blocked")))
                .andExpect(jsonPath("$.rule").value("Confidential"));
        assertThat(count("select count(*) from ledger_entries where tenant_id = ?", a.tenantId())).isEqualTo(ledgerBefore);

        String unique = UUID.randomUUID().toString();
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content(completion("Reply to jane.doe@acme.example about invoice " + unique)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aethergate.redactions").value(1));
        // the semantic cache is written asynchronously: it must only ever hold the masked prompt
        String cached = null;
        for (int i = 0; i < 50 && cached == null; i++) {
            List<String> rows = jdbc.queryForList("select prompt_text from semantic_cache where tenant_id = ? and prompt_text like ?",
                    String.class, a.tenantId(), "%" + unique + "%");
            cached = rows.isEmpty() ? null : rows.getFirst();
            if (cached == null) {
                Thread.sleep(100);
            }
        }
        assertThat(cached).contains("[REDACTED:Emails]").doesNotContain("jane.doe@acme.example");

        mvc.perform(get(GUARDRAILS).with(a.auth()))
                .andExpect(jsonPath("$.rules[0].hits30d").value(1))
                .andExpect(jsonPath("$.rules[1].hits30d").value(1))
                .andExpect(jsonPath("$.recent_events.length()").value(2))
                .andExpect(jsonPath("$.recent_events[0].rule_name").value("Emails"));

        // the playground goes through the same pipeline
        mvc.perform(post("/api/v1/playground/completions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"what is project falcon?\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("dry run, cache invalidation after commit, and tenant isolation of rules")
    void dryRunInvalidationIsolation() throws Exception {
        Account a = signUp();
        Account b = signUp();
        String rawA = key(a);
        String rawB = key(b);
        String id = rule(a, "Cards", "PII_CARD", null, "BLOCK");

        mvc.perform(post(GUARDRAILS + "/test").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"charge 4111 1111 1111 1111\"}"))
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.blocked_by").value("Cards"));
        mvc.perform(post(GUARDRAILS + "/test").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"order 1234 5678 9012 3456\"}"))
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.matches.length()").value(0));

        String card = completion("charge 4111 1111 1111 1111 now " + UUID.randomUUID());
        call(post("/v1/chat/completions").header("X-API-Key", rawA).contentType(MediaType.APPLICATION_JSON).content(card))
                .andExpect(status().isUnprocessableEntity());
        // tenant B has no rules: the same prompt passes there
        call(post("/v1/chat/completions").header("X-API-Key", rawB).contentType(MediaType.APPLICATION_JSON).content(card))
                .andExpect(status().isOk());
        mvc.perform(get(GUARDRAILS).with(b.auth())).andExpect(jsonPath("$.rules.length()").value(0));
        mvc.perform(patch(GUARDRAILS + "/" + id).with(b.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isNotFound());

        // disabling takes effect on the very next request (enforcer cache dropped after commit)
        mvc.perform(patch(GUARDRAILS + "/" + id).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        call(post("/v1/chat/completions").header("X-API-Key", rawA).contentType(MediaType.APPLICATION_JSON)
                .content(completion("charge 4111 1111 1111 1111 now " + UUID.randomUUID()))).andExpect(status().isOk());
    }

    // ── model catalog ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("model catalog: add (priority n+1), 409 duplicate, 400 MOCK_LLM, reprice drives the next hold, delete renumbers")
    void modelCatalog() throws Exception {
        Account a = signUp();
        String raw = key(a);
        MvcResult added = mvc.perform(post(PROVIDERS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"gemini\",\"model_name\":\"gemini-2.5-flash\",\"input_cost_per1m\":\"0.30\",\"output_cost_per1m\":\"2.50\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("GEMINI"))
                .andExpect(jsonPath("$.priority_order").value(3))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();
        String gemini = read(added).get("id").asText();

        mvc.perform(post(PROVIDERS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GEMINI\",\"model_name\":\"GEMINI-2.5-FLASH\",\"input_cost_per1m\":\"1\",\"output_cost_per1m\":\"1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(PROVIDERS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"MOCK_LLM\",\"model_name\":\"sim\",\"input_cost_per1m\":\"1\",\"output_cost_per1m\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PROVIDERS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI\",\"model_name\":\"bad name!\",\"input_cost_per1m\":\"1\",\"output_cost_per1m\":\"1\"}"))
                .andExpect(status().isBadRequest());

        // the hold is priced at the most expensive model: make gemini absurdly expensive and watch the next RESERVE
        mvc.perform(put(PROVIDERS + "/" + gemini).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model_name\":\"gemini-2.5-pro\",\"input_cost_per1m\":\"0\",\"output_cost_per1m\":\"1000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model_name").value("gemini-2.5-pro"))
                .andExpect(jsonPath("$.output_cost_per1m").value("1000.00000000"));
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content(completion("price probe " + UUID.randomUUID()))).andExpect(status().isOk());
        BigDecimal hold = jdbc.queryForObject("""
                select amount_usd from ledger_entries where tenant_id = ? and entry_type = 'RESERVE' order by created_at desc limit 1""",
                BigDecimal.class, a.tenantId());
        assertThat(hold).isEqualByComparingTo("0.06"); // 60 max tokens x $1000 / 1M, prompt priced at $0

        mvc.perform(delete(PROVIDERS + "/" + gemini).with(a.auth())).andExpect(status().isNoContent());
        MvcResult chain = mvc.perform(get(PROVIDERS).with(a.auth())).andReturn();
        assertThat(read(chain).findValues("priority_order").stream().map(JsonNode::asInt).toList()).containsExactly(1, 2);

        // the last enabled model can never be deleted
        String first = read(chain).get(0).get("id").asText();
        String second = read(chain).get(1).get("id").asText();
        mvc.perform(patch(PROVIDERS + "/" + second).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(delete(PROVIDERS + "/" + first).with(a.auth())).andExpect(status().isConflict());
        mvc.perform(delete(PROVIDERS + "/" + second).with(a.auth())).andExpect(status().isNoContent());

        for (int i = 0; i < 5; i++) {
            mvc.perform(post(PROVIDERS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"provider\":\"OPENAI\",\"model_name\":\"m-" + i + "\",\"input_cost_per1m\":\"1\",\"output_cost_per1m\":\"1\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post(PROVIDERS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI\",\"model_name\":\"m-6\",\"input_cost_per1m\":\"1\",\"output_cost_per1m\":\"1\"}"))
                .andExpect(status().isConflict());
    }

    // ── workspace settings ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("workspace settings: rename shows in /me at once, limit cannot drop below committed spend, demo is read-only")
    void workspaceSettings() throws Exception {
        Account a = signUp();
        mvc.perform(get("/api/v1/tenant/settings").with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hard_budget_limit_usd").value("500.00000000"))
                .andExpect(jsonPath("$.committed_usd").value("0.00000000"));

        mvc.perform(put("/api/v1/tenant/settings").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Robotics\",\"hard_budget_limit_usd\":\"250\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Robotics"))
                .andExpect(jsonPath("$.hard_budget_limit_usd").value("250.00000000"));
        mvc.perform(get("/api/v1/me").with(a.auth())).andExpect(jsonPath("$.tenant.name").value("Acme Robotics"));

        jdbc.update("""
                insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description)
                values (?, 'req_spent', 'SETTLE', 3.5, 96.5, 'spent')""", a.tenantId());
        jdbc.update("update tenants set available_balance_usd = available_balance_usd - 3.5 where id = ?", a.tenantId());
        mvc.perform(put("/api/v1/tenant/settings").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Robotics\",\"hard_budget_limit_usd\":\"3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("committed spend")));
        mvc.perform(put("/api/v1/tenant/settings").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"hard_budget_limit_usd\":\"300\"}"))
                .andExpect(status().isBadRequest());

        jdbc.update("update tenants set is_demo = true where id = ?", a.tenantId());
        mvc.perform(put("/api/v1/tenant/settings").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Vandalised\",\"hard_budget_limit_usd\":\"300\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(endsWith("/demo-read-only")));
    }
}
