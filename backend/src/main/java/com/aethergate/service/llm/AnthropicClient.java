package com.aethergate.service.llm;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.domain.model.ProviderType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Anthropic Messages API streaming (system prompt hoisted, usage from message_start / message_delta). */
@Component
@Order(1)
public class AnthropicClient implements LlmProviderClient {

    static final String API_VERSION = "2023-06-01";

    private final ObjectMapper json;
    private final AetherGateProperties.Provider config;

    public AnthropicClient(ObjectMapper json, AetherGateProperties properties) {
        this.json = json;
        this.config = properties.providers().anthropic();
    }

    @Override
    public boolean supports(ProviderType provider) {
        return provider == ProviderType.ANTHROPIC;
    }

    @Override
    public boolean isLive(ProviderType provider) {
        return supports(provider) && config.hasKey();
    }

    @Override
    public ProviderResult stream(ProviderType provider, String model, List<ChatMessage> messages, int maxTokens, TokenSink sink) {
        ObjectNode body = json.createObjectNode()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("stream", true);
        String system = messages.stream().filter(m -> "system".equals(m.role()))
                .map(ChatMessage::content).collect(Collectors.joining("\n\n"));
        if (!system.isEmpty()) {
            body.put("system", system);
        }
        ArrayNode msgs = body.putArray("messages");
        messages.stream().filter(m -> !"system".equals(m.role()))
                .forEach(m -> msgs.addObject().put("role", "assistant".equals(m.role()) ? "assistant" : "user")
                        .put("content", m.content()));

        StringBuilder text = new StringBuilder();
        int[] usage = {-1, -1};
        String[] finish = {"stop"};
        SseHttp.postStream(provider.name(), config.baseUrl() + "/messages",
                Map.of("x-api-key", config.apiKey(), "anthropic-version", API_VERSION), body.toString(),
                Duration.ofSeconds(90), ev -> {
                    JsonNode node = parse(ev.data());
                    switch (node.path("type").asText()) {
                        case "message_start" -> usage[0] = node.path("message").path("usage").path("input_tokens").asInt(-1);
                        case "content_block_delta" -> {
                            String delta = node.path("delta").path("text").asText("");
                            if (!delta.isEmpty()) {
                                text.append(delta);
                                sink.onToken(delta);
                            }
                        }
                        case "message_delta" -> {
                            usage[1] = node.path("usage").path("output_tokens").asInt(-1);
                            String stop = node.path("delta").path("stop_reason").asText("");
                            finish[0] = "max_tokens".equals(stop) ? "length" : "stop";
                        }
                        case "error" -> throw new ProviderUnavailableException(provider.name(),
                                node.path("error").path("message").asText("stream error"));
                        default -> {
                            // ping, content_block_start/stop, message_stop carry nothing we need
                        }
                    }
                });
        boolean reported = usage[0] >= 0 && usage[1] >= 0;
        return new ProviderResult(text.toString(),
                reported ? usage[0] : TokenEstimator.countPrompt(messages),
                reported ? usage[1] : TokenEstimator.count(text.toString()),
                reported, finish[0]);
    }

    private JsonNode parse(String data) {
        try {
            return json.readTree(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed upstream event", e);
        }
    }
}
