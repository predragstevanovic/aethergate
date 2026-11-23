package com.aethergate.service.guardrail;

/** 422: a BLOCK guardrail matched the prompt. Thrown before any hold is taken, so the request costs nothing. */
public class GuardrailBlockedException extends RuntimeException {

    private final String rule;
    private final int matches;

    public GuardrailBlockedException(String rule, int matches) {
        super("Blocked by guardrail '" + rule + "' (" + matches + " match" + (matches == 1 ? "" : "es") + ")");
        this.rule = rule;
        this.matches = matches;
    }

    public String getRule() { return rule; }
    public int getMatches() { return matches; }
}
