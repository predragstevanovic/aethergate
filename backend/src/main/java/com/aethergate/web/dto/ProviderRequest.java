package com.aethergate.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Add (POST, provider required) or edit (PUT, provider ignored) a model in the fallback chain. Prices per 1M tokens. */
public record ProviderRequest(
        String provider,
        @NotBlank @Size(max = 80) String modelName,
        @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal inputCostPer1m,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal outputCostPer1m) {}
