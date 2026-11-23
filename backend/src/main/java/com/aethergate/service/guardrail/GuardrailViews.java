package com.aethergate.service.guardrail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models of the Guardrails API. */
public final class GuardrailViews {

    private GuardrailViews() {}

    public record GuardrailView(UUID id, String name, String type, String pattern, String action, boolean enabled,
                                long hits30d, Instant lastHitAt, long version, Instant createdAt, Instant updatedAt) {}

    public record GuardrailEventView(String ruleName, String action, String requestId, int matches, Instant createdAt) {}

    public record GuardrailList(List<GuardrailView> rules, List<GuardrailEventView> recentEvents, int maxRules) {}

    public record MatchView(String rule, String action, int count) {}

    /** Dry run of the current enabled rules against a sample text: exactly what the gateway would do. */
    public record TestResult(boolean blocked, String blockedBy, String resultText, List<MatchView> matches) {}

    public record GuardrailDraft(String name, String type, String pattern, String action) {}
}
