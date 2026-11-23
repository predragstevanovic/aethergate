package com.aethergate.service.guardrail;

import com.aethergate.domain.model.GuardrailAction;
import com.aethergate.domain.model.GuardrailType;
import com.aethergate.service.llm.ChatMessage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Pure guardrail evaluation (no Spring, no I/O), so every rule is unit-testable.
 *
 * <p>Order: if any BLOCK rule matches, the request is rejected and nothing is redacted. Otherwise REDACT rules
 * mask their matches one after another. A user-supplied regex runs against a {@link DeadlineCharSequence}; if
 * it exceeds its time budget (catastrophic backtracking, "ReDoS"), the evaluation <em>fails closed</em>: the
 * request is blocked rather than let through unchecked.
 */
public final class GuardrailEngine {

    static final int MAX_PATTERN_LENGTH = 500;
    static final int MAX_KEYWORDS = 50;
    static final long DEFAULT_BUDGET_NANOS = 25_000_000L; // 25 ms per rule per message

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");

    private GuardrailEngine() {}

    /** A rule ready to run. {@code id} is null for dry runs of unsaved rules. */
    public record CompiledRule(UUID id, String name, GuardrailType type, GuardrailAction action, Pattern pattern) {}

    public record Match(UUID ruleId, String ruleName, GuardrailAction action, int count) {}

    /** {@code blockedBy} is set when a BLOCK rule fired; then {@code messages} are the originals. */
    public record Verdict(Match blockedBy, List<ChatMessage> messages, List<Match> matches) {
        public boolean blocked() {
            return blockedBy != null;
        }

        public int redactions() {
            return matches.stream().filter(m -> m.action() == GuardrailAction.REDACT).mapToInt(Match::count).sum();
        }
    }

    /** Validates and compiles a rule definition; throws {@link IllegalArgumentException} with a user-facing message. */
    public static CompiledRule compile(UUID id, String name, GuardrailType type, GuardrailAction action, String pattern) {
        Pattern compiled = switch (type) {
            case PII_EMAIL -> EMAIL;
            case PII_CARD -> CARD;
            case KEYWORDS -> keywords(pattern);
            case REGEX -> regex(pattern);
        };
        return new CompiledRule(id, name, type, action, compiled);
    }

    public static Verdict evaluate(List<CompiledRule> rules, List<ChatMessage> messages) {
        return evaluate(rules, messages, DEFAULT_BUDGET_NANOS);
    }

    static Verdict evaluate(List<CompiledRule> rules, List<ChatMessage> messages, long budgetNanos) {
        for (CompiledRule rule : rules) {
            if (rule.action() != GuardrailAction.BLOCK) {
                continue;
            }
            int hits = 0;
            for (ChatMessage m : messages) {
                try {
                    hits += count(rule, m.content(), budgetNanos);
                } catch (DeadlineCharSequence.TimeoutException e) {
                    hits += 1; // fail closed
                }
            }
            if (hits > 0) {
                Match blocked = new Match(rule.id(), rule.name(), GuardrailAction.BLOCK, hits);
                return new Verdict(blocked, messages, List.of(blocked));
            }
        }

        Map<CompiledRule, Integer> counts = new LinkedHashMap<>();
        List<ChatMessage> current = new ArrayList<>(messages);
        for (CompiledRule rule : rules) {
            if (rule.action() != GuardrailAction.REDACT) {
                continue;
            }
            for (int i = 0; i < current.size(); i++) {
                ChatMessage m = current.get(i);
                Redaction r;
                try {
                    r = redact(rule, m.content(), budgetNanos);
                } catch (DeadlineCharSequence.TimeoutException e) {
                    Match blocked = new Match(rule.id(), rule.name() + " (evaluation timed out)", GuardrailAction.BLOCK, 1);
                    return new Verdict(blocked, messages, List.of(blocked));
                }
                if (r.count() > 0) {
                    current.set(i, new ChatMessage(m.role(), r.text()));
                    counts.merge(rule, r.count(), Integer::sum);
                }
            }
        }
        List<Match> matches = counts.entrySet().stream()
                .map(e -> new Match(e.getKey().id(), e.getKey().name(), GuardrailAction.REDACT, e.getValue()))
                .toList();
        return new Verdict(null, List.copyOf(current), matches);
    }

    private record Redaction(String text, int count) {}

    private static int count(CompiledRule rule, String text, long budgetNanos) {
        Matcher m = rule.pattern().matcher(new DeadlineCharSequence(text, budgetNanos));
        int n = 0;
        while (m.find()) {
            if (m.end() > m.start() && accept(rule, m.group())) {
                n++;
            }
        }
        return n;
    }

    private static Redaction redact(CompiledRule rule, String text, long budgetNanos) {
        Matcher m = rule.pattern().matcher(new DeadlineCharSequence(text, budgetNanos));
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        int n = 0;
        while (m.find()) {
            if (m.end() == m.start() || !accept(rule, m.group())) {
                continue; // zero-length matches (lookarounds) never redact anything
            }
            out.append(text, last, m.start()).append("[REDACTED:").append(rule.name()).append(']');
            last = m.end();
            n++;
        }
        out.append(text, last, text.length());
        return new Redaction(out.toString(), n);
    }

    /**
     * Card candidates must start like a real card (major networks: 2-6, i.e. Mastercard, Amex/Diners, Visa,
     * Discover) AND pass the Luhn checksum. Without the prefix check ~10% of random 13-digit numbers, such as
     * millisecond timestamps (which start with 1), would look like cards.
     */
    private static boolean accept(CompiledRule rule, String candidate) {
        if (rule.type() != GuardrailType.PII_CARD) {
            return true;
        }
        String digits = candidate.replaceAll("[ -]", "");
        char first = digits.charAt(0);
        return first >= '2' && first <= '6' && luhn(digits);
    }

    static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static Pattern keywords(String pattern) {
        List<String> words = pattern == null ? List.of() : Arrays.stream(pattern.split("[,\\n]"))
                .map(String::strip).filter(w -> !w.isEmpty()).distinct().toList();
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Give at least one keyword (comma or newline separated)");
        }
        if (words.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("At most " + MAX_KEYWORDS + " keywords per rule");
        }
        String alternation = words.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static Pattern regex(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("A regex rule needs a pattern");
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("Pattern must be at most " + MAX_PATTERN_LENGTH + " characters");
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription());
        }
        if (compiled.matcher("").matches()) {
            throw new IllegalArgumentException("Pattern matches the empty string, so it would match everywhere");
        }
        return compiled;
    }
}
