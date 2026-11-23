package com.aethergate.service;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.LlmRequestLog;
import com.aethergate.service.cache.CacheHit;
import com.aethergate.service.cache.SemanticCacheService;
import com.aethergate.service.llm.AllProvidersFailedException;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.LlmRouterService;
import com.aethergate.service.llm.ProviderResult;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.llm.RoutedCompletion;
import com.aethergate.service.llm.StreamInterruptedException;
import com.aethergate.service.llm.TokenEstimator;
import com.aethergate.service.llm.TokenSink;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.metering.MeteringLedgerService;
import com.aethergate.service.metering.RequestLogService;
import com.aethergate.service.metering.Reservation;
import com.aethergate.service.metering.Settlement;
import com.aethergate.service.ratelimit.RateLimiterService;
import com.aethergate.service.tenant.GatewayPrincipal;
import com.aethergate.service.tenant.TenantAdminService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The gateway pipeline: Auth (done by the filter) → Rate limit → Semantic cache → Reserve →
 * Route/stream → Settle → Log → Cache store.
 *
 * <p>{@link #prepare} runs on the request thread and may throw 402/429/503 while the HTTP
 * status can still be chosen. {@link GatewayExecution#run} then streams and always leaves the
 * ledger balanced: the hold is settled (delivered tokens) or released (nothing delivered).
 */
@Service
public class GatewayOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(GatewayOrchestratorService.class);
    private static final Pattern REPLAY_CHUNKS = Pattern.compile("\\S+\\s*|\\s+");
    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(CostCalculator.SCALE);
    static final int MAX_COMPLETION_TOKENS = 4096;

    private final RateLimiterService rateLimiter;
    private final SemanticCacheService cache;
    private final TenantAdminService admin;
    private final MeteringLedgerService ledger;
    private final LlmRouterService router;
    private final RequestLogService requestLogs;
    private final Executor cacheWriter;
    private final int defaultMaxTokens;
    private final LongSupplier clockMillis;

    public GatewayOrchestratorService(RateLimiterService rateLimiter, SemanticCacheService cache, TenantAdminService admin,
                                      MeteringLedgerService ledger, LlmRouterService router, RequestLogService requestLogs,
                                      @Qualifier("cacheWriteExecutor") Executor cacheWriter, AetherGateProperties properties) {
        this.rateLimiter = rateLimiter;
        this.cache = cache;
        this.admin = admin;
        this.ledger = ledger;
        this.router = router;
        this.requestLogs = requestLogs;
        this.cacheWriter = cacheWriter;
        this.defaultMaxTokens = properties.metering().defaultMaxTokens();
        this.clockMillis = System::currentTimeMillis;
    }

    public GatewayExecution prepare(CompletionCommand cmd) {
        long start = clockMillis.getAsLong();
        GatewayPrincipal principal = cmd.principal();
        UUID tenantId = principal.tenantId();
        String requestId = newRequestId();
        int maxTokens = Math.clamp(cmd.maxTokens() == null ? defaultMaxTokens : cmd.maxTokens(), 1, MAX_COMPLETION_TOKENS);
        int promptTokens = TokenEstimator.countPrompt(cmd.messages());
        String cacheModel = cmd.model() == null || cmd.model().isBlank() ? "auto" : cmd.model();
        String cacheKey = canonicalPrompt(cmd.messages());

        RateLimiterService.Usage usage = rateLimiter.acquire(principal.limits(), promptTokens);

        Optional<CacheHit> hit = TenantContext.callAs(tenantId, () -> cache.lookup(cacheModel, cacheKey));
        if (hit.isPresent()) {
            return new CachedExecution(requestId, principal, usage, hit.get(), promptTokens, start);
        }

        List<RouteTarget> chain = TenantContext.callAs(tenantId, admin::routeChain);
        if (chain.isEmpty()) {
            throw new AllProvidersFailedException(List.of("no enabled providers configured"));
        }
        BigDecimal hold = chain.stream()
                .map(t -> CostCalculator.estimatedMaxCost(promptTokens, maxTokens, t.inputCostPer1m(), t.outputCostPer1m()))
                .max(BigDecimal::compareTo)
                .orElseThrow();
        Reservation reservation = TenantContext.callAs(tenantId, () -> ledger.reserveBudget(requestId, hold,
                "Pre-flight hold: " + promptTokens + " prompt + " + maxTokens + " max completion tokens"));
        return new LiveExecution(requestId, principal, usage, cmd, chain, reservation, maxTokens, cacheModel, cacheKey, start);
    }

    /** Conversation as one string: the cache key covers the whole context, not just the last turn. */
    static String canonicalPrompt(List<ChatMessage> messages) {
        return messages.stream().map(m -> m.role() + ": " + m.content()).collect(Collectors.joining("\n"));
    }

    static String newRequestId() {
        StringBuilder sb = new StringBuilder("req_");
        for (int i = 0; i < 24; i++) {
            sb.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }

    /** Wraps the client sink to timestamp the first token. */
    private final class TimingSink implements TokenSink {
        private final TokenSink delegate;
        private final long start;
        private long firstTokenAt = -1;

        TimingSink(TokenSink delegate, long start) {
            this.delegate = delegate;
            this.start = start;
        }

        @Override
        public void onToken(String delta) {
            if (firstTokenAt < 0) {
                firstTokenAt = clockMillis.getAsLong();
            }
            delegate.onToken(delta);
        }

        int ttftMs() {
            return (int) Math.max(0, (firstTokenAt < 0 ? clockMillis.getAsLong() : firstTokenAt) - start);
        }
    }

    private final class CachedExecution implements GatewayExecution {
        private final String requestId;
        private final GatewayPrincipal principal;
        private final RateLimiterService.Usage usage;
        private final CacheHit hit;
        private final int promptTokens;
        private final long start;

        CachedExecution(String requestId, GatewayPrincipal principal, RateLimiterService.Usage usage, CacheHit hit,
                        int promptTokens, long start) {
            this.requestId = requestId;
            this.principal = principal;
            this.usage = usage;
            this.hit = hit;
            this.promptTokens = promptTokens;
            this.start = start;
        }

        @Override public String requestId() { return requestId; }
        @Override public boolean cacheHit() { return true; }
        @Override public RateLimiterService.Usage rateLimitUsage() { return usage; }

        @Override
        public CompletionOutcome run(TokenSink sink) {
            TimingSink timing = new TimingSink(sink, start);
            Matcher m = REPLAY_CHUNKS.matcher(hit.responseText());
            while (m.find()) {
                timing.onToken(m.group());
            }
            int latency = (int) (clockMillis.getAsLong() - start);
            BigDecimal saved = hit.originalCostUsd() == null ? ZERO : hit.originalCostUsd();
            TenantContext.runAs(principal.tenantId(), () -> requestLogs.record(new LlmRequestLog(principal.tenantId(),
                    requestId, principal.apiKeyId(), "CACHE", hit.modelName(), promptTokens, hit.completionTokens(),
                    ZERO, saved, latency, timing.ttftMs(), "HIT", false, 200)));
            log.info("cache HIT request={} similarity={} saved=${}", requestId, String.format("%.4f", hit.similarity()), saved);
            return new CompletionOutcome(requestId, "HIT", "CACHE", hit.modelName(), false, promptTokens,
                    hit.completionTokens(), ZERO, ZERO, ZERO, saved, timing.ttftMs(), latency, false, List.of(), "stop",
                    hit.responseText(), hit.similarity());
        }
    }

    private final class LiveExecution implements GatewayExecution {
        private final String requestId;
        private final GatewayPrincipal principal;
        private final RateLimiterService.Usage usage;
        private final CompletionCommand cmd;
        private final List<RouteTarget> chain;
        private final Reservation reservation;
        private final int maxTokens;
        private final String cacheModel;
        private final String cacheKey;
        private final long start;

        @SuppressWarnings("java:S107")
        LiveExecution(String requestId, GatewayPrincipal principal, RateLimiterService.Usage usage, CompletionCommand cmd,
                      List<RouteTarget> chain, Reservation reservation, int maxTokens, String cacheModel, String cacheKey,
                      long start) {
            this.requestId = requestId;
            this.principal = principal;
            this.usage = usage;
            this.cmd = cmd;
            this.chain = chain;
            this.reservation = reservation;
            this.maxTokens = maxTokens;
            this.cacheModel = cacheModel;
            this.cacheKey = cacheKey;
            this.start = start;
        }

        @Override public String requestId() { return requestId; }
        @Override public boolean cacheHit() { return false; }
        @Override public RateLimiterService.Usage rateLimitUsage() { return usage; }

        @Override
        public CompletionOutcome run(TokenSink sink) {
            UUID tenantId = principal.tenantId();
            TimingSink timing = new TimingSink(sink, start);
            MDC.put("request_id", requestId);
            MDC.put("tenant_id", tenantId.toString());
            try {
                RoutedCompletion routed = router.route(tenantId, chain, cmd.messages(), maxTokens, cmd.simulatePrimaryFailure(), timing);
                CompletionOutcome outcome = settle(routed.target(), routed.result(), timing, routed.failoverTriggered(),
                        routed.live(), routed.attempts(), 200);
                if (!routed.result().text().isBlank()) {
                    BigDecimal cost = outcome.settledUsd();
                    String text = routed.result().text();
                    int completion = routed.result().completionTokens();
                    cacheWriter.execute(() -> storeInCache(tenantId, text, cost, completion));
                }
                return outcome;
            } catch (StreamInterruptedException e) {
                settle(e.getTarget(), e.getPartial(), timing, false, router.isLive(e.getTarget().provider()), List.of(),
                        e.isClientAborted() ? 499 : 502);
                throw e;
            } catch (AllProvidersFailedException e) {
                TenantContext.runAs(tenantId, () -> ledger.releaseReservation(requestId, "All providers unavailable"));
                int latency = (int) (clockMillis.getAsLong() - start);
                TenantContext.runAs(tenantId, () -> requestLogs.record(new LlmRequestLog(tenantId, requestId,
                        principal.apiKeyId(), "NONE", chain.getFirst().model(), TokenEstimator.countPrompt(cmd.messages()), 0,
                        ZERO, ZERO, latency, latency, "MISS", true, 503)));
                throw e;
            } finally {
                MDC.remove("request_id");
                MDC.remove("tenant_id");
            }
        }

        private CompletionOutcome settle(RouteTarget target, ProviderResult result, TimingSink timing, boolean failover,
                                         boolean live, List<String> attempts, int status) {
            UUID tenantId = principal.tenantId();
            BigDecimal actual = CostCalculator.actualCost(result.promptTokens(), result.completionTokens(),
                    target.inputCostPer1m(), target.outputCostPer1m());
            Settlement settlement = TenantContext.callAs(tenantId, () -> ledger.settleTransaction(requestId, actual,
                    target.model() + ": " + result.promptTokens() + " in / " + result.completionTokens() + " out tokens"));
            int latency = (int) (clockMillis.getAsLong() - start);
            TenantContext.runAs(tenantId, () -> requestLogs.record(new LlmRequestLog(tenantId, requestId, principal.apiKeyId(),
                    target.provider().name(), target.model(), result.promptTokens(), result.completionTokens(),
                    settlement.settledUsd(), ZERO, latency, timing.ttftMs(), "MISS", failover, status)));
            rateLimiter.recordCompletion(principal.limits(), result.completionTokens());
            log.info("settled request={} provider={} cost=${} released=${} failover={}", requestId, target.provider(),
                    settlement.settledUsd(), settlement.releasedUsd(), failover);
            return new CompletionOutcome(requestId, "MISS", target.provider().name(), target.model(), live,
                    result.promptTokens(), result.completionTokens(), settlement.reservedUsd(), settlement.settledUsd(),
                    settlement.releasedUsd(), ZERO, timing.ttftMs(), latency, failover, attempts, result.finishReason(),
                    result.text(), null);
        }

        private void storeInCache(UUID tenantId, String text, BigDecimal cost, int completionTokens) {
            try {
                TenantContext.runAs(tenantId, () -> cache.store(cacheModel, cacheKey, text, cost, completionTokens));
            } catch (RuntimeException e) {
                log.warn("Semantic cache store failed for {}: {}", requestId, e.getMessage());
            }
        }
    }
}
