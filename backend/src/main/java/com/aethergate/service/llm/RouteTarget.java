package com.aethergate.service.llm;

import com.aethergate.domain.model.ProviderType;
import java.math.BigDecimal;

/** One hop of a tenant's fallback chain, priced in USD per 1M tokens. */
public record RouteTarget(ProviderType provider, String model, int priority, BigDecimal inputCostPer1m, BigDecimal outputCostPer1m) {}
