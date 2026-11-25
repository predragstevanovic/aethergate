package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.MemberRole;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.prompt.PromptService;
import com.aethergate.service.prompt.PromptService.TemplateDetail;
import com.aethergate.service.prompt.PromptService.TemplateDraft;
import com.aethergate.service.prompt.PromptService.TemplateView;
import com.aethergate.web.dto.ActivateVersionRequest;
import com.aethergate.web.dto.PromptMetaRequest;
import com.aethergate.web.dto.PromptRequest;
import com.aethergate.web.dto.PromptVersionRequest;
import com.aethergate.web.dto.RenderRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versioned prompt templates. Developers draft versions; publishing (or rolling back) is an Admin action.
 *
 * <pre>
 * GET    /api/v1/tenant/prompts                       list with 30-day usage
 * POST   /api/v1/tenant/prompts                       create (version 1, active)        Developer
 * GET    /api/v1/tenant/prompts/{id}                  detail + per-version stats → ETag
 * PUT    /api/v1/tenant/prompts/{id}                  rename / describe (If-Match)      Developer
 * POST   /api/v1/tenant/prompts/{id}/versions         new immutable draft version       Developer
 * PUT    /api/v1/tenant/prompts/{id}/active-version   publish / roll back (If-Match)    Admin
 * POST   /api/v1/tenant/prompts/{id}/render           preview with variables            Developer
 * DELETE /api/v1/tenant/prompts/{id}                  only without traffic, else 409    Admin
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/tenant/prompts")
public class PromptController {

    private final CurrentTenant currentTenant;
    private final PromptService prompts;

    public PromptController(CurrentTenant currentTenant, PromptService prompts) {
        this.currentTenant = currentTenant;
        this.prompts = prompts;
    }

    @GetMapping
    public Map<String, List<TemplateView>> list(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("templates", TenantContext.callAs(tenant(jwt), prompts::list));
    }

    @PostMapping
    @RequiresRole(MemberRole.DEVELOPER)
    public ResponseEntity<TemplateDetail> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PromptRequest b) {
        TemplateDetail d = TenantContext.callAs(tenant(jwt), () -> prompts.create(
                new TemplateDraft(b.name(), b.description(), b.systemPrompt(), b.userTemplate(), b.note()), email(jwt)));
        return ResponseEntity.created(URI.create("/api/v1/tenant/prompts/" + d.template().id()))
                .eTag(IfMatch.etag(d.template().version())).body(d);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TemplateDetail> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TemplateDetail d = TenantContext.callAs(tenant(jwt), () -> prompts.get(id));
        return ResponseEntity.ok().eTag(IfMatch.etag(d.template().version())).body(d);
    }

    @PutMapping("/{id}")
    @RequiresRole(MemberRole.DEVELOPER)
    public ResponseEntity<TemplateDetail> rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody PromptMetaRequest b) {
        long expected = IfMatch.parse(ifMatch);
        TemplateDetail d = TenantContext.callAs(tenant(jwt), () -> prompts.rename(id, expected, b.name(), b.description()));
        return ResponseEntity.ok().eTag(IfMatch.etag(d.template().version())).body(d);
    }

    @PostMapping("/{id}/versions")
    @RequiresRole(MemberRole.DEVELOPER)
    public ResponseEntity<TemplateDetail> addVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                     @Valid @RequestBody PromptVersionRequest b) {
        TemplateDetail d = TenantContext.callAs(tenant(jwt), () -> prompts.addVersion(id,
                new TemplateDraft(null, null, b.systemPrompt(), b.userTemplate(), b.note()), email(jwt)));
        return ResponseEntity.status(201).eTag(IfMatch.etag(d.template().version())).body(d);
    }

    @PutMapping("/{id}/active-version")
    @RequiresRole(MemberRole.ADMIN)
    public ResponseEntity<TemplateDetail> activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                   @Valid @RequestBody ActivateVersionRequest b) {
        long expected = IfMatch.parse(ifMatch);
        TemplateDetail d = TenantContext.callAs(tenant(jwt), () -> prompts.activate(id, expected, b.version()));
        return ResponseEntity.ok().eTag(IfMatch.etag(d.template().version())).body(d);
    }

    @PostMapping("/{id}/render")
    @RequiresRole(MemberRole.DEVELOPER)
    public Map<String, List<ChatMessage>> render(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @RequestBody RenderRequest b) {
        return Map.of("messages", TenantContext.callAs(tenant(jwt),
                () -> prompts.render(id.toString(), b.version(), b.variables()).messages()));
    }

    @DeleteMapping("/{id}")
    @RequiresRole(MemberRole.ADMIN)
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext.runAs(tenant(jwt), () -> prompts.delete(id));
        return ResponseEntity.noContent().build();
    }

    private UUID tenant(Jwt jwt) {
        return currentTenant.of(jwt).id();
    }

    private static String email(Jwt jwt) {
        return jwt.getClaimAsString("email");
    }
}
