package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.ClientAbortedException;
import com.aethergate.service.llm.MockLlmEngine;
import com.aethergate.service.llm.ProviderResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockLlmEngineTest {

    private final MockLlmEngine engine = new MockLlmEngine(TestProps.defaults());

    @Test
    void answersAreTopicalAndDeterministic() {
        List<ChatMessage> q = List.of(new ChatMessage("user", "Explain the semantic cache with pgvector"));
        assertThat(engine.compose("gpt-4o-mini", q)).contains("384-dimensional").isEqualTo(engine.compose("x", q));
        assertThat(engine.compose("m", List.of(new ChatMessage("user", "How do circuit breakers work?")))).contains("half-open");
        assertThat(engine.compose("m", List.of(new ChatMessage("user", "rate limit tpm?")))).contains("token bucket");
        assertThat(engine.compose("m", List.of(new ChatMessage("user", "virtual threads vs webflux")))).contains("pinning");
        assertThat(engine.compose("m", List.of(new ChatMessage("user", "cheapest model pricing")))).contains("gpt-4o-mini");
        assertThat(engine.compose("m", List.of(new ChatMessage("user", "double-entry ledger")))).contains("RESERVE = SETTLE + RELEASE");
    }

    @Test
    void genericAnswerEchoesQuestionAndLastUserTurn() {
        String longQ = "Q".repeat(200);
        String answer = engine.compose("claude-haiku-4-5", List.of(
                new ChatMessage("user", "first question"), new ChatMessage("assistant", "a"), new ChatMessage("user", longQ)));
        assertThat(answer).contains("claude-haiku-4-5 simulator").contains("...");
        assertThat(engine.compose("m", List.of(new ChatMessage("system", "only system")))).contains("only system");
        assertThat(engine.compose("m", List.of())).contains("You asked");
    }

    @Test
    void streamsChunksAndHonoursMaxTokens() {
        List<ChatMessage> q = List.of(new ChatMessage("user", "Tell me about row level security"));
        List<String> chunks = new ArrayList<>();
        ProviderResult full = engine.stream("m", q, 4096, chunks::add);
        assertThat(String.join("", chunks)).isEqualTo(full.text()).isEqualTo(engine.compose("m", q));
        assertThat(full.finishReason()).isEqualTo("stop");
        assertThat(full.usageReported()).isFalse();

        ProviderResult cut = engine.stream("m", q, 10, d -> { });
        assertThat(cut.finishReason()).isEqualTo("length");
        assertThat(cut.completionTokens()).isLessThanOrEqualTo(10);
    }

    @Test
    void realisticPacingAndInterruptBecomesClientAbort() {
        AetherGateProperties slow = new AetherGateProperties("", new AetherGateProperties.Cors(List.of()),
                new AetherGateProperties.Supabase("", "", ""), new AetherGateProperties.Demo("", "", ""),
                new AetherGateProperties.Metering(1, BigDecimal.ONE), new AetherGateProperties.Cache(0.9, Duration.ofHours(1), false),
                new AetherGateProperties.RateLimit(1, 1, Duration.ofSeconds(1)),
                new AetherGateProperties.Providers(null, null, null), new AetherGateProperties.Mock(5, 5, 1, 2), null);
        MockLlmEngine paced = new MockLlmEngine(slow);
        long t0 = System.nanoTime();
        paced.stream("m", List.of(new ChatMessage("user", "hello")), 5, d -> { });
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(5);

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> paced.stream("m", List.of(new ChatMessage("user", "hello")), 5, d -> { }))
                .isInstanceOf(ClientAbortedException.class);
        assertThat(Thread.interrupted()).isTrue();
    }
}
