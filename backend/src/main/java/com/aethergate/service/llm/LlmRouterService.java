package com.aethergate.service.llm;

import com.aethergate.domain.model.ProviderType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Primary → fallback routing with a Resilience4j circuit breaker and retry per provider.
 *
 * <p>Failover is only allowed while nothing has been streamed to the client yet; after the
 * first token a provider failure ends the request ({@link MidStreamFailureException}).
 */
@Service
public class LlmRouterService {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterService.class);

    private final List<LlmProviderClient> clients;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;

    public LlmRouterService(List<LlmProviderClient> clients, CircuitBreakerRegistry breakers, RetryRegistry retries) {
        this.clients = List.copyOf(clients);
        this.breakers = breakers;
        this.retries = retries;
    }

    public RoutedCompletion route(UUID tenantId, List<RouteTarget> chain, List<ChatMessage> messages, int maxTokens,
                                  boolean simulatePrimaryFailure, TokenSink sink) {
        if (chain.isEmpty()) {
            throw new AllProvidersFailedException(List.of("no enabled providers configured"));
        }
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            RouteTarget target = chain.get(i);
            boolean injectFailure = simulatePrimaryFailure && i == 0;
            LlmProviderClient client = clientFor(target.provider());
            CircuitBreaker breaker = breakers.circuitBreaker(breakerName(tenantId, target.provider()));
            Retry retry = retries.retry(breakerName(tenantId, target.provider()));
            GuardedSink guarded = new GuardedSink(sink);

            Supplier<ProviderResult> call = () -> invoke(client, target, messages, maxTokens, injectFailure, guarded);
            try {
                ProviderResult result = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call)).get();
                return new RoutedCompletion(target, result, i > 0, client.isLive(target.provider()), List.copyOf(attempts));
            } catch (CallNotPermittedException e) {
                attempts.add(target.provider() + ": circuit OPEN, skipped");
            } catch (ClientAbortedException e) {
                String text = guarded.emitted();
                throw new StreamInterruptedException(target, new ProviderResult(text, TokenEstimator.countPrompt(messages),
                        TokenEstimator.count(text), false, "client_abort"), true, e);
            } catch (MidStreamFailureException e) {
                throw new StreamInterruptedException(target, e.getPartial(), false, e);
            } catch (ProviderUnavailableException e) {
                attempts.add(e.getMessage());
                log.warn("Provider {} unavailable for tenant {}: {}", target.provider(), tenantId, e.getMessage());
            }
        }
        throw new AllProvidersFailedException(attempts);
    }

    /** Current breaker state per provider ("CLOSED", "OPEN", "HALF_OPEN"); untouched breakers are CLOSED. */
    public Map<ProviderType, String> breakerStates(UUID tenantId, List<ProviderType> providers) {
        Map<ProviderType, String> states = new LinkedHashMap<>();
        for (ProviderType p : providers) {
            String state = breakers.find(breakerName(tenantId, p))
                    .map(cb -> cb.getState().name())
                    .orElse(CircuitBreaker.State.CLOSED.name());
            states.put(p, state);
        }
        return states;
    }

    public boolean isLive(ProviderType provider) {
        return clientFor(provider).isLive(provider);
    }

    static String breakerName(UUID tenantId, ProviderType provider) {
        return tenantId + ":" + provider.name();
    }

    private LlmProviderClient clientFor(ProviderType provider) {
        return clients.stream()
                .filter(c -> c.supports(provider) && c.isLive(provider))
                .findFirst()
                // no API key configured: the simulator stands in for this provider
                .or(() -> clients.stream().filter(SimulatedProviderClient.class::isInstance).findFirst())
                .orElseThrow(() -> new IllegalStateException("No client for provider " + provider));
    }

    private static ProviderResult invoke(LlmProviderClient client, RouteTarget target, List<ChatMessage> messages,
                                         int maxTokens, boolean injectFailure, GuardedSink sink) {
        if (injectFailure) {
            throw new ProviderUnavailableException(target.provider().name(), "simulated HTTP 503 Service Unavailable");
        }
        try {
            return client.stream(target.provider(), target.model(), messages, maxTokens, sink);
        } catch (ClientAbortedException e) {
            throw e;
        } catch (RuntimeException e) {
            if (sink.emittedAny()) {
                String text = sink.emitted();
                throw new MidStreamFailureException(target.provider().name(),
                        new ProviderResult(text, TokenEstimator.countPrompt(messages), TokenEstimator.count(text), false, "error"), e);
            }
            throw e instanceof ProviderUnavailableException pue
                    ? pue
                    : new ProviderUnavailableException(target.provider().name(), e.getMessage(), e);
        }
    }

    /** Remembers what was forwarded so the router knows whether failover is still possible. */
    private static final class GuardedSink implements TokenSink {
        private final TokenSink delegate;
        private final StringBuilder emitted = new StringBuilder();

        GuardedSink(TokenSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onToken(String delta) {
            delegate.onToken(delta);
            emitted.append(delta);
        }

        boolean emittedAny() {
            return !emitted.isEmpty();
        }

        String emitted() {
            return emitted.toString();
        }
    }
}
