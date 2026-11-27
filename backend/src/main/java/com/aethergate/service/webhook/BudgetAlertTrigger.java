package com.aethergate.service.webhook;

import com.aethergate.config.TenantContext;
import com.aethergate.service.finops.AnomalyService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Runs post-settlement checks off the response path (inline in tests): budget alerts after every settlement, and the
 * cost-anomaly scan of the last two hours at most once per tenant and minute (it reads a week of hourly buckets).
 */
@Component
public class BudgetAlertTrigger {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertTrigger.class);
    static final long ANOMALY_SCAN_INTERVAL_MS = 60_000;

    private final AlertService alerts;
    private final AnomalyService anomalies;
    private final Executor executor;
    private final Map<UUID, Long> lastScan = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis = System::currentTimeMillis;

    public BudgetAlertTrigger(AlertService alerts, AnomalyService anomalies, @Qualifier("cacheWriteExecutor") Executor executor) {
        this.alerts = alerts;
        this.anomalies = anomalies;
        this.executor = executor;
    }

    public void afterSettle(UUID tenantId, UUID projectId) {
        executor.execute(() -> {
            try {
                TenantContext.runAs(tenantId, () -> alerts.evaluate(projectId));
            } catch (RuntimeException e) {
                log.warn("Budget alert evaluation failed for tenant {}: {}", tenantId, e.getMessage());
            }
            long now = clockMillis.getAsLong();
            Long previous = lastScan.get(tenantId);
            if (previous != null && now - previous < ANOMALY_SCAN_INTERVAL_MS) {
                return;
            }
            lastScan.put(tenantId, now);
            try {
                TenantContext.runAs(tenantId, () -> anomalies.scan(2));
            } catch (RuntimeException e) {
                log.warn("Cost anomaly scan failed for tenant {}: {}", tenantId, e.getMessage());
            }
        });
    }
}
