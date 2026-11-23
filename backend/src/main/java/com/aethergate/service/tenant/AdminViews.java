package com.aethergate.service.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read models returned by {@link TenantAdminService}. */
public final class AdminViews {

    private AdminViews() {}

    public record ApiKeyView(UUID id, String name, String keyPrefix, int rateLimitRpm, int rateLimitTpm, boolean active,
                             Instant lastUsedAt, Instant createdAt) {}

    /** Returned exactly once, at creation: the raw key is never stored or shown again. */
    public record CreatedApiKey(ApiKeyView key, String rawKey) {}

    public record ProviderView(UUID id, String provider, String modelName, int priorityOrder, BigDecimal inputCostPer1m,
                               BigDecimal outputCostPer1m, boolean enabled, boolean live) {}

    public record TopUpResult(String requestId, BigDecimal amountUsd, BigDecimal availableBalanceUsd) {}
}
