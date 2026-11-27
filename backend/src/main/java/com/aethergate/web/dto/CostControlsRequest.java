package com.aethergate.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Full replacement of the workspace cost controls; null = that control is off. */
public record CostControlsRequest(
        @DecimalMin("0.0001") @DecimalMax("100000") BigDecimal endUserDailyCapUsd,
        @DecimalMin("0.0001") @DecimalMax("100000") BigDecimal sessionCapUsd,
        @Min(1) @Max(100_000) Integer sessionMaxRequests,
        @Min(1) @Max(100) Integer downgradeAtPct,
        @Size(max = 80) String downgradeModel) {}
