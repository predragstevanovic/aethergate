package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.AetherGateProperties.Provider;
import com.aethergate.domain.model.ProviderType;
import com.aethergate.service.llm.AnthropicClient;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.OpenAiCompatibleClient;
import com.aethergate.service.llm.ProviderResult;
import com.aethergate.service.llm.ProviderUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Wire-level contract tests of the OpenAI and Anthropic streaming clients against a stub server. */
class ProviderClientsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ChatMessage> MESSAGES = List.of(new ChatMessage("system", "Be brief."), new ChatMessage("user", "Hi"));
    private StubHttpServer stub;

    @BeforeEach
    void start() throws Exception {
        stub = new StubHttpServer();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private OpenAiCompatibleClient openai() {
        Provider p = new Provider("sk-test", stub.url() + "/openai");
        return new OpenAiCompatibleClient(JSON, TestProps.with("http://x", "", "", "", p, new Provider("", ""), new Provider("gm-key", stub.url() + "/gemini")));
    }

    private AnthropicClient anthropic(String key) {
        return new AnthropicClient(JSON, TestProps.with("http://x", "", "", "", new Provider("", ""), new Provider(key, stub.url() + "/anthropic"), new Provider("", "")));
    }

    @Test
    void openAiStreamsDeltasAndReadsReportedUsage() {
        stub.on("POST /openai/chat/completions", new StubHttpServer.Response(200, "text/event-stream", """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"content":" world"},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":2}}

                data: [DONE]

                """));
        List<String> deltas = new ArrayList<>();
        ProviderResult r = openai().stream(ProviderType.OPENAI, "gpt-4o-mini", MESSAGES, 64, deltas::add);
        assertThat(deltas).containsExactly("Hello", " world");
        assertThat(r.text()).isEqualTo("Hello world");
        assertThat(r.usageReported()).isTrue();
        assertThat(r.promptTokens()).isEqualTo(11);
        assertThat(r.completionTokens()).isEqualTo(2);
        assertThat(r.finishReason()).isEqualTo("stop");

        StubHttpServer.Recorded req = stub.requests.getFirst();
        assertThat(req.headers().get("Authorization")).containsExactly("Bearer sk-test");
        assertThat(req.body()).contains("\"stream\":true", "\"include_usage\":true", "\"max_tokens\":64");
    }

    @Test
    void geminiUsesItsOwnEndpointAndEstimatesWhenNoUsage() {
        stub.on("POST /gemini/chat/completions", new StubHttpServer.Response(200, "text/event-stream", """
                data: {"choices":[{"index":0,"delta":{"content":"Bonjour"},"finish_reason":"length"}]}

                data: [DONE]
                """));
        OpenAiCompatibleClient client = openai();
        assertThat(client.supports(ProviderType.GEMINI)).isTrue();
        assertThat(client.supports(ProviderType.ANTHROPIC)).isFalse();
        assertThat(client.isLive(ProviderType.GEMINI)).isTrue();
        ProviderResult r = client.stream(ProviderType.GEMINI, "gemini-2.5-flash", MESSAGES, 16, d -> { });
        assertThat(r.usageReported()).isFalse();
        assertThat(r.completionTokens()).isPositive();
        assertThat(r.finishReason()).isEqualTo("length");
    }

    @Test
    void openAiNon2xxIsProviderUnavailable() {
        stub.on("POST /openai/chat/completions", new StubHttpServer.Response(503, "application/json", "{\"error\":\"overloaded\"}"));
        assertThatThrownBy(() -> openai().stream(ProviderType.OPENAI, "gpt-4o-mini", MESSAGES, 16, d -> { }))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void openAiMalformedChunkFails() {
        stub.on("POST /openai/chat/completions", new StubHttpServer.Response(200, "text/event-stream", "data: {not json\n\n"));
        assertThatThrownBy(() -> openai().stream(ProviderType.OPENAI, "gpt-4o-mini", MESSAGES, 16, d -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void connectionRefusedIsProviderUnavailable() {
        Provider dead = new Provider("sk", "http://127.0.0.1:1");
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(JSON, TestProps.with("http://x", "", "", "", dead, dead, dead));
        assertThatThrownBy(() -> client.stream(ProviderType.OPENAI, "m", MESSAGES, 8, d -> { }))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("connection failed");
    }

    @Test
    void anthropicStreamsTextAndReadsUsageFromEvents() {
        stub.on("POST /anthropic/messages", new StubHttpServer.Response(200, "text/event-stream", """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":21,"output_tokens":1}}}

                event: ping
                data: {"type":"ping"}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """));
        AnthropicClient client = anthropic("ak-test");
        assertThat(client.isLive(ProviderType.ANTHROPIC)).isTrue();
        assertThat(client.supports(ProviderType.OPENAI)).isFalse();
        List<String> deltas = new ArrayList<>();
        ProviderResult r = client.stream(ProviderType.ANTHROPIC, "claude-haiku-4-5", List.of(
                new ChatMessage("system", "Be brief."), new ChatMessage("user", "Hi"), new ChatMessage("assistant", "Yo"),
                new ChatMessage("user", "Again")), 32, deltas::add);
        assertThat(deltas).containsExactly("Hi ", "there");
        assertThat(r.promptTokens()).isEqualTo(21);
        assertThat(r.completionTokens()).isEqualTo(5);
        assertThat(r.finishReason()).isEqualTo("length");

        StubHttpServer.Recorded req = stub.requests.getFirst();
        assertThat(req.headers().get("X-api-key")).containsExactly("ak-test");
        assertThat(req.headers().get("Anthropic-version")).containsExactly("2023-06-01");
        assertThat(req.body()).contains("\"system\":\"Be brief.\"", "\"role\":\"assistant\"").doesNotContain("\"role\":\"system\"");
    }

    @Test
    void anthropicWithoutSystemPromptAndWithoutUsageEstimates() {
        stub.on("POST /anthropic/messages", new StubHttpServer.Response(200, "text/event-stream", """
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                """));
        ProviderResult r = anthropic("ak").stream(ProviderType.ANTHROPIC, "m", List.of(new ChatMessage("user", "Hi")), 8, d -> { });
        assertThat(r.usageReported()).isFalse();
        assertThat(r.finishReason()).isEqualTo("stop");
        assertThat(stub.requests.getFirst().body()).doesNotContain("\"system\"");
    }

    @Test
    void anthropicErrorEventAndHttpErrorsAreUnavailable() {
        stub.on("POST /anthropic/messages",
                new StubHttpServer.Response(200, "text/event-stream",
                        "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n"),
                new StubHttpServer.Response(529, "application/json", "{}"),
                new StubHttpServer.Response(200, "text/event-stream", "data: <html>\n\n"));
        AnthropicClient client = anthropic("ak");
        assertThatThrownBy(() -> client.stream(ProviderType.ANTHROPIC, "m", MESSAGES, 8, d -> { }))
                .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("Overloaded");
        assertThatThrownBy(() -> client.stream(ProviderType.ANTHROPIC, "m", MESSAGES, 8, d -> { }))
                .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("529");
        assertThatThrownBy(() -> client.stream(ProviderType.ANTHROPIC, "m", MESSAGES, 8, d -> { }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(anthropic("").isLive(ProviderType.ANTHROPIC)).isFalse();
    }
}
