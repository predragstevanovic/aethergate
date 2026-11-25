package com.aethergate.service.webhook;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Transactional outbox for webhooks. {@link #publish} writes one delivery row per subscribed endpoint inside
 * the CALLER's transaction: if the business change rolls back, no webhook is ever sent; if it commits, the
 * delivery is durable even if the process dies a millisecond later. Nothing is sent from inside a transaction.
 */
@Service
public class WebhookOutbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public WebhookOutbox(JdbcTemplate jdbc, ObjectMapper json, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    /** Enqueues {@code type} for every enabled endpoint of the current tenant subscribed to it. Returns the count. */
    @TenantScoped
    public int publish(String type, Map<String, Object> data) {
        List<UUID> endpoints = jdbc.query("""
                select id, event_types from webhook_endpoints
                where tenant_id = aethergate.current_tenant_id() and is_enabled""", (rs, i) ->
                subscribed(rs.getString("event_types"), type) ? rs.getObject("id", UUID.class) : null)
                .stream().filter(java.util.Objects::nonNull).toList();
        return enqueue(endpoints, type, data);
    }

    /** "Send test": one endpoint, regardless of its subscriptions. */
    @TenantScoped
    public int publishTo(UUID endpointId, String type, Map<String, Object> data) {
        return enqueue(List.of(endpointId), type, data);
    }

    /** Wakes the dispatcher after the current transaction commits (e.g. a manual redelivery). */
    public void wake() {
        events.publishEvent(new WebhooksEnqueued(1));
    }

    private int enqueue(List<UUID> endpoints, String type, Map<String, Object> data) {
        if (endpoints.isEmpty()) {
            return 0;
        }
        UUID tenant = TenantContext.require();
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", type);
        envelope.put("created_at", clock.instant().toString());
        envelope.put("tenant_id", tenant.toString());
        envelope.put("data", data);
        String body;
        try {
            body = json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook payload is not serialisable", e);
        }
        for (UUID endpoint : endpoints) {
            jdbc.update("""
                    insert into webhook_deliveries (tenant_id, endpoint_id, event_id, event_type, payload)
                    values (?, ?, ?, ?, ?)""", tenant, endpoint, eventId, type, body);
        }
        events.publishEvent(new WebhooksEnqueued(endpoints.size())); // dispatcher wakes up after commit
        return endpoints.size();
    }

    static boolean subscribed(String csv, String type) {
        for (String t : csv.split(",")) {
            if (t.strip().equals(type)) {
                return true;
            }
        }
        return false;
    }
}
