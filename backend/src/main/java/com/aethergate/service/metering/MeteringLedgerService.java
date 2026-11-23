package com.aethergate.service.metering;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.EntryType;
import com.aethergate.domain.model.LedgerEntry;
import com.aethergate.domain.model.Project;
import com.aethergate.domain.model.Tenant;
import com.aethergate.domain.repository.LedgerEntryRepository;
import com.aethergate.domain.repository.ProjectRepository;
import com.aethergate.domain.repository.TenantRepository;
import com.aethergate.service.project.ProjectArchivedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Two-phase metering ledger: pre-flight RESERVE, post-flight SETTLE + RELEASE.
 *
 * <p>Every mutation runs in one tenant-scoped transaction that first takes
 * {@code SELECT ... FOR UPDATE} on the tenant row, so concurrent requests of one tenant are
 * serialised exactly where it matters (the balance) and nowhere else. Every operation is
 * idempotent on {@code (tenant_id, request_id, entry_type)}: a retry replays the stored outcome.
 *
 * <p>Budgets are hierarchical: a hold must fit the tenant balance, the tenant's monthly hard limit
 * AND, when the caller's key belongs to a project, that project's monthly budget. Because all of
 * it is checked under the same tenant row lock, concurrent requests can never overshoot either level.
 */
@Service
public class MeteringLedgerService {

    private static final Logger log = LoggerFactory.getLogger(MeteringLedgerService.class);

    private final TenantRepository tenants;
    private final LedgerEntryRepository ledger;
    private final ProjectRepository projects;
    private final Clock clock;

    public MeteringLedgerService(TenantRepository tenants, LedgerEntryRepository ledger, ProjectRepository projects, Clock clock) {
        this.tenants = tenants;
        this.ledger = ledger;
        this.projects = projects;
        this.clock = clock;
    }

    @TenantScoped
    public Reservation reserveBudget(String requestId, BigDecimal estimatedMaxCost, String description) {
        return reserveBudget(requestId, estimatedMaxCost, description, null);
    }

    @TenantScoped
    public Reservation reserveBudget(String requestId, BigDecimal estimatedMaxCost, String description, UUID projectId) {
        BigDecimal amount = requirePositive(estimatedMaxCost);
        UUID tenantId = TenantContext.require();
        Tenant tenant = lock(tenantId);

        Optional<LedgerEntry> existing = ledger.findByTenantIdAndRequestIdAndEntryType(tenantId, requestId, EntryType.RESERVE);
        if (existing.isPresent()) {
            return new Reservation(requestId, existing.get().getAmountUsd(), tenant.getAvailableBalanceUsd(), true);
        }
        if (tenant.getAvailableBalanceUsd().compareTo(amount) < 0) {
            throw new InsufficientQuotaException(tenant.getAvailableBalanceUsd(), amount);
        }
        BigDecimal projected = ledger.sumSince(tenantId, EntryType.SETTLE, monthStart())
                .add(tenant.getReservedBalanceUsd())
                .add(amount);
        if (projected.compareTo(tenant.getHardBudgetLimitUsd()) > 0) {
            throw new BudgetLimitExceededException(tenant.getHardBudgetLimitUsd(), CostCalculator.normalize(projected));
        }
        if (projectId != null) {
            checkProjectBudget(tenantId, projectId, amount);
        }

        tenant.reserve(amount);
        ledger.save(new LedgerEntry(tenantId, requestId, EntryType.RESERVE, amount, tenant.getAvailableBalanceUsd(),
                description, projectId));
        return new Reservation(requestId, amount, tenant.getAvailableBalanceUsd(), false);
    }

    /**
     * Charges the actual cost out of the hold and returns the unused remainder ("dust").
     * If the actual cost ever exceeded the hold it is capped at the hold, and logged.
     */
    @TenantScoped
    public Settlement settleTransaction(String requestId, BigDecimal actualCost, String description) {
        UUID tenantId = TenantContext.require();
        Tenant tenant = lock(tenantId);
        List<LedgerEntry> history = ledger.findByTenantIdAndRequestIdOrderByCreatedAtAsc(tenantId, requestId);
        LedgerEntry reserve = findType(history, EntryType.RESERVE)
                .orElseThrow(() -> new IllegalStateException("No reservation for request " + requestId));
        if (isCleared(history)) {
            return replay(history, reserve, tenant);
        }

        BigDecimal reserved = reserve.getAmountUsd();
        BigDecimal settled = CostCalculator.normalize(actualCost.max(BigDecimal.ZERO));
        if (settled.compareTo(reserved) > 0) {
            log.warn("Actual cost {} exceeded hold {} for request {}; capping at hold", settled, reserved, requestId);
            settled = reserved;
        }
        BigDecimal released = reserved.subtract(settled);

        BigDecimal availableBeforeRelease = tenant.getAvailableBalanceUsd();
        tenant.clearReservation(reserved, released);
        UUID projectId = reserve.getProjectId(); // SETTLE/RELEASE are charged to the project that took the hold
        ledger.save(new LedgerEntry(tenantId, requestId, EntryType.SETTLE, settled, availableBeforeRelease, description, projectId));
        ledger.save(new LedgerEntry(tenantId, requestId, EntryType.RELEASE, released, tenant.getAvailableBalanceUsd(),
                "Unused hold returned", projectId));
        return new Settlement(requestId, reserved, settled, released, tenant.getAvailableBalanceUsd(), false);
    }

