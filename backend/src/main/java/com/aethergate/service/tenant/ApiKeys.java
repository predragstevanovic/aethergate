package com.aethergate.service.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API key format: {@code ag_live_} + 32 base62 characters (≈190 bits of entropy).
 * Only the SHA-256 hash is stored; with keys this random a per-key salt adds nothing
 * (there is no dictionary to precompute), which is the same trade-off GitHub and Stripe make.
 */
public final class ApiKeys {

    public static final String PREFIX = "ag_live_";
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Display prefix, e.g. {@code ag_live_8f92}. */
    public static String displayPrefix(String rawKey) {
        return rawKey.substring(0, Math.min(rawKey.length(), PREFIX.length() + 4));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean looksValid(String rawKey) {
        return rawKey != null && rawKey.startsWith(PREFIX) && rawKey.length() >= PREFIX.length() + 16 && rawKey.length() <= 128;
    }
}
