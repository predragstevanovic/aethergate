package com.aethergate.domain.model;

/** What a guardrail looks for. Presets carry no pattern; KEYWORDS and REGEX do. */
public enum GuardrailType {
    /** Comma- or newline-separated words, matched case-insensitively on word boundaries. */
    KEYWORDS,
    /** A Java regular expression (length-capped and evaluated under a time budget). */
    REGEX,
    /** E-mail addresses. */
    PII_EMAIL,
    /** Payment card numbers (13-19 digits, Luhn-validated to avoid false positives). */
    PII_CARD;

    public boolean needsPattern() {
        return this == KEYWORDS || this == REGEX;
    }
}
