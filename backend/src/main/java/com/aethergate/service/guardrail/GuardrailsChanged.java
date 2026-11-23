package com.aethergate.service.guardrail;

import java.util.UUID;

/** Published by CRUD; the enforcer drops its cached rules for the tenant once the transaction commits. */
public record GuardrailsChanged(UUID tenantId) {}
