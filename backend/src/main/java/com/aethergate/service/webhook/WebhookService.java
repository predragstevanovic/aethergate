package com.aethergate.service.webhook;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.StaleVersionException;
import com.aethergate.service.tenant.DemoReadOnlyException;
import com.aethergate.service.webhook.WebhookViews.DeliveryView;
import com.aethergate.service.webhook.WebhookViews.EndpointDraft;
import com.aethergate.service.webhook.WebhookViews.EndpointList;
import com.aethergate.service.webhook.WebhookViews.EndpointView;
import com.aethergate.service.webhook.WebhookViews.EndpointWithSecret;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Webhook endpoints of the current tenant: CRUD, secret rotation, test events, delivery log, redelivery. */
@Service
public class WebhookService {

    public static final int MAX_ENDPOINTS = 5;

    private final JdbcTemplate jdbc;
    private final WebhookUrlPolicy urls;
    private final WebhookOutbox outbox;

    public WebhookService(JdbcTemplate jdbc, WebhookUrlPolicy urls, WebhookOutbox outbox) {
        this.jdbc = jdbc;
        this.urls = urls;
        this.outbox = outbox;
    }

    @TenantScoped(readOnly = true)
    public EndpointList list() {
        return new EndpointList(query(null), WebhookEvents.SUBSCRIBABLE, MAX_ENDPOINTS);
    }

