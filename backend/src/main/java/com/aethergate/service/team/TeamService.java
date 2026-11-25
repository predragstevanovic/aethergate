package com.aethergate.service.team;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.MemberRole;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.StaleVersionException;
import com.aethergate.service.tenant.ApiKeys;
import com.aethergate.service.tenant.DemoReadOnlyException;
import com.aethergate.service.tenant.InsufficientRoleException;
import com.aethergate.service.tenant.TenantDirectoryService;
import com.aethergate.service.tenant.TenantDirectoryService.Invitation;
import com.aethergate.service.tenant.WorkspaceAccess;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Team members and invitations of the current workspace.
 *
 * <p>Rules that prevent privilege escalation and lock-outs:
 * <ul>
 *   <li>You can only invite, promote to, demote from or remove roles BELOW your own (an Admin manages Developers
 *       and Viewers; Owners manage everyone). Nobody changes their own role.</li>
 *   <li>The primary owner (who created the workspace) can never be removed or demoted.</li>
 *   <li>Invitations are single-use bearer tokens: only a SHA-256 is stored, they expire after 72 h, re-sending
 *       replaces the token, and the accepting account must have the invited e-mail address.</li>
 * </ul>
 */
@Service
public class TeamService {

    public static final int MAX_MEMBERS = 25;
    static final Duration INVITE_TTL = Duration.ofHours(72);
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TenantDirectoryService directory;
    private final InvitationActivator activator;
    private final Clock clock;

