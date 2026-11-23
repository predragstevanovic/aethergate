package com.aethergate.web;

import com.aethergate.service.tenant.TenantDirectoryService;
import com.aethergate.service.tenant.TenantRef;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Maps a verified Supabase JWT ({@code sub}, {@code email}) to the caller's workspace, provisioning on first login. */
@Component
public class CurrentTenant {

    private final TenantDirectoryService directory;

    public CurrentTenant(TenantDirectoryService directory) {
        this.directory = directory;
    }

    public TenantRef of(Jwt jwt) {
        return directory.resolveOrProvision(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"));
    }
}
