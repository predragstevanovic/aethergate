package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.MemberRole;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.StaleVersionException;
import com.aethergate.service.prompt.PromptService;
import com.aethergate.service.prompt.PromptService.TemplateDraft;
import com.aethergate.service.team.InvitationInvalidException;
import com.aethergate.service.team.TeamService;
import com.aethergate.service.tenant.AdminViews.ApiKeyView;
import com.aethergate.service.tenant.AdminViews.KeyPolicyDraft;
import com.aethergate.service.tenant.DemoReadOnlyException;
import com.aethergate.service.tenant.InsufficientRoleException;
import com.aethergate.service.tenant.TenantAdminService;
import com.aethergate.service.tenant.TenantRef;
import com.aethergate.service.tenant.WorkspaceAccess;
import com.aethergate.service.webhook.AlertService;
import com.aethergate.service.webhook.WebhookDispatcher;
import com.aethergate.service.webhook.WebhookSender;
import com.aethergate.service.webhook.WebhookService;
import com.aethergate.service.webhook.WebhookViews.EndpointDraft;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Validation and edge paths of the team, webhook, alert, key-policy and prompt services, called directly under RLS. */
class NewFeaturesEdgeCasesIntegrationTest extends AbstractIntegrationTest {

    @Autowired TenantAdminService admin;
    @Autowired PromptService prompts;
    @Autowired AlertService alerts;
    @Autowired TeamService team;
    @Autowired WebhookService webhooks;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired WebhookSender sender;

