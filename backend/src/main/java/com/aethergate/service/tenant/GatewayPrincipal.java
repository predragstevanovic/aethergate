package com.aethergate.service.tenant;

import com.aethergate.service.ratelimit.RateLimiterService;
import java.util.UUID;

/**
 * The authenticated caller of the gateway: a tenant plus the credential (API key or dashboard
 * playground) whose rate limits apply. {@code apiKeyId} is null for playground calls.
 */
public record GatewayPrincipal(UUID tenantId, String tenantName, UUID apiKeyId, UUID limiterKey, String keyPrefix,
                               int rpm, int tpm) {

    public RateLimiterService.Limits limits() {
        return new RateLimiterService.Limits(limiterKey, tenantId, rpm, tpm, apiKeyId != null);
    }

    public static GatewayPrincipal forApiKey(UUID tenantId, String tenantName, UUID apiKeyId, String prefix, int rpm, int tpm) {
        return new GatewayPrincipal(tenantId, tenantName, apiKeyId, apiKeyId, prefix, rpm, tpm);
    }

    public static GatewayPrincipal forPlayground(UUID tenantId, String tenantName, int rpm, int tpm) {
        UUID limiterKey = UUID.nameUUIDFromBytes(("playground:" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new GatewayPrincipal(tenantId, tenantName, null, limiterKey, "playground", rpm, tpm);
    }
}
