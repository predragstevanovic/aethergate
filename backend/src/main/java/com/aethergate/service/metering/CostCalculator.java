package com.aethergate.service.metering;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Exact fixed-point pricing (ADR-002). Prices are USD per 1M tokens; results have scale 8.
 *
 * <p>Pre-flight estimates round UP (a hold must never be smaller than what can be consumed);
 * actual costs round HALF_UP. Because the estimate also uses the maximum completion length
 * and the most expensive provider in the fallback chain, actual ≤ estimate always holds.
 */
public final class CostCalculator {

    public static final int SCALE = 8;
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    private CostCalculator() {}

    public static BigDecimal actualCost(long promptTokens, long completionTokens,
                                        BigDecimal inputPer1m, BigDecimal outputPer1m) {
        return raw(promptTokens, completionTokens, inputPer1m, outputPer1m).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal estimatedMaxCost(long promptTokens, long maxCompletionTokens,
                                              BigDecimal inputPer1m, BigDecimal outputPer1m) {
        return raw(promptTokens, maxCompletionTokens, inputPer1m, outputPer1m).setScale(SCALE, RoundingMode.CEILING);
    }

    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal raw(long promptTokens, long completionTokens, BigDecimal inputPer1m, BigDecimal outputPer1m) {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
        BigDecimal input = BigDecimal.valueOf(promptTokens).multiply(inputPer1m, MathContext.DECIMAL128);
        BigDecimal output = BigDecimal.valueOf(completionTokens).multiply(outputPer1m, MathContext.DECIMAL128);
        return input.add(output, MathContext.DECIMAL128).divide(ONE_MILLION, MathContext.DECIMAL128);
    }
}
