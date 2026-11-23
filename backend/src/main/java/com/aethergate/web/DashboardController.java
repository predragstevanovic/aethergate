package com.aethergate.web;

import com.aethergate.service.tenant.DashboardService;
import com.aethergate.service.tenant.DashboardViews.LedgerPage;
import com.aethergate.service.tenant.DashboardViews.Overview;
import com.aethergate.service.tenant.TenantRef;
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

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        TenantRef tenant = currentTenant.of(jwt);
        String email = jwt.getClaimAsString("email");
        return Map.of("user_id", jwt.getSubject(), "email", email == null ? "" : email, "tenant", tenant);
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
