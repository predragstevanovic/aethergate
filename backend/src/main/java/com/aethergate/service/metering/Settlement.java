package com.aethergate.service.metering;

import java.math.BigDecimal;

/** Outcome of clearing a hold: {@code reserved = settled + released}. */
public record Settlement(String requestId, BigDecimal reservedUsd, BigDecimal settledUsd, BigDecimal releasedUsd,
                         BigDecimal availableAfterUsd, boolean replayed) {}
