package com.aethergate.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TopUpRequest(@NotNull @DecimalMin("0.01") @DecimalMax("1000") BigDecimal amountUsd) {}