    private static <T> T as(UUID tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, work);
    }

    private static void run(UUID tenant, Runnable work) {
        TenantContext.runAs(tenant, work);
    }

    private static KeyPolicyDraft policy(String name, int rpm, int tpm, Instant exp, List<String> models, List<String> ips) {
        return new KeyPolicyDraft(name, rpm, tpm, exp, models, ips);
    }

    @Test
    void keyPolicyValidation() {
        UUID t = newTenant("Keys Edge", new BigDecimal("10"));
        ApiKeyView key = as(t, () -> admin.createKey("k", 60, 100_000).key());
        UUID id = key.id();
        List<KeyPolicyDraft> bad = List.of(
                policy(" ", 60, 1000, null, null, null),
                policy(null, 60, 1000, null, null, null),
                policy("ok", 60, 1000, null, Collections.singletonList(null), null),
                policy("x".repeat(101), 60, 1000, null, null, null),
                policy("ok", 0, 1000, null, null, null),
                policy("ok", 10_001, 1000, null, null, null),
                policy("ok", 60, 99, null, null, null),
                policy("ok", 60, 10_000_001, null, null, null),
                policy("ok", 60, 1000, Instant.now().minusSeconds(5), null, null),
                policy("ok", 60, 1000, null, List.of("nope-model"), null),
                policy("ok", 60, 1000, null, null, List.of("1.2.3.4/40")));
        for (KeyPolicyDraft d : bad) {
            assertThatThrownBy(() -> run(t, () -> admin.updatePolicy(id, 0, d))).isInstanceOf(IllegalArgumentException.class);
        }
        List<String> eleven = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            eleven.add("10.0.0." + i);
        }
        assertThatThrownBy(() -> run(t, () -> admin.updatePolicy(id, 0, policy("ok", 60, 1000, null, null, eleven))))
                .hasMessageContaining("At most");
        assertThatThrownBy(() -> run(t, () -> admin.updatePolicy(id, 7, policy("ok", 60, 1000, null, null, null))))
                .isInstanceOf(StaleVersionException.class);
        ApiKeyView updated = as(t, () -> admin.updatePolicy(id, 0, policy("ok", 60, 1000, Instant.now().plusSeconds(3600),
                List.of("GPT-4O-MINI"), List.of(" ", "192.0.2.0/24"))));
        assertThat(updated.ipAllowlist()).containsExactly("192.0.2.0/24");
        assertThat(updated.expiresAt()).isNotNull();
        ApiKeyView cleared = as(t, () -> admin.updatePolicy(id, 1, policy("ok", 60, 1000, null, List.of(), List.of(" "))));
        assertThat(cleared.allowedModels()).isEmpty();
        assertThat(cleared.ipAllowlist()).isEmpty();

        assertThatThrownBy(() -> run(t, () -> admin.rotateKey(id, -1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(t, () -> admin.rotateKey(id, 169))).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("update api_keys set expires_at = now() - interval '1 minute' where id = ?", id);
        assertThatThrownBy(() -> run(t, () -> admin.rotateKey(id, 1))).isInstanceOf(ResourceConflictException.class);
        assertThat(as(t, admin::listKeys).getFirst().status()).isEqualTo("EXPIRED");

        // revoked keys and the shared demo key cannot be changed
        UUID other = as(t, () -> admin.createKey("k2", 60, 100_000).key().id());
        run(t, () -> admin.revokeKey(other));
        assertThatThrownBy(() -> run(t, () -> admin.rotateKey(other, 0))).hasMessageContaining("revoked");
        jdbc.update("update api_keys set key_prefix = 'ag_live_demo', is_active = true where id = ?", other);
        assertThatThrownBy(() -> run(t, () -> admin.updatePolicy(other, 0, policy("ok", 60, 1000, null, null, null))))
                .hasMessageContaining("demo key");
        assertThatThrownBy(() -> run(t, () -> admin.rotateKey(UUID.randomUUID(), 0))).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rotationRespectsTheKeyLimit() {
        UUID t = newTenant("Many Keys", new BigDecimal("10"));
        UUID first = as(t, () -> admin.createKey("k0", 60, 100_000).key().id());
        for (int i = 1; i < 20; i++) {
            int n = i;
            run(t, () -> admin.createKey("k" + n, 60, 100_000));
        }
        assertThatThrownBy(() -> run(t, () -> admin.rotateKey(first, 24))).isInstanceOf(ResourceConflictException.class);
        assertThat(as(t, () -> admin.rotateKey(first, 0)).previous().status()).isEqualTo("REVOKED");
    }

    @Test
    void promptValidation() {
        UUID t = newTenant("Prompt Edge", new BigDecimal("10"));
        List<TemplateDraft> bad = List.of(
                new TemplateDraft("x", null, null, "hello", null),
                new TemplateDraft(null, null, null, "hello", null),
                new TemplateDraft("Fine", null, null, null, null),
                new TemplateDraft("y".repeat(81), null, null, "hello", null),
                new TemplateDraft("Fine", "d".repeat(281), null, "hello", null),
                new TemplateDraft("Fine", null, null, " ", null),
                new TemplateDraft("Fine", null, null, "u".repeat(8001), null),
                new TemplateDraft("Fine", null, "s".repeat(4001), "hello", null),
                new TemplateDraft("Fine", null, null, "hello", "n".repeat(201)));
        for (TemplateDraft d : bad) {
            assertThatThrownBy(() -> run(t, () -> prompts.create(d, "a@b.c"))).isInstanceOf(IllegalArgumentException.class);
        }
        UUID id = as(t, () -> prompts.create(new TemplateDraft("Greeting", " ", " ", "Hi {{name}}", " "), null)).template().id();
        run(t, () -> prompts.create(new TemplateDraft("Other", null, null, "x", null), null));
        assertThatThrownBy(() -> run(t, () -> prompts.rename(id, 5, "Greeting", null))).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.rename(id, 0, "other", null))).isInstanceOf(ResourceConflictException.class);
        assertThat(as(t, () -> prompts.rename(id, 0, "Greeting 2", "desc")).template().description()).isEqualTo("desc");
        assertThatThrownBy(() -> run(t, () -> prompts.activate(id, 1, 9))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.activate(id, 0, 1))).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.addVersion(UUID.randomUUID(), new TemplateDraft(null, null, null, "x", null), null)))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.delete(UUID.randomUUID()))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.get(UUID.randomUUID()))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.render("missing", null, null))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.render(id.toString(), 4, Map.of()))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> prompts.render(id.toString(), null, null))).hasMessageContaining("name");
        assertThat(as(t, () -> prompts.render("greeting 2", 1, Map.of("name", "Ana"))).messages()).hasSize(1);
        // another tenant cannot see it at all
        UUID stranger = newTenant("Prompt Stranger", new BigDecimal("10"));
        assertThat(as(stranger, prompts::list)).isEmpty();
        assertThatThrownBy(() -> run(stranger, () -> prompts.render(id.toString(), null, Map.of()))).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void alertValidationAndProjectAlerts() {
        UUID t = newTenant("Alert Edge", new BigDecimal("10"), new BigDecimal("1"));
        assertThatThrownBy(() -> run(t, () -> alerts.create(null, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(t, () -> alerts.create(null, 101))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(t, () -> alerts.create(UUID.randomUUID(), 50))).isInstanceOf(NoSuchElementException.class);
        UUID noBudget = UUID.randomUUID();
        UUID budgeted = UUID.randomUUID();
        jdbc.update("insert into projects (id, tenant_id, name, monthly_budget_usd) values (?, ?, 'Free', null), (?, ?, 'Capped', 0.001)",
                noBudget, t, budgeted, t);
        assertThatThrownBy(() -> run(t, () -> alerts.create(noBudget, 50))).hasMessageContaining("no monthly budget");
        run(t, () -> alerts.create(null, 50));
        assertThatThrownBy(() -> run(t, () -> alerts.create(null, 50))).isInstanceOf(ResourceConflictException.class);
        // spend already past the project threshold: creating the rule fires it right away, once
        jdbc.update("""
                insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, project_id)
                values (?, 'r1', 'RESERVE', 0.002, 9.998, ?), (?, 'r1', 'SETTLE', 0.002, 9.998, ?), (?, 'r1', 'RELEASE', 0, 9.998, ?)""",
                t, budgeted, t, budgeted, t, budgeted);
        var list = as(t, () -> alerts.create(budgeted, 90));
        assertThat(list.rules()).hasSize(2);
        assertThat(list.rules().get(1).firedThisMonthAt()).isNotNull();
        assertThat(list.rules().get(1).usedPct()).isGreaterThan(100);
        assertThat(as(t, () -> alerts.evaluate(budgeted))).isZero();
        UUID ruleId = list.rules().getFirst().id();
        assertThat(as(t, () -> alerts.setEnabled(ruleId, false)).rules().getFirst().enabled()).isFalse();
        assertThatThrownBy(() -> run(t, () -> alerts.setEnabled(UUID.randomUUID(), true))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> alerts.delete(UUID.randomUUID()))).isInstanceOf(NoSuchElementException.class);
        run(t, () -> alerts.delete(ruleId));
        assertThat(as(t, () -> alerts.evaluate(null))).isZero();
    }

    @Test
    void teamEdgeCases() {
        UUID t = newTenant("Team Edge", new BigDecimal("10"));
        TenantRef ref = new TenantRef(t, "Team Edge", "t-" + t, "PRO", false);
        WorkspaceAccess owner = new WorkspaceAccess(ref, MemberRole.OWNER, true);
        WorkspaceAccess developer = new WorkspaceAccess(ref, MemberRole.DEVELOPER, false);
        assertThatThrownBy(() -> run(t, () -> team.invite(owner, "me@x.dev", "x@y.dev", "GOD"))).hasMessageContaining("Role must be");
        assertThatThrownBy(() -> run(t, () -> team.invite(owner, "me@x.dev", "x@y.dev", null))).hasMessageContaining("Role must be");
        assertThatThrownBy(() -> run(t, () -> team.invite(owner, "me@x.dev", "no-at-sign", "VIEWER"))).hasMessageContaining("valid e-mail");
        assertThatThrownBy(() -> run(t, () -> team.invite(owner, "me@x.dev", null, "VIEWER"))).hasMessageContaining("valid e-mail");
        assertThatThrownBy(() -> run(t, () -> team.invite(owner, "me@x.dev", "a".repeat(250) + "@x.dev", "VIEWER")))
                .hasMessageContaining("valid e-mail");
        assertThatThrownBy(() -> run(t, () -> team.invite(owner, "me@x.dev", "ME@x.dev", "VIEWER"))).hasMessageContaining("already");
        assertThatThrownBy(() -> run(t, () -> team.invite(developer, "d@x.dev", "v@y.dev", "VIEWER")))
                .isInstanceOf(InsufficientRoleException.class);
        WorkspaceAccess demo = new WorkspaceAccess(new TenantRef(t, "Demo", "demo", "PRO", true), MemberRole.OWNER, true);
        assertThatThrownBy(() -> run(t, () -> team.invite(demo, "o@x.dev", "v@y.dev", "VIEWER"))).isInstanceOf(DemoReadOnlyException.class);

        var created = as(t, () -> team.invite(owner, null, "member@y.dev", "DEVELOPER"));
        assertThatThrownBy(() -> team.accept(UUID.randomUUID(), null, created.token()))
                .isInstanceOf(com.aethergate.service.team.InvitationEmailMismatchException.class);
        UUID memberUser = UUID.randomUUID();
        team.accept(memberUser, "member@y.dev", created.token());
        UUID memberId = created.member().id();
        assertThatThrownBy(() -> run(t, () -> team.resendInvite(owner, memberId))).isInstanceOf(ResourceConflictException.class);
        WorkspaceAccess asMember = new WorkspaceAccess(ref, MemberRole.ADMIN, false);
        assertThatThrownBy(() -> run(t, () -> team.changeRole(asMember, memberUser, memberId, 1, "VIEWER")))
                .hasMessageContaining("own role");
        assertThatThrownBy(() -> run(t, () -> team.remove(owner, UUID.randomUUID(), UUID.randomUUID()))).isInstanceOf(NoSuchElementException.class);
        assertThat(as(t, () -> team.list(owner, null, null)).assignableRoles()).contains("OWNER");

        for (String token : new String[] {null, " ", "x".repeat(101), "inv_unknown"}) {
            assertThatThrownBy(() -> team.accept(UUID.randomUUID(), "a@b.c", token)).isInstanceOf(InvitationInvalidException.class);
        }
        // the primary owner cannot "join" their own workspace; an existing member cannot join twice
        UUID ownerUser = jdbc.queryForObject("select owner_user_id from tenants where id = ?", UUID.class, t);
        var toOwner = as(t, () -> team.invite(owner, "me@x.dev", "owner-alias@x.dev", "VIEWER"));
        assertThatThrownBy(() -> team.accept(ownerUser, "owner-alias@x.dev", toOwner.token())).isInstanceOf(ResourceConflictException.class);
        var second = as(t, () -> team.invite(owner, "me@x.dev", "member-alias@y.dev", "VIEWER"));
        assertThatThrownBy(() -> team.accept(memberUser, "member-alias@y.dev", second.token())).isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void webhookValidationAndDispatcherEdges() throws Exception {
        UUID t = newTenant("Hook Edge", new BigDecimal("10"));
        List<String> events = List.of("member.joined");
        List<EndpointDraft> bad = new ArrayList<>(List.of(
                new EndpointDraft("x", "https://a.example", events),
                new EndpointDraft("Okay", "https://a.example", List.of()),
                new EndpointDraft("Okay", "https://a.example", Collections.singletonList(null))));
        bad.add(new EndpointDraft("Okay", "https://a.example", null));
        for (EndpointDraft d : bad) {
            assertThatThrownBy(() -> run(t, () -> webhooks.create(d))).isInstanceOf(IllegalArgumentException.class);
        }
        UUID first = null;
        for (int i = 0; i < 5; i++) {
            int n = i;
            UUID id = as(t, () -> webhooks.create(new EndpointDraft("Hook " + n, "http://127.0.0.1:9/never", events)).endpoint().id());
            first = first == null ? id : first;
        }
        assertThatThrownBy(() -> run(t, () -> webhooks.create(new EndpointDraft("Hook 6", "https://a.example", events))))
                .isInstanceOf(ResourceConflictException.class);
        UUID hook = first;
        assertThatThrownBy(() -> run(t, () -> webhooks.update(hook, 9, new EndpointDraft("Hook 0", "https://a.example", events))))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> run(t, () -> webhooks.update(hook, 0, new EndpointDraft("hook 1", "https://a.example", events))))
                .isInstanceOf(ResourceConflictException.class);
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> run(t, () -> webhooks.setEnabled(missing, true))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> webhooks.rotateSecret(missing))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> webhooks.delete(missing))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> webhooks.deliveries(missing))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> run(t, () -> webhooks.redeliver(missing))).isInstanceOf(NoSuchElementException.class);

        // connection refused → retry scheduled; at the last attempt → DEAD; a disabled endpoint → DEAD without sending
        run(t, () -> webhooks.sendTest(hook));
        jdbc.update("update webhook_deliveries set attempts = 5 where endpoint_id = ?", hook);
        for (int i = 0; i < 50 && count("select count(*) from webhook_deliveries where endpoint_id = ? and status = 'DEAD'", hook) == 0; i++) {
            jdbc.update("update webhook_deliveries set next_attempt_at = now() where endpoint_id = ? and status = 'PENDING'", hook);
            dispatcher.dispatchDue();
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForObject("select last_error from webhook_deliveries where endpoint_id = ?", String.class, hook))
                .contains("ConnectException");
        UUID second = jdbc.queryForObject("select id from webhook_endpoints where tenant_id = ? and name = 'Hook 1'", UUID.class, t);
        run(t, () -> webhooks.sendTest(second));
        run(t, () -> webhooks.setEnabled(second, false));
        for (int i = 0; i < 50 && count("select count(*) from webhook_deliveries where endpoint_id = ? and status = 'DEAD'", second) == 0; i++) {
            jdbc.update("update webhook_deliveries set next_attempt_at = now() where endpoint_id = ? and status = 'PENDING'", second);
            dispatcher.dispatchDue();
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForObject("select last_error from webhook_deliveries where endpoint_id = ?", String.class, second))
                .isEqualTo("Endpoint disabled");

        // demo workspaces cannot create webhooks
        jdbc.update("update tenants set is_demo = true where id = ?", t);
        assertThatThrownBy(() -> run(t, () -> webhooks.create(new EndpointDraft("Demo", "https://a.example", events))))
                .isInstanceOf(DemoReadOnlyException.class);
    }

    @Test
    void senderNeverFollowsRedirectsAndReportsFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, Integer> codes = new HashMap<>(Map.of("/redirect", 302, "/ok", 200, "/slow", 200));
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/slow")) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.equals("/redirect")) {
                ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            }
            ex.sendResponseHeaders(codes.getOrDefault(path, 404), -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(sender.send(base + "/ok", "whsec_x", "d", "e", "webhook.test", "{}").success()).isTrue();
            WebhookSender.Result redirect = sender.send(base + "/redirect", "whsec_x", "d", "e", "webhook.test", "{}");
            assertThat(redirect.success()).isFalse();
            assertThat(redirect.error()).contains("redirects are not followed");
            assertThat(sender.send(base + "/slow", "whsec_x", "d", "e", "webhook.test", "{}").error()).contains("Timed out");
            assertThat(sender.send("not a url", "whsec_x", "d", "e", "webhook.test", "{}").success()).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
