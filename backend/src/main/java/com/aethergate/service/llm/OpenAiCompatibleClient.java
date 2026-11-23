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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** OpenAI Chat Completions streaming. Gemini is served through its OpenAI-compatible endpoint. */
@Component
@Order(1)
public class OpenAiCompatibleClient implements LlmProviderClient {

    private final ObjectMapper json;
    private final AetherGateProperties.Providers providers;

    public OpenAiCompatibleClient(ObjectMapper json, AetherGateProperties properties) {
        this.json = json;
        this.providers = properties.providers();
    }

    @Override
    public boolean supports(ProviderType provider) {
        return provider == ProviderType.OPENAI || provider == ProviderType.GEMINI;
    }

    @Override
    public boolean isLive(ProviderType provider) {
        return supports(provider) && config(provider).hasKey();
    }

    @Override
    public ProviderResult stream(ProviderType provider, String model, List<ChatMessage> messages, int maxTokens, TokenSink sink) {
        AetherGateProperties.Provider cfg = config(provider);
        ObjectNode body = json.createObjectNode()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        ArrayNode msgs = body.putArray("messages");
        messages.forEach(m -> msgs.addObject().put("role", m.role()).put("content", m.content()));

        StringBuilder text = new StringBuilder();
        int[] usage = {-1, -1};
        String[] finish = {"stop"};
        SseHttp.postStream(provider.name(), cfg.baseUrl() + "/chat/completions",
                Map.of("Authorization", "Bearer " + cfg.apiKey()), body.toString(), Duration.ofSeconds(90), ev -> {
                    if ("[DONE]".equals(ev.data())) {
                        return;
                    }
                    JsonNode chunk = parse(ev.data());
                    JsonNode choice = chunk.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        text.append(delta);
                        sink.onToken(delta);
                    }
                    if (choice.hasNonNull("finish_reason")) {
                        finish[0] = choice.get("finish_reason").asText();
                    }
                    JsonNode u = chunk.path("usage");
                    if (u.isObject()) {
                        usage[0] = u.path("prompt_tokens").asInt(-1);
                        usage[1] = u.path("completion_tokens").asInt(-1);
                    }
                });
        boolean reported = usage[0] >= 0 && usage[1] >= 0;
        return new ProviderResult(text.toString(),
                reported ? usage[0] : TokenEstimator.countPrompt(messages),
                reported ? usage[1] : TokenEstimator.count(text.toString()),
                reported, finish[0]);
    }

    private AetherGateProperties.Provider config(ProviderType provider) {
        return provider == ProviderType.GEMINI ? providers.gemini() : providers.openai();
    }

    private JsonNode parse(String data) {
        try {
            return json.readTree(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed upstream chunk", e);
        }
    }
}
