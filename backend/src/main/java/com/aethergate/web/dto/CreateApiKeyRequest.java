package com.aethergate.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateApiKeyRequest(
        @NotBlank @Size(max = 100) String name,
        @Min(1) @Max(10_000) Integer rateLimitRpm,
        @Min(100) @Max(10_000_000) Integer rateLimitTpm,
        UUID projectId) {}
