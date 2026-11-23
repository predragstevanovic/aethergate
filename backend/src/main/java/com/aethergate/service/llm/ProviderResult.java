package com.aethergate.service.llm;

/**
 * What one provider call produced. Token counts come from the provider's usage report when it
 * sends one; otherwise they are estimated locally (and {@code usageReported} is false).
 */
public record ProviderResult(String text, int promptTokens, int completionTokens, boolean usageReported, String finishReason) {}
