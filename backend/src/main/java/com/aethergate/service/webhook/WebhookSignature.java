package com.aethergate.service.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe-style signatures: header {@code X-AetherGate-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>} over
 * {@code "<t>.<raw body>"}. The timestamp is signed too, so a captured request cannot be replayed later
 * (receivers reject a t older than ~5 minutes) and the body cannot be altered.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-AetherGate-Signature";
    public static final String SIGNING_KEY_PREFIX = "whsec_";
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSignature() {}

    public static String newSecret() {
        StringBuilder sb = new StringBuilder(SIGNING_KEY_PREFIX);
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static String header(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmac(secret, timestampSeconds + "." + body);
    }

    /** Receiver-side check (documented for customers, used by the tests): constant-time compare + tolerance window. */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t < 0 || v1 == null || Math.abs(nowSeconds - t) > toleranceSeconds) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII),
                v1.getBytes(StandardCharsets.US_ASCII));
    }

    static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
