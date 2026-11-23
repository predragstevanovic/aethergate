package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.service.project.ProjectService;
import com.aethergate.service.project.ProjectViews.ArchiveResult;
import com.aethergate.service.project.ProjectViews.ProjectDetail;
import com.aethergate.service.project.ProjectViews.ProjectDraft;
import com.aethergate.service.project.ProjectViews.ProjectList;
import com.aethergate.service.project.ProjectViews.ProjectView;
import com.aethergate.web.dto.ProjectRequest;
import jakarta.validation.Valid;
import java.net.URI;
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
 * Projects (cost centres) of the signed-in tenant.
 *
 * <pre>
 * GET    /api/v1/tenant/projects                list + budget position + unassigned spend
 * POST   /api/v1/tenant/projects                create            → 201, Location, ETag
 * GET    /api/v1/tenant/projects/{id}           detail (30-day usage, keys) → ETag
 * PUT    /api/v1/tenant/projects/{id}           replace (If-Match required: 428 / 412)
 * POST   /api/v1/tenant/projects/{id}/archive   archive + revoke its keys
 * POST   /api/v1/tenant/projects/{id}/restore   reactivate (keys stay revoked)
 * DELETE /api/v1/tenant/projects/{id}           only if it never handled traffic → 204, else 409
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/tenant/projects")
public class ProjectController {

    private final CurrentTenant currentTenant;
    private final ProjectService projects;

    public ProjectController(CurrentTenant currentTenant, ProjectService projects) {
        this.currentTenant = currentTenant;
        this.projects = projects;
    }

    @GetMapping
    public ProjectList list(@AuthenticationPrincipal Jwt jwt) {
        return TenantContext.callAs(tenant(jwt), projects::list);
    }

    @PostMapping
    public ResponseEntity<ProjectView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProjectRequest body) {
        ProjectView created = TenantContext.callAs(tenant(jwt), () -> projects.create(draft(body)));
        return ResponseEntity.created(URI.create("/api/v1/tenant/projects/" + created.id()))
                .eTag(IfMatch.etag(created.version())).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDetail> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ProjectDetail detail = TenantContext.callAs(tenant(jwt), () -> projects.get(id));
        return ResponseEntity.ok().eTag(IfMatch.etag(detail.project().version())).body(detail);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @Valid @RequestBody ProjectRequest body) {
        long expected = IfMatch.parse(ifMatch);
        ProjectView updated = TenantContext.callAs(tenant(jwt), () -> projects.update(id, expected, draft(body)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @PostMapping("/{id}/archive")
    public ArchiveResult archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TenantContext.callAs(tenant(jwt), () -> projects.archive(id));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<ProjectView> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ProjectView restored = TenantContext.callAs(tenant(jwt), () -> projects.restore(id));
        return ResponseEntity.ok().eTag(IfMatch.etag(restored.version())).body(restored);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        TenantContext.runAs(tenant(jwt), () -> projects.delete(id));
        return ResponseEntity.noContent().build();
    }

    private UUID tenant(Jwt jwt) {
        return currentTenant.of(jwt).id();
    }

    private static ProjectDraft draft(ProjectRequest body) {
        return new ProjectDraft(body.name(), body.description(), body.monthlyBudgetUsd());
    }
}
