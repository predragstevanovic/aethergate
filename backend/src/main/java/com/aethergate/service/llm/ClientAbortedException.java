package com.aethergate.service.llm;

/** The downstream client went away mid-stream; generation stops and only emitted tokens are billed. */
public class ClientAbortedException extends RuntimeException {
    public ClientAbortedException(Throwable cause) {
        super("Client disconnected", cause);
    }
}
