package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
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

import com.aethergate.config.TenantContext;
import com.aethergate.service.metering.MeteringLedgerService;
import com.aethergate.service.metering.ProjectBudgetExceededException;
import com.aethergate.service.project.ProjectArchivedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Projects (cost centres): CRUD semantics, optimistic concurrency, hierarchical budgets and tenant isolation. */
@AutoConfigureMockMvc
class ProjectsIntegrationTest extends AbstractIntegrationTest {

    private static final String PROJECTS = "/api/v1/tenant/projects";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MeteringLedgerService ledger;

    private record Account(JwtRequestPostProcessor auth, UUID tenantId) {}

    private Account signUp() throws Exception {
        UUID user = UUID.randomUUID();
        JwtRequestPostProcessor auth = jwt().jwt(j -> j.subject(user.toString()).claim("email", "p" + user + "@example.com")
                .audience(List.of("authenticated")));
        MvcResult me = mvc.perform(get("/api/v1/me").with(auth)).andExpect(status().isOk()).andReturn();
        return new Account(auth, UUID.fromString(read(me).at("/tenant/id").asText()));
    }

    private JsonNode read(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private UUID createProject(Account a, String name, String budget) throws Exception {
        String body = budget == null ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"monthly_budget_usd\":\"%s\"}".formatted(name, budget);
        MvcResult r = mvc.perform(post(PROJECTS).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(read(r).get("id").asText());
    }

    private String createKey(Account a, UUID projectId) throws Exception {
        String body = "{\"name\":\"k\",\"rate_limit_rpm\":600,\"rate_limit_tpm\":1000000%s}"
                .formatted(projectId == null ? "" : ",\"project_id\":\"" + projectId + "\"");
        MvcResult r = mvc.perform(post("/api/v1/tenant/api-keys").with(a.auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return read(r).get("raw_key").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder builder) throws Exception {
        ResultActions actions = mvc.perform(builder);
        MvcResult result = actions.andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)) : actions;
    }

    /** Three random UUIDs: no shared words, so two calls can never be semantic-cache neighbours. */
    private static String completion(int maxTokens) {
        return """
                {"model":"auto","max_tokens":%d,"messages":[{"role":"user","content":"%s %s %s"}]}
                """.formatted(maxTokens, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("create → list → get → update with If-Match: 201/Location/ETag, 409 duplicate, 428, 412, 400")
    void crudWithOptimisticConcurrency() throws Exception {
        Account a = signUp();
        MvcResult created = mvc.perform(post(PROJECTS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Support Copilot \",\"description\":\"Inbox replies\",\"monthly_budget_usd\":\"25\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Location", startsWith(PROJECTS + "/")))
                .andExpect(jsonPath("$.name").value("Support Copilot"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monthly_budget_usd").value("25.00000000"))
                .andExpect(jsonPath("$.remaining_usd").value("25.00000000"))
                .andExpect(jsonPath("$.budget_utilization").value(0.0))
                .andReturn();
        String id = read(created).get("id").asText();

        // names are unique per tenant among active projects, case-insensitively
        mvc.perform(post(PROJECTS).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"support copilot\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("/conflict")));
        mvc.perform(post(PROJECTS).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PROJECTS).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Negative\",\"monthly_budget_usd\":\"-1\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(PROJECTS).with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.unassigned.requests30d").value(0))
                .andExpect(jsonPath("$.max_active_projects").value(25));
        mvc.perform(get(PROJECTS + "/" + id).with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.project.description").value("Inbox replies"))
                .andExpect(jsonPath("$.keys.length()").value(0));

        String update = "{\"name\":\"Support Copilot v2\",\"description\":null,\"monthly_budget_usd\":null}";
        mvc.perform(put(PROJECTS + "/" + id).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(PROJECTS + "/" + id).with(a.auth()).header("If-Match", "\"7\"").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.current_version").value(0));
        mvc.perform(put(PROJECTS + "/" + id).with(a.auth()).header("If-Match", "nonsense").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isBadRequest());
        mvc.perform(put(PROJECTS + "/" + id).with(a.auth()).header("If-Match", "W/\"0\"").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.name").value("Support Copilot v2"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.monthly_budget_usd").doesNotExist())
                .andExpect(jsonPath("$.budget_utilization").doesNotExist());
        // the second tab still holds version 0 → lost update prevented
        mvc.perform(put(PROJECTS + "/" + id).with(a.auth()).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    @DisplayName("archive revokes the project's keys at once; restore keeps them revoked; archived projects reject keys and edits")
    void archiveAndRestore() throws Exception {
        Account a = signUp();
        UUID project = createProject(a, "Docs Search", null);
        String raw = createKey(a, project);
        createKey(a, null); // an unassigned key is untouched by the archive

        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON).content(completion(50)))
                .andExpect(status().isOk());

        mvc.perform(post(PROJECTS + "/" + project + "/archive").with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked_keys").value(1))
                .andExpect(jsonPath("$.project.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.project.archived_at").exists())
                .andExpect(jsonPath("$.project.active_keys").value(0))
                .andExpect(jsonPath("$.project.requests30d").value(1));
        assertThat(count("select count(*) from api_keys where tenant_id = ? and is_active", a.tenantId())).isEqualTo(1);
        call(post("/v1/chat/completions").header("X-API-Key", raw).contentType(MediaType.APPLICATION_JSON).content(completion(50)))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/tenant/api-keys").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"late\",\"project_id\":\"" + project + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("/project-archived")));
        mvc.perform(put(PROJECTS + "/" + project).with(a.auth()).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Docs Search\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/playground/completions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hi\",\"project_id\":\"" + project + "\"}"))
                .andExpect(status().isConflict());

        // a new active project takes the name; restoring the old one would duplicate it → 409
        UUID successor = createProject(a, "docs search", null);
        mvc.perform(post(PROJECTS + "/" + project + "/restore").with(a.auth())).andExpect(status().isConflict());
        mvc.perform(delete(PROJECTS + "/" + successor).with(a.auth())).andExpect(status().isNoContent());

        mvc.perform(post(PROJECTS + "/" + project + "/restore").with(a.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active_keys").value(0));
        mvc.perform(post(PROJECTS + "/" + project + "/archive").with(a.auth())).andExpect(status().isOk());
        mvc.perform(post(PROJECTS + "/" + project + "/archive").with(a.auth())) // idempotent
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked_keys").value(0));
    }

    @Test
    @DisplayName("delete: only a never-used project; one with billing history must be archived (append-only ledger)")
    void deleteRules() throws Exception {
        Account a = signUp();
        UUID unused = createProject(a, "Typo Project", null);
        String key = createKey(a, unused);
        mvc.perform(delete(PROJECTS + "/" + unused).with(a.auth())).andExpect(status().isNoContent());
        mvc.perform(get(PROJECTS + "/" + unused).with(a.auth())).andExpect(status().isNotFound());
        assertThat(count("select count(*) from api_keys where tenant_id = ? and project_id is null and is_active", a.tenantId()))
                .isEqualTo(1);
        // the key now charges "unassigned" instead of a deleted project (cache was evicted after commit)
        call(post("/v1/chat/completions").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(completion(40)))
                .andExpect(status().isOk());

        UUID used = createProject(a, "Billing History", null);
        call(post("/v1/chat/completions").header("X-API-Key", createKey(a, used)).contentType(MediaType.APPLICATION_JSON)
                .content(completion(40))).andExpect(status().isOk());
        mvc.perform(delete(PROJECTS + "/" + used).with(a.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("archive it instead")));
        assertThat(count("select count(*) from ledger_entries where project_id = ?", used)).isEqualTo(3);
    }

    @Test
    @DisplayName("project budget is enforced at pre-flight (402 project-budget-exceeded) while the tenant still has money")
    void projectBudgetAtGateway() throws Exception {
        Account a = signUp();
        UUID tight = createProject(a, "Tight", "0.0005");
        String key = createKey(a, tight);
        BigDecimal before = available(a.tenantId());

        call(post("/v1/chat/completions").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(completion(2000)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value(endsWith("/project-budget-exceeded")))
                .andExpect(jsonPath("$.project").value("Tight"))
                .andExpect(jsonPath("$.monthly_budget_usd").value("0.00050000"));
        assertThat(available(a.tenantId())).isEqualByComparingTo(before);

        // move the key to "unassigned": the same request now passes (only the tenant budget applies)
        MvcResult keys = mvc.perform(get("/api/v1/tenant/api-keys").with(a.auth())).andReturn();
        String keyId = read(keys).get(0).get("id").asText();
        mvc.perform(patch("/api/v1/tenant/api-keys/" + keyId).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project_id\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project_id").doesNotExist());
        call(post("/v1/chat/completions").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(completion(2000)))
                .andExpect(status().isOk());

        // and back into a roomy project: spend is attributed there
        UUID roomy = createProject(a, "Roomy", "50");
        mvc.perform(patch("/api/v1/tenant/api-keys/" + keyId).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"project_id\":\"" + roomy + "\"}")).andExpect(jsonPath("$.project_id").value(roomy.toString()));
        call(post("/v1/chat/completions").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(completion(100)))
                .andExpect(status().isOk());
        mvc.perform(get(PROJECTS + "/" + roomy).with(a.auth()))
                .andExpect(jsonPath("$.project.requests30d").value(1))
                .andExpect(jsonPath("$.daily.length()").value(1))
                .andExpect(jsonPath("$.keys[0].id").value(keyId));
        mvc.perform(get(PROJECTS).with(a.auth()))
                .andExpect(jsonPath("$.unassigned.requests30d").value(1));
    }

    @Test
    @DisplayName("budget cannot be lowered below the month's committed spend (keeps spend <= budget true)")
    void budgetBelowCommittedSpend() throws Exception {
        Account a = signUp();
        UUID project = createProject(a, "Committed", "1");
        TenantContext.callAs(a.tenantId(), () -> ledger.reserveBudget("req_hold_open", new BigDecimal("0.40"), "hold", project));

        mvc.perform(get(PROJECTS + "/" + project).with(a.auth()))
                .andExpect(jsonPath("$.project.open_holds_usd").value("0.40000000"))
                .andExpect(jsonPath("$.project.committed_usd").value("0.40000000"))
                .andExpect(jsonPath("$.project.budget_utilization").value(0.4));
        mvc.perform(put(PROJECTS + "/" + project).with(a.auth()).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Committed\",\"monthly_budget_usd\":\"0.30\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put(PROJECTS + "/" + project).with(a.auth()).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Committed\",\"monthly_budget_usd\":\"0.50\"}"))
                .andExpect(status().isOk());

        // settle/release inherit the project of the hold
        TenantContext.callAs(a.tenantId(), () -> ledger.settleTransaction("req_hold_open", new BigDecimal("0.10"), "done"));
        assertThat(count("select count(*) from ledger_entries where request_id = 'req_hold_open' and project_id = ?", project))
                .isEqualTo(3);
        assertThatThrownBy(() -> TenantContext.callAs(a.tenantId(),
                () -> ledger.reserveBudget("req_over", new BigDecimal("0.41"), "hold", project)))
                .isInstanceOf(ProjectBudgetExceededException.class);
    }

    @Test
    @DisplayName("20 concurrent holds against a $1 project budget: exactly 10 x $0.10 fit, never more")
    void concurrentHoldsNeverOvershootProjectBudget() throws Exception {
        Account a = signUp();
        UUID project = createProject(a, "Concurrent", "1");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String requestId = "req_conc_" + i;
                tasks.add(() -> {
                    try {
                        TenantContext.callAs(a.tenantId(), () -> ledger.reserveBudget(requestId, new BigDecimal("0.10"), "hold", project));
                        return true;
                    } catch (ProjectBudgetExceededException e) {
                        rejected.incrementAndGet();
                        return false;
                    }
                });
            }
            long accepted = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                accepted += f.get() ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(10);
            assertThat(rejected.get()).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select sum(amount_usd) from ledger_entries where project_id = ? and entry_type = 'RESERVE'",
                BigDecimal.class, project)).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("isolation: another tenant's project is 404 everywhere, and the DB refuses cross-tenant project references")
    void isolation() throws Exception {
        Account a = signUp();
        Account b = signUp();
        UUID aProject = createProject(a, "Secret Roadmap", "10");

        mvc.perform(get(PROJECTS + "/" + aProject).with(b.auth())).andExpect(status().isNotFound());
        mvc.perform(post(PROJECTS + "/" + aProject + "/archive").with(b.auth())).andExpect(status().isNotFound());
        mvc.perform(delete(PROJECTS + "/" + aProject).with(b.auth())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tenant/api-keys").with(b.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"steal\",\"project_id\":\"" + aProject + "\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/playground/completions").with(b.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"hi\",\"project_id\":\"" + aProject + "\"}")).andExpect(status().isNotFound());
        mvc.perform(get(PROJECTS).with(b.auth())).andExpect(jsonPath("$.projects.length()").value(0));
        assertThatThrownBy(() -> TenantContext.callAs(b.tenantId(),
                () -> ledger.reserveBudget("req_x", new BigDecimal("0.01"), "hold", aProject)))
                .isInstanceOf(java.util.NoSuchElementException.class);

        // even the table owner (bypassing RLS) cannot attach B's ledger line to A's project: composite FK
        assertThatThrownBy(() -> jdbc.update("""
                insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, project_id)
                values (?, 'req_cross', 'TOP_UP', 1, 1, ?)""", b.tenantId(), aProject))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("archived project: direct ledger hold is refused, and the 25-active-projects cap is enforced")
    void archivedHoldAndCap() throws Exception {
        Account a = signUp();
        UUID p = createProject(a, "Retired", null);
        mvc.perform(post(PROJECTS + "/" + p + "/archive").with(a.auth())).andExpect(status().isOk());
        assertThatThrownBy(() -> TenantContext.callAs(a.tenantId(),
                () -> ledger.reserveBudget("req_arch", new BigDecimal("0.01"), "hold", p)))
                .isInstanceOf(ProjectArchivedException.class);

        for (int i = 0; i < 25; i++) {
            createProject(a, "Project " + i, null);
        }
        mvc.perform(post(PROJECTS).with(a.auth()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"One too many\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(PROJECTS + "/" + p + "/restore").with(a.auth())).andExpect(status().isConflict());
    }
}
