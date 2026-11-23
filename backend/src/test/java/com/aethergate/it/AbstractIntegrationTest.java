package com.aethergate.it;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL 17 + pgvector in a container (never a mocked DB layer). One container is
 * shared by all integration test classes; every test creates its own tenants, so tests are
 * independent without truncating the append-only ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("aethergate")
            .withUsername("aethergate")
            .withPassword("aethergate");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /** Inserts a tenant (as table owner) with a TOP_UP that matches its balance, plus a two-provider chain. */
    protected UUID newTenant(String name, BigDecimal balance) {
        return newTenant(name, balance, new BigDecimal("500"));
    }

    protected UUID newTenant(String name, BigDecimal balance, BigDecimal hardLimit) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into tenants (id, owner_user_id, name, slug, available_balance_usd, hard_budget_limit_usd)
                values (?, ?, ?, ?, ?, ?)""", id, UUID.randomUUID(), name, "t-" + id, balance, hardLimit);
        if (balance.signum() > 0) {
            jdbc.update("""
                    insert into ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description, created_at)
                    values (?, 'topup_seed', 'TOP_UP', ?, ?, 'test credit', ?)""", id, balance, balance, Timestamp.from(Instant.now()));
        }
        jdbc.update("""
                insert into provider_configs (tenant_id, provider, model_name, priority_order, input_cost_per_1m, output_cost_per_1m)
                values (?, 'OPENAI', 'gpt-4o-mini', 1, 0.15, 0.60), (?, 'ANTHROPIC', 'claude-haiku-4-5', 2, 1.00, 5.00)""", id, id);
        return id;
    }

    protected BigDecimal available(UUID tenantId) {
        return jdbc.queryForObject("select available_balance_usd from tenants where id = ?", BigDecimal.class, tenantId);
    }

    protected BigDecimal reserved(UUID tenantId) {
        return jdbc.queryForObject("select reserved_balance_usd from tenants where id = ?", BigDecimal.class, tenantId);
    }

    protected BigDecimal sum(UUID tenantId, String type) {
        return jdbc.queryForObject("select coalesce(sum(amount_usd), 0) from ledger_entries where tenant_id = ? and entry_type = ?",
                BigDecimal.class, tenantId, type);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
