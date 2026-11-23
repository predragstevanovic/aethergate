package com.aethergate.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed view of the {@code aethergate.*} configuration tree. Secrets arrive only via environment variables. */
@ConfigurationProperties(prefix = "aethergate")
public record AetherGateProperties(
        @DefaultValue("") String tool,
        @DefaultValue Cors cors,
        @DefaultValue Supabase supabase,
        @DefaultValue Demo demo,
        @DefaultValue Metering metering,
        @DefaultValue Cache cache,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Providers providers,
        @DefaultValue Mock mock) {

    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {}

    public record Supabase(
            @DefaultValue("http://localhost:54321") String url,
            @DefaultValue("") String anonKey,
            @DefaultValue("") String serviceRoleKey) {}

    public record Demo(
            @DefaultValue("demo@aethergate.dev") String email,
            @DefaultValue("") String password,
            @DefaultValue("vortex-ai-labs") String tenantSlug) {}

    public record Metering(
            @DefaultValue("512") int defaultMaxTokens,
            @DefaultValue("1000") BigDecimal maxTopUpUsd) {}

    public record Cache(
            @DefaultValue("0.96") double similarityThreshold,
            @DefaultValue("24h") Duration ttl,
            @DefaultValue("true") boolean asyncStore) {}

    public record RateLimit(
            @DefaultValue("30") int playgroundRpm,
            @DefaultValue("60000") int playgroundTpm,
            @DefaultValue("5s") Duration syncInterval) {}

    public record Provider(@DefaultValue("") String apiKey, @DefaultValue("") String baseUrl) {
        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Providers(@DefaultValue Provider openai, @DefaultValue Provider anthropic, @DefaultValue Provider gemini) {}

    public record Mock(
            @DefaultValue("80") int ttftMinMs,
            @DefaultValue("180") int ttftMaxMs,
            @DefaultValue("12") int tokenMinMs,
            @DefaultValue("28") int tokenMaxMs) {}
}
