package com.aethergate.config;

import com.aethergate.service.llm.ClientAbortedException;
import com.aethergate.service.llm.MidStreamFailureException;
import com.aethergate.service.llm.ProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breaker and retry policy for upstream LLM providers.
 *
 * <p>Breakers are keyed per (tenant, provider): a chaos experiment in one demo workspace
 * never trips the breaker for another tenant. A real deployment that wants shared upstream
 * health would key by provider endpoint instead (see ADR-006).
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(ProviderUnavailableException.class, MidStreamFailureException.class)
                .ignoreExceptions(ClientAbortedException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(100))
                .retryExceptions(ProviderUnavailableException.class)
                .ignoreExceptions(MidStreamFailureException.class, ClientAbortedException.class)
                .build();
        return RetryRegistry.of(config);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
