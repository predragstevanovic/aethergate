package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.MemberRole;
import com.aethergate.service.team.AuditService;
import com.aethergate.service.team.AuditService.AuditEntry;
import com.aethergate.service.team.TeamService;
import com.aethergate.service.team.TeamService.AcceptedInvitation;
import com.aethergate.service.team.TeamService.CreatedInvitation;
import com.aethergate.service.team.TeamService.MemberView;
import com.aethergate.service.team.TeamService.TeamList;
import com.aethergate.service.tenant.WorkspaceAccess;
import com.aethergate.web.dto.AcceptInvitationRequest;
import com.aethergate.web.dto.ChangeRoleRequest;
import com.aethergate.web.dto.InviteMemberRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspaces, team members, invitations and the audit log.
 *
 * <pre>
 * GET    /api/v1/workspaces                      workspaces I can open (own + joined) with my role
 * POST   /api/v1/invitations/accept              {"token"}: join a workspace (invited e-mail only)
 * GET    /api/v1/tenant/members                  members + pending invitations
 * POST   /api/v1/tenant/members                  invite → 201 with a one-time token
 * POST   /api/v1/tenant/members/{id}/resend      new token, old link dies
 * PATCH  /api/v1/tenant/members/{id}             change role (If-Match)
 * DELETE /api/v1/tenant/members/{id}             remove / cancel invitation / leave
 * GET    /api/v1/tenant/audit                    who changed what (Admin)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class TeamController {

    private final CurrentTenant currentTenant;
    private final TeamService team;
    private final AuditService audit;

    public TeamController(CurrentTenant currentTenant, TeamService team, AuditService audit) {
        this.currentTenant = currentTenant;
        this.team = team;
        this.audit = audit;
    }

    public record WorkspaceSummary(UUID id, String name, String slug, String role, boolean demo, boolean primaryOwner) {}

    @GetMapping("/workspaces")
    @WorkspaceAgnostic
    public List<WorkspaceSummary> workspaces(@AuthenticationPrincipal Jwt jwt) {
        return currentTenant.workspaces(jwt).stream().map(TeamController::summary).toList();
    }

    @PostMapping("/invitations/accept")
    @WorkspaceAgnostic
    public AcceptedInvitation accept(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AcceptInvitationRequest body) {
        return team.accept(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"), body.token());
    }

    @GetMapping("/tenant/members")
    public TeamList members(@AuthenticationPrincipal Jwt jwt) {
        WorkspaceAccess me = currentTenant.access(jwt);
        return TenantContext.callAs(me.tenantId(), () -> team.list(me, userId(jwt), jwt.getClaimAsString("email")));
    }

    @PostMapping("/tenant/members")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedInvitation invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InviteMemberRequest body) {
        WorkspaceAccess me = currentTenant.access(jwt);
        return TenantContext.callAs(me.tenantId(), () -> team.invite(me, jwt.getClaimAsString("email"), body.email(), body.role()));
    }

    @PostMapping("/tenant/members/{id}/resend")
    public CreatedInvitation resend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        WorkspaceAccess me = currentTenant.access(jwt);
        return TenantContext.callAs(me.tenantId(), () -> team.resendInvite(me, id));
    }

    @PatchMapping("/tenant/members/{id}")
    public ResponseEntity<MemberView> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody ChangeRoleRequest body) {
        long expected = IfMatch.parse(ifMatch);
        WorkspaceAccess me = currentTenant.access(jwt);
        MemberView updated = TenantContext.callAs(me.tenantId(), () -> team.changeRole(me, userId(jwt), id, expected, body.role()));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    /** Viewer is enough to LEAVE; removing someone else is checked against the role hierarchy in the service. */
    @DeleteMapping("/tenant/members/{id}")
    @RequiresRole(MemberRole.VIEWER)
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        WorkspaceAccess me = currentTenant.access(jwt);
        TenantContext.runAs(me.tenantId(), () -> team.remove(me, userId(jwt), id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tenant/audit")
    @RequiresRole(MemberRole.ADMIN)
    public Map<String, Object> auditLog(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "100") int limit) {
        List<AuditEntry> entries = TenantContext.callAs(currentTenant.of(jwt).id(), () -> audit.recent(limit));
        return Map.of("entries", entries);
    }

    static WorkspaceSummary summary(WorkspaceAccess a) {
        return new WorkspaceSummary(a.tenantId(), a.tenant().name(), a.tenant().slug(), a.role().name(), a.tenant().demo(),
                a.primaryOwner());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
