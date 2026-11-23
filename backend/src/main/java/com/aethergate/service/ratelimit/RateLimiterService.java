package com.aethergate.service.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Dual-layer rate limiting (ADR-003): RPM + TPM token buckets per API key in Caffeine for
 * sub-millisecond admission, synchronised with PostgreSQL minute-window counters so that
 * several gateway instances share one view of usage without a Redis cluster.
 *
 * <p>Sync model: each instance flushes its usage deltas every few seconds; a bucket that is
 * (re)created on an instance is pre-drained with what the whole fleet already used this minute.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    /** Limits that apply to one caller (an API key, or a dashboard playground). */
    public record Limits(UUID keyId, UUID tenantId, int rpm, int tpm, boolean persistent) {}

    /** Snapshot for X-RateLimit-* response headers. */
    public record Usage(long rpmLimit, long rpmRemaining, long tpmLimit, long tpmRemaining) {}

    private static final class KeyState {
        final ReentrantLock lock = new ReentrantLock();
        final TokenBucket rpm;
        final TokenBucket tpm;

        KeyState(TokenBucket rpm, TokenBucket tpm) {
            this.rpm = rpm;
            this.tpm = tpm;
        }
    }

    private record Delta(UUID tenantId, LongAdder requests, LongAdder tokens) {}

    private final Cache<UUID, KeyState> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();
    private final Map<UUID, Delta> pending = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LongSupplier nanoTime;

    @Autowired
    public RateLimiterService(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, System::nanoTime);
    }

    /** Visible for tests: lets the monotonic clock be driven manually. */
    public RateLimiterService(JdbcTemplate jdbc, Clock clock, LongSupplier nanoTime) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /** Admits one request carrying {@code promptTokens}, or throws {@link RateLimitExceededException}. */
    public Usage acquire(Limits limits, int promptTokens) {
        KeyState state = buckets.get(limits.keyId(), id -> create(limits));
        long now = nanoTime.getAsLong();
        state.lock.lock();
        try {
            if (!state.rpm.canConsume(1, now)) {
                throw new RateLimitExceededException("requests/min", limits.rpm(), state.rpm.secondsUntil(1, now));
            }
            if (!state.tpm.canConsume(promptTokens, now)) {
                throw new RateLimitExceededException("tokens/min", limits.tpm(), state.tpm.secondsUntil(promptTokens, now));
            }
            state.rpm.consume(1, now);
            state.tpm.consume(promptTokens, now);
            record(limits, 1, promptTokens);
            return new Usage(limits.rpm(), state.rpm.available(now), limits.tpm(), Math.max(0, state.tpm.available(now)));
        } finally {
            state.lock.unlock();
        }
    }

    /** Charges completion tokens after the stream finished (may overdraw the TPM bucket). */
    public void recordCompletion(Limits limits, int completionTokens) {
        KeyState state = buckets.getIfPresent(limits.keyId());
        if (state == null || completionTokens <= 0) {
            return;
        }
        long now = nanoTime.getAsLong();
        state.lock.lock();
        try {
            state.tpm.consume(completionTokens, now);
        } finally {
            state.lock.unlock();
        }
        record(limits, 0, completionTokens);
    }

    /** Writes this instance's usage deltas into the shared minute windows. */
    @Scheduled(fixedDelayString = "${aethergate.rate-limit.sync-interval:5s}")
    public int flush() {
        if (pending.isEmpty()) {
            return 0;
        }
        Timestamp window = Timestamp.from(currentWindow());
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<UUID, Delta> e : pending.entrySet()) {
            long requests = e.getValue().requests().sumThenReset();
            long tokens = e.getValue().tokens().sumThenReset();
            if (requests > 0 || tokens > 0) {
                batch.add(new Object[]{e.getKey(), e.getValue().tenantId(), window, requests, tokens});
            }
        }
        // Entries are kept (one per active key): removing them here could race with record().
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            jdbc.batchUpdate("""
                    insert into rate_limit_windows (api_key_id, tenant_id, window_start, request_count, token_count)
                    values (?, ?, ?, ?, ?)
                    on conflict (api_key_id, window_start) do update
                       set request_count = rate_limit_windows.request_count + excluded.request_count,
                           token_count   = rate_limit_windows.token_count + excluded.token_count
                    """, batch);
        } catch (RuntimeException e) {
            log.warn("Rate-limit window sync failed; buckets keep enforcing locally: {}", e.getMessage());
            return 0;
        }
        return batch.size();
    }

    @Scheduled(cron = "0 17 * * * *")
    public void purgeOldWindows() {
        jdbc.update("delete from rate_limit_windows where window_start < ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(2))));
    }

    public void invalidate(UUID keyId) {
        buckets.invalidate(keyId);
    }

    private KeyState create(Limits limits) {
        long now = nanoTime.getAsLong();
        KeyState state = new KeyState(new TokenBucket(limits.rpm(), limits.rpm(), now), new TokenBucket(limits.tpm(), limits.tpm(), now));
        if (limits.persistent()) {
            try {
                List<long[]> used = jdbc.query(
                        "select request_count, token_count from rate_limit_windows where api_key_id = ? and window_start = ?",
                        (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)},
                        limits.keyId(), Timestamp.from(currentWindow()));
                if (!used.isEmpty()) {
                    state.rpm.consume(used.getFirst()[0], now);
                    state.tpm.consume(used.getFirst()[1], now);
                }
            } catch (RuntimeException e) {
                log.warn("Could not load shared rate-limit window, starting with a full bucket: {}", e.getMessage());
            }
        }
        return state;
    }

    private void record(Limits limits, long requests, long tokens) {
        if (!limits.persistent()) {
            return;
        }
        Delta delta = pending.computeIfAbsent(limits.keyId(), k -> new Delta(limits.tenantId(), new LongAdder(), new LongAdder()));
        delta.requests().add(requests);
        delta.tokens().add(tokens);
    }

    private Instant currentWindow() {
        return clock.instant().truncatedTo(ChronoUnit.MINUTES);
    }
}
