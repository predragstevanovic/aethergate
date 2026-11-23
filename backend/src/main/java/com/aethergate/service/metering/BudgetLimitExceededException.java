package com.aethergate.service.metering;

import java.math.BigDecimal;

/** Month-to-date spend (settled + in-flight + this hold) would exceed the tenant's hard limit → HTTP 402. */
public class BudgetLimitExceededException extends RuntimeException {

    private final BigDecimal limit;
    private final BigDecimal projected;

    public BudgetLimitExceededException(BigDecimal limit, BigDecimal projected) {
        super("Monthly hard budget limit of $" + limit.toPlainString() + " would be exceeded (projected $"
                + projected.toPlainString() + ")");
        this.limit = limit;
        this.projected = projected;
    }

    public BigDecimal getLimit() { return limit; }
    public BigDecimal getProjected() { return projected; }
}
