package com.chatbot.agent.service.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REST header policy. Covers audit finding F-4: parameters were substituted into header values with
 * no validation at all, so a model-chosen argument could inject headers or split the request.
 */
class RestHeaderPolicyTest {

    private final RestHeaderPolicy policy =
            new RestHeaderPolicy(List.of("accept", "content-type", "user-agent"));

    @Test
    @DisplayName("allowlisted names pass, case-insensitively")
    void allowlistedNamesPass() {
        assertTrue(policy.isAllowed("Accept"));
        assertTrue(policy.isAllowed("CONTENT-TYPE"));
        assertTrue(policy.isAllowed("  user-agent  "));
    }

    @Test
    @DisplayName("credential-bearing headers are not allowlisted by default")
    void credentialHeadersRejected() {
        assertFalse(policy.isAllowed("Authorization"));
        assertFalse(policy.isAllowed("Cookie"));
        assertFalse(policy.isAllowed("Proxy-Authorization"));
        assertFalse(policy.isAllowed("X-Api-Key"));
    }

    @Test
    void blankAndNullNamesRejected() {
        assertFalse(policy.isAllowed(null));
        assertFalse(policy.isAllowed(""));
        assertFalse(policy.isAllowed("   "));
    }

    @Test
    @DisplayName("CR/LF in a value is rejected, not silently stripped")
    void crlfInValueRejected() {
        String injected = "application/json" + (char) 13 + (char) 10 + "X-Injected: yes";
        SecurityException e = assertThrows(SecurityException.class,
                () -> policy.requireSafe("content-type", injected));
        assertTrue(e.getMessage().contains("control characters"));
    }

    @Test
    void bareLineFeedAndNulRejected() {
        assertThrows(SecurityException.class,
                () -> policy.requireSafe("accept", "a" + (char) 10 + "b"));
        assertThrows(SecurityException.class,
                () -> policy.requireSafe("accept", "a" + (char) 0 + "b"));
        assertThrows(SecurityException.class,
                () -> policy.requireSafe("acc" + (char) 13 + "ept", "ok"));
    }

    @Test
    void ordinaryValuesAreAccepted() {
        assertDoesNotThrow(() -> policy.requireSafe("content-type", "application/json"));
        assertDoesNotThrow(() -> policy.requireSafe("accept", "*/*"));
        assertFalse(RestHeaderPolicy.containsControlCharacters("perfectly normal; charset=utf-8"));
    }
}
