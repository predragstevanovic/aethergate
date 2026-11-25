package com.aethergate.service.webhook;

/** Published when deliveries were written to the outbox; the dispatcher wakes up after commit. */
public record WebhooksEnqueued(int count) {}
