package com.aethergate.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Create (POST) or full replacement (PUT, with If-Match) of a project. {@code monthlyBudgetUsd = null}: no cap. */
public record ProjectRequest(
        @NotBlank @Size(min = 2, max = 80) String name,
        @Size(max = 280) String description,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") @Digits(integer = 7, fraction = 8)
        BigDecimal monthlyBudgetUsd) {}
