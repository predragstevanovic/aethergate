package com.aethergate.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** OpenAI-compatible {@code POST /v1/chat/completions} body (the subset the gateway honours). */
public record ChatCompletionRequest(
        @Size(max = 80) String model,
        @Size(max = 64) List<@Valid Message> messages,
        @Min(1) @Max(4096) Integer maxTokens,
        Boolean stream,
        Double temperature,
        @Valid TemplateInvocation promptTemplate) {

    public record Message(
            @NotBlank @Pattern(regexp = "system|user|assistant") String role,
            @NotBlank @Size(max = 32_000) String content) {}

    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }
}
