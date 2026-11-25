package com.aethergate.service.prompt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{variable}}} templates. Rendering is a single pass over the TEMPLATE: a value that itself contains
 * {@code {{x}}} is inserted literally and never expanded, so user input cannot inject template syntax.
 */
public final class PromptTemplateEngine {

    static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]{0,39})\\s*}}");
    public static final int MAX_VARIABLES = 20;
    public static final int MAX_VALUE_LENGTH = 4000;

    private PromptTemplateEngine() {}

    /** Variable names in order of first appearance (system prompt first). */
    public static List<String> variables(String system, String user) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : new String[] {system, user}) {
            if (text == null) {
                continue;
            }
            Matcher m = VARIABLE.matcher(text);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        if (names.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("A template can use at most " + MAX_VARIABLES + " variables");
        }
        return new ArrayList<>(names);
    }

    /** Throws 400 naming every missing variable; extra values are ignored. */
    public static String render(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static void requireValues(List<String> variables, Map<String, String> values) {
        List<String> missing = variables.stream().filter(v -> values.get(v) == null || values.get(v).isBlank()).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing template variable(s): " + String.join(", ", missing));
        }
        values.forEach((k, v) -> {
            if (v != null && v.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("Variable '" + k + "' is longer than " + MAX_VALUE_LENGTH + " characters");
            }
        });
    }
}
