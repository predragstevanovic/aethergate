package com.aethergate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 64, unique = true)
    private String keyHash;

    @Column(name = "rate_limit_rpm", nullable = false)
    private int rateLimitRpm;

    @Column(name = "rate_limit_tpm", nullable = false)
    private int rateLimitTpm;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {}

    public ApiKey(UUID tenantId, String name, String keyPrefix, String keyHash, int rpm, int tpm) {
        this(tenantId, name, keyPrefix, keyHash, rpm, tpm, null);
    }

    public ApiKey(UUID tenantId, String name, String keyPrefix, String keyHash, int rpm, int tpm, UUID projectId) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.rateLimitRpm = rpm;
        this.rateLimitTpm = tpm;
        this.projectId = projectId;
        this.active = true;
        this.createdAt = Instant.now();
    }

    /** Moves the key to another project ({@code null} = unassigned); future requests are charged there. */
    public void assignProject(UUID projectId) {
        this.projectId = projectId;
    }

    public void revoke() {
        this.active = false;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public int getRateLimitRpm() { return rateLimitRpm; }
    public int getRateLimitTpm() { return rateLimitTpm; }
    public boolean isActive() { return active; }
    public UUID getProjectId() { return projectId; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
