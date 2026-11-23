package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.TenantContext;
import com.aethergate.service.CompletionCommand;
import com.aethergate.service.GatewayExecution;
import com.aethergate.service.GatewayOrchestratorService;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.ClientAbortedException;
import com.aethergate.service.llm.StreamInterruptedException;
import com.aethergate.service.tenant.DashboardService;
import com.aethergate.service.tenant.GatewayPrincipal;
import com.aethergate.service.tenant.TenantAdminService;
import com.aethergate.service.tenant.TenantDirectoryService;
import com.aethergate.service.tenant.TenantRef;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EdgeCasesIntegrationTest extends AbstractIntegrationTest {

    @Autowired GatewayOrchestratorService orchestrator;
    @Autowired TenantDirectoryService directory;
    @Autowired TenantAdminService admin;
    @Autowired DashboardService dashboard;

    @Test
    @DisplayName("client disconnects mid-stream: only delivered tokens are billed, the rest of the hold is released")
    void clientAbortBillsDeliveredTokensOnly() {
        UUID tenant = newTenant("Abort Co", new BigDecimal("5"));
        GatewayPrincipal principal = GatewayPrincipal.forPlayground(tenant, "Abort Co", 30, 60_000);
        GatewayExecution exec = orchestrator.prepare(new CompletionCommand(principal, "auto",
                List.of(new ChatMessage("user", "Explain row level security in depth")), 400, false));
        AtomicInteger delivered = new AtomicInteger();
        assertThatThrownBy(() -> exec.run(delta -> {
            if (delivered.incrementAndGet() > 3) {
                throw new ClientAbortedException(new IOException("broken pipe"));
            }
        })).isInstanceOfSatisfying(StreamInterruptedException.class, e -> assertThat(e.isClientAborted()).isTrue());

        String requestId = exec.requestId();
        BigDecimal reserve = jdbc.queryForObject("select amount_usd from ledger_entries where request_id = ? and entry_type = 'RESERVE'", BigDecimal.class, requestId);
        BigDecimal settle = jdbc.queryForObject("select amount_usd from ledger_entries where request_id = ? and entry_type = 'SETTLE'", BigDecimal.class, requestId);
        BigDecimal release = jdbc.queryForObject("select amount_usd from ledger_entries where request_id = ? and entry_type = 'RELEASE'", BigDecimal.class, requestId);
        assertThat(reserve).isEqualByComparingTo(settle.add(release));
        assertThat(release).isGreaterThan(settle);
        assertThat(count("select status_code from llm_request_logs where request_id = ?", requestId)).isEqualTo(499);
        assertThat(reserved(tenant)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("demo binding: rebinds the seeded tenant once, refuses users who already own a workspace")
    void bindOwner() {
        assertThat(directory.findBySlug("vortex-ai-labs")).map(TenantRef::demo).contains(true);
        UUID demoUser = UUID.randomUUID();
        directory.bindOwner("vortex-ai-labs", demoUser);
        directory.bindOwner("vortex-ai-labs", demoUser); // idempotent
        assertThat(directory.ownerOf("vortex-ai-labs")).contains(demoUser);
        assertThat(directory.resolveOrProvision(demoUser, "demo@aethergate.dev").slug()).isEqualTo("vortex-ai-labs");

        UUID other = UUID.randomUUID();
        directory.resolveOrProvision(other, null); // provisions "Workspace's Workspace"
        assertThatThrownBy(() -> directory.bindOwner("vortex-ai-labs", other)).isInstanceOf(IllegalStateException.class);
        directory.bindOwner("no-such-tenant", other);
        assertThat(directory.findBySlug("no-such-tenant")).isEmpty();
        assertThat(directory.authenticateApiKey("not-a-key")).isEmpty();
    }

    @Test
    @DisplayName("the shared public demo key cannot be revoked by a demo visitor")
    void publicDemoKeyIsProtected() {
        UUID vortex = UUID.fromString("7f1c2a9e-4b3d-4e8a-9c61-2d5e8f0a1b01");
        UUID keyId = jdbc.queryForObject("select id from api_keys where tenant_id = ? and key_prefix = 'ag_live_demo'", UUID.class, vortex);
        assertThatThrownBy(() -> TenantContext.runAs(vortex, () -> admin.revokeKey(keyId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be revoked");
        assertThat(count("select count(*) from api_keys where id = ? and is_active", keyId)).isEqualTo(1);
    }

    @Test
    void provisioningHandlesOddEmails() {
        TenantRef ref = directory.resolveOrProvision(UUID.randomUUID(), "@nolocal.dev");
        assertThat(ref.name()).isEqualTo("Workspace's Workspace");
        assertThat(ref.slug()).startsWith("workspace-");
        TenantRef dots = directory.resolveOrProvision(UUID.randomUUID(), "---@x.dev");
        assertThat(dots.slug()).startsWith("workspace-");
    }

    @Test
    void adminGuards() {
        UUID tenant = newTenant("Guards Co", new BigDecimal("1"));
        TenantContext.runAs(tenant, () -> {
            for (int i = 0; i < 20; i++) {
                admin.createKey("k" + i, 60, 1000);
            }
            assertThatThrownBy(() -> admin.createKey("one too many", 60, 1000)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> admin.setProviderEnabled(UUID.randomUUID(), true)).isInstanceOf(NoSuchElementException.class);
            UUID first = admin.listProviders().getFirst().id();
            assertThatThrownBy(() -> admin.reorderProviders(List.of(first, first))).isInstanceOf(IllegalArgumentException.class);
            assertThat(admin.setProviderEnabled(first, true).enabled()).isTrue();
        });
        assertThatThrownBy(() -> admin.topUp(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.topUp(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ledgerFiltersAndRangeParsing() {
        UUID tenant = newTenant("Ledger Filter Co", new BigDecimal("2"));
        TenantRef ref = new TenantRef(tenant, "Ledger Filter Co", "t", "PRO", false);
        assertThat(dashboard.ledger(ref, "all", -1, 999).entries()).hasSize(1);
        assertThat(dashboard.ledger(ref, "", 0, 0).size()).isEqualTo(1);
        assertThat(dashboard.ledger(ref, "top_up", 0, 10).balanced()).isTrue();
        assertThat(DashboardService.Range.parse("7D")).isEqualTo(DashboardService.Range.D7);
        assertThatThrownBy(() -> DashboardService.Range.parse("1y")).isInstanceOf(IllegalArgumentException.class);
        assertThat(dashboard.overview(ref, DashboardService.Range.H24).kpis().avgTtftMissMs()).isNull();
    }
}
