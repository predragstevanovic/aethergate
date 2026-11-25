package com.aethergate.service.webhook;

/** A delivery was refused by the SSRF guard (recorded on the delivery, never thrown to an HTTP caller). */
public class WebhookBlockedException extends RuntimeException {
    public WebhookBlockedException(String message) {
        super(message);
    }
}
