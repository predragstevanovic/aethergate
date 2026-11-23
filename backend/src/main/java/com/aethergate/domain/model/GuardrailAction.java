package com.aethergate.domain.model;

public enum GuardrailAction {
    /** Reject the request with 422 before any hold is taken: it costs nothing. */
    BLOCK,
    /** Mask every match and let the request continue; providers and the cache only see the masked text. */
    REDACT
}
