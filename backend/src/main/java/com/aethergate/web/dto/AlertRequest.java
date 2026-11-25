package com.aethergate.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** projectId null = the workspace's monthly hard limit. */
public record AlertRequest(UUID projectId, @NotNull @Min(1) @Max(100) Integer thresholdPct) {}
