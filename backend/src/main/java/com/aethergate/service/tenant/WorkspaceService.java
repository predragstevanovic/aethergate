package com.aethergate.service.tenant;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.EntryType;
import com.aethergate.domain.model.Tenant;
import com.aethergate.domain.repository.LedgerEntryRepository;
import com.aethergate.domain.repository.TenantRepository;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.metering.CostCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Workspace settings of the signed-in tenant: display name and monthly hard limit.
 *
 * <p>No If-Match here on purpose: the tenant row's version moves with every reservation, so an ETag would be
 * stale within milliseconds under traffic. The update takes the same row lock as metering instead, and the
 * limit can never drop below what the month has already committed.
 */
@Service
public class WorkspaceService {

    static final BigDecimal MAX_LIMIT = new BigDecimal("1000000");

    private final TenantRepository tenants;
    private final LedgerEntryRepository ledger;
    private final TenantDirectoryService directory;
    private final Clock clock;

    public WorkspaceService(TenantRepository tenants, LedgerEntryRepository ledger, TenantDirectoryService directory, Clock clock) {
        this.tenants = tenants;
        this.ledger = ledger;
        this.directory = directory;
        this.clock = clock;
    }

    /** {@code committedUsd} = month-to-date settled + holds in flight: the floor for the hard limit. */
    public record WorkspaceSettings(String name, String slug, String planTier, boolean demo, BigDecimal hardBudgetLimitUsd,
                                    BigDecimal monthToDateUsd, BigDecimal reservedUsd, BigDecimal committedUsd,
                                    BigDecimal availableBalanceUsd, Instant createdAt) {}

    @TenantScoped(readOnly = true)
    public WorkspaceSettings get() {
        return view(tenants.findById(TenantContext.require()).orElseThrow());
    }

    @TenantScoped
    public WorkspaceSettings update(String name, BigDecimal hardBudgetLimit) {
        Tenant t = tenants.findByIdForUpdate(TenantContext.require()).orElseThrow();
        if (t.isDemo()) {
            throw new DemoReadOnlyException();
        }
        String n = name == null ? "" : name.strip();
        if (n.length() < 2 || n.length() > 120) {
            throw new IllegalArgumentException("Workspace name must be 2-120 characters");
        }
        if (hardBudgetLimit == null || hardBudgetLimit.signum() <= 0 || hardBudgetLimit.compareTo(MAX_LIMIT) > 0) {
            throw new IllegalArgumentException("Monthly hard limit must be between $0 and $1,000,000");
        }
        BigDecimal limit = CostCalculator.normalize(hardBudgetLimit);
        BigDecimal committed = committed(t);
        if (limit.compareTo(committed) < 0) {
            throw new ResourceConflictException("Limit $" + limit.stripTrailingZeros().toPlainString()
                    + " is below this month's committed spend of $" + committed.toPlainString());
        }
        t.updateSettings(n, limit);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    directory.evictAllWorkspaces(); // /me must show the new name to every member
                }
            });
        }
        return view(t);
    }

    private BigDecimal committed(Tenant t) {
        return ledger.sumSince(t.getId(), EntryType.SETTLE, monthStart()).add(t.getReservedBalanceUsd());
    }

    private WorkspaceSettings view(Tenant t) {
        BigDecimal mtd = CostCalculator.normalize(ledger.sumSince(t.getId(), EntryType.SETTLE, monthStart()));
        BigDecimal reserved = CostCalculator.normalize(t.getReservedBalanceUsd());
        return new WorkspaceSettings(t.getName(), t.getSlug(), t.getPlanTier(), t.isDemo(),
                CostCalculator.normalize(t.getHardBudgetLimitUsd()), mtd, reserved, mtd.add(reserved),
                CostCalculator.normalize(t.getAvailableBalanceUsd()), t.getCreatedAt());
    }

    private Instant monthStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
