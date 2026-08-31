package com.chatbot.agent.observability;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Removes credentials and sensitive values before anything reaches a log.
 *
 * <p>Logs travel further than the data they describe: they are shipped to aggregators, retained for
 * months, and readable by more people than the database is. A credential logged once is a credential
 * disclosed, and no later deletion undoes it.
 *
 * <p>Redaction is by key AND by value shape. Key-based alone misses a token that arrives in a field
 * called {@code note}; value-based alone misses a short password that looks like ordinary text.
 * Neither is sufficient, so both run.
 */
public final class LogRedactor {

    public static final String MASK = "[REDACTED]";

    /** Field names whose values are never safe to log, matched case-insensitively as substrings. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "auth", "cookie", "set-cookie", "proxy-authorization",
            "password", "passwd", "pwd", "secret", "token", "api-key", "apikey",
            "api_key", "access-key", "accesskey", "private-key", "privatekey",
            "credential", "credentials", "session", "bearer", "x-api-key", "signature");

    /** Value shapes that are credentials regardless of the field they arrive in. */
    private static final List<Pattern> SENSITIVE_VALUES = List.of(
            Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}=*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bBasic\\s+[A-Za-z0-9+/]{8,}=*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(sk|pk)_(live|test)_[A-Za-z0-9]{8,}"),
            Pattern.compile("\\bghp_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    private LogRedactor() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.stream().anyMatch(lower::contains);
    }

    /** Mask credential-shaped substrings in free text. */
    public static String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Pattern p : SENSITIVE_VALUES) {
            out = p.matcher(out).replaceAll(MASK);
        }
        return out;
    }

    /**
     * Redact a structured payload, recursively.
     *
     * <p>Nested maps are traversed because a credential one level down is exactly as disclosed as
     * one at the top.
     */
    public static Map<String, Object> redact(Map<String, Object> input) {
        if (input == null) {
            return Map.of();
        }
        Map<String, Object> out = new TreeMap<>();
        input.forEach((key, value) -> out.put(key, redactValue(key, value)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return MASK;
        }
        if (value instanceof String s) {
            return redactText(s);
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> converted = new TreeMap<>();
            nested.forEach((k, v) -> converted.put(String.valueOf(k), v));
            return redact(converted);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> redactValue(key, v)).toList();
        }
        return value;
    }

    /**
     * Truncate a value that is safe but potentially large.
     *
     * <p>Retrieved document text is not a credential, but logging it in full copies the corpus into
     * the log store, where it is neither access-controlled nor retention-managed the same way.
     */
    public static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...[truncated " + (text.length() - maxChars) + " chars]";
    }
}
