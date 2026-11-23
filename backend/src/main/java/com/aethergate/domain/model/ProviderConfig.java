package com.aethergate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "provider_configs")
public class ProviderConfig extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProviderType provider;

    @Column(name = "model_name", nullable = false, length = 80)
    private String modelName;

    @Column(name = "priority_order", nullable = false)
    private int priorityOrder;

    @Column(name = "input_cost_per_1m", nullable = false, precision = 19, scale = 8)
    private BigDecimal inputCostPer1m;

    @Column(name = "output_cost_per_1m", nullable = false, precision = 19, scale = 8)
    private BigDecimal outputCostPer1m;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    protected ProviderConfig() {}

    public ProviderConfig(UUID tenantId, ProviderType provider, String modelName, int priorityOrder,
                          BigDecimal inputCostPer1m, BigDecimal outputCostPer1m) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.provider = provider;
        this.modelName = modelName;
        this.priorityOrder = priorityOrder;
        this.inputCostPer1m = inputCostPer1m;
        this.outputCostPer1m = outputCostPer1m;
        this.enabled = true;
    }

    public void setPriorityOrder(int priorityOrder) { this.priorityOrder = priorityOrder; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public ProviderType getProvider() { return provider; }
    public String getModelName() { return modelName; }
    public int getPriorityOrder() { return priorityOrder; }
    public BigDecimal getInputCostPer1m() { return inputCostPer1m; }
    public BigDecimal getOutputCostPer1m() { return outputCostPer1m; }
    public boolean isEnabled() { return enabled; }
}
