package com.aethergate.service.webhook;

import com.aethergate.config.AetherGateProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends due outbox deliveries. A background system worker, so it reads across tenants (like the API-key
 * directory) and never runs tenant code.
 *
 * <ul>
 *   <li><b>Claiming</b> uses {@code FOR UPDATE SKIP LOCKED} plus a 2-minute lease (next_attempt_at is pushed
 *       forward): several gateway instances never send the same delivery twice at once, and a crash mid-send
 *       simply lets the lease expire and another worker retry it.</li>
 *   <li><b>Retries</b> use exponential backoff with jitter (10 s, 30 s, 2 min, 10 min, 30 min); after
 *       {@code maxAttempts} the delivery is DEAD and can be re-sent by hand.</li>
 *   <li><b>At-least-once</b>: receivers de-duplicate on {@code X-AetherGate-Event-Id}.</li>
 * </ul>
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    static final int BATCH = 25;
    static final Duration[] BACKOFF = {Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofMinutes(30)};

    private final JdbcTemplate jdbc;
    private final WebhookSender sender;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final ReentrantLock running = new ReentrantLock();
    private final ExecutorService kicker = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopped;

    public WebhookDispatcher(JdbcTemplate jdbc, WebhookSender sender, PlatformTransactionManager txManager,
                             AetherGateProperties properties) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.tx = new TransactionTemplate(txManager);
        this.maxAttempts = properties.webhooks().maxAttempts();
    }

    private record Claimed(UUID id, String eventId, String eventType, String payload, int attempts, String url,
                           String secret, boolean enabled) {}

    /** New deliveries are sent right after the transaction that wrote them commits, not on the next poll. */
    @TransactionalEventListener(fallbackExecution = true)
    void onEnqueued(WebhooksEnqueued event) {
        if (!stopped) {
            kicker.execute(this::dispatchDue);
        }
    }

    /** No new sends once the context is closing (the connection pool goes away with it). */
    @jakarta.annotation.PreDestroy
    void stop() {
        stopped = true;
        kicker.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${aethergate.webhooks.poll-interval:5s}", initialDelayString = "10s")
    public void poll() {
        dispatchDue();
    }

    /** Sends every due delivery (in batches); returns how many were attempted. Safe to call concurrently. */
    public int dispatchDue() {
        if (stopped || !running.tryLock()) {
            return 0; // another thread of this instance is already draining; it will pick these up
        }
        int total = 0;
        try {
            List<Claimed> batch;
            do {
                batch = claim();
                total += batch.size();
                sendAll(batch);
            } while (batch.size() == BATCH && !stopped);
        } catch (RuntimeException e) {
            log.warn("Webhook dispatch failed: {}", e.getMessage());
        } finally {
            running.unlock();
        }
        return total;
    }

    private List<Claimed> claim() {
        return tx.execute(status -> jdbc.query("""
                with due as (
                    select id from webhook_deliveries
                    where status = 'PENDING' and next_attempt_at <= now()
                    order by next_attempt_at limit ? for update skip locked
                ), claimed as (
                    update webhook_deliveries d set next_attempt_at = now() + interval '2 minutes'
                    from due where d.id = due.id
                    returning d.id, d.tenant_id, d.endpoint_id, d.event_id, d.event_type, d.payload, d.attempts
                )
                select c.id, c.event_id, c.event_type, c.payload, c.attempts, e.url, e.secret, e.is_enabled
                from claimed c join webhook_endpoints e on e.tenant_id = c.tenant_id and e.id = c.endpoint_id
                """, (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("event_id"),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts"), rs.getString("url"),
                rs.getString("secret"), rs.getBoolean("is_enabled")), BATCH));
    }

    private void sendAll(List<Claimed> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = batch.stream().map(c -> pool.submit(() -> deliver(c))).<Future<?>>map(f -> f).toList();
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warn("Webhook delivery crashed: {}", e.getCause().getMessage());
                }
            }
        }
    }

    private void deliver(Claimed c) {
        if (!c.enabled()) {
            jdbc.update("update webhook_deliveries set status = 'DEAD', last_error = 'Endpoint disabled' where id = ?", c.id());
            return;
        }
        WebhookSender.Result result = sender.send(c.url(), c.secret(), c.id().toString(), c.eventId(), c.eventType(), c.payload());
        int attempt = c.attempts() + 1;
        if (result.success()) {
            jdbc.update("""
                    update webhook_deliveries set status = 'SUCCEEDED', attempts = ?, last_status_code = ?, last_error = null,
                    delivered_at = now() where id = ?""", attempt, result.statusCode(), c.id());
        } else if (attempt >= maxAttempts) {
            jdbc.update("""
                    update webhook_deliveries set status = 'DEAD', attempts = ?, last_status_code = ?, last_error = ?
                    where id = ?""", attempt, result.statusCode(), result.error(), c.id());
            log.info("Webhook delivery {} is DEAD after {} attempts: {}", c.id(), attempt, result.error());
        } else {
            jdbc.update("""
                    update webhook_deliveries set attempts = ?, last_status_code = ?, last_error = ?,
                    next_attempt_at = now() + make_interval(secs => ?) where id = ?""",
                    attempt, result.statusCode(), result.error(), backoffSeconds(attempt), c.id());
        }
    }

    /** 10 s, 30 s, 2 min, 10 min, 30 min, each with ±10 % jitter so retries of one outage do not arrive in lockstep. */
    static double backoffSeconds(int attempt) {
        Duration base = BACKOFF[Math.min(attempt, BACKOFF.length) - 1];
        double jitter = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return base.toMillis() / 1000.0 * jitter;
    }
}