    /** Returns the whole hold, e.g. when every provider failed. No-op if already cleared. */
    @TenantScoped
    public Settlement releaseReservation(String requestId, String reason) {
        UUID tenantId = TenantContext.require();
        Tenant tenant = lock(tenantId);
        List<LedgerEntry> history = ledger.findByTenantIdAndRequestIdOrderByCreatedAtAsc(tenantId, requestId);
        LedgerEntry reserve = findType(history, EntryType.RESERVE)
                .orElseThrow(() -> new IllegalStateException("No reservation for request " + requestId));
        if (isCleared(history)) {
            return replay(history, reserve, tenant);
        }
        BigDecimal reserved = reserve.getAmountUsd();
        tenant.clearReservation(reserved, reserved);
        ledger.save(new LedgerEntry(tenantId, requestId, EntryType.RELEASE, reserved, tenant.getAvailableBalanceUsd(), reason,
                reserve.getProjectId()));
        return new Settlement(requestId, reserved, BigDecimal.ZERO.setScale(CostCalculator.SCALE), reserved,
                tenant.getAvailableBalanceUsd(), false);
    }

    @TenantScoped
    public BigDecimal topUp(String requestId, BigDecimal amount, String description) {
        BigDecimal credit = requirePositive(amount);
        UUID tenantId = TenantContext.require();
        Tenant tenant = lock(tenantId);
        if (ledger.findByTenantIdAndRequestIdAndEntryType(tenantId, requestId, EntryType.TOP_UP).isPresent()) {
            return tenant.getAvailableBalanceUsd();
        }
        tenant.credit(credit);
        ledger.save(new LedgerEntry(tenantId, requestId, EntryType.TOP_UP, credit, tenant.getAvailableBalanceUsd(), description));
        return tenant.getAvailableBalanceUsd();
    }

    /** Runs under the tenant row lock taken by the caller, so concurrent holds are serialised here too. */
    private void checkProjectBudget(UUID tenantId, UUID projectId, BigDecimal amount) {
        Project project = projects.findById(projectId).orElseThrow(() -> new NoSuchElementException("Project not found"));
        if (!project.isActive()) {
            throw new ProjectArchivedException(project.getName());
        }
        BigDecimal budget = project.getMonthlyBudgetUsd();
        if (budget == null) {
            return;
        }
        BigDecimal projected = ledger.sumProjectSince(tenantId, projectId, EntryType.SETTLE, monthStart())
                .add(ledger.openProjectHolds(tenantId, projectId))
                .add(amount);
        if (projected.compareTo(budget) > 0) {
            throw new ProjectBudgetExceededException(project.getName(), budget, CostCalculator.normalize(projected));
        }
    }

    private Tenant lock(UUID tenantId) {
        return tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not visible in current context: " + tenantId));
    }

    private Instant monthStart() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return CostCalculator.normalize(amount);
    }

    private static boolean isCleared(List<LedgerEntry> history) {
        return findType(history, EntryType.SETTLE).isPresent() || findType(history, EntryType.RELEASE).isPresent();
    }

    private static Settlement replay(List<LedgerEntry> history, LedgerEntry reserve, Tenant tenant) {
        BigDecimal settled = findType(history, EntryType.SETTLE).map(LedgerEntry::getAmountUsd)
                .orElse(BigDecimal.ZERO.setScale(CostCalculator.SCALE));
        BigDecimal released = findType(history, EntryType.RELEASE).map(LedgerEntry::getAmountUsd)
                .orElse(BigDecimal.ZERO.setScale(CostCalculator.SCALE));
        return new Settlement(reserve.getRequestId(), reserve.getAmountUsd(), settled, released,
                tenant.getAvailableBalanceUsd(), true);
    }

    private static Optional<LedgerEntry> findType(List<LedgerEntry> history, EntryType type) {
        return history.stream().filter(e -> e.getEntryType() == type).findFirst();
    }
}
