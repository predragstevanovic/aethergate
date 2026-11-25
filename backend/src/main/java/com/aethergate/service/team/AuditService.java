package com.aethergate.service.team;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only "who changed what" log of the console (every write, including the ones that were refused). */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditEntry(String actorEmail, String actorRole, String action, String targetId, int statusCode, Instant createdAt) {}

    @TenantScoped
    public void record(UUID actorId, String actorEmail, String actorRole, String action, String targetId, int status) {
        jdbc.update("""
                insert into audit_events (tenant_id, actor_id, actor_email, actor_role, action, target_id, status_code)
                values (?, ?, ?, ?, ?, ?, ?)""", TenantContext.require(), actorId, truncate(actorEmail, 254), actorRole,
                truncate(action, 160), truncate(targetId, 64), status);
    }

    @TenantScoped(readOnly = true)
    public List<AuditEntry> recent(int limit) {
        return jdbc.query("""
                select actor_email, actor_role, action, target_id, status_code, created_at from audit_events
                where tenant_id = aethergate.current_tenant_id() order by created_at desc, id limit ?""",
                (rs, i) -> new AuditEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getTimestamp(6).toInstant()), Math.clamp(limit, 1, 200));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
