package com.chatbot.agent.service.policy;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Outbound request policy for REST tools.
 *
 * <p>Replaces a set of string heuristics that missed, among others, the cloud instance-metadata
 * address - the single most valuable SSRF target on any cloud host, since it hands out the
 * machine's role credentials. The previous check also compared hostnames with
 * {@code endsWith("api.github.com")}, which {@code notapi.github.com} satisfies.
 *
 * <p>This version parses the URL, resolves the hostname, and inspects <em>every</em> address it
 * resolves to. Blocking is based on the resolved address, not on how the host was spelled, so
 * decimal, octal, hex and IPv6-mapped encodings of a private address are all caught by the same
 * check rather than needing a rule each.
 *
 * <h2>Known limitation: DNS rebinding</h2>
 * <p>This resolves the name, then the HTTP client resolves it again when it connects. A name that
 * returns a public address to the first lookup and a private one to the second defeats the check.
 * Closing that gap properly requires pinning the validated address into the connection itself via a
 * custom socket factory. That is not implemented here and is recorded as a known limitation rather
 * than papered over - see docs/security/SANDBOX_SECURITY_REPORT.md.
 */
@Slf4j
public final class SsrfGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** Cloud instance metadata. Present on AWS, Azure and GCP; hands out role credentials. */
    private static final Set<String> METADATA_ADDRESSES = Set.of(
            "169.254.169.254",  // AWS / Azure / OpenStack IMDS
            "169.254.170.2",    // AWS ECS task metadata
            "100.100.100.200",  // Alibaba Cloud
            "fd00:ec2::254"     // AWS IMDS over IPv6
    );

    private final boolean enforceAllowlist;
    private final List<String> allowedHosts;

    public SsrfGuard(boolean enforceAllowlist, List<String> allowedHosts) {
        this.enforceAllowlist = enforceAllowlist;
        this.allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream()
                .map(h -> h.toLowerCase(Locale.ROOT).strip())
                .filter(h -> !h.isEmpty())
                .toList();
    }

    public record Verdict(boolean allowed, String reason, String detail) {
        static Verdict ok() {
            return new Verdict(true, "ALLOWED", "allowed");
        }

        static Verdict deny(String reason, String detail) {
            return new Verdict(false, reason, detail);
        }
    }

    public Verdict check(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Verdict.deny("MALFORMED_URL", "URL is empty");
        }

        // Control characters in a URL are request-splitting attempts, not addresses.
        if (rawUrl.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            return Verdict.deny("MALFORMED_URL", "URL contains control characters");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (Exception e) {
            return Verdict.deny("MALFORMED_URL", "URL could not be parsed");
        }

        if (!uri.isAbsolute()) {
            return Verdict.deny("MALFORMED_URL", "URL is not absolute");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            // Catches file:, gopher:, jar:, netdoc: and the rest of the SSRF-adjacent scheme zoo.
            return Verdict.deny("FORBIDDEN_SCHEME", "Scheme not permitted: " + scheme);
        }

        // Credentials in the URL are used both to smuggle secrets outward and to confuse
        // host parsing (https://trusted.example@evil.test/).
        if (uri.getUserInfo() != null) {
            return Verdict.deny("CREDENTIALS_IN_URL", "URL must not contain userinfo");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Verdict.deny("MALFORMED_URL", "URL has no host");
        }

        // Normalise: case, and the trailing dot that makes "localhost." a distinct string but the
        // same name.
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        String bracketless = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;

        if (METADATA_ADDRESSES.contains(bracketless)) {
            return Verdict.deny("METADATA_ENDPOINT", "Cloud instance metadata endpoint is blocked");
        }

        // Allowlist, when enforced, is checked on the name before resolution.
        if (enforceAllowlist && !hostMatchesAllowlist(host)) {
            return Verdict.deny("HOST_NOT_ALLOWED", "Host is not in the outbound allowlist");
        }

        // Resolve and inspect every address. ANY forbidden address denies the request: a name with
        // both a public and a private A record must not be reachable.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(bracketless);
        } catch (UnknownHostException e) {
            return Verdict.deny("UNRESOLVABLE_HOST", "Host could not be resolved");
        }

        for (InetAddress address : addresses) {
            String denial = forbiddenReason(address);
            if (denial != null) {
                // The resolved address is deliberately not echoed back to the caller: that would
                // turn this guard into an internal-network scanner with a helpful error message.
                log.warn("SSRF blocked: host={} resolved to a {} address", host, denial);
                return Verdict.deny("PRIVATE_ADDRESS", "Host resolves to a non-public address (" + denial + ")");
            }
        }

        return Verdict.ok();
    }

    /**
     * Allowlist match on hostname label boundaries.
     *
     * <p>{@code api.github.com} matches itself and {@code foo.api.github.com}, but NOT
     * {@code notapi.github.com} - which a naive {@code endsWith} would have accepted.
     */
    boolean hostMatchesAllowlist(String host) {
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return a short category when the address must not be contacted, otherwise null
     */
    String forbiddenReason(InetAddress address) {
        if (address.isLoopbackAddress()) return "loopback";
        if (address.isAnyLocalAddress()) return "wildcard";
        if (address.isLinkLocalAddress()) return "link-local";   // 169.254/16, fe80::/10
        if (address.isSiteLocalAddress()) return "private";      // 10/8, 172.16/12, 192.168/16
        if (address.isMulticastAddress()) return "multicast";

        byte[] b = address.getAddress();

        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xFF, a1 = b[1] & 0xFF;
            // 100.64.0.0/10 carrier-grade NAT
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return "cgnat";
            // 192.0.0.0/24 IETF protocol assignments
            if (a0 == 192 && a1 == 0 && (b[2] & 0xFF) == 0) return "reserved";
            // 198.18.0.0/15 benchmarking
            if (a0 == 198 && (a1 == 18 || a1 == 19)) return "benchmark";
            // 240.0.0.0/4 reserved, includes 255.255.255.255
            if (a0 >= 240) return "reserved";
        }

        if (address instanceof Inet6Address) {
            // fc00::/7 unique local addresses
            if ((b[0] & 0xFE) == 0xFC) return "unique-local";
            // ::ffff:0:0/96 IPv4-mapped - re-check the embedded IPv4 address
            if (((Inet6Address) address).isIPv4CompatibleAddress()) return "ipv4-compatible";
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { mapped = false; break; }
            }
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    InetAddress embedded = InetAddress.getByAddress(
                            new byte[]{b[12], b[13], b[14], b[15]});
                    String nested = forbiddenReason(embedded);
                    if (nested != null) return "ipv4-mapped-" + nested;
                } catch (UnknownHostException ignored) {
                    return "ipv4-mapped-unknown";
                }
            }
        }

        return null;
    }
}
