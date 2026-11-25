package com.aethergate.service.tenant;

import com.aethergate.service.ratelimit.RateLimiterService;
import java.util.UUID;

/**
 * The authenticated caller of the gateway: a tenant plus the credential (API key or dashboard
 * playground) whose rate limits apply. {@code apiKeyId} is null for playground calls.
 * {@code projectId} is the cost centre every request is charged to (null = unassigned).
 */
public record GatewayPrincipal(UUID tenantId, String tenantName, UUID apiKeyId, UUID limiterKey, String keyPrefix,
                               int rpm, int tpm, UUID projectId, KeyPolicy policy) {

    public GatewayPrincipal {
        policy = policy == null ? KeyPolicy.NONE : policy;
    }

    public GatewayPrincipal withPolicy(KeyPolicy newPolicy) {
        return new GatewayPrincipal(tenantId, tenantName, apiKeyId, limiterKey, keyPrefix, rpm, tpm, projectId, newPolicy);
    }

    public RateLimiterService.Limits limits() {
        return new RateLimiterService.Limits(limiterKey, tenantId, rpm, tpm, apiKeyId != null);
    }

    public static GatewayPrincipal forApiKey(UUID tenantId, String tenantName, UUID apiKeyId, String prefix, int rpm, int tpm) {
        return forApiKey(tenantId, tenantName, apiKeyId, prefix, rpm, tpm, null);
    }

    public static GatewayPrincipal forApiKey(UUID tenantId, String tenantName, UUID apiKeyId, String prefix, int rpm, int tpm,
                                             UUID projectId) {
        return new GatewayPrincipal(tenantId, tenantName, apiKeyId, apiKeyId, prefix, rpm, tpm, projectId, KeyPolicy.NONE);
    }

    public static GatewayPrincipal forPlayground(UUID tenantId, String tenantName, int rpm, int tpm) {
        return forPlayground(tenantId, tenantName, rpm, tpm, null);
    }

    public static GatewayPrincipal forPlayground(UUID tenantId, String tenantName, int rpm, int tpm, UUID projectId) {
        UUID limiterKey = UUID.nameUUIDFromBytes(("playground:" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new GatewayPrincipal(tenantId, tenantName, null, limiterKey, "playground", rpm, tpm, projectId, KeyPolicy.NONE);
    }
}
