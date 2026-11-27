package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.service.finops.AnalyticsService;
import com.aethergate.service.finops.AnomalyService;
import com.aethergate.service.finops.CostControlService;
import com.aethergate.service.finops.CostControlService.CostControls;
import com.aethergate.service.finops.FinOpsViews.Anomalies;
import com.aethergate.service.finops.FinOpsViews.Attribution;
import com.aethergate.service.finops.FinOpsViews.EndUsers;
import com.aethergate.service.finops.FinOpsViews.Forecast;
import com.aethergate.service.finops.FinOpsViews.Recommendations;
import com.aethergate.service.finops.FinOpsViews.RequestPage;
import com.aethergate.service.finops.FinOpsViews.SessionDetail;
import com.aethergate.service.finops.FinOpsViews.Sessions;
import com.aethergate.service.finops.FinOpsViews.Window;
import com.aethergate.service.finops.FocusExporter;
import com.aethergate.service.finops.ForecastService;
import com.aethergate.service.finops.SavingsAdvisor;
import com.aethergate.web.dto.CostControlsRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FinOps console API: cost allocation (showback), end users, agent sessions, request explorer, forecast, anomalies,
 * savings recommendations, FOCUS export, and the workspace cost controls. Reads need VIEWER, the PUT needs ADMIN.
 */
@RestController
@RequestMapping("/api/v1")
public class FinOpsController {

    private static final List<String> FILTERS = List.of("model", "provider", "project", "api_key", "end_user", "session", "tag",
            "cache", "status", "routing", "q");

    private final CurrentTenant currentTenant;
    private final AnalyticsService analytics;
    private final ForecastService forecasts;
    private final AnomalyService anomalies;
    private final SavingsAdvisor advisor;
    private final FocusExporter focus;
    private final CostControlService controls;

    @SuppressWarnings("java:S107")
    public FinOpsController(CurrentTenant currentTenant, AnalyticsService analytics, ForecastService forecasts,
                            AnomalyService anomalies, SavingsAdvisor advisor, FocusExporter focus, CostControlService controls) {
        this.currentTenant = currentTenant;
        this.analytics = analytics;
        this.forecasts = forecasts;
        this.anomalies = anomalies;
        this.advisor = advisor;
        this.focus = focus;
        this.controls = controls;
    }

    private UUID tenant(Jwt jwt) {
        return currentTenant.of(jwt).id();
    }

    @GetMapping("/analytics/attribution")
    public Attribution attribution(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "project") String dimension,
                                   @RequestParam(defaultValue = "30d") String range) {
        Window w = Window.parse(range);
        return TenantContext.callAs(tenant(jwt), () -> analytics.attribution(dimension, w));
    }

    @GetMapping("/analytics/end-users")
    public EndUsers endUsers(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "30d") String range) {
        Window w = Window.parse(range);
        return TenantContext.callAs(tenant(jwt), () -> analytics.endUsers(w));
    }

    @GetMapping("/analytics/sessions")
    public Sessions sessions(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "7d") String range,
                             @RequestParam(required = false) String q) {
        Window w = Window.parse(range);
        return TenantContext.callAs(tenant(jwt), () -> analytics.sessions(w, q));
    }

    @GetMapping("/analytics/sessions/{sessionId}")
    public SessionDetail session(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        return TenantContext.callAs(tenant(jwt), () -> analytics.session(sessionId, controls));
    }

    @GetMapping("/analytics/requests")
    public RequestPage requests(@AuthenticationPrincipal Jwt jwt, @RequestParam Map<String, String> params) {
        Window w = Window.parse(params.get("range"));
        int page = intParam(params.get("page"), 0);
        int size = intParam(params.get("size"), 25);
        Map<String, String> filters = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (FILTERS.contains(k)) {
                filters.put(k, v);
            } else if (!List.of("range", "page", "size").contains(k)) {
                throw new IllegalArgumentException("Unknown filter '" + k + "'; use " + FILTERS);
            }
        });
        return TenantContext.callAs(tenant(jwt), () -> analytics.requests(w, filters, page, size));
    }

    @GetMapping("/analytics/forecast")
    public Forecast forecast(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(tenant(jwt), forecasts::forecast);
    }

    @GetMapping("/analytics/anomalies")
    public Anomalies anomalies(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(tenant(jwt), anomalies::view);
    }

    @GetMapping("/analytics/recommendations")
    public Recommendations recommendations(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(tenant(jwt), advisor::recommendations);
    }

    /** FOCUS 1.2 CSV for one calendar month (UTC), default the current one. */
    @GetMapping("/analytics/export/focus")
    public ResponseEntity<byte[]> focusExport(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month) {
        FocusExporter.Export export = TenantContext.callAs(tenant(jwt), () -> focus.export(month));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.filename()).build().toString())
                .header("X-AetherGate-Rows", String.valueOf(export.rows()))
                .header("X-AetherGate-Billed-Cost", export.billedCostUsd().toPlainString())
                .body(export.csv().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/tenant/cost-controls")
    public Map<String, Object> costControls(@AuthenticationPrincipal Jwt jwt) {
        UUID t = tenant(jwt);
        return Map.of("controls", TenantContext.callAs(t, controls::get),
                "events", TenantContext.callAs(t, () -> controls.recentEvents(30)));
    }

    @PutMapping("/tenant/cost-controls")
    public CostControls updateCostControls(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CostControlsRequest body) {
        return TenantContext.callAs(tenant(jwt), () -> controls.update(body.endUserDailyCapUsd(), body.sessionCapUsd(),
                body.sessionMaxRequests(), body.downgradeAtPct(), body.downgradeModel()));
    }

    private static int intParam(String v, int fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("page and size must be integers");
        }
    }
}
