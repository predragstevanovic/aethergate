package com.aethergate.service.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models returned by {@link TenantAdminService}. */
public final class AdminViews {

    private AdminViews() {}

    /** {@code status}: ACTIVE, EXPIRED (past expires_at, e.g. after a rotation grace period) or REVOKED. */
    public record ApiKeyView(UUID id, String name, String keyPrefix, int rateLimitRpm, int rateLimitTpm, boolean active,
                             Instant lastUsedAt, Instant createdAt, UUID projectId, String status, Instant expiresAt,
                             List<String> allowedModels, List<String> ipAllowlist, UUID rotatedFrom, long version) {}

    /** Replacement for a key's limits and restrictions (PUT, If-Match). Empty lists = no restriction. */
    public record KeyPolicyDraft(String name, int rateLimitRpm, int rateLimitTpm, Instant expiresAt, List<String> allowedModels,
                                 List<String> ipAllowlist) {}

    /** Rotation result: the new key (raw secret shown once) and the old key with its grace expiry. */
    public record RotatedApiKey(ApiKeyView key, String rawKey, ApiKeyView previous) {}

    /** Returned exactly once, at creation: the raw key is never stored or shown again. */
    public record CreatedApiKey(ApiKeyView key, String rawKey) {}

    public record ProviderView(UUID id, String provider, String modelName, int priorityOrder, BigDecimal inputCostPer1m,
                               BigDecimal outputCostPer1m, boolean enabled, boolean live) {}

    public record TopUpResult(String requestId, BigDecimal amountUsd, BigDecimal availableBalanceUsd) {}
}
