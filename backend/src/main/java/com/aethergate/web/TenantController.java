package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.service.tenant.AdminViews.ApiKeyView;
import com.aethergate.service.tenant.AdminViews.CreatedApiKey;
import com.aethergate.service.tenant.AdminViews.ProviderView;
import com.aethergate.service.tenant.AdminViews.TopUpResult;
import com.aethergate.service.tenant.TenantAdminService;
import com.aethergate.service.tenant.TenantRef;
import com.aethergate.web.dto.AssignKeyProjectRequest;
import com.aethergate.web.dto.CreateApiKeyRequest;
import com.aethergate.web.dto.ReorderProvidersRequest;
import com.aethergate.web.dto.ToggleProviderRequest;
import com.aethergate.web.dto.TopUpRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant self-service: API keys, LLM routing priority, credit. */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {

    private final CurrentTenant currentTenant;
    private final TenantAdminService admin;

    public TenantController(CurrentTenant currentTenant, TenantAdminService admin) {
        this.currentTenant = currentTenant;
        this.admin = admin;
    }

    @GetMapping("/api-keys")
    public List<ApiKeyView> keys(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(currentTenant.of(jwt).id(), admin::listKeys);
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKey createKey(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateApiKeyRequest body) {
        TenantRef tenant = currentTenant.of(jwt);
        return TenantContext.callAs(tenant.id(), () -> admin.createKey(body.name(),
                body.rateLimitRpm() == null ? 60 : body.rateLimitRpm(),
                body.rateLimitTpm() == null ? 100_000 : body.rateLimitTpm(), body.projectId()));
    }

    @PatchMapping("/api-keys/{id}")
    public ApiKeyView assignKey(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody AssignKeyProjectRequest body) {
        return TenantContext.callAs(currentTenant.of(jwt).id(), () -> admin.assignKeyToProject(id, body.projectId()));
    }

    @DeleteMapping("/api-keys/{id}")
    public ApiKeyView revokeKey(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TenantContext.callAs(currentTenant.of(jwt).id(), () -> admin.revokeKey(id));
    }

    @GetMapping("/providers")
    public List<ProviderView> providers(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(currentTenant.of(jwt).id(), admin::listProviders);
    }

    @PutMapping("/providers/order")
    public List<ProviderView> reorder(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReorderProvidersRequest body) {
        return TenantContext.callAs(currentTenant.of(jwt).id(), () -> admin.reorderProviders(body.providerIds()));
    }

    @PatchMapping("/providers/{id}")
    public ProviderView toggle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ToggleProviderRequest body) {
        return TenantContext.callAs(currentTenant.of(jwt).id(), () -> admin.setProviderEnabled(id, body.enabled()));
    }

    @PostMapping("/top-up")
    public TopUpResult topUp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TopUpRequest body) {
        return TenantContext.callAs(currentTenant.of(jwt).id(), () -> admin.topUp(body.amountUsd()));
    }
}