    public TeamService(JdbcTemplate jdbc, TenantDirectoryService directory, InvitationActivator activator, Clock clock) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.activator = activator;
        this.clock = clock;
    }

    public record MemberView(UUID id, UUID userId, String email, String role, String status, boolean primaryOwner, boolean you,
                             Instant joinedAt, Instant inviteExpiresAt, String invitedBy, long version) {}

    public record TeamList(List<MemberView> members, String yourRole, int maxMembers, List<String> assignableRoles) {}

    /** The raw token is returned exactly once; the console turns it into an invite link. */
    public record CreatedInvitation(MemberView member, String token) {}

    public record AcceptedInvitation(UUID tenantId, String tenantName, String role) {}

    @TenantScoped(readOnly = true)
    public TeamList list(WorkspaceAccess me, UUID myUserId, String myEmail) {
        List<MemberView> members = new ArrayList<>();
        // the primary owner is the tenant row itself, never a member row
        members.add(jdbc.queryForObject("""
                select owner_user_id, created_at from tenants where id = aethergate.current_tenant_id()""",
                (rs, i) -> {
                    UUID owner = rs.getObject("owner_user_id", UUID.class);
                    boolean you = owner.equals(myUserId);
                    return new MemberView(null, owner, you ? myEmail : null, MemberRole.OWNER.name(), "ACTIVE", true, you,
                            rs.getTimestamp("created_at").toInstant(), null, null, 0);
                }));
        members.addAll(jdbc.query("""
                select id, user_id, email, role, status, joined_at, invite_expires_at, invited_by_email, version
                from tenant_members where tenant_id = aethergate.current_tenant_id()
                order by status, joined_at nulls last, created_at""", (rs, i) -> member(rs, myUserId)));
        return new TeamList(members, me.role().name(), MAX_MEMBERS, assignable(me.role()));
    }

    @TenantScoped
    public CreatedInvitation invite(WorkspaceAccess me, String inviterEmail, String email, String role) {
        if (me.tenant().demo()) {
            throw new DemoReadOnlyException("The shared demo workspace cannot invite people; sign in to your own workspace");
        }
        String address = normaliseEmail(email);
        MemberRole target = parseRole(role);
        requireCanManage(me, target);
        if (address.equalsIgnoreCase(inviterEmail == null ? "" : inviterEmail)) {
            throw new IllegalArgumentException("You are already in this workspace");
        }
        Integer count = jdbc.queryForObject("select count(*) from tenant_members where tenant_id = aethergate.current_tenant_id()",
                Integer.class);
        if (count != null && count + 1 >= MAX_MEMBERS) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_MEMBERS + " members including invitations");
        }
        Integer existing = jdbc.queryForObject("""
                select count(*) from tenant_members where tenant_id = aethergate.current_tenant_id() and lower(email) = ?""",
                Integer.class, address);
        if (existing != null && existing > 0) {
            throw new ResourceConflictException(address + " is already a member or has a pending invitation");
        }
        String token = newToken();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into tenant_members (id, tenant_id, email, role, status, invite_token_hash, invite_expires_at, invited_by_email)
                values (?, ?, ?, ?, 'INVITED', ?, ?, ?)""", id, TenantContext.require(), address, target.name(),
                ApiKeys.sha256Hex(token), Timestamp.from(clock.instant().plus(INVITE_TTL)), inviterEmail);
        return new CreatedInvitation(get(id, null), token);
    }

    /** New token and a fresh 72 h; the previous link stops working. */
    @TenantScoped
    public CreatedInvitation resendInvite(WorkspaceAccess me, UUID memberId) {
        MemberView m = get(memberId, null);
        if (!"INVITED".equals(m.status())) {
            throw new ResourceConflictException("Only pending invitations can be re-sent");
        }
        requireCanManage(me, MemberRole.valueOf(m.role()));
        String token = newToken();
        jdbc.update("""
                update tenant_members set invite_token_hash = ?, invite_expires_at = ?, version = version + 1 where id = ?""",
                ApiKeys.sha256Hex(token), Timestamp.from(clock.instant().plus(INVITE_TTL)), memberId);
        return new CreatedInvitation(get(memberId, null), token);
    }

    @TenantScoped
    public MemberView changeRole(WorkspaceAccess me, UUID myUserId, UUID memberId, long expectedVersion, String role) {
        MemberView m = get(memberId, myUserId);
        if (m.you()) {
            throw new InsufficientRoleException("You cannot change your own role", me.role(), me.role());
        }
        MemberRole target = parseRole(role);
        requireCanManage(me, MemberRole.valueOf(m.role())); // may I touch this person at all?
        requireCanManage(me, target);                        // ... and hand out that role?
        if (m.version() != expectedVersion) {
            throw new StaleVersionException(expectedVersion, m.version());
        }
        jdbc.update("update tenant_members set role = ?, version = version + 1 where id = ? and version = ?",
                target.name(), memberId, expectedVersion);
        evictAfterCommit(m.userId());
        return get(memberId, myUserId);
    }

    /** Removes a member or cancels an invitation. Members may always remove themselves ("leave"). */
    @TenantScoped
    public void remove(WorkspaceAccess me, UUID myUserId, UUID memberId) {
        MemberView m = get(memberId, myUserId);
        if (!m.you()) {
            requireCanManage(me, MemberRole.valueOf(m.role()));
        }
        jdbc.update("delete from tenant_members where id = ?", memberId);
        evictAfterCommit(m.userId());
    }

    /**
     * Accepts an invitation as the signed-in user. Not tenant-scoped on entry (the user is not a member yet):
     * the token is looked up through the directory, then the membership is activated inside that tenant.
     */
    public AcceptedInvitation accept(UUID userId, String email, String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 100) {
            throw new InvitationInvalidException();
        }
        Invitation inv = directory.findInvitation(ApiKeys.sha256Hex(rawToken.strip())).orElseThrow(InvitationInvalidException::new);
        if (!clock.instant().isBefore(inv.expiresAt())) {
            throw new InvitationInvalidException();
        }
        if (email == null || !inv.email().equalsIgnoreCase(email.strip())) {
            throw new InvitationEmailMismatchException(inv.email());
        }
        if (inv.ownerUserId().equals(userId)) {
            throw new ResourceConflictException("You already own this workspace");
        }
        TenantContext.runAs(inv.tenantId(), () -> activator.activate(inv, userId));
        directory.evictUser(userId);
        return new AcceptedInvitation(inv.tenantId(), inv.tenantName(), inv.role().name());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** Strictly below your own role, except that Owners may manage Owners. */
    static void requireCanManage(WorkspaceAccess me, MemberRole target) {
        boolean ok = me.role() == MemberRole.OWNER || me.role().compareTo(target) > 0;
        if (!ok || !me.role().atLeast(MemberRole.ADMIN)) {
            throw new InsufficientRoleException("Your role (" + me.role().name() + ") cannot manage " + target.name() + " members",
                    target == MemberRole.VIEWER ? MemberRole.ADMIN : MemberRole.OWNER, me.role());
        }
    }

    static List<String> assignable(MemberRole mine) {
        return switch (mine) {
            case OWNER -> List.of("OWNER", "ADMIN", "DEVELOPER", "VIEWER");
            case ADMIN -> List.of("DEVELOPER", "VIEWER");
            default -> List.of();
        };
    }

    private MemberView get(UUID id, UUID myUserId) {
        List<MemberView> found = jdbc.query("""
                select id, user_id, email, role, status, joined_at, invite_expires_at, invited_by_email, version
                from tenant_members where id = ?""", (rs, i) -> member(rs, myUserId), id);
        if (found.isEmpty()) {
            throw new NoSuchElementException("Member not found");
        }
        return found.getFirst();
    }

    private static MemberView member(ResultSet rs, UUID myUserId) throws SQLException {
        UUID user = rs.getObject("user_id", UUID.class);
        Timestamp joined = rs.getTimestamp("joined_at");
        Timestamp expires = rs.getTimestamp("invite_expires_at");
        return new MemberView(rs.getObject("id", UUID.class), user, rs.getString("email"), rs.getString("role"),
                rs.getString("status"), false, user != null && user.equals(myUserId), joined == null ? null : joined.toInstant(),
                expires == null ? null : expires.toInstant(), rs.getString("invited_by_email"), rs.getLong("version"));
    }

    private void evictAfterCommit(UUID userId) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    directory.evictUser(userId); // the removed / demoted user loses access on their very next request
                }
            });
        } else {
            directory.evictUser(userId);
        }
    }

    static MemberRole parseRole(String role) {
        try {
            return MemberRole.valueOf(role == null ? "" : role.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Role must be one of OWNER, ADMIN, DEVELOPER, VIEWER");
        }
    }

    static String normaliseEmail(String email) {
        String e = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (e.length() > 254 || !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid e-mail address");
        }
        return e;
    }

    static String newToken() {
        StringBuilder sb = new StringBuilder("inv_");
        for (int i = 0; i < 40; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
