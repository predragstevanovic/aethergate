package com.aethergate.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Console playground prompt (free text, or a stored template with variables); always streamed. */
public record PlaygroundRequest(
        @Size(max = 8000) String prompt,
        @Size(max = 2000) String system,
        @Min(1) @Max(2048) Integer maxTokens,
        boolean simulateFailure,
        UUID projectId,
        @Valid TemplateInvocation template,
        @Size(max = 128) String endUser,
        @Size(max = 128) String sessionId,
        @Size(max = 600) String tags) {

    public PlaygroundRequest(String prompt, String system, Integer maxTokens, boolean simulateFailure, UUID projectId,
                             TemplateInvocation template) {
        this(prompt, system, maxTokens, simulateFailure, projectId, template, null, null, null);
    }
}
