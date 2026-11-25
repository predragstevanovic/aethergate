package com.aethergate.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Full replacement of a key's policy (PUT + If-Match). Empty / null lists mean "no restriction". */
public record KeyPolicyRequest(@NotBlank @Size(max = 100) String name,
                               @NotNull @Min(1) @Max(10_000) Integer rateLimitRpm,
                               @NotNull @Min(100) @Max(10_000_000) Integer rateLimitTpm,
                               Instant expiresAt,
                               @Size(max = 10) List<String> allowedModels,
                               @Size(max = 10) List<String> ipAllowlist) {}
