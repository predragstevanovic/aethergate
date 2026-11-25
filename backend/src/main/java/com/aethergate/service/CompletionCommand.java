package com.aethergate.service;

import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.tenant.GatewayPrincipal;
import java.util.List;

/** A chat completion as admitted by the gateway (OpenAI-compatible fields only). */
public record CompletionCommand(GatewayPrincipal principal, String model, List<ChatMessage> messages, Integer maxTokens,
                                boolean simulatePrimaryFailure, TemplateRef template) {

    public CompletionCommand(GatewayPrincipal principal, String model, List<ChatMessage> messages, Integer maxTokens,
                             boolean simulatePrimaryFailure) {
        this(principal, model, messages, maxTokens, simulatePrimaryFailure, null);
    }

    public CompletionCommand {
        messages = List.copyOf(messages);
    }

    /** Same request with guardrail-redacted messages: providers and the cache never see the original text. */
    public CompletionCommand withMessages(List<ChatMessage> redacted) {
        return new CompletionCommand(principal, model, redacted, maxTokens, simulatePrimaryFailure, template);
    }
}
