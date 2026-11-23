package com.aethergate.web;

/** Optimistic-concurrency helpers shared by versioned resources (projects, guardrails). */
final class IfMatch {

    private IfMatch() {}

    static String etag(long version) {
        return "\"" + version + "\"";
    }

    /** Accepts {@code "3"}, {@code W/"3"} or a bare {@code 3}; missing → 428, garbage → 400. */
    static long parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        String v = ifMatch.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be the ETag returned by the API, e.g. \"3\"");
        }
    }
}
