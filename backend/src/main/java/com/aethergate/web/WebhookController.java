package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.MemberRole;
import com.aethergate.service.webhook.AlertService;
import com.aethergate.service.webhook.WebhookService;
import com.aethergate.service.webhook.WebhookViews.AlertList;
import com.aethergate.service.webhook.WebhookViews.DeliveryView;
import com.aethergate.service.webhook.WebhookViews.EndpointDraft;
import com.aethergate.service.webhook.WebhookViews.EndpointList;
import com.aethergate.service.webhook.WebhookViews.EndpointView;
import com.aethergate.service.webhook.WebhookViews.EndpointWithSecret;
import com.aethergate.web.dto.AlertRequest;
import com.aethergate.web.dto.EnabledRequest;
import com.aethergate.web.dto.WebhookRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signed webhooks and budget alerts (Admin).
 *
 * <pre>
 * GET/POST           /api/v1/tenant/webhooks                    list / create (secret shown once)
 * PUT                /api/v1/tenant/webhooks/{id}               edit (If-Match)
 * PATCH / DELETE     /api/v1/tenant/webhooks/{id}               enable-disable / delete
 * POST               /api/v1/tenant/webhooks/{id}/rotate-secret new secret, shown once
 * POST               /api/v1/tenant/webhooks/{id}/test          enqueue a webhook.test event
 * GET                /api/v1/tenant/webhooks/{id}/deliveries    last 50 attempts
 * POST               /api/v1/tenant/webhooks/deliveries/{id}/redeliver
 * GET/POST           /api/v1/tenant/alerts                      budget alert rules (GET: any member)
 * PATCH / DELETE     /api/v1/tenant/alerts/{id}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/tenant")
@RequiresRole(MemberRole.ADMIN)
public class WebhookController {

    private final CurrentTenant currentTenant;
    private final WebhookService webhooks;
    private final AlertService alerts;

    public WebhookController(CurrentTenant currentTenant, WebhookService webhooks, AlertService alerts) {
        this.currentTenant = currentTenant;
        this.webhooks = webhooks;
        this.alerts = alerts;
    }

    @GetMapping("/webhooks")
    public EndpointList list(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(tenant(jwt), webhooks::list);
    }

    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    public EndpointWithSecret create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WebhookRequest body) {
        return TenantContext.callAs(tenant(jwt), () -> webhooks.create(draft(body)));
    }

    @PutMapping("/webhooks/{id}")
    public ResponseEntity<EndpointView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody WebhookRequest body) {
        long expected = IfMatch.parse(ifMatch);
        EndpointView v = TenantContext.callAs(tenant(jwt), () -> webhooks.update(id, expected, draft(body)));
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(v);
    }

    @PatchMapping("/webhooks/{id}")
    public EndpointView toggle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody EnabledRequest body) {
        return TenantContext.callAs(tenant(jwt), () -> webhooks.setEnabled(id, body.enabled()));
    }

    @DeleteMapping("/webhooks/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext.runAs(tenant(jwt), () -> webhooks.delete(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/webhooks/{id}/rotate-secret")
    public EndpointWithSecret rotateSecret(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TenantContext.callAs(tenant(jwt), () -> webhooks.rotateSecret(id));
    }

    @PostMapping("/webhooks/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeliveryView test(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TenantContext.callAs(tenant(jwt), () -> webhooks.sendTest(id));
    }

    @GetMapping("/webhooks/{id}/deliveries")
    public List<DeliveryView> deliveries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TenantContext.callAs(tenant(jwt), () -> webhooks.deliveries(id));
    }

    @PostMapping("/webhooks/deliveries/{id}/redeliver")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeliveryView redeliver(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TenantContext.callAs(tenant(jwt), () -> webhooks.redeliver(id));
    }

    @GetMapping("/alerts")
    @RequiresRole(MemberRole.VIEWER)
    public AlertList alerts(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(tenant(jwt), alerts::list);
    }

    @PostMapping("/alerts")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertList createAlert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AlertRequest body) {
        return TenantContext.callAs(tenant(jwt), () -> alerts.create(body.projectId(), body.thresholdPct()));
    }

    @PatchMapping("/alerts/{id}")
    public AlertList toggleAlert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody EnabledRequest body) {
        return TenantContext.callAs(tenant(jwt), () -> alerts.setEnabled(id, body.enabled()));
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Void> deleteAlert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext.runAs(tenant(jwt), () -> alerts.delete(id));
        return ResponseEntity.noContent().build();
    }

    private UUID tenant(Jwt jwt) {
        return currentTenant.of(jwt).id();
    }

    private static EndpointDraft draft(WebhookRequest b) {
        return new EndpointDraft(b.name(), b.url(), b.eventTypes());
    }
}
