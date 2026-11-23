package com.aethergate.service.metering;

import java.math.BigDecimal;

/** A pre-flight hold. {@code replayed} is true when an identical request id was already reserved. */
public record Reservation(String requestId, BigDecimal reservedUsd, BigDecimal availableAfterUsd, boolean replayed) {}
