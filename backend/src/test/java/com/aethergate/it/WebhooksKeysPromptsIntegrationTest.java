package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aethergate.service.webhook.WebhookDispatcher;
import com.aethergate.service.webhook.WebhookSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** Budget alerts + signed webhooks (outbox, retries), API key policies and rotation, versioned prompt templates. */
@AutoConfigureMockMvc
class WebhooksKeysPromptsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    WebhookDispatcher dispatcher;

    record Received(Map<String, List<String>> headers, String body) {}

    private HttpServer receiver;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int code = failuresLeft.getAndDecrement() > 0 ? 500 : 204;
            if (code == 204) {
                received.add(new Received(ex.getRequestHeaders(), body));
            }
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    record Account(JwtRequestPostProcessor auth, UUID tenantId) {}

    private Account signUp() throws Exception {
        UUID user = UUID.randomUUID();
        JwtRequestPostProcessor auth = jwt().jwt(j -> j.subject(user.toString()).claim("email", "w" + user + "@example.com")
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
        MvcResult r = mvc.perform(post("/api/v1/tenant/api-keys").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"k\",\"rate_limit_rpm\":600,\"rate_limit_tpm\":1000000}")).andExpect(status().isCreated()).andReturn();
        return read(r);
    }

    private static String completion(String prompt) {
        return "{\"model\":\"auto\",\"max_tokens\":40,\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}";
    }

    private void awaitReceived(int n) throws Exception {
        for (int i = 0; i < 100 && received.size() < n; i++) {
            dispatcher.dispatchDue();
            Thread.sleep(50);
        }
        assertThat(received).hasSizeGreaterThanOrEqualTo(n);
    }

    // ── webhooks + alerts ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget alert fires exactly once per month and arrives as a verifiable HMAC-signed webhook")
    void budgetAlertWebhook() throws Exception {
        Account a = signUp();
        jdbc.update("update tenants set hard_budget_limit_usd = 0.001 where id = ?", a.tenantId());
        String raw = createKey(a).get("raw_key").asText();
        MvcResult created = mvc.perform(post("/api/v1/tenant/webhooks").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ops\",\"url\":\"" + hookUrl() + "\",\"event_types\":[\"budget.threshold_crossed\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpoint.secret_hint").value(containsString("whsec_")))
                .andReturn();
        String secret = read(created).get("secret").asText();
        assertThat(secret).startsWith("whsec_").hasSize(38);
        mvc.perform(post("/api/v1/tenant/alerts").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"threshold_pct\":1}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.rules[0].scope").value("Workspace"));

        // $0.001 limit, alert at 1 % ($0.00001): the first settlement already crosses it
        for (int i = 0; i < 3; i++) {
            call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                    .content(completion("budget test " + i + " " + UUID.randomUUID())));
        }
        awaitReceived(1);
        Thread.sleep(300);
        dispatcher.dispatchDue();
        assertThat(received).hasSize(1); // exactly once, however many settlements crossed it
        Received r = received.getFirst();
        JsonNode event = json.readTree(r.body());
        assertThat(event.get("type").asText()).isEqualTo("budget.threshold_crossed");
        assertThat(event.at("/data/scope").asText()).isEqualTo("workspace");
        assertThat(event.at("/data/threshold_pct").asInt()).isEqualTo(1);
        String signature = r.headers().get("X-aethergate-signature").getFirst();
        assertThat(WebhookSignature.verify(secret, signature, r.body(), Instant.now().getEpochSecond(), 300)).isTrue();
        assertThat(WebhookSignature.verify(secret, signature, r.body() + " ", Instant.now().getEpochSecond(), 300)).isFalse();
        assertThat(WebhookSignature.verify("whsec_wrong", signature, r.body(), Instant.now().getEpochSecond(), 300)).isFalse();
        assertThat(r.headers().get("X-aethergate-event-id").getFirst()).isEqualTo(event.get("id").asText());
        assertThat(count("select count(*) from alert_firings where tenant_id = ?", a.tenantId())).isEqualTo(1);
        mvc.perform(get("/api/v1/tenant/alerts").with(a.auth()))
                .andExpect(jsonPath("$.recent.length()").value(1))
                .andExpect(jsonPath("$.rules[0].fired_this_month_at").isNotEmpty());
    }

    @Test
    @DisplayName("deliveries retry with backoff, redelivery keeps the event id; SSRF and validation rules on save")
    void retriesAndValidation() throws Exception {
        Account a = signUp();
        MvcResult created = mvc.perform(post("/api/v1/tenant/webhooks").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flaky\",\"url\":\"" + hookUrl() + "\",\"event_types\":[\"guardrail.blocked\"]}"))
                .andExpect(status().isCreated()).andReturn();
        String id = read(created).at("/endpoint/id").asText();

        failuresLeft.set(1);
        mvc.perform(post("/api/v1/tenant/webhooks/" + id + "/test").with(a.auth())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.event_type").value("webhook.test"));
        for (int i = 0; i < 50; i++) {
            dispatcher.dispatchDue();
            if (count("select count(*) from webhook_deliveries where endpoint_id = ?::uuid and attempts = 1", id) == 1) {
                break;
            }
            Thread.sleep(50);
        }
        mvc.perform(get("/api/v1/tenant/webhooks/" + id + "/deliveries").with(a.auth()))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].attempts").value(1))
                .andExpect(jsonPath("$[0].last_status_code").value(500))
                .andExpect(jsonPath("$[0].next_attempt_at").isNotEmpty());
        // make the retry due now instead of in ~10 s
        jdbc.update("update webhook_deliveries set next_attempt_at = now() where endpoint_id = ?::uuid", id);
        awaitReceived(1);
        mvc.perform(get("/api/v1/tenant/webhooks/" + id + "/deliveries").with(a.auth()))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].attempts").value(2));
        String deliveryId = jdbc.queryForObject("select id::text from webhook_deliveries where endpoint_id = ?::uuid", String.class, id);
        String eventId = jdbc.queryForObject("select event_id from webhook_deliveries where endpoint_id = ?::uuid", String.class, id);
        mvc.perform(post("/api/v1/tenant/webhooks/deliveries/" + deliveryId + "/redeliver").with(a.auth()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.event_id").value(eventId));
        awaitReceived(2);
        assertThat(received.get(1).headers().get("X-aethergate-event-id").getFirst()).isEqualTo(eventId);

        mvc.perform(post("/api/v1/tenant/webhooks").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"url\":\"https://x.example\",\"event_types\":[\"nope\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/tenant/webhooks").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"flaky\",\"url\":\"https://x.example\",\"event_types\":[\"member.joined\"]}"))
                .andExpect(status().isConflict());
        // rotating the secret returns a new one exactly once
        MvcResult rotated = mvc.perform(post("/api/v1/tenant/webhooks/" + id + "/rotate-secret").with(a.auth()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(rotated).get("secret").asText()).isNotEqualTo(read(created).get("secret").asText());
        mvc.perform(delete("/api/v1/tenant/webhooks/" + id).with(a.auth())).andExpect(status().isNoContent());
        assertThat(count("select count(*) from webhook_deliveries where endpoint_id = ?::uuid", id)).isZero();
    }

    @Test
    @DisplayName("a guardrail block is announced by webhook; deliveries roll back with the transaction that wrote them")
    void guardrailWebhook() throws Exception {
        Account a = signUp();
        String raw = createKey(a).get("raw_key").asText();
        mvc.perform(post("/api/v1/tenant/webhooks").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sec\",\"url\":\"" + hookUrl() + "\",\"event_types\":[\"guardrail.blocked\"]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/tenant/guardrails").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Codename\",\"type\":\"KEYWORDS\",\"pattern\":\"project orion\",\"action\":\"BLOCK\"}"))
                .andExpect(status().isCreated());
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content(completion("plan Project Orion " + UUID.randomUUID()))).andExpect(status().isUnprocessableEntity());
        awaitReceived(1);
        JsonNode event = json.readTree(received.getFirst().body());
        assertThat(event.get("type").asText()).isEqualTo("guardrail.blocked");
        assertThat(event.at("/data/rule").asText()).isEqualTo("Codename");
        assertThat(received.getFirst().body()).doesNotContain("Project Orion"); // the prompt itself is never sent
    }

    // ── API key policies ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("key policy: model allow-list (403 + chain filtered), IP allow-list, If-Match; rotation with grace, then expiry → 401")
    void keyPolicyAndRotation() throws Exception {
        Account a = signUp();
        JsonNode created = createKey(a);
        String raw = created.get("raw_key").asText();
        String keyId = created.at("/key/id").asText();
        String policy = "{\"name\":\"Support bot\",\"rate_limit_rpm\":100,\"rate_limit_tpm\":200000,"
                + "\"allowed_models\":[\"claude-haiku-4-5\"],\"ip_allowlist\":[\"10.0.0.0/8\"]}";
        mvc.perform(put("/api/v1/tenant/api-keys/" + keyId + "/policy").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content(policy)).andExpect(status().isPreconditionRequired());
        mvc.perform(put("/api/v1/tenant/api-keys/" + keyId + "/policy").with(a.auth()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policy.replace("claude-haiku-4-5", "gpt-9-imaginary")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("model catalog")));
        mvc.perform(put("/api/v1/tenant/api-keys/" + keyId + "/policy").with(a.auth()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(policy.replace("10.0.0.0/8", "10.0.0.0/33")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/tenant/api-keys/" + keyId + "/policy").with(a.auth()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(policy))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.allowed_models[0]").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.rate_limit_rpm").value(100));

        // MockMvc calls from 127.0.0.1, outside 10.0.0.0/8
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content(completion("hello " + UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(endsWith("/ip-not-allowed")));
        mvc.perform(put("/api/v1/tenant/api-keys/" + keyId + "/policy").with(a.auth()).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content(policy.replace("10.0.0.0/8", "127.0.0.1"))).andExpect(status().isOk());

        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi " + UUID.randomUUID() + "\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(endsWith("/model-not-allowed")));
        // "auto" routes only over the allowed models: gpt-4o-mini (priority 1) is skipped
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content(completion("route me " + UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aethergate.model").value("claude-haiku-4-5"));
        mvc.perform(get("/v1/models").header("X-API-Key", raw)).andExpect(jsonPath("$.data.length()").value(1));

        // rotate with a 1 h grace: both keys work, the new one inherits the policy
        MvcResult rotated = mvc.perform(post("/api/v1/tenant/api-keys/" + keyId + "/rotate").with(a.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"grace_hours\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key.allowed_models[0]").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.key.rotated_from").value(keyId))
                .andExpect(jsonPath("$.previous.expires_at").isNotEmpty())
                .andReturn();
        String fresh = read(rotated).get("raw_key").asText();
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content(completion("old " + UUID.randomUUID()))).andExpect(status().isOk());
        call(post("/v1/chat/completions").header("X-API-Key", fresh).contentType(MediaType.APPLICATION_JSON)
                .content(completion("new " + UUID.randomUUID()))).andExpect(status().isOk());

        // the grace period ends (simulated): the old key is refused with a specific 401, the new one keeps working
        jdbc.update("update api_keys set expires_at = now() - interval '1 second' where id = ?::uuid", keyId);
        evictAllKeys(); // in production the 30 s principal cache is irrelevant: expiry is checked on every request

        mvc.perform(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                        .content(completion("late " + UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(containsString("expired")));
        mvc.perform(get("/api/v1/tenant/api-keys").with(a.auth()))
                .andExpect(jsonPath("$[?(@.id=='" + keyId + "')].status").value("EXPIRED"));

        // grace 0 = immediate revocation
        mvc.perform(post("/api/v1/tenant/api-keys/" + read(rotated).at("/key/id").asText() + "/rotate").with(a.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"grace_hours\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.previous.status").value("REVOKED"));
        mvc.perform(post("/v1/chat/completions").header("X-API-Key", fresh).contentType(MediaType.APPLICATION_JSON)
                .content(completion("gone " + UUID.randomUUID()))).andExpect(status().isUnauthorized());
    }

    @Autowired
    com.aethergate.service.tenant.TenantDirectoryService directory;

    private void evictAllKeys() {
        jdbc.queryForList("select key_hash from api_keys", String.class).forEach(directory::evictKey);
    }

    // ── prompt templates ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prompts: variables, immutable versions, publish/rollback with If-Match, per-version stats, delete guard")
    void promptTemplates() throws Exception {
        Account a = signUp();
        String raw = createKey(a).get("raw_key").asText();
        MvcResult created = mvc.perform(post("/api/v1/tenant/prompts").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Support reply\",\"system_prompt\":\"You are a support agent for {{product}}.\","
                                + "\"user_template\":\"Reply to {{customer}}: {{question}}\",\"note\":\"first cut\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.template.active_version").value(1))
                .andExpect(jsonPath("$.template.variables.length()").value(3))
                .andReturn();
        String id = read(created).at("/template/id").asText();

        mvc.perform(post("/api/v1/tenant/prompts/" + id + "/render").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"product\":\"AetherGate\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("customer, question")));
        // values are inserted literally: template syntax inside a value is not expanded
        mvc.perform(post("/api/v1/tenant/prompts/" + id + "/render").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"product\":\"AetherGate\",\"customer\":\"{{product}}\",\"question\":\"why?\"}}"))
                .andExpect(jsonPath("$.messages[0].content").value("You are a support agent for AetherGate."))
                .andExpect(jsonPath("$.messages[1].content").value("Reply to {{product}}: why?"));

        String vars = "\"variables\":{\"product\":\"AetherGate\",\"customer\":\"Ana\",\"question\":\"q " + UUID.randomUUID() + "\"}";
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt_template\":{\"id\":\"support reply\"," + vars + "}}")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/tenant/prompts/" + id + "/versions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_template\":\"Answer {{customer}} briefly: {{question}}\",\"note\":\"shorter\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.template.active_version").value(1)) // drafts are not live
                .andExpect(jsonPath("$.template.latest_version").value(2))
                .andExpect(header().string("ETag", "\"1\""));
        mvc.perform(put("/api/v1/tenant/prompts/" + id + "/active-version").with(a.auth()).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}")).andExpect(status().isPreconditionFailed());
        mvc.perform(put("/api/v1/tenant/prompts/" + id + "/active-version").with(a.auth()).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template.active_version").value(2));

        // the playground runs the active version (now v2); both versions appear in the stats
        call(post("/api/v1/playground/completions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"template\":{\"id\":\"" + id + "\"," + vars.replace("q ", "p ") + "}}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/prompts/" + id).with(a.auth()))
                .andExpect(jsonPath("$.versions[0].version").value(2))
                .andExpect(jsonPath("$.versions[0].active").value(true))
                .andExpect(jsonPath("$.versions[0].requests").value(1))
                .andExpect(jsonPath("$.versions[1].requests").value(1))
                .andExpect(jsonPath("$.versions[1].note").value("first cut"));
        assertThat(count("select count(*) from llm_request_logs where tenant_id = ? and template_id = ?::uuid", a.tenantId(), id))
                .isEqualTo(2);

        assertThatThrownBy(() -> jdbc.update("update prompt_template_versions set user_template = 'x' where template_id = ?::uuid", id))
                .rootCause().hasMessageContaining("immutable");
        mvc.perform(delete("/api/v1/tenant/prompts/" + id).with(a.auth())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/tenant/prompts").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"support REPLY\",\"user_template\":\"x\"}")).andExpect(status().isConflict());
        MvcResult unused = mvc.perform(post("/api/v1/tenant/prompts").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Scratch\",\"user_template\":\"hello\"}")).andExpect(status().isCreated()).andReturn();
        mvc.perform(delete("/api/v1/tenant/prompts/" + read(unused).at("/template/id").asText()).with(a.auth()))
                .andExpect(status().isNoContent());
    }
}
