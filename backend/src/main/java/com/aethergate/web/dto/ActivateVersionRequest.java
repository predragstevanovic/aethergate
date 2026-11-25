package com.aethergate.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ActivateVersionRequest(@NotNull @Min(1) Integer version) {}
