package com.chatbot.agent.service.policy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which headers a REST tool may set, and what their values may contain.
 *
 * <p>Tool header values are built by substituting invocation parameters, and those parameters are
 * frequently chosen by a language model. Two consequences follow, and this class exists for both:
 *
 * <ul>
 *   <li>A value containing CR or LF splits the request, letting an argument inject headers or a
 *       second request entirely. Such values are rejected outright rather than sanitised, because
 *       silently stripping bytes hides the attempt.</li>
 *   <li>An unrestricted header name lets a tool definition attach {@code Authorization},
 *       {@code Cookie} or {@code Proxy-Authorization} to an outbound call - a credential-forwarding
 *       primitive. Names are therefore allowlisted, and the allowlist deliberately omits all three.</li>
 * </ul>
 */
public final class RestHeaderPolicy {

    private final Set<String> allowed;

    public RestHeaderPolicy(List<String> allowedHeaderNames) {
        Set<String> normalised = new LinkedHashSet<>();
        if (allowedHeaderNames != null) {
            for (String name : allowedHeaderNames) {
                if (name != null && !name.isBlank()) {
                    normalised.add(name.toLowerCase(Locale.ROOT).strip());
                }
            }
        }
        this.allowed = Set.copyOf(normalised);
    }

    /** @return true when a header of this name may be sent at all */
    public boolean isAllowed(String headerName) {
        return headerName != null
                && !headerName.isBlank()
                && allowed.contains(headerName.toLowerCase(Locale.ROOT).strip());
    }

    /**
     * CR, LF and NUL in a header name or value are request-splitting attempts, not data.
     */
    public static boolean containsControlCharacters(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }

    /**
     * @throws SecurityException if the name or value carries control characters
     */
    public void requireSafe(String name, String value) {
        if (containsControlCharacters(name) || containsControlCharacters(value)) {
            throw new SecurityException(
                    "Header '" + name + "' contains control characters after parameter "
                    + "substitution; refusing to send the request");
        }
    }
}
