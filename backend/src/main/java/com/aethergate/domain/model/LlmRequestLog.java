package com.aethergate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "llm_request_logs")
public class LlmRequestLog extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "request_id", nullable = false, length = 64, unique = true)
    private String requestId;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "provider_used", nullable = false, length = 32)
    private String providerUsed;

    @Column(name = "model_used", nullable = false, length = 80)
    private String modelUsed;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_cost_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal totalCostUsd;

    @Column(name = "saved_cost_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal savedCostUsd;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "ttft_ms", nullable = false)
    private int ttftMs;

    @Column(name = "cache_status", nullable = false, length = 16)
    private String cacheStatus;

    @Column(name = "failover_triggered", nullable = false)
    private boolean failoverTriggered;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "template_version")
    private Integer templateVersion;

    protected LlmRequestLog() {}

    @SuppressWarnings("java:S107") // a log row is inherently wide
    public LlmRequestLog(UUID tenantId, String requestId, UUID apiKeyId, UUID projectId, String providerUsed, String modelUsed,
                         int promptTokens, int completionTokens, BigDecimal totalCostUsd, BigDecimal savedCostUsd,
                         int latencyMs, int ttftMs, String cacheStatus, boolean failoverTriggered, int statusCode) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.requestId = requestId;
        this.apiKeyId = apiKeyId;
        this.projectId = projectId;
        this.providerUsed = providerUsed;
        this.modelUsed = modelUsed;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalCostUsd = totalCostUsd;
        this.savedCostUsd = savedCostUsd;
        this.latencyMs = latencyMs;
        this.ttftMs = ttftMs;
        this.cacheStatus = cacheStatus;
        this.failoverTriggered = failoverTriggered;
        this.statusCode = statusCode;
        this.createdAt = Instant.now();
    }

    /** Records which prompt template version produced the request (null for free-form prompts). */
    public LlmRequestLog withTemplate(UUID id, Integer version) {
        this.templateId = id;
        this.templateVersion = version;
        return this;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getRequestId() { return requestId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getProjectId() { return projectId; }
    public String getProviderUsed() { return providerUsed; }
    public String getModelUsed() { return modelUsed; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public BigDecimal getSavedCostUsd() { return savedCostUsd; }
    public int getLatencyMs() { return latencyMs; }
    public int getTtftMs() { return ttftMs; }
    public String getCacheStatus() { return cacheStatus; }
    public boolean isFailoverTriggered() { return failoverTriggered; }
    public int getStatusCode() { return statusCode; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getTemplateId() { return templateId; }
    public Integer getTemplateVersion() { return templateVersion; }
}
