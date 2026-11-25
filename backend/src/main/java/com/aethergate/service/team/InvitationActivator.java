package com.aethergate.service.team;

import com.aethergate.config.TenantScoped;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.tenant.TenantDirectoryService.Invitation;
import com.aethergate.service.webhook.WebhookEvents;
import com.aethergate.service.webhook.WebhookOutbox;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Activates a membership inside the inviting tenant (its own bean, so the RLS aspect applies). */
@Component
class InvitationActivator {

    private final JdbcTemplate jdbc;
    private final WebhookOutbox outbox;

    InvitationActivator(JdbcTemplate jdbc, WebhookOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @TenantScoped
    public void activate(Invitation inv, UUID userId) {
        Integer already = jdbc.queryForObject("""
                select count(*) from tenant_members where tenant_id = aethergate.current_tenant_id() and user_id = ?""",
                Integer.class, userId);
        if (already != null && already > 0) {
            throw new ResourceConflictException("You are already a member of this workspace");
        }
        int updated = jdbc.update("""
                update tenant_members set status = 'ACTIVE', user_id = ?, invite_token_hash = null, invite_expires_at = null,
                joined_at = now(), version = version + 1 where id = ? and status = 'INVITED'""", userId, inv.memberId());
        if (updated == 0) {
            throw new InvitationInvalidException(); // accepted twice at the same time: exactly one wins
        }
        outbox.publish(WebhookEvents.MEMBER_JOINED, Map.of("email", inv.email(), "role", inv.role().name()));
    }
}
