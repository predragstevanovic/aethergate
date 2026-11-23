package com.aethergate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A tenant-defined prompt policy enforced by the gateway before caching and routing. */
@Entity
@Table(name = "guardrail_rules")
public class GuardrailRule extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private GuardrailType type;

    @Column(length = 500)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GuardrailAction action;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GuardrailRule() {}

    public GuardrailRule(UUID tenantId, String name, GuardrailType type, String pattern, GuardrailAction action) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.type = type;
        this.pattern = pattern;
        this.action = action;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void redefine(String newName, GuardrailType newType, String newPattern, GuardrailAction newAction) {
        this.name = newName;
        this.type = newType;
        this.pattern = newPattern;
        this.action = newAction;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    @Override
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public GuardrailType getType() { return type; }
    public String getPattern() { return pattern; }
    public GuardrailAction getAction() { return action; }
    public boolean isEnabled() { return enabled; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
