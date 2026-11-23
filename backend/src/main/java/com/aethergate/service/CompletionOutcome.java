package com.aethergate.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything that happened to one request: which provider answered, what it cost, what the
 * hold was and how much "dust" went back to the balance. Feeds both the API response and the
 * playground telemetry drawer.
 */
public record CompletionOutcome(String requestId, String cacheStatus, String provider, String model, boolean live,
                                int promptTokens, int completionTokens, BigDecimal reservedUsd, BigDecimal settledUsd,
                                BigDecimal releasedUsd, BigDecimal savedUsd, int ttftMs, int latencyMs,
                                boolean failoverTriggered, List<String> attempts, String finishReason, String text,
                                Double cacheSimilarity) {}
