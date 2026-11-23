package com.aethergate.service.llm;

/**
 * The provider failed after tokens were already forwarded to the client. Failover is no longer
 * possible without corrupting the stream, so the request ends and only emitted tokens are billed.
 */
public class MidStreamFailureException extends RuntimeException {

    private final transient ProviderResult partial;

    public MidStreamFailureException(String provider, ProviderResult partial, Throwable cause) {
        super(provider + " failed mid-stream", cause);
        this.partial = partial;
    }

    public ProviderResult getPartial() { return partial; }
}
