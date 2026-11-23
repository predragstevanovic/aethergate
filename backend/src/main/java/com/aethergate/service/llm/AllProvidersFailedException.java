package com.aethergate.service.llm;

import java.util.List;

/** Every provider in the tenant's fallback chain was unavailable → HTTP 503. */
public class AllProvidersFailedException extends RuntimeException {

    private final List<String> attempts;

    public AllProvidersFailedException(List<String> attempts) {
        super("All upstream providers are unavailable: " + String.join("; ", attempts));
        this.attempts = List.copyOf(attempts);
    }

    public List<String> getAttempts() { return attempts; }
}
