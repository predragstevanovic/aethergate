package com.aethergate.service.ratelimit;

/**
 * Continuous-refill token bucket. Not thread-safe by itself: {@link RateLimiterService}
 * guards each key's buckets with a {@code ReentrantLock} (no {@code synchronized}, so
 * virtual threads are never pinned).
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, long refillPerMinute, long nowNanos) {
        if (capacity <= 0 || refillPerMinute <= 0) {
            throw new IllegalArgumentException("capacity and refill must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerMinute / 60_000_000_000d;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    public boolean canConsume(long amount, long nowNanos) {
        refill(nowNanos);
        return tokens >= Math.min(amount, (long) capacity);
    }

    /** Consumes unconditionally (may go negative: post-flight usage is charged even if it overshoots). */
    public void consume(long amount, long nowNanos) {
        refill(nowNanos);
        tokens -= amount;
    }

    /** Seconds until {@code amount} tokens are available again (at least 1). */
    public long secondsUntil(long amount, long nowNanos) {
        refill(nowNanos);
        double needed = Math.min(amount, capacity) - tokens;
        if (needed <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(needed / refillPerNano / 1_000_000_000d));
    }

    public long available(long nowNanos) {
        refill(nowNanos);
        return (long) Math.floor(tokens);
    }

    public long capacity() {
        return (long) capacity;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = nowNanos;
        }
    }
}
