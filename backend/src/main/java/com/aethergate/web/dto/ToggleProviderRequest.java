package com.aethergate.web.dto;

import jakarta.validation.constraints.NotNull;

public record ToggleProviderRequest(@NotNull Boolean enabled) {}
