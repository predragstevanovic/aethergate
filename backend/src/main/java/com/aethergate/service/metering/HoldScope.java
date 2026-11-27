package com.aethergate.service.metering;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything a hold is charged to besides the tenant: the project (cost centre), the API key, the caller's end
 * user and the agent session. {@code keyMonthlyBudgetUsd} is the key's own cap (from its cached policy); the end-user
 * and session caps are workspace settings read from the locked tenant row.
 */
public record HoldScope(UUID projectId, UUID apiKeyId, String endUserId, String sessionId, BigDecimal keyMonthlyBudgetUsd) {

    public static final HoldScope NONE = new HoldScope(null, null, null, null, null);

    public static HoldScope project(UUID projectId) {
        return new HoldScope(projectId, null, null, null, null);
    }
}
