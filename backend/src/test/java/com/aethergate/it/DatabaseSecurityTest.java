package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.TenantAspect;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutability trigger, Row-Level Security (USING + WITH CHECK) and the overdraft CHECK, tested in PostgreSQL itself. */
class DatabaseSecurityTest extends AbstractIntegrationTest {

    @Autowired
    PlatformTransactionManager txManager;

    private <T> T asTenant(UUID tenantId, java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> {
            jdbc.queryForObject("select set_config('app.current_tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.execute("set local role " + TenantAspect.APP_ROLE);
            return work.get();
        });
    }

    @Test
    @DisplayName("ledger_entries rejects UPDATE and DELETE, even for the table owner")
    void ledgerIsAppendOnly() {
        UUID tenant = newTenant("Immutable Co", new BigDecimal("5"));
        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount_usd = 0 where tenant_id = ?", tenant))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("SECURITY VIOLATION: Ledger entries are strictly append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where tenant_id = ?", tenant))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThat(sum(tenant, "TOP_UP")).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("RLS: a tenant session sees only its own rows in every tenant-owned table")
    void rlsHidesOtherTenants() {
        UUID a = newTenant("Tenant A", new BigDecimal("1"));
        UUID b = newTenant("Tenant B", new BigDecimal("2"));
        assertThat(count("select count(*) from ledger_entries where tenant_id in (?, ?)", a, b)).isEqualTo(2);

        Integer visibleB = asTenant(a, () -> jdbc.queryForObject("select count(*) from ledger_entries where tenant_id = ?", Integer.class, b));
        Integer visibleTenants = asTenant(a, () -> jdbc.queryForObject("select count(*) from tenants", Integer.class));
        Integer visibleProviders = asTenant(a, () -> jdbc.queryForObject("select count(*) from provider_configs", Integer.class));
        assertThat(visibleB).isZero();
        assertThat(visibleTenants).isEqualTo(1);
        assertThat(visibleProviders).isEqualTo(2);
    }

    @Test
    @DisplayName("RLS WITH CHECK: a tenant cannot write a row for another tenant")
    void rlsBlocksForeignWrites() {
        UUID a = newTenant("Writer A", new BigDecimal("1"));
        UUID b = newTenant("Victim B", new BigDecimal("1"));
        assertThatThrownBy(() -> asTenant(a, () -> jdbc.update("""
                insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd)
                values (?, 'forged', 'TOP_UP', 1000, 1000)""", b)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
        Integer updated = asTenant(a, () -> jdbc.update("update tenants set available_balance_usd = 999 where id = ?", b));
        assertThat(updated).isZero();
        assertThat(available(b)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("No tenant bound → the app role sees nothing at all")
    void noTenantContextSeesNothing() {
        newTenant("Hidden Co", new BigDecimal("1"));
        Integer visible = new TransactionTemplate(txManager).execute(s -> {
            jdbc.execute("set local role " + TenantAspect.APP_ROLE);
            return jdbc.queryForObject("select count(*) from ledger_entries", Integer.class);
        });
        assertThat(visible).isZero();
    }

    @Test
    @DisplayName("The app role cannot TRUNCATE or bypass the immutable ledger")
    void appRoleCannotTruncate() {
        UUID a = newTenant("Truncate Co", new BigDecimal("1"));
        assertThatThrownBy(() -> asTenant(a, () -> {
            jdbc.execute("truncate ledger_entries");
            return null;
        })).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("CHECK constraint: the database refuses a negative available balance")
    void databaseRefusesOverdraft() {
        UUID a = newTenant("Overdraft Co", new BigDecimal("1"));
        assertThatThrownBy(() -> jdbc.update("update tenants set available_balance_usd = -0.00000001 where id = ?", a))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_tenants_available_non_negative");
    }
}
