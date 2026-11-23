package com.aethergate.service.llm;

/**
 * A stream that already delivered tokens ended early, either because the client disconnected
 * ({@code clientAborted}) or because the provider failed mid-stream. Carries what was emitted
 * so the gateway can bill exactly the delivered tokens and release the rest of the hold.
 */
public class StreamInterruptedException extends RuntimeException {

    private final transient RouteTarget target;
    private final transient ProviderResult partial;
    private final boolean clientAborted;

    public StreamInterruptedException(RouteTarget target, ProviderResult partial, boolean clientAborted, Throwable cause) {
        super((clientAborted ? "Client disconnected" : target.provider() + " failed") + " after "
                + partial.completionTokens() + " tokens", cause);
        this.target = target;
        this.partial = partial;
        this.clientAborted = clientAborted;
    }

    public RouteTarget getTarget() { return target; }
    public ProviderResult getPartial() { return partial; }
    public boolean isClientAborted() { return clientAborted; }
}
