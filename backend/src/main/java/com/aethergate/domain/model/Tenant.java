package com.aethergate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80, unique = true)
    private String slug;

    @Column(name = "plan_tier", nullable = false, length = 32)
    private String planTier;

    @Column(name = "available_balance_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal availableBalanceUsd;

    @Column(name = "reserved_balance_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal reservedBalanceUsd;

    @Column(name = "hard_budget_limit_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal hardBudgetLimitUsd;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "end_user_daily_cap_usd", precision = 19, scale = 8)
    private BigDecimal endUserDailyCapUsd;

    @Column(name = "session_cap_usd", precision = 19, scale = 8)
    private BigDecimal sessionCapUsd;

    @Column(name = "session_max_requests")
    private Integer sessionMaxRequests;

    @Column(name = "downgrade_at_pct")
    private Integer downgradeAtPct;

    @Column(name = "downgrade_model", length = 80)
    private String downgradeModel;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {}

    public Tenant(UUID id, UUID ownerUserId, String name, String slug, BigDecimal initialBalance, BigDecimal hardBudgetLimit) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.slug = slug;
        this.planTier = "PRO";
        this.availableBalanceUsd = initialBalance;
        this.reservedBalanceUsd = BigDecimal.ZERO;
        this.hardBudgetLimitUsd = hardBudgetLimit;
        this.createdAt = Instant.now();
    }

    /** Moves funds from available to reserved. Caller holds the row lock. */
    public void reserve(BigDecimal amount) {
        availableBalanceUsd = availableBalanceUsd.subtract(amount);
        reservedBalanceUsd = reservedBalanceUsd.add(amount);
    }

    /** Clears a reservation: the reserved amount leaves "reserved", the unused part returns to "available". */
    public void clearReservation(BigDecimal reserved, BigDecimal refund) {
        reservedBalanceUsd = reservedBalanceUsd.subtract(reserved);
        availableBalanceUsd = availableBalanceUsd.add(refund);
    }

    public void credit(BigDecimal amount) {
        availableBalanceUsd = availableBalanceUsd.add(amount);
    }

    /** Workspace settings. The caller holds the row lock, so this never races a reservation. */
    public void updateSettings(String newName, BigDecimal newHardBudgetLimit) {
        this.name = newName;
        this.hardBudgetLimitUsd = newHardBudgetLimit;
    }

    /** Spend caps and the soft-limit downgrade. Null = off. The caller holds the row lock. */
    public void updateCostControls(BigDecimal userDailyCap, BigDecimal sessionCap, Integer sessionMaxSteps,
                                   Integer downgradePct, String downgradeTo) {
        this.endUserDailyCapUsd = userDailyCap;
        this.sessionCapUsd = sessionCap;
        this.sessionMaxRequests = sessionMaxSteps;
        this.downgradeAtPct = downgradePct;
        this.downgradeModel = downgradeTo;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getPlanTier() { return planTier; }
    public BigDecimal getAvailableBalanceUsd() { return availableBalanceUsd; }
    public BigDecimal getReservedBalanceUsd() { return reservedBalanceUsd; }
    public BigDecimal getHardBudgetLimitUsd() { return hardBudgetLimitUsd; }
    public boolean isDemo() { return demo; }
    public Instant getCreatedAt() { return createdAt; }
    public BigDecimal getEndUserDailyCapUsd() { return endUserDailyCapUsd; }
    public BigDecimal getSessionCapUsd() { return sessionCapUsd; }
    public Integer getSessionMaxRequests() { return sessionMaxRequests; }
    public Integer getDowngradeAtPct() { return downgradeAtPct; }
    public String getDowngradeModel() { return downgradeModel; }
}
