package com.aethergate.service;

import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.tenant.GatewayPrincipal;
import java.util.List;

/** A chat completion as admitted by the gateway (OpenAI-compatible fields only). */
public record CompletionCommand(GatewayPrincipal principal, String model, List<ChatMessage> messages, Integer maxTokens,
                                boolean simulatePrimaryFailure) {

    public CompletionCommand {
        messages = List.copyOf(messages);
    }
}
