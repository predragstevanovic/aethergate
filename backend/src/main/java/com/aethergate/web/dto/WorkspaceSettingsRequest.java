package com.aethergate.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record WorkspaceSettingsRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") BigDecimal hardBudgetLimitUsd) {}
