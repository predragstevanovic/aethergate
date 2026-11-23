package com.aethergate.service;

import com.aethergate.service.llm.TokenSink;
import com.aethergate.service.ratelimit.RateLimiterService;

/**
 * An admitted request: authenticated, rate-limited, cache-checked and (on a miss) funded.
 * Splitting admission from execution lets HTTP errors (402/429) be returned with proper status
 * codes before a streaming response is committed.
 */
public interface GatewayExecution {

    String requestId();

    boolean cacheHit();

    RateLimiterService.Usage rateLimitUsage();

    /** Streams the completion into {@code sink} and settles the ledger. Blocks until done. */
    CompletionOutcome run(TokenSink sink);
}
