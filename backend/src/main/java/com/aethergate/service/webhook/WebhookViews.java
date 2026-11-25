package com.aethergate.service.webhook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models for webhooks and budget alerts. */
public final class WebhookViews {

    private WebhookViews() {}

    public record EndpointDraft(String name, String url, List<String> eventTypes) {}

    /** The secret is never returned again after creation/rotation, only its first characters. */
    public record EndpointView(UUID id, String name, String url, List<String> eventTypes, boolean enabled, String secretHint,
                               long version, long deliveries24h, long failed24h, String lastStatus, Instant lastDeliveryAt,
                               Instant createdAt) {}

    public record EndpointList(List<EndpointView> endpoints, List<String> eventTypes, int maxEndpoints) {}

    /** Returned exactly once: at creation and after a secret rotation. */
    public record EndpointWithSecret(EndpointView endpoint, String secret) {}

    public record DeliveryView(UUID id, String eventId, String eventType, String status, int attempts, Integer lastStatusCode,
                               String lastError, Instant createdAt, Instant nextAttemptAt, Instant deliveredAt, String payload) {}

    public record AlertRuleView(UUID id, UUID projectId, String scope, int thresholdPct, boolean enabled, BigDecimal spendUsd,
                                BigDecimal limitUsd, double usedPct, Instant firedThisMonthAt) {}

    public record AlertFiringView(String scope, int thresholdPct, BigDecimal spendUsd, BigDecimal limitUsd, Instant firedAt) {}

    public record AlertList(List<AlertRuleView> rules, List<AlertFiringView> recent, String period) {}
}
