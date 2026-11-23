package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** type: KEYWORDS | REGEX | PII_EMAIL | PII_CARD; action: BLOCK | REDACT; pattern only for KEYWORDS / REGEX. */
public record GuardrailRequest(
        @NotBlank @Size(min = 2, max = 80) String name,
        @NotBlank String type,
        @Size(max = 500) String pattern,
        @NotBlank String action) {}
