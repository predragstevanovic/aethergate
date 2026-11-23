package com.aethergate.service.cache;

import java.math.BigDecimal;
import java.util.UUID;

/** A cached completion whose prompt embedding matched within the similarity threshold. */
public record CacheHit(UUID entryId, String promptText, String responseText, String modelName, double similarity,
                       BigDecimal originalCostUsd, int completionTokens) {}
