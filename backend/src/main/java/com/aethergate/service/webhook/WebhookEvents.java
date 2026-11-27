package com.aethergate.service.webhook;

import java.util.List;

/** Event types a webhook endpoint can subscribe to. */
public final class WebhookEvents {

    public static final String BUDGET_THRESHOLD = "budget.threshold_crossed";
    public static final String GUARDRAIL_BLOCKED = "guardrail.blocked";
    public static final String KEY_ROTATED = "api_key.rotated";
    public static final String MEMBER_JOINED = "member.joined";
    /** A spend cap (key budget, end-user daily cap, session budget / steps) blocked its first request of the period. */
    public static final String SPEND_CAP_REACHED = "spend_cap.reached";
    /** An hour's spend was far above the tenant's own baseline (robust z-score). Once per tenant and hour. */
    public static final String COST_ANOMALY = "cost.anomaly_detected";
    /** Sent by "Send test"; always delivered to the endpoint it was sent to, whatever it subscribes to. */
    public static final String TEST = "webhook.test";

    public static final List<String> SUBSCRIBABLE = List.of(BUDGET_THRESHOLD, GUARDRAIL_BLOCKED, KEY_ROTATED, MEMBER_JOINED,
            SPEND_CAP_REACHED, COST_ANOMALY);

    private WebhookEvents() {}
}
