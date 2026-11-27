package com.aethergate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only metering line. Immutable in JPA and guarded by a database trigger. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "request_id", nullable = false, length = 64, updatable = false)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private EntryType entryType;

    @Column(name = "amount_usd", nullable = false, precision = 19, scale = 8, updatable = false)
    private BigDecimal amountUsd;

    @Column(name = "balance_after_usd", nullable = false, precision = 19, scale = 8, updatable = false)
    private BigDecimal balanceAfterUsd;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(length = 255, updatable = false)
    private String description;

    @Column(name = "api_key_id", updatable = false)
    private UUID apiKeyId;

    @Column(name = "end_user_id", length = 128, updatable = false)
    private String endUserId;

    @Column(name = "session_id", length = 128, updatable = false)
    private String sessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {}

    public LedgerEntry(UUID tenantId, String requestId, EntryType entryType, BigDecimal amountUsd,
                       BigDecimal balanceAfterUsd, String description) {
        this(tenantId, requestId, entryType, amountUsd, balanceAfterUsd, description, null);
    }

    /** {@code projectId}: the cost centre charged; RESERVE, SETTLE and RELEASE of one request share it. */
    public LedgerEntry(UUID tenantId, String requestId, EntryType entryType, BigDecimal amountUsd,
                       BigDecimal balanceAfterUsd, String description, UUID projectId) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.requestId = requestId;
        this.entryType = entryType;
        this.amountUsd = amountUsd;
        this.balanceAfterUsd = balanceAfterUsd;
        this.description = description;
        this.projectId = projectId;
        this.createdAt = Instant.now();
    }

    /** The same line, also charged to an API key, end user and agent session (any may be null). */
    public LedgerEntry attributedTo(UUID key, String endUser, String session) {
        this.apiKeyId = key;
        this.endUserId = endUser;
        this.sessionId = session;
        return this;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getRequestId() { return requestId; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmountUsd() { return amountUsd; }
    public BigDecimal getBalanceAfterUsd() { return balanceAfterUsd; }
    public String getDescription() { return description; }
    public UUID getProjectId() { return projectId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public String getEndUserId() { return endUserId; }
    public String getSessionId() { return sessionId; }
    public Instant getCreatedAt() { return createdAt; }
}
