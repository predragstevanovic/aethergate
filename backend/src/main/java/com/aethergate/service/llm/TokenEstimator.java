package com.aethergate.service.llm;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic BPE-like token estimate (≈ cl100k on English prose, within ~10%).
 * Words are split into pieces of up to 6 characters (most common English words are one token), punctuation counts as one token, and each chat
 * message carries OpenAI's documented framing overhead. Used for pre-flight holds and for
 * providers that do not report usage.
 */
public final class TokenEstimator {

    private static final Pattern PIECES = Pattern.compile("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]");
    private static final int PER_MESSAGE_OVERHEAD = 4;
    private static final int REPLY_PRIMING = 3;

    private TokenEstimator() {}

    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        Matcher m = PIECES.matcher(text);
        while (m.find()) {
            int len = m.end() - m.start();
            tokens += (len + 5) / 6;
        }
        return tokens;
    }

    public static int countPrompt(List<ChatMessage> messages) {
        int total = REPLY_PRIMING;
        for (ChatMessage message : messages) {
            total += PER_MESSAGE_OVERHEAD + count(message.role()) + count(message.content());
        }
        return total;
    }
}
