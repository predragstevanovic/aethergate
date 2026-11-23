package com.aethergate.service.metering;

import java.math.BigDecimal;

/** Pre-flight hold cannot be covered by the available balance → HTTP 402. */
public class InsufficientQuotaException extends RuntimeException {

    private final BigDecimal available;
    private final BigDecimal required;

    public InsufficientQuotaException(BigDecimal available, BigDecimal required) {
        super("Insufficient credit: request needs a hold of $" + required.toPlainString()
                + " but only $" + available.toPlainString() + " is available");
        this.available = available;
        this.required = required;
    }

    public BigDecimal getAvailable() { return available; }
    public BigDecimal getRequired() { return required; }
}
