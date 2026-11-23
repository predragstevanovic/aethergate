package com.aethergate.service.llm;

import com.aethergate.domain.model.ProviderType;
import java.util.List;

/** One upstream integration. Implementations stream deltas into the sink and block until done. */
public interface LlmProviderClient {

    boolean supports(ProviderType provider);

    /** True when this client talks to the real upstream API (an API key is configured). */
    boolean isLive(ProviderType provider);

    ProviderResult stream(ProviderType provider, String model, List<ChatMessage> messages, int maxTokens, TokenSink sink);
}
