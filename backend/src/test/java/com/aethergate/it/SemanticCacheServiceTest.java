package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aethergate.config.TenantContext;
import com.aethergate.service.cache.CacheHit;
import com.aethergate.service.cache.SemanticCacheService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** pgvector HNSW lookups through the real service, under RLS. */
class SemanticCacheServiceTest extends AbstractIntegrationTest {

    @Autowired
    SemanticCacheService cache;

    @Test
    @DisplayName("exact and trivially re-phrased prompts hit; a different question misses")
    void hitsOnlyAboveThreshold() {
        UUID tenant = newTenant("Cache Co", BigDecimal.ONE);
        TenantContext.runAs(tenant, () -> cache.store("auto", "user: How does row level security work?",
                "RLS filters every row.", new BigDecimal("0.00012000"), 42));

        Optional<CacheHit> exact = TenantContext.callAs(tenant, () -> cache.lookup("auto", "user: How does row level security work?"));
        assertThat(exact).isPresent();
        assertThat(exact.get().similarity()).isGreaterThan(0.999);
        assertThat(exact.get().originalCostUsd()).isEqualByComparingTo("0.00012");
        assertThat(exact.get().completionTokens()).isEqualTo(42);

        Optional<CacheHit> rephrased = TenantContext.callAs(tenant, () -> cache.lookup("auto", "user: how does ROW-LEVEL security work"));
        assertThat(rephrased).isPresent();

        Optional<CacheHit> other = TenantContext.callAs(tenant, () -> cache.lookup("auto", "user: Explain circuit breakers in Java"));
        assertThat(other).isEmpty();

        Optional<CacheHit> otherModel = TenantContext.callAs(tenant, () -> cache.lookup("gpt-4o", "user: How does row level security work?"));
        assertThat(otherModel).isEmpty();

        assertThat(count("select hit_count from semantic_cache where tenant_id = ?", tenant)).isEqualTo(2);
    }

    @Test
    @DisplayName("tenant B is never served tenant A's cached answer")
    void cacheIsTenantIsolated() {
        UUID a = newTenant("Cache A", BigDecimal.ONE);
        UUID b = newTenant("Cache B", BigDecimal.ONE);
        TenantContext.runAs(a, () -> cache.store("auto", "user: secret roadmap", "A's private answer", BigDecimal.ZERO, 3));
        assertThat(TenantContext.callAs(b, () -> cache.lookup("auto", "user: secret roadmap"))).isEmpty();
        assertThat(TenantContext.callAs(a, () -> cache.lookup("auto", "user: secret roadmap"))).isPresent();
    }

    @Test
    void expiredEntriesAreIgnoredAndEvicted() {
        UUID tenant = newTenant("Expiry Co", BigDecimal.ONE);
        UUID id = TenantContext.callAs(tenant, () -> cache.store("auto", "user: stale", "old", BigDecimal.ZERO, 1));
        jdbc.update("update semantic_cache set expires_at = now() - interval '1 minute' where id = ?", id);
        assertThat(TenantContext.callAs(tenant, () -> cache.lookup("auto", "user: stale"))).isEmpty();
        assertThat(TenantContext.callAs(tenant, cache::evictExpired)).isEqualTo(1);
        assertThat(cache.threshold()).isEqualTo(0.96);
    }
}
