package com.aethergate.service.ratelimit;

/** RPM or TPM bucket is empty → HTTP 429 with Retry-After. */
public class RateLimitExceededException extends RuntimeException {

    private final String limitType;
    private final long limit;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String limitType, long limit, long retryAfterSeconds) {
        super("Rate limit exceeded: " + limit + " " + limitType + ". Retry in " + retryAfterSeconds + "s");
        this.limitType = limitType;
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getLimitType() { return limitType; }
    public long getLimit() { return limit; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
