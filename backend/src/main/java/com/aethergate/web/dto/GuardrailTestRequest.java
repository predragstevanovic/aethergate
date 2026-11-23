package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuardrailTestRequest(@NotBlank @Size(max = 8000) String text) {}
