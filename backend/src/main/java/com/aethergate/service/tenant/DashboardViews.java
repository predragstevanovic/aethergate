package com.aethergate.service.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models for the management console. */
public final class DashboardViews {

    private DashboardViews() {}

    public record Kpis(BigDecimal availableBalanceUsd, BigDecimal reservedBalanceUsd, BigDecimal hardBudgetLimitUsd,
                       BigDecimal monthToDateSpendUsd, BigDecimal spendUsd, BigDecimal cacheSavingsUsd, long requests,
                       long cacheHits, double cacheHitRatio, long failovers, long promptTokens, long completionTokens,
                       Integer avgTtftMissMs, Integer avgTtftHitMs) {}

    public record SeriesPoint(Instant bucket, long promptTokens, long completionTokens, BigDecimal costUsd,
                              BigDecimal savedUsd, long requests, long cacheHits) {}

    public record ProviderStatus(String provider, String modelName, int priorityOrder, boolean enabled, boolean live,
                                 String circuitState) {}

    /** Proof that RLS is on: rows owned by other tenants that exist vs. rows this session can see. */
    public record IsolationProof(long otherTenantRowsInDatabase, long otherTenantRowsVisible) {}

    public record RequestRow(String requestId, String providerUsed, String modelUsed, int promptTokens,
                             int completionTokens, BigDecimal totalCostUsd, BigDecimal savedCostUsd, int latencyMs,
                             int ttftMs, String cacheStatus, boolean failoverTriggered, int statusCode, Instant createdAt) {}

    public record Overview(TenantRef tenant, String range, Kpis kpis, List<SeriesPoint> series,
                           List<ProviderStatus> providers, IsolationProof isolation, List<RequestRow> recentRequests) {}

    public record LedgerRow(UUID id, String requestId, String entryType, BigDecimal amountUsd, BigDecimal balanceAfterUsd,
                            String description, Instant createdAt) {}

    public record LedgerPage(List<LedgerRow> entries, int page, int size, long totalElements, int totalPages,
                             Map<String, BigDecimal> totalsByType, boolean balanced) {}
}
