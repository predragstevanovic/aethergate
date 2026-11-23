package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.domain.model.GuardrailAction;
import com.aethergate.domain.model.GuardrailType;
import com.aethergate.service.guardrail.GuardrailEngine;
import com.aethergate.service.guardrail.GuardrailEngine.CompiledRule;
import com.aethergate.service.guardrail.GuardrailEngine.Verdict;
import com.aethergate.service.llm.ChatMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuardrailEngineTest {

    private static CompiledRule rule(String name, GuardrailType type, GuardrailAction action, String pattern) {
        return GuardrailEngine.compile(UUID.randomUUID(), name, type, action, pattern);
    }

    private static List<ChatMessage> user(String text) {
        return List.of(new ChatMessage("system", "Be brief."), new ChatMessage("user", text));
    }

    @Test
    @DisplayName("keywords: case-insensitive, whole words only, regex metacharacters are literal")
    void keywords() {
        CompiledRule r = rule("Secrets", GuardrailType.KEYWORDS, GuardrailAction.REDACT, "Project Falcon, a.b*, confidential\n");
        Verdict v = GuardrailEngine.evaluate(List.of(r), user("CONFIDENTIAL: project falcon ships; a.b* and aXb are different; confidentiality is fine"));
        assertThat(v.blocked()).isFalse();
        assertThat(v.redactions()).isEqualTo(3);
        assertThat(v.messages().get(1).content())
                .isEqualTo("[REDACTED:Secrets]: [REDACTED:Secrets] ships; [REDACTED:Secrets] and aXb are different; confidentiality is fine");
        assertThat(v.messages().getFirst().content()).isEqualTo("Be brief.");
    }

    @Test
    @DisplayName("e-mail preset redacts every address; untouched text stays identical")
    void email() {
        CompiledRule r = rule("Emails", GuardrailType.PII_EMAIL, GuardrailAction.REDACT, null);
        Verdict v = GuardrailEngine.evaluate(List.of(r), user("Write to ana.k+billing@clinic.example.org or bob@x.io"));
        assertThat(v.redactions()).isEqualTo(2);
        assertThat(v.messages().get(1).content()).isEqualTo("Write to [REDACTED:Emails] or [REDACTED:Emails]");

        Verdict clean = GuardrailEngine.evaluate(List.of(r), user("no addresses here"));
        assertThat(clean.matches()).isEmpty();
        assertThat(clean.messages().get(1).content()).isEqualTo("no addresses here");
    }

    @Test
    @DisplayName("card preset: network prefix + Luhn (spaces/dashes allowed); order numbers and timestamps are not cards")
    void cardsUseLuhn() {
        CompiledRule r = rule("Cards", GuardrailType.PII_CARD, GuardrailAction.BLOCK, null);
        assertThat(GuardrailEngine.evaluate(List.of(r), user("pay with 4111 1111 1111 1111 please")).blocked()).isTrue();
        assertThat(GuardrailEngine.evaluate(List.of(r), user("card 5500-0000-0000-0004")).blocked()).isTrue();
        assertThat(GuardrailEngine.evaluate(List.of(r), user("order 1234 5678 9012 3456 shipped")).blocked()).isFalse();
        assertThat(GuardrailEngine.evaluate(List.of(r), user("call 555-0100 at 10")).blocked()).isFalse();
        // a Luhn-valid millisecond timestamp is not a card: real card numbers start with 2-6
        assertThat(GuardrailEngine.evaluate(List.of(r), user("trace 1795457353120 failed")).blocked()).isFalse();
        assertThat(GuardrailEngine.evaluate(List.of(r), user("amex 3782 822463 10005")).blocked()).isTrue();
    }

    @Test
    @DisplayName("BLOCK wins over REDACT: nothing is redacted and the originals are returned")
    void blockWins() {
        CompiledRule redact = rule("Emails", GuardrailType.PII_EMAIL, GuardrailAction.REDACT, null);
        CompiledRule block = rule("No passwords", GuardrailType.REGEX, GuardrailAction.BLOCK, "(?i)password\\s*[:=]\\s*\\S+");
        Verdict v = GuardrailEngine.evaluate(List.of(redact, block), user("me@x.io password: hunter2 and PASSWORD=abc"));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockedBy().ruleName()).isEqualTo("No passwords");
        assertThat(v.blockedBy().count()).isEqualTo(2);
        assertThat(v.redactions()).isZero();
        assertThat(v.messages().get(1).content()).contains("me@x.io");
    }

    @Test
    @DisplayName("catastrophic backtracking (ReDoS) is cut off by the time budget and fails closed")
    void redosFailsClosed() {
        CompiledRule evil = rule("Evil", GuardrailType.REGEX, GuardrailAction.REDACT, "(a+)+$");
        String attack = "a".repeat(5_000) + "!";
        long t0 = System.nanoTime();
        Verdict v = GuardrailEngine.evaluate(List.of(evil), user(attack));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockedBy().ruleName()).contains("timed out");
        assertThat(ms).isLessThan(2_000);
    }

    @Test
    @DisplayName("zero-length matches (lookarounds) never redact anything")
    void zeroLength() {
        CompiledRule r = rule("Lookahead", GuardrailType.REGEX, GuardrailAction.REDACT, "(?=x)");
        Verdict v = GuardrailEngine.evaluate(List.of(r), user("xxx"));
        assertThat(v.matches()).isEmpty();
        assertThat(v.messages().get(1).content()).isEqualTo("xxx");
    }

    @Test
    @DisplayName("invalid definitions are rejected at save time with a user-facing message")
    void validation() {
        assertThatThrownBy(() -> rule("r", GuardrailType.REGEX, GuardrailAction.BLOCK, "([a-z"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid regular expression");
        assertThatThrownBy(() -> rule("r", GuardrailType.REGEX, GuardrailAction.BLOCK, "a*"))
                .hasMessageContaining("empty string");
        assertThatThrownBy(() -> rule("r", GuardrailType.REGEX, GuardrailAction.BLOCK, " "))
                .hasMessageContaining("needs a pattern");
        assertThatThrownBy(() -> rule("r", GuardrailType.REGEX, GuardrailAction.BLOCK, "x".repeat(501)))
                .hasMessageContaining("at most 500");
        assertThatThrownBy(() -> rule("r", GuardrailType.KEYWORDS, GuardrailAction.BLOCK, " , \n "))
                .hasMessageContaining("at least one keyword");
        assertThatThrownBy(() -> rule("r", GuardrailType.KEYWORDS, GuardrailAction.BLOCK, null))
                .hasMessageContaining("at least one keyword");
        String tooMany = String.join(",", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "w" + i).toList());
        assertThatThrownBy(() -> rule("r", GuardrailType.KEYWORDS, GuardrailAction.BLOCK, tooMany))
                .hasMessageContaining("At most 50");
    }

    @Test
    @DisplayName("no rules → the messages pass through unchanged")
    void noRules() {
        Verdict v = GuardrailEngine.evaluate(List.of(), user("anything"));
        assertThat(v.blocked()).isFalse();
        assertThat(v.matches()).isEmpty();
        assertThat(v.redactions()).isZero();
    }
}
