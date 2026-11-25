package com.aethergate.service.tenant;

/** 403: the API key is valid but its policy forbids this call (source network or model). */
public class KeyPolicyViolationException extends RuntimeException {

    private final String type;

    public KeyPolicyViolationException(String type, String message) {
        super(message);
        this.type = type;
    }

    /** Problem type slug: {@code ip-not-allowed} or {@code model-not-allowed}. */
    public String getType() { return type; }
}
