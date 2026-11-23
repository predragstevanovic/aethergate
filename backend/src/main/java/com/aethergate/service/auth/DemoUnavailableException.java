package com.aethergate.service.auth;

/** Demo login cannot be served right now → HTTP 503. */
public class DemoUnavailableException extends RuntimeException {
    public DemoUnavailableException(String message) {
        super(message);
    }
}
