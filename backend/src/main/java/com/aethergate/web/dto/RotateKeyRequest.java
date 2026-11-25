package com.aethergate.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** graceHours: how long the old key keeps working (0 = revoke now, max 168 = one week). Default 24. */
public record RotateKeyRequest(@Min(0) @Max(168) Integer graceHours) {}
