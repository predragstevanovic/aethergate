package com.aethergate.service.cache;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped semantic cache on pgvector. Lookups use the HNSW index ({@code <=>} is cosine
 * distance) and only return a hit at or above the configured similarity. Row-Level Security
 * guarantees one tenant can never be served another tenant's cached answer.
 */
@Service
public class SemanticCacheService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final double threshold;
    private final AetherGateProperties.Cache config;

    public SemanticCacheService(JdbcTemplate jdbc, Clock clock, AetherGateProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.config = properties.cache();
        this.threshold = config.similarityThreshold();
    }

    @TenantScoped
    public Optional<CacheHit> lookup(String cacheModel, String promptText) {
        String vector = HashingEmbedder.toVectorLiteral(HashingEmbedder.embed(promptText));
        List<CacheHit> rows = jdbc.query("""
                        select id, prompt_text, response_text, model_name, original_cost_usd, completion_tokens,
                               1 - (embedding <=> cast(? as extensions.vector)) as similarity
                        from semantic_cache
                        where model_name = ? and expires_at > ?
                        order by embedding <=> cast(? as extensions.vector)
                        limit 1
                        """,
                (rs, i) -> new CacheHit(rs.getObject("id", UUID.class), rs.getString("prompt_text"),
                        rs.getString("response_text"), rs.getString("model_name"), rs.getDouble("similarity"),
                        rs.getBigDecimal("original_cost_usd"), rs.getInt("completion_tokens")),
                vector, cacheModel, Timestamp.from(clock.instant()), vector);
        Optional<CacheHit> hit = rows.stream().filter(r -> r.similarity() >= threshold).findFirst();
        hit.ifPresent(h -> jdbc.update("update semantic_cache set hit_count = hit_count + 1 where id = ?", h.entryId()));
        return hit;
    }

    @TenantScoped
    public UUID store(String cacheModel, String promptText, String responseText, BigDecimal originalCostUsd, int completionTokens) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                        insert into semantic_cache (id, tenant_id, prompt_text, embedding, response_text, model_name,
                                                    original_cost_usd, completion_tokens, expires_at, created_at)
                        values (?, ?, ?, cast(? as extensions.vector), ?, ?, ?, ?, ?, ?)
                        """,
                id, TenantContext.require(), promptText,
                HashingEmbedder.toVectorLiteral(HashingEmbedder.embed(promptText)), responseText, cacheModel,
                originalCostUsd, completionTokens, Timestamp.from(now.plus(config.ttl())), Timestamp.from(now));
        return id;
    }

    /** Removes expired entries of the current tenant; returns the number deleted. */
    @TenantScoped
    public int evictExpired() {
        return jdbc.update("delete from semantic_cache where expires_at <= ?", Timestamp.from(clock.instant()));
    }

    public double threshold() {
        return threshold;
    }
}
