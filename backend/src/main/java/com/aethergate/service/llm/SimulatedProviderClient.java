package com.aethergate.service.llm;

import com.aethergate.domain.model.ProviderType;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Serves MOCK_LLM, and stands in for any provider whose API key is not configured. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SimulatedProviderClient implements LlmProviderClient {

    private final MockLlmEngine engine;

    public SimulatedProviderClient(MockLlmEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean supports(ProviderType provider) {
        return true;
    }

    @Override
    public boolean isLive(ProviderType provider) {
        return false;
    }

    @Override
    public ProviderResult stream(ProviderType provider, String model, List<ChatMessage> messages, int maxTokens, TokenSink sink) {
        return engine.stream(model, messages, maxTokens, sink);
    }
}
