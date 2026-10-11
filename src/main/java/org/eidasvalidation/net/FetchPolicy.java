package org.eidasvalidation.net;

import java.time.Duration;

/**
 * Limits applied to every outbound fetch.
 *
 * @param connectTimeout  time allowed to establish a connection
 * @param readTimeout     time allowed to wait for the response or for each chunk of data
 * @param totalTimeout    wall-clock limit for the whole fetch, redirects included
 * @param maxBytes        largest response body accepted
 * @param maxRedirects    how many redirects to follow; each target is vetted like the first URL
 * @param allowLoopback   permit 127.0.0.0/8 and ::1 (tests only)
 * @param allowPrivate    permit private, link-local and other non-public ranges (tests only)
 * @param userAgent       sent with every request
 */
public record FetchPolicy(Duration connectTimeout,
                          Duration readTimeout,
                          Duration totalTimeout,
                          long maxBytes,
                          int maxRedirects,
                          boolean allowLoopback,
                          boolean allowPrivate,
                          String userAgent) {

    private static final String UA = "eidas-validator (+https://github.com/juraj-jumic/eidas-validator)";

    /** For CRL, OCSP and AIA fetches: URLs come from untrusted certificates, so keep it tight. */
    public static FetchPolicy revocation() {
        return new FetchPolicy(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
                4L * 1024 * 1024, 3, false, false, UA);
    }

    /** For trusted-list downloads: URLs come from the signed LOTL, lists can be several megabytes. */
    public static FetchPolicy trustedLists() {
        return new FetchPolicy(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120),
                32L * 1024 * 1024, 3, false, false, UA);
    }

    public FetchPolicy withMaxBytes(long bytes) {
        return new FetchPolicy(connectTimeout, readTimeout, totalTimeout, bytes, maxRedirects, allowLoopback,
                allowPrivate, userAgent);
    }

    public FetchPolicy withTimeouts(Duration connect, Duration read, Duration total) {
        return new FetchPolicy(connect, read, total, maxBytes, maxRedirects, allowLoopback, allowPrivate, userAgent);
    }

    /** Lifts the address restrictions; for tests against a local server only. */
    public FetchPolicy allowingLocalAddresses() {
        return new FetchPolicy(connectTimeout, readTimeout, totalTimeout, maxBytes, maxRedirects, true, true,
                userAgent);
    }

    /** Lifts only the loopback restriction, so tests can check that private targets stay blocked. */
    public FetchPolicy allowingLoopbackOnly() {
        return new FetchPolicy(connectTimeout, readTimeout, totalTimeout, maxBytes, maxRedirects, true, false,
                userAgent);
    }
}
