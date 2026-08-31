package com.chatbot.agent.service.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSRF policy. Each case corresponds to a bypass the previous string-matching implementation
 * permitted (audit finding F-3).
 */
class SsrfGuardTest {

    /** Allowlist off, so these cases exercise the address checks rather than the allowlist. */
    private final SsrfGuard open = new SsrfGuard(false, List.of());

    private final SsrfGuard restricted = new SsrfGuard(true, List.of("api.github.com", "example.com"));

    // ---------------------------------------------------------------- schemes

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://127.0.0.1:11211/_stats",
            "ftp://example.com/x",
            "netdoc:///etc/passwd",
    })
    void nonHttpSchemesAreRejected(String url) {
        assertFalse(open.check(url).allowed(), url);
    }

    // ---------------------------------------------------------------- loopback and wildcard

    @ParameterizedTest
    @DisplayName("loopback in every spelling the old check missed")
    @ValueSource(strings = {
            "http://localhost/x",
            "http://LOCALHOST/x",
            "http://localhost./x",
            "http://127.0.0.1/x",
            "http://127.0.0.2/x",
            "http://[::1]/x",
            "http://0.0.0.0/x",
            // Numeric encodings. Blocked because the guard resolves the host and inspects the
            // resulting address rather than pattern-matching how it was spelled.
            "http://2130706433/x",   // 127.0.0.1 as a decimal integer
            "http://0x7f.0.0.1/x",   // hex - URI rejects it as a hostname
            "http://127.1/x",        // short form - likewise
    })
    void loopbackAndWildcardAreRejected(String url) {
        assertFalse(open.check(url).allowed(), url);
    }

    // ---------------------------------------------------------------- metadata

    @ParameterizedTest
    @DisplayName("cloud instance metadata - the highest-value SSRF target, previously unblocked")
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://169.254.170.2/v2/credentials",
            "http://100.100.100.200/latest/meta-data/",
    })
    void metadataEndpointsAreRejected(String url) {
        assertFalse(open.check(url).allowed(), url);
    }

    // ---------------------------------------------------------------- private ranges

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.5/x",
            "http://192.168.1.1/x",
            "http://172.16.0.1/x",
            "http://172.31.255.254/x",
            "http://100.64.0.1/x",
            "http://198.18.0.1/x",
            "http://[fc00::1]/x",
            "http://[fe80::1]/x",
    })
    void privateRangesAreRejected(String url) {
        assertFalse(open.check(url).allowed(), url);
    }

    // ---------------------------------------------------------------- URL shape

    @Test
    @DisplayName("credentials embedded in the URL are rejected")
    void userInfoRejected() {
        assertFalse(open.check("http://api.github.com@evil.test/x").allowed());
    }

    @Test
    void controlCharactersRejected() {
        assertFalse(open.check("http://example.com/x\r\nHost: evil.test").allowed());
    }

    @Test
    void malformedUrlsRejected() {
        assertFalse(open.check(null).allowed());
        assertFalse(open.check("").allowed());
        assertFalse(open.check("not a url").allowed());
        assertFalse(open.check("/relative/path").allowed());
    }

    // ---------------------------------------------------------------- allowlist boundaries

    @Test
    @DisplayName("allowlist matches on label boundaries, not endsWith")
    void allowlistBoundaries() {
        // The exact bypass the audit called out: endsWith("api.github.com") accepts this.
        assertFalse(restricted.hostMatchesAllowlist("notapi.github.com"));
        assertFalse(restricted.hostMatchesAllowlist("api.github.com.evil.test"));
        assertFalse(restricted.hostMatchesAllowlist("evilexample.com"));

        assertTrue(restricted.hostMatchesAllowlist("api.github.com"));
        assertTrue(restricted.hostMatchesAllowlist("foo.api.github.com"));
        assertTrue(restricted.hostMatchesAllowlist("example.com"));
    }

    @Test
    @DisplayName("the allowlist is ENFORCED, not merely logged")
    void allowlistIsEnforced() {
        SsrfGuard.Verdict v = restricted.check("https://raw.githubusercontent.com/x");
        assertFalse(v.allowed());
        assertEquals("HOST_NOT_ALLOWED", v.reason());
    }

    // ---------------------------------------------------------------- address classifier

    @Test
    void classifierCategorisesAddressesDirectly() throws Exception {
        assertEquals("loopback", open.forbiddenReason(InetAddress.getByName("127.0.0.1")));
        assertEquals("link-local", open.forbiddenReason(InetAddress.getByName("169.254.169.254")));
        assertEquals("private", open.forbiddenReason(InetAddress.getByName("10.1.2.3")));
        assertEquals("cgnat", open.forbiddenReason(InetAddress.getByName("100.64.0.1")));
        assertEquals("unique-local", open.forbiddenReason(InetAddress.getByName("fc00::1")));
        // A genuinely public address must pass, or the guard would block everything.
        assertNull(open.forbiddenReason(InetAddress.getByName("93.184.216.34")));
    }

    @Test
    @DisplayName("guard and HTTP client agree on how a host resolves, so parser differentials do not apply")
    void resolutionIsConsistentWithTheClient() throws Exception {
        // "0177.0.0.1" looks like octal for 127.0.0.1, and a libc-based client would read it that
        // way. Java's resolver reads it as decimal 177.0.0.1, so this guard permits it - and that is
        // correct, because the RestTemplate this guard protects uses the SAME resolver and will
        // therefore also contact 177.0.0.1, not loopback. The guard blocks by resolved address, so
        // it cannot disagree with the client about where a request is going.
        assertEquals("177.0.0.1", InetAddress.getByName("0177.0.0.1").getHostAddress());
        assertNull(open.forbiddenReason(InetAddress.getByName("0177.0.0.1")));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 does not smuggle a private address past the check")
    void ipv4MappedIsUnwrapped() throws Exception {
        assertNotNull(open.forbiddenReason(InetAddress.getByName("::ffff:127.0.0.1")));
        assertNotNull(open.forbiddenReason(InetAddress.getByName("::ffff:169.254.169.254")));
    }
}
