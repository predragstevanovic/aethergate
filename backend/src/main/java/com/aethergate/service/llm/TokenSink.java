package com.aethergate.service.llm;

/** Receives streamed completion deltas. Throwing {@link ClientAbortedException} stops generation. */
@FunctionalInterface
public interface TokenSink {
    void onToken(String delta);
}
