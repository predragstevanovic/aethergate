package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aethergate.service.ratelimit.RateLimitExceededException;
import com.aethergate.service.ratelimit.RateLimiterService;
import com.aethergate.service.ratelimit.RateLimiterService.Limits;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RateLimiterServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T10:15:30Z"), ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();
    private final RateLimiterService limiter = new RateLimiterService(jdbc, clock, nanos::get);

    @Test
    void enforcesRequestsPerMinute() {
        Limits limits = new Limits(UUID.randomUUID(), UUID.randomUUID(), 2, 10_000, false);
        RateLimiterService.Usage u = limiter.acquire(limits, 10);
        assertThat(u.rpmRemaining()).isEqualTo(1);
        assertThat(u.tpmRemaining()).isEqualTo(9_990);
        limiter.acquire(limits, 10);
        assertThatThrownBy(() -> limiter.acquire(limits, 10))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.getLimitType()).isEqualTo("requests/min");
                    assertThat(e.getLimit()).isEqualTo(2);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(30);
                });
        nanos.addAndGet(30_000_000_000L);
        assertThat(limiter.acquire(limits, 10).rpmLimit()).isEqualTo(2);
    }

    @Test
    void enforcesTokensPerMinuteIncludingPostFlightCompletionTokens() {
        Limits limits = new Limits(UUID.randomUUID(), UUID.randomUUID(), 100, 1_000, false);
        limiter.acquire(limits, 100);
        limiter.recordCompletion(limits, 950); // overdraws
        assertThatThrownBy(() -> limiter.acquire(limits, 10))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.getLimitType()).isEqualTo("tokens/min"));
        limiter.recordCompletion(new Limits(UUID.randomUUID(), UUID.randomUUID(), 1, 1, false), 5); // unknown key: no-op
        limiter.recordCompletion(limits, 0);
        assertThat(limiter.flush()).isZero(); // non-persistent limits never sync
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistentKeysPreloadFleetUsageAndFlushDeltas() {
        UUID key = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(key), any())).thenReturn(List.of(new long[]{59, 0}));
        Limits limits = new Limits(key, tenant, 60, 100_000, true);
        limiter.acquire(limits, 40);
        assertThatThrownBy(() -> limiter.acquire(limits, 1)).isInstanceOf(RateLimitExceededException.class);
        limiter.recordCompletion(limits, 25);

        assertThat(limiter.flush()).isEqualTo(1);
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), batch.capture());
        Object[] row = batch.getValue().getFirst();
        assertThat(row[0]).isEqualTo(key);
        assertThat(row[1]).isEqualTo(tenant);
        assertThat(row[2]).isEqualTo(java.sql.Timestamp.from(Instant.parse("2026-11-22T10:15:00Z")));
        assertThat(row[3]).isEqualTo(1L);
        assertThat(row[4]).isEqualTo(65L);
        assertThat(limiter.flush()).isZero(); // deltas were reset

        limiter.invalidate(key);
        limiter.purgeOldWindows();
        verify(jdbc).update(anyString(), any(java.sql.Timestamp.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseTroubleNeverBlocksAdmission() {
        UUID key = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(key), any())).thenThrow(new IllegalStateException("db down"));
        when(jdbc.batchUpdate(anyString(), anyList())).thenThrow(new IllegalStateException("db down"));
        Limits limits = new Limits(key, UUID.randomUUID(), 5, 1000, true);
        assertThat(limiter.acquire(limits, 1).rpmRemaining()).isEqualTo(4);
        assertThat(limiter.flush()).isZero();
        verify(jdbc, never()).update(anyString(), any(java.sql.Timestamp.class));
    }
}
