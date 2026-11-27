package com.aethergate.service.metering;

import java.math.BigDecimal;

/**
 * A request would exceed a fine-grained spend cap: the API key's monthly budget, the end user's daily cap, or the
 * agent session's cost / step limit (runaway-agent guard). Budgets answer 402, the step limit 429.
 */
public class SpendCapExceededException extends RuntimeException {

    public enum Kind {
        KEY_MONTHLY_BUDGET("key-budget-exceeded", 402),
        END_USER_DAILY_CAP("end-user-cap-exceeded", 402),
        SESSION_BUDGET("session-budget-exceeded", 402),
        SESSION_STEPS("session-step-limit", 429);

        private final String problemType;
        private final int status;

        Kind(String problemType, int status) {
            this.problemType = problemType;
            this.status = status;
        }

        public String problemType() { return problemType; }
        public int status() { return status; }
    }

    private final Kind kind;
    private final String scopeKey;
    private final BigDecimal limit;
    private final BigDecimal observed;

    public SpendCapExceededException(Kind kind, String scopeKey, BigDecimal limit, BigDecimal observed, String message) {
        super(message);
        this.kind = kind;
        this.scopeKey = scopeKey;
        this.limit = limit;
        this.observed = observed;
    }

    public Kind getKind() { return kind; }
    public String getScopeKey() { return scopeKey; }
    public BigDecimal getLimit() { return limit; }
    public BigDecimal getObserved() { return observed; }
}
