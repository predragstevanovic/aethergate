package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptMetaRequest(@NotBlank @Size(max = 80) String name, @Size(max = 280) String description) {}
