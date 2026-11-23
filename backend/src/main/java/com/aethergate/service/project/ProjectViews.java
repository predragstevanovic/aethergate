package com.aethergate.service.project;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read models of the Projects API. Money is BigDecimal (serialised as a decimal string). */
public final class ProjectViews {

    private ProjectViews() {}

    /**
     * One project with its live budget position. {@code committedUsd} = month-to-date settled spend +
     * holds still in flight; this is exactly the number the pre-flight check compares to the budget.
     */
    public record ProjectView(UUID id, String name, String description, String status, BigDecimal monthlyBudgetUsd,
                              BigDecimal monthToDateUsd, BigDecimal openHoldsUsd, BigDecimal committedUsd,
                              BigDecimal remainingUsd, Double budgetUtilization, long requests30d, long cacheHits30d,
                              BigDecimal cost30dUsd, long activeKeys, long version, Instant createdAt, Instant updatedAt,
                              Instant archivedAt) {}

    /** Spend of keys that belong to no project (and of the console playground when no project is picked). */
    public record UnassignedUsage(BigDecimal monthToDateUsd, long requests30d, BigDecimal cost30dUsd) {}

    public record ProjectList(List<ProjectView> projects, UnassignedUsage unassigned, int maxActiveProjects) {}

    public record DailyUsage(LocalDate day, long requests, long cacheHits, BigDecimal costUsd) {}

    public record KeyRef(UUID id, String name, String keyPrefix, boolean active) {}

    public record ProjectDetail(ProjectView project, List<DailyUsage> daily, List<KeyRef> keys) {}

    public record ArchiveResult(ProjectView project, int revokedKeys) {}

    /** Full replacement of the editable fields (PUT). {@code monthlyBudgetUsd = null} removes the cap. */
    public record ProjectDraft(String name, String description, BigDecimal monthlyBudgetUsd) {}
}
