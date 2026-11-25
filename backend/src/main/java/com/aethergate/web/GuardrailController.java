package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.service.guardrail.GuardrailService;
import com.aethergate.service.guardrail.GuardrailViews.GuardrailDraft;
import com.aethergate.service.guardrail.GuardrailViews.GuardrailList;
import com.aethergate.service.guardrail.GuardrailViews.GuardrailView;
import com.aethergate.service.guardrail.GuardrailViews.TestResult;
import com.aethergate.web.dto.EnabledRequest;
import com.aethergate.web.dto.GuardrailRequest;
import com.aethergate.web.dto.GuardrailTestRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Guardrail rules of the signed-in tenant.
 *
 * <pre>
 * GET    /api/v1/tenant/guardrails          rules + 30-day hits + recent events
 * POST   /api/v1/tenant/guardrails          create             → 201, Location, ETag
 * PUT    /api/v1/tenant/guardrails/{id}     redefine (If-Match required: 428 / 412)
 * PATCH  /api/v1/tenant/guardrails/{id}     {"enabled": bool}
 * DELETE /api/v1/tenant/guardrails/{id}     → 204 (events keep the rule name)
 * POST   /api/v1/tenant/guardrails/test     dry run of the enabled rules on a sample text
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/tenant/guardrails")
public class GuardrailController {

    private final CurrentTenant currentTenant;
    private final GuardrailService guardrails;

    public GuardrailController(CurrentTenant currentTenant, GuardrailService guardrails) {
        this.currentTenant = currentTenant;
        this.guardrails = guardrails;
    }

    @GetMapping
    public GuardrailList list(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(tenant(jwt), guardrails::list);
    }

    @PostMapping
    public ResponseEntity<GuardrailView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GuardrailRequest body) {
        GuardrailView created = TenantContext.callAs(tenant(jwt), () -> guardrails.create(draft(body)));
        return ResponseEntity.created(URI.create("/api/v1/tenant/guardrails/" + created.id()))
                .eTag(IfMatch.etag(created.version())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<GuardrailView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @Valid @RequestBody GuardrailRequest body) {
        long expected = IfMatch.parse(ifMatch);
        GuardrailView updated = TenantContext.callAs(tenant(jwt), () -> guardrails.update(id, expected, draft(body)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @PatchMapping("/{id}")
    public GuardrailView toggle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody EnabledRequest body) {
        return TenantContext.callAs(tenant(jwt), () -> guardrails.setEnabled(id, body.enabled()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext.runAs(tenant(jwt), () -> guardrails.delete(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @RequiresRole(com.aethergate.domain.model.MemberRole.DEVELOPER)
    public TestResult test(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GuardrailTestRequest body) {
        return TenantContext.callAs(tenant(jwt), () -> guardrails.test(body.text()));
    }

    private UUID tenant(Jwt jwt) {
        return currentTenant.of(jwt).id();
    }

    private static GuardrailDraft draft(GuardrailRequest b) {
        return new GuardrailDraft(b.name(), b.type(), b.pattern(), b.action());
    }
}
