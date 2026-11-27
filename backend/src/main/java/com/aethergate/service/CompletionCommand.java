package com.aethergate.service;

import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.tenant.GatewayPrincipal;
import java.util.List;

/** A chat completion as admitted by the gateway (OpenAI-compatible fields plus attribution). */
public record CompletionCommand(GatewayPrincipal principal, String model, List<ChatMessage> messages, Integer maxTokens,
                                boolean simulatePrimaryFailure, TemplateRef template, RequestAttribution attribution) {

    public CompletionCommand(GatewayPrincipal principal, String model, List<ChatMessage> messages, Integer maxTokens,
                             boolean simulatePrimaryFailure) {
        this(principal, model, messages, maxTokens, simulatePrimaryFailure, null, RequestAttribution.NONE);
    }

    public CompletionCommand(GatewayPrincipal principal, String model, List<ChatMessage> messages, Integer maxTokens,
                             boolean simulatePrimaryFailure, TemplateRef template) {
        this(principal, model, messages, maxTokens, simulatePrimaryFailure, template, RequestAttribution.NONE);
    }

    public CompletionCommand {
        messages = List.copyOf(messages);
        attribution = attribution == null ? RequestAttribution.NONE : attribution;
    }

    /** Same request with guardrail-redacted messages: providers and the cache never see the original text. */
    public CompletionCommand withMessages(List<ChatMessage> redacted) {
        return new CompletionCommand(principal, model, redacted, maxTokens, simulatePrimaryFailure, template, attribution);
    }
}
