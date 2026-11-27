package com.aethergate.service.finops;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read models of the FinOps console (attribution, sessions, forecast, anomalies, recommendations). */
public final class FinOpsViews {

    private FinOpsViews() {}

    /** Look-back windows accepted by the analytics endpoints. */
    public enum Window {
        H24("24h", Duration.ofHours(24)), D7("7d", Duration.ofDays(7)), D30("30d", Duration.ofDays(30)),
        D90("90d", Duration.ofDays(90));

        private final String label;
        private final Duration duration;

        Window(String label, Duration duration) {
            this.label = label;
            this.duration = duration;
        }

        public String label() { return label; }
        public Duration duration() { return duration; }

        public static Window parse(String value) {
            if (value == null || value.isBlank()) {
                return D30;
            }
            for (Window w : values()) {
                if (w.label.equalsIgnoreCase(value.strip())) {
                    return w;
                }
            }
            throw new IllegalArgumentException("range must be one of 24h, 7d, 30d, 90d");
        }
    }

    // ── Attribution / showback ──

    public record AttributionRow(String key, String label, long requests, long promptTokens, long completionTokens,
                                 BigDecimal costUsd, BigDecimal savedUsd, double share, double cacheHitRatio,
                                 Integer avgLatencyMs, long errors) {}

    /** {@code allocatedPct}: share of spend that carries at least one cost tag (tag coverage, a FinOps KPI). */
    public record Attribution(String dimension, String range, List<AttributionRow> rows, BigDecimal totalCostUsd,
                              long totalRequests, List<String> tagKeys, double allocatedPct, BigDecimal unallocatedUsd) {}

    // ── End users ──

    public record EndUserRow(String endUserId, long requests, long tokens, BigDecimal costUsd, BigDecimal todayUsd,
                             long sessions, Instant lastSeenAt, Double capUsedPct, int blockedToday) {}

    public record EndUsers(String range, List<EndUserRow> users, BigDecimal dailyCapUsd, long anonymousRequests,
                           BigDecimal anonymousCostUsd) {}

    // ── Agent sessions ──

    public record SessionRow(String sessionId, String endUserId, Instant startedAt, Instant lastAt, long durationMs,
                             long steps, long promptTokens, long completionTokens, BigDecimal costUsd, long errors,
                             long cacheHits, List<String> models, int blocked, Double budgetUsedPct, Double stepsUsedPct) {}

    public record Sessions(String range, List<SessionRow> sessions, BigDecimal sessionCapUsd, Integer sessionMaxRequests,
                           long totalSessions, BigDecimal totalCostUsd) {}

    public record SessionStep(int step, String requestId, Instant at, long offsetMs, String provider, String model,
                              int promptTokens, int completionTokens, BigDecimal costUsd, BigDecimal cumulativeUsd,
                              int latencyMs, String cacheStatus, int statusCode, boolean failover, String routingReason,
                              Map<String, String> tags) {}

    public record SessionDetail(SessionRow summary, List<SessionStep> steps, List<CostControlService.ControlEvent> blocks) {}

    // ── Request explorer ──

    @SuppressWarnings("java:S107")
    public record RequestRow(String requestId, Instant createdAt, String provider, String model, String requestedModel,
                             String routingReason, String projectName, String apiKeyPrefix, String endUserId, String sessionId,
                             Map<String, String> tags, int promptTokens, int completionTokens, BigDecimal costUsd,
                             BigDecimal savedUsd, int latencyMs, int ttftMs, String cacheStatus, boolean failover,
                             int statusCode, Integer templateVersion) {}

    public record RequestPage(List<RequestRow> rows, int page, int size, long total, BigDecimal costUsd, long tokens,
                              Map<String, String> filters) {}

    // ── Forecast ──

    public record ForecastPoint(LocalDate day, BigDecimal actualUsd, BigDecimal cumulativeUsd, BigDecimal projectedUsd,
                                BigDecimal lowUsd, BigDecimal highUsd) {}

    public record ProjectForecast(String projectId, String name, BigDecimal monthToDateUsd, BigDecimal dailyRunRateUsd,
                                  BigDecimal projectedUsd, BigDecimal budgetUsd, Double projectedPct, String status) {}

    @SuppressWarnings("java:S107")
    public record Forecast(String period, int daysInMonth, double elapsedDays, BigDecimal monthToDateUsd,
                           BigDecimal dailyRunRateUsd, BigDecimal linearProjectionUsd, BigDecimal projectedMonthEndUsd,
                           BigDecimal projectedLowUsd, BigDecimal projectedHighUsd, BigDecimal hardLimitUsd,
                           double projectedPctOfLimit, LocalDate limitReachedOn, BigDecimal availableBalanceUsd,
                           Double balanceRunwayDays, LocalDate balanceExhaustedOn, String status, double trendPct,
                           List<ForecastPoint> points, List<ProjectForecast> projects) {}

    // ── Anomalies ──

    public record HourPoint(Instant hour, BigDecimal costUsd, BigDecimal baselineUsd, BigDecimal thresholdUsd, boolean anomalous) {}

    public record AnomalyView(Instant bucketStart, BigDecimal observedUsd, BigDecimal baselineUsd, BigDecimal thresholdUsd,
                              double zScore, String severity, String topDimension, Instant detectedAt) {}

    public record Anomalies(List<HourPoint> hours, List<AnomalyView> anomalies, double zThreshold, String method) {}

    // ── Savings advisor ──

    public record Action(String label, String href) {}

    public record Recommendation(String id, String kind, String category, String severity, String title, String detail,
                                 BigDecimal monthlySavingsUsd, Action action) {}

    public record Recommendations(List<Recommendation> items, BigDecimal totalMonthlySavingsUsd, BigDecimal last30dSpendUsd,
                                  Instant generatedAt) {}
}
