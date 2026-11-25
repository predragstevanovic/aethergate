package com.aethergate.service.tenant;

import com.aethergate.domain.model.MemberRole;
import java.util.UUID;

/** The signed-in user acting in one workspace with one role. {@code primaryOwner}: the user who created it. */
public record WorkspaceAccess(TenantRef tenant, MemberRole role, boolean primaryOwner) {

    public UUID tenantId() {
        return tenant.id();
    }
}
