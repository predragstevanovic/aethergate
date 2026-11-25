package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** eventTypes: any of budget.threshold_crossed, guardrail.blocked, api_key.rotated, member.joined. */
public record WebhookRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 500) String url,
                             @Size(max = 10) List<String> eventTypes) {}
