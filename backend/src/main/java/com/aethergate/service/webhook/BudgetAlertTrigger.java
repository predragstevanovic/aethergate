package com.aethergate.service.webhook;

import com.aethergate.config.TenantContext;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Runs budget-alert evaluation off the response path after a settlement (inline in tests). */
@Component
public class BudgetAlertTrigger {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertTrigger.class);

    private final AlertService alerts;
    private final Executor executor;

    public BudgetAlertTrigger(AlertService alerts, @Qualifier("cacheWriteExecutor") Executor executor) {
        this.alerts = alerts;
        this.executor = executor;
    }

    public void afterSettle(UUID tenantId, UUID projectId) {
        executor.execute(() -> {
            try {
                TenantContext.runAs(tenantId, () -> alerts.evaluate(projectId));
            } catch (RuntimeException e) {
                log.warn("Budget alert evaluation failed for tenant {}: {}", tenantId, e.getMessage());
            }
        });
    }
}
