package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptVersionRequest(@Size(max = 4000) String systemPrompt, @NotBlank @Size(max = 8000) String userTemplate,
                                   @Size(max = 200) String note) {}
