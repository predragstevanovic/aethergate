package com.aethergate.web;

import com.aethergate.service.tenant.DashboardService;
import com.aethergate.service.tenant.DashboardViews.LedgerPage;
import com.aethergate.service.tenant.DashboardViews.Overview;
import com.aethergate.service.tenant.WorkspaceAccess;
import com.aethergate.service.tenant.WorkspaceAccessDeniedException;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Console read API (Supabase JWT). */
@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private final CurrentTenant currentTenant;
    private final DashboardService dashboard;

    public DashboardController(CurrentTenant currentTenant, DashboardService dashboard) {
        this.currentTenant = currentTenant;
        this.dashboard = dashboard;
    }

    /**
     * Who am I, which workspace am I in, with which role, and which other workspaces can I switch to. Workspace-agnostic:
     * a stale X-Workspace-Id (e.g. the user was removed) falls back to the default workspace instead of failing.
     */
    @GetMapping("/me")
    @WorkspaceAgnostic
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        WorkspaceAccess access;
        boolean fallback = false;
        try {
            access = currentTenant.access(jwt);
        } catch (WorkspaceAccessDeniedException e) {
            access = currentTenant.workspaces(jwt).getFirst();
            fallback = true;
        }
        String email = jwt.getClaimAsString("email");
        return Map.of("user_id", jwt.getSubject(), "email", email == null ? "" : email, "tenant", access.tenant(),
                "role", access.role().name(), "workspace_fallback", fallback,
                "workspaces", currentTenant.workspaces(jwt).stream().map(TeamController::summary).toList());
    }

    @GetMapping("/dashboard/overview")
    public Overview overview(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "24h") String range) {
        return dashboard.overview(currentTenant.of(jwt), DashboardService.Range.parse(range));
    }

    @GetMapping("/dashboard/ledger")
    public LedgerPage ledger(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(required = false) String type,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return dashboard.ledger(currentTenant.of(jwt), type, page, size);
    }
}
