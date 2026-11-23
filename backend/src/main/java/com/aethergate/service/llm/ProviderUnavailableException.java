package com.aethergate.service.llm;

/** Upstream provider failed before producing output (503, timeout, connection refused, circuit open). */
public class ProviderUnavailableException extends RuntimeException {

    private final String provider;

    public ProviderUnavailableException(String provider, String message) {
        super(provider + ": " + message);
        this.provider = provider;
    }

    public ProviderUnavailableException(String provider, String message, Throwable cause) {
        super(provider + ": " + message, cause);
        this.provider = provider;
    }

    public String getProvider() { return provider; }
}
