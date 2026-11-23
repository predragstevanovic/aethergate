package com.aethergate.service.tenant;

import java.util.UUID;

public record TenantRef(UUID id, String name, String slug, String planTier, boolean demo) {}
