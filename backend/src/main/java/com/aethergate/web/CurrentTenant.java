package com.aethergate.web;

import com.aethergate.service.tenant.TenantDirectoryService;
import com.aethergate.service.tenant.TenantRef;
import com.aethergate.service.tenant.WorkspaceAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Maps a verified Supabase JWT ({@code sub}, {@code email}) to the workspace the caller acts in and their role
 * there. The console picks a workspace with {@code X-Workspace-Id}; membership is re-checked on every request
 * (cached for 30 s, evicted on any membership change), so a removed member loses access immediately.
 */
@Component
public class CurrentTenant {

    public static final String WORKSPACE_HEADER = "X-Workspace-Id";
    static final String ATTRIBUTE = CurrentTenant.class.getName() + ".access";

    private final TenantDirectoryService directory;

    public CurrentTenant(TenantDirectoryService directory) {
        this.directory = directory;
    }

    public TenantRef of(Jwt jwt) {
        return access(jwt).tenant();
    }

    public WorkspaceAccess access(Jwt jwt) {
        HttpServletRequest request = currentRequest();
        if (request != null && request.getAttribute(ATTRIBUTE) instanceof WorkspaceAccess cached) {
            return cached;
        }
        WorkspaceAccess access = directory.access(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"),
                requestedWorkspace(request));
        if (request != null) {
            request.setAttribute(ATTRIBUTE, access);
        }
        return access;
    }

    public List<WorkspaceAccess> workspaces(Jwt jwt) {
        return directory.workspaces(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"));
    }

    private static UUID requestedWorkspace(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader(WORKSPACE_HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(header.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(WORKSPACE_HEADER + " must be a workspace id");
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs.getRequest() : null;
    }
}
