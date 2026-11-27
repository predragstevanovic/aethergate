package com.aethergate.service.finops;

import java.util.Arrays;

/**
 * Robust spike detection for hourly spend: median and MAD (median absolute deviation) of the trailing week instead of
 * mean and standard deviation, so the spike being judged (or an earlier one) cannot inflate its own baseline.
 *
 * <p>{@code sigma = 1.4826 * MAD} (consistent with the standard deviation for normal data), floored so that a flat,
 * near-zero history does not turn every cent into an "anomaly". A bucket is anomalous when its robust z-score is at
 * least {@link #Z_WARNING}, it is at least twice the median and it is above an absolute floor.
 */
public final class RobustBaseline {

    public static final double Z_WARNING = 4.0;
    public static final double Z_CRITICAL = 8.0;
    /** Below this an hour is never an anomaly, whatever its z-score: nobody wants a page for one cent. */
    public static final double MIN_ABSOLUTE_USD = 0.01;
    static final double MIN_SIGMA_USD = 0.0005;

    private RobustBaseline() {}

    public record Verdict(double median, double sigma, double threshold, double z, boolean anomalous, String severity) {}

    public static Verdict evaluate(double[] history, double observed) {
        if (history.length == 0) {
            return new Verdict(0, MIN_SIGMA_USD, Math.max(MIN_ABSOLUTE_USD, Z_WARNING * MIN_SIGMA_USD), 0, false, null);
        }
        double median = median(history);
        double[] deviations = Arrays.stream(history).map(v -> Math.abs(v - median)).toArray();
        double sigma = Math.max(1.4826 * median(deviations), Math.max(0.1 * median, MIN_SIGMA_USD));
        double z = (observed - median) / sigma;
        double threshold = Math.max(median + Z_WARNING * sigma, Math.max(2 * median, MIN_ABSOLUTE_USD));
        boolean anomalous = z >= Z_WARNING && observed >= 2 * median && observed >= MIN_ABSOLUTE_USD;
        String severity = !anomalous ? null : z >= Z_CRITICAL ? "CRITICAL" : "WARNING";
        return new Verdict(median, sigma, threshold, z, anomalous, severity);
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
