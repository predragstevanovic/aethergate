package com.aethergate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A cost centre inside a tenant: API keys belong to it, every metering line is attributed to it,
 * and its optional monthly budget is enforced at pre-flight time alongside the tenant budget.
 *
 * <p>Lifecycle: ACTIVE ⇄ ARCHIVED. A project with billing history is never deleted (the ledger is
 * append-only and references it), only archived; {@code version} gives optimistic concurrency
 * for edits from several browser tabs (HTTP {@code If-Match}).
 */
@Entity
@Table(name = "projects")
public class Project extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 280)
    private String description;

    @Column(name = "monthly_budget_usd", precision = 19, scale = 8)
    private BigDecimal monthlyBudgetUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProjectStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Project() {}

    public Project(UUID tenantId, String name, String description, BigDecimal monthlyBudgetUsd) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.monthlyBudgetUsd = monthlyBudgetUsd;
        this.status = ProjectStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void rename(String newName) {
        this.name = newName;
        touch();
    }

    public void describe(String newDescription) {
        this.description = newDescription;
        touch();
    }

    /** {@code null} removes the cap (the project is then bounded only by the tenant budget). */
    public void setMonthlyBudget(BigDecimal budget) {
        this.monthlyBudgetUsd = budget;
        touch();
    }

    public void archive() {
        if (status == ProjectStatus.ARCHIVED) {
            return;
        }
        status = ProjectStatus.ARCHIVED;
        archivedAt = Instant.now();
        touch();
    }

    public void restore() {
        if (status == ProjectStatus.ACTIVE) {
            return;
        }
        status = ProjectStatus.ACTIVE;
        archivedAt = null;
        touch();
    }

    public boolean isActive() {
        return status == ProjectStatus.ACTIVE;
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getMonthlyBudgetUsd() { return monthlyBudgetUsd; }
    public ProjectStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
}
