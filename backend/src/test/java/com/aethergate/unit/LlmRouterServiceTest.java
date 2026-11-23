package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.ResilienceConfig;
import com.aethergate.domain.model.ProviderType;
import com.aethergate.service.llm.AllProvidersFailedException;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.ClientAbortedException;
import com.aethergate.service.llm.LlmProviderClient;
import com.aethergate.service.llm.LlmRouterService;
import com.aethergate.service.llm.MockLlmEngine;
import com.aethergate.service.llm.ProviderResult;
import com.aethergate.service.llm.ProviderUnavailableException;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.llm.RoutedCompletion;
import com.aethergate.service.llm.SimulatedProviderClient;
import com.aethergate.service.llm.StreamInterruptedException;
import com.aethergate.service.llm.TokenSink;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmRouterServiceTest {

    private static final List<ChatMessage> MSG = List.of(new ChatMessage("user", "hello router"));
    private static final RouteTarget OPENAI = new RouteTarget(ProviderType.OPENAI, "gpt-4o-mini", 1, BigDecimal.ONE, BigDecimal.ONE);
    private static final RouteTarget ANTHROPIC = new RouteTarget(ProviderType.ANTHROPIC, "claude", 2, BigDecimal.ONE, BigDecimal.ONE);

    /** A "live" client for one provider with scripted behaviour. */
    private static final class ScriptedClient implements LlmProviderClient {
        final ProviderType type;
        final AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<TokenSink, ProviderResult> behaviour;

        ScriptedClient(ProviderType type, java.util.function.Function<TokenSink, ProviderResult> behaviour) {
            this.type = type;
            this.behaviour = behaviour;
        }

        @Override public boolean supports(ProviderType p) { return p == type; }
        @Override public boolean isLive(ProviderType p) { return p == type; }

        @Override
        public ProviderResult stream(ProviderType p, String model, List<ChatMessage> m, int max, TokenSink sink) {
            calls.incrementAndGet();
            return behaviour.apply(sink);
        }
    }

    private static ProviderResult ok(TokenSink sink) {
        sink.onToken("ok");
        return new ProviderResult("ok", 3, 1, true, "stop");
    }

    private LlmRouterService router(LlmProviderClient... live) {
        ResilienceConfig cfg = new ResilienceConfig();
        List<LlmProviderClient> clients = new ArrayList<>(List.of(live));
        clients.add(new SimulatedProviderClient(new MockLlmEngine(TestProps.defaults())));
        return new LlmRouterService(clients, cfg.circuitBreakerRegistry(), cfg.retryRegistry());
    }

    @Test
    void usesPrimaryWhenHealthy() {
        ScriptedClient openai = new ScriptedClient(ProviderType.OPENAI, LlmRouterServiceTest::ok);
        RoutedCompletion r = router(openai).route(UUID.randomUUID(), List.of(OPENAI, ANTHROPIC), MSG, 10, false, d -> { });
        assertThat(r.target()).isEqualTo(OPENAI);
        assertThat(r.failoverTriggered()).isFalse();
        assertThat(r.live()).isTrue();
        assertThat(r.attempts()).isEmpty();
    }

    @Test
    @DisplayName("primary 503 → one retry → failover to simulated fallback; breaker opens after repeated failures")
    void failsOverAndOpensBreaker() {
        ScriptedClient openai = new ScriptedClient(ProviderType.OPENAI, sink -> {
            throw new ProviderUnavailableException("OPENAI", "HTTP 503");
        });
        LlmRouterService router = router(openai);
        UUID tenant = UUID.randomUUID();

        RoutedCompletion first = router.route(tenant, List.of(OPENAI, ANTHROPIC), MSG, 50, false, d -> { });
        assertThat(first.target()).isEqualTo(ANTHROPIC);
        assertThat(first.failoverTriggered()).isTrue();
        assertThat(first.live()).isFalse();
        assertThat(openai.calls).hasValue(2); // retried once

        router.route(tenant, List.of(OPENAI, ANTHROPIC), MSG, 50, false, d -> { });
        assertThat(router.breakerStates(tenant, List.of(ProviderType.OPENAI, ProviderType.ANTHROPIC)))
                .containsEntry(ProviderType.OPENAI, "OPEN")
                .containsEntry(ProviderType.ANTHROPIC, "CLOSED");

        RoutedCompletion third = router.route(tenant, List.of(OPENAI, ANTHROPIC), MSG, 50, false, d -> { });
        assertThat(third.attempts()).containsExactly("OPENAI: circuit OPEN, skipped");
        assertThat(openai.calls).hasValue(4); // not called while open

        // other tenants are unaffected (breakers are per tenant + provider)
        assertThat(router.breakerStates(UUID.randomUUID(), List.of(ProviderType.OPENAI))).isEqualTo(Map.of(ProviderType.OPENAI, "CLOSED"));
    }

    @Test
    void simulatedFailureOnlyHitsThePrimary() {
        ScriptedClient openai = new ScriptedClient(ProviderType.OPENAI, LlmRouterServiceTest::ok);
        RoutedCompletion r = router(openai).route(UUID.randomUUID(), List.of(OPENAI, ANTHROPIC), MSG, 50, true, d -> { });
        assertThat(r.target()).isEqualTo(ANTHROPIC);
        assertThat(openai.calls).hasValue(0);
        assertThat(r.attempts().getFirst()).contains("simulated HTTP 503");
    }

    @Test
    void allProvidersFailing() {
        ScriptedClient openai = new ScriptedClient(ProviderType.OPENAI, sink -> {
            throw new IllegalStateException("boom");
        });
        LlmRouterService router = router(openai);
        assertThatThrownBy(() -> router.route(UUID.randomUUID(), List.of(OPENAI), MSG, 5, false, d -> { }))
                .isInstanceOf(AllProvidersFailedException.class)
                .satisfies(e -> assertThat(((AllProvidersFailedException) e).getAttempts()).hasSize(1));
        assertThatThrownBy(() -> router.route(UUID.randomUUID(), List.of(), MSG, 5, false, d -> { }))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    @DisplayName("a failure after the first token cannot fail over: partial output is reported")
    void midStreamFailureIsNotFailedOver() {
        ScriptedClient openai = new ScriptedClient(ProviderType.OPENAI, sink -> {
            sink.onToken("partial ");
            throw new ProviderUnavailableException("OPENAI", "connection reset");
        });
        assertThatThrownBy(() -> router(openai).route(UUID.randomUUID(), List.of(OPENAI, ANTHROPIC), MSG, 50, false, d -> { }))
                .isInstanceOfSatisfying(StreamInterruptedException.class, e -> {
                    assertThat(e.isClientAborted()).isFalse();
                    assertThat(e.getTarget()).isEqualTo(OPENAI);
                    assertThat(e.getPartial().text()).isEqualTo("partial ");
                });
        assertThat(openai.calls).hasValue(1);
    }

    @Test
    void clientAbortStopsWithoutCountingAsProviderFailure() {
        ScriptedClient openai = new ScriptedClient(ProviderType.OPENAI, LlmRouterServiceTest::ok);
        UUID tenant = UUID.randomUUID();
        TokenSink brokenPipe = d -> {
            throw new ClientAbortedException(new java.io.IOException("broken pipe"));
        };
        assertThatThrownBy(() -> router(openai).route(tenant, List.of(OPENAI, ANTHROPIC), MSG, 50, false, brokenPipe))
                .isInstanceOfSatisfying(StreamInterruptedException.class, e -> assertThat(e.isClientAborted()).isTrue());
    }

    @Test
    void simulatorStandsInForProvidersWithoutKeys() {
        LlmRouterService router = router();
        assertThat(router.isLive(ProviderType.OPENAI)).isFalse();
        StringBuilder out = new StringBuilder();
        RoutedCompletion r = router.route(UUID.randomUUID(), List.of(OPENAI), MSG, 40, false, out::append);
        assertThat(r.live()).isFalse();
        assertThat(out.toString()).isEqualTo(r.result().text()).isNotBlank();
    }
}
