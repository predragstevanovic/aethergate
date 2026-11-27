package com.aethergate.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Who and what a request is for: the caller's end user, the agent session (one "run" made of many calls) and
 * free-form cost-allocation tags such as {@code feature=search, env=prod}. Every dimension is optional and is
 * written to both the request log (analytics) and the ledger (caps), so spend can be shown back per dimension.
 *
 * <p>Sources, first wins: {@code X-AetherGate-User} / {@code X-AetherGate-Session} / {@code X-AetherGate-Tags}
 * headers, then the OpenAI-compatible body fields {@code user} and {@code metadata}.
 */
public record RequestAttribution(String endUserId, String sessionId, Map<String, String> tags) {

    public static final RequestAttribution NONE = new RequestAttribution(null, null, Map.of());
    public static final int MAX_TAGS = 10;
    /** Tag keys reserved for metadata fields with their own column. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("user", "session_id", "end_user", "session");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@/+=-]{1,128}");
    private static final Pattern TAG_KEY = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,39}");
    private static final Pattern TAG_VALUE = Pattern.compile("[A-Za-z0-9 _.:/@+=-]{1,80}");

    public RequestAttribution {
        endUserId = normalizeId(endUserId, "end user");
        sessionId = normalizeId(sessionId, "session");
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public boolean isEmpty() {
        return endUserId == null && sessionId == null && tags.isEmpty();
    }

    /**
     * Merges the header values over the body values. {@code metadata.session_id} is accepted as the session id,
     * every other metadata entry becomes a tag. Validation errors are 400s with the offending entry named.
     */
    public static RequestAttribution resolve(String userHeader, String sessionHeader, String tagsHeader,
                                             String bodyUser, Map<String, String> bodyMetadata) {
        Map<String, String> tags = new LinkedHashMap<>();
        String bodySession = null;
        if (bodyMetadata != null) {
            for (Map.Entry<String, String> e : bodyMetadata.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().strip().toLowerCase(Locale.ROOT);
                if ("session_id".equals(key) || "session".equals(key)) {
                    bodySession = e.getValue();
                } else if ("user".equals(key) || "end_user".equals(key)) {
                    bodyUser = bodyUser == null ? e.getValue() : bodyUser;
                } else {
                    putTag(tags, key, e.getValue());
                }
            }
        }
        if (tagsHeader != null && !tagsHeader.isBlank()) {
            for (String pair : tagsHeader.split(",")) {
                if (pair.isBlank()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("X-AetherGate-Tags entries must be key=value; got '" + pair.strip() + "'");
                }
                putTag(tags, pair.substring(0, eq).strip().toLowerCase(Locale.ROOT), pair.substring(eq + 1));
            }
        }
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("At most " + MAX_TAGS + " cost tags per request");
        }
        return new RequestAttribution(firstNonBlank(userHeader, bodyUser), firstNonBlank(sessionHeader, bodySession), tags);
    }

    private static void putTag(Map<String, String> tags, String key, String rawValue) {
        String value = rawValue == null ? "" : rawValue.strip();
        if (!TAG_KEY.matcher(key).matches() || RESERVED.contains(key)) {
            throw new IllegalArgumentException("Invalid cost tag key '" + key + "': lower-case letters, digits, _ . - (max 40)");
        }
        if (!TAG_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid value for cost tag '" + key + "': 1-80 letters, digits, spaces or _ . : / @ + = -");
        }
        tags.put(key, value);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String normalizeId(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip();
        if (!ID.matcher(v).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " id: 1-128 letters, digits or . _ : @ / + = -");
        }
        return v;
    }
}
