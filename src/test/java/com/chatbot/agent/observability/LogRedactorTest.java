package com.chatbot.agent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redaction before logging.
 *
 * <p>A credential logged once is a credential disclosed; logs are shipped, retained and widely
 * readable, and no later deletion undoes it. These tests exist so the guarantee is enforced rather
 * than intended.
 */
class LogRedactorTest {

    @ParameterizedTest
    @DisplayName("credential-bearing field names are masked")
    @ValueSource(strings = {"Authorization", "authorization", "X-Api-Key", "api_key", "apiKey",
            "password", "PASSWORD", "pwd", "secret", "token", "Cookie", "Set-Cookie",
            "proxy-authorization", "private-key", "credentials", "signature"})
    void sensitiveKeysAreMasked(String key) {
        var out = LogRedactor.redact(Map.of(key, "super-secret-value"));
        assertEquals(LogRedactor.MASK, out.get(key), key + " must be masked");
        assertFalse(out.toString().contains("super-secret-value"));
    }

    @Test
    @DisplayName("ordinary fields survive, so logs stay useful")
    void ordinaryFieldsAreKept() {
        var out = LogRedactor.redact(Map.of("runId", "abc-123", "nodeId", "charge", "attempt", 2));
        assertEquals("abc-123", out.get("runId"));
        assertEquals("charge", out.get("nodeId"));
        assertEquals(2, out.get("attempt"));
    }

    @ParameterizedTest
    @DisplayName("credential-shaped VALUES are masked even in an innocuous field")
    @ValueSource(strings = {
            "Bearer abcdefghijklmnop1234567890",
            "Basic dXNlcjpwYXNzd29yZA==",
            "sk_live_abcdefghij1234567890",
            "sk_test_abcdefghij1234567890",
            "ghp_abcdefghijklmnopqrstuvwxyz0123",
            "AKIAIOSFODNN7EXAMPLE",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
    })
    void sensitiveValueShapesAreMasked(String value) {
        // The field name is deliberately innocuous: key-based redaction alone would miss this.
        var out = LogRedactor.redact(Map.of("note", value));
        assertEquals(LogRedactor.MASK, out.get("note"), "value shape must be masked: " + value);
    }

    @Test
    @DisplayName("a PEM private key is masked in full, not partially")
    void pemKeyIsMasked() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...lines...\n-----END RSA PRIVATE KEY-----";
        String out = LogRedactor.redactText("config: " + pem);
        assertFalse(out.contains("MIIEow"), "no part of a private key may survive");
        assertTrue(out.contains(LogRedactor.MASK));
    }

    @Test
    @DisplayName("nested credentials are masked - one level down is just as disclosed")
    void nestedCredentialsAreMasked() {
        var out = LogRedactor.redact(Map.of(
                "request", Map.of("headers", Map.of("Authorization", "Bearer abcdefghijklmnop"),
                                  "path", "/v1/charges")));
        String rendered = out.toString();
        assertFalse(rendered.contains("abcdefghijklmnop"), rendered);
        assertTrue(rendered.contains("/v1/charges"), "non-sensitive context must survive");
    }

    @Test
    @DisplayName("credentials inside a list are masked")
    void listValuesAreMasked() {
        var out = LogRedactor.redact(Map.of("headers",
                List.of("Bearer abcdefghijklmnop1234", "Accept: application/json")));
        String rendered = out.toString();
        assertFalse(rendered.contains("abcdefghijklmnop1234"));
        assertTrue(rendered.contains("application/json"));
    }

    @Test
    @DisplayName("large but non-sensitive text is truncated rather than copied wholesale")
    void largeTextIsTruncated() {
        String doc = "x".repeat(5000);
        String out = LogRedactor.truncate(doc, 100);
        assertTrue(out.length() < 200);
        assertTrue(out.contains("truncated 4900 chars"));
    }

    @Test
    @DisplayName("null and empty inputs do not throw")
    void nullSafety() {
        assertNull(LogRedactor.redactText(null));
        assertEquals(Map.of(), LogRedactor.redact(null));
        assertNull(LogRedactor.truncate(null, 10));
        assertFalse(LogRedactor.isSensitiveKey(null));
    }

    @Test
    @DisplayName("NEGATIVE CONTROL: a value that is not a credential is not masked")
    void nonCredentialsAreNotMasked() {
        // If everything were masked, the tests above would pass while logs became useless.
        for (String benign : List.of("hello world", "customer-4711", "2026-08-31T10:00:00Z",
                "SELECT id FROM orders", "application/json")) {
            assertEquals(benign, LogRedactor.redactText(benign),
                    "over-redaction would make logs useless: " + benign);
        }
    }
}