    @TenantScoped
    public EndpointWithSecret create(EndpointDraft draft) {
        requireNotDemo();
        Valid v = validate(draft);
        Integer n = jdbc.queryForObject("select count(*) from webhook_endpoints where tenant_id = aethergate.current_tenant_id()",
                Integer.class);
        if (n != null && n >= MAX_ENDPOINTS) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_ENDPOINTS + " webhook endpoints");
        }
        requireUniqueName(v.name(), null);
        UUID id = UUID.randomUUID();
        String secret = WebhookSignature.newSecret();
        jdbc.update("""
                insert into webhook_endpoints (id, tenant_id, name, url, secret, event_types) values (?, ?, ?, ?, ?, ?)""",
                id, TenantContext.require(), v.name(), v.url(), secret, String.join(",", v.events()));
        return new EndpointWithSecret(get(id), secret);
    }

    @TenantScoped
    public EndpointView update(UUID id, long expectedVersion, EndpointDraft draft) {
        Valid v = validate(draft);
        long current = version(id);
        if (current != expectedVersion) {
            throw new StaleVersionException(expectedVersion, current);
        }
        requireUniqueName(v.name(), id);
        jdbc.update("""
                update webhook_endpoints set name = ?, url = ?, event_types = ?, version = version + 1, updated_at = now()
                where id = ? and version = ?""", v.name(), v.url(), String.join(",", v.events()), id, expectedVersion);
        return get(id);
    }

    @TenantScoped
    public EndpointView setEnabled(UUID id, boolean enabled) {
        version(id);
        jdbc.update("update webhook_endpoints set is_enabled = ?, version = version + 1, updated_at = now() where id = ?",
                enabled, id);
        return get(id);
    }

    /** The old secret stops working immediately; the new one is shown exactly once. */
    @TenantScoped
    public EndpointWithSecret rotateSecret(UUID id) {
        version(id);
        String secret = WebhookSignature.newSecret();
        jdbc.update("update webhook_endpoints set secret = ?, version = version + 1, updated_at = now() where id = ?", secret, id);
        return new EndpointWithSecret(get(id), secret);
    }

    /** Deliveries cascade with the endpoint. */
    @TenantScoped
    public void delete(UUID id) {
        if (jdbc.update("delete from webhook_endpoints where id = ?", id) == 0) {
            throw notFound();
        }
    }

    @TenantScoped
    public DeliveryView sendTest(UUID id) {
        EndpointView endpoint = get(id);
        outbox.publishTo(id, WebhookEvents.TEST, Map.of("message", "Test event from AetherGate", "endpoint", endpoint.name()));
        return deliveries(id).getFirst();
    }

    @TenantScoped(readOnly = true)
    public List<DeliveryView> deliveries(UUID endpointId) {
        version(endpointId);
        return jdbc.query("""
                select id, event_id, event_type, status, attempts, last_status_code, last_error, created_at, next_attempt_at,
                       delivered_at, payload
                from webhook_deliveries where tenant_id = aethergate.current_tenant_id() and endpoint_id = ?
                order by created_at desc, id limit 50""", WebhookService::delivery, endpointId);
    }

    /** Queues a fresh attempt of a delivery with the SAME event id, so receivers can still de-duplicate. */
    @TenantScoped
    public DeliveryView redeliver(UUID deliveryId) {
        List<UUID> endpoint = jdbc.queryForList("select endpoint_id from webhook_deliveries where id = ?", UUID.class, deliveryId);
        if (endpoint.isEmpty()) {
            throw new NoSuchElementException("Delivery not found");
        }
        UUID copy = UUID.randomUUID();
        jdbc.update("""
                insert into webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, payload)
                select ?, tenant_id, endpoint_id, event_id, event_type, payload from webhook_deliveries where id = ?""",
                copy, deliveryId);
        outbox.wake();
        return jdbc.query("""
                select id, event_id, event_type, status, attempts, last_status_code, last_error, created_at, next_attempt_at,
                       delivered_at, payload from webhook_deliveries where id = ?""", WebhookService::delivery, copy).getFirst();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private record Valid(String name, String url, List<String> events) {}

    private Valid validate(EndpointDraft d) {
        String name = d.name() == null ? "" : d.name().strip();
        if (name.length() < 2 || name.length() > 80) {
            throw new IllegalArgumentException("Endpoint name must be 2-80 characters");
        }
        String url = urls.validate(d.url());
        Set<String> events = new LinkedHashSet<>();
        for (String e : d.eventTypes() == null ? List.<String>of() : d.eventTypes()) {
            String t = e == null ? "" : e.strip();
            if (!WebhookEvents.SUBSCRIBABLE.contains(t)) {
                throw new IllegalArgumentException("Unknown event type '" + e + "'; use one of " + WebhookEvents.SUBSCRIBABLE);
            }
            events.add(t);
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Subscribe the endpoint to at least one event type");
        }
        return new Valid(name, url, new ArrayList<>(events));
    }

    private void requireUniqueName(String name, UUID exceptId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from webhook_endpoints where tenant_id = aethergate.current_tenant_id()
                and lower(name) = lower(?) and (?::uuid is null or id <> ?::uuid)""", Integer.class, name, exceptId, exceptId);
        if (n != null && n > 0) {
            throw new ResourceConflictException("A webhook endpoint called '" + name + "' already exists");
        }
    }

    private void requireNotDemo() {
        Boolean demo = jdbc.queryForObject("select is_demo from tenants where id = aethergate.current_tenant_id()", Boolean.class);
        if (Boolean.TRUE.equals(demo)) {
            throw new DemoReadOnlyException("Webhooks are disabled in the shared demo workspace (every visitor could point them anywhere); sign in to your own workspace");
        }
    }

    private long version(UUID id) {
        List<Long> v = jdbc.queryForList("select version from webhook_endpoints where id = ?", Long.class, id);
        if (v.isEmpty()) {
            throw notFound();
        }
        return v.getFirst();
    }

    private EndpointView get(UUID id) {
        return query(id).stream().findFirst().orElseThrow(WebhookService::notFound);
    }

    private List<EndpointView> query(UUID onlyId) {
        return jdbc.query("""
                select e.id, e.name, e.url, e.event_types, e.is_enabled, e.secret, e.version, e.created_at,
                       coalesce(s.total, 0) as total, coalesce(s.failed, 0) as failed, l.status as last_status, l.created_at as last_at
                from webhook_endpoints e
                left join lateral (select count(*) as total, count(*) filter (where d.status = 'DEAD' or d.attempts > 0 and d.status = 'PENDING') as failed
                                   from webhook_deliveries d where d.tenant_id = e.tenant_id and d.endpoint_id = e.id
                                   and d.created_at >= now() - interval '24 hours') s on true
                left join lateral (select d.status, d.created_at from webhook_deliveries d
                                   where d.tenant_id = e.tenant_id and d.endpoint_id = e.id order by d.created_at desc limit 1) l on true
                where e.tenant_id = aethergate.current_tenant_id() and (?::uuid is null or e.id = ?::uuid)
                order by e.created_at""", WebhookService::endpoint, onlyId, onlyId);
    }

    private static EndpointView endpoint(ResultSet rs, int i) throws SQLException {
        Timestamp last = rs.getTimestamp("last_at");
        String secret = rs.getString("secret");
        return new EndpointView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"),
                Arrays.stream(rs.getString("event_types").split(",")).map(String::strip).toList(), rs.getBoolean("is_enabled"),
                secret.substring(0, Math.min(secret.length(), WebhookSignature.SIGNING_KEY_PREFIX.length() + 4)) + "…",
                rs.getLong("version"), rs.getLong("total"), rs.getLong("failed"), rs.getString("last_status"),
                last == null ? null : last.toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    private static DeliveryView delivery(ResultSet rs, int i) throws SQLException {
        Timestamp delivered = rs.getTimestamp("delivered_at");
        Object code = rs.getObject("last_status_code");
        String status = rs.getString("status");
        return new DeliveryView(rs.getObject("id", UUID.class), rs.getString("event_id"), rs.getString("event_type"), status,
                rs.getInt("attempts"), code == null ? null : ((Number) code).intValue(), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                "PENDING".equals(status) ? rs.getTimestamp("next_attempt_at").toInstant() : null,
                delivered == null ? null : delivered.toInstant(), rs.getString("payload"));
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException("Webhook endpoint not found");
    }
}
