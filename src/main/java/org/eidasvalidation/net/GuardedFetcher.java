package org.eidasvalidation.net;

import eu.europa.esig.dss.spi.client.http.DataLoader;
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The only component that talks to the network. Every URL a validator follows (CRL and OCSP
 * responders, AIA issuer links, trusted-list locations) goes through here.
 *
 * <p>Guards, in order:
 * <ol>
 *   <li>Scheme must be {@code http} or {@code https}.</li>
 *   <li>The host is resolved and every address is checked against {@link BlockedAddresses};
 *       the connection is then pinned to the vetted addresses, so a DNS answer that changes
 *       between check and connect (rebinding) cannot reach an internal host. Redirect targets
 *       are resolved through the same check.</li>
 *   <li>Connect, read and total timeouts from the {@link FetchPolicy}.</li>
 *   <li>A byte limit enforced on the declared length and again while streaming the body.</li>
 * </ol>
 *
 * <p>Failures surface as {@link DSSExternalResourceException}, which DSS treats as "resource
 * unavailable" and reports in the validation result instead of aborting.
 */
public final class GuardedFetcher implements DataLoader {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(GuardedFetcher.class);

    private final FetchPolicy policy;
    private String contentType;
    private transient CloseableHttpClient client;

    public GuardedFetcher(FetchPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** A fetcher pre-configured for OCSP requests, which DSS sends as POST bodies. */
    public static GuardedFetcher forOcsp(FetchPolicy policy) {
        GuardedFetcher fetcher = new GuardedFetcher(policy);
        fetcher.setContentType("application/ocsp-request");
        return fetcher;
    }

    public FetchPolicy policy() {
        return policy;
    }

    @Override
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public byte[] get(String url) {
        return execute(new HttpGet(vet(url)), url);
    }

    @Override
    public DataAndUrl get(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw new DSSExternalResourceException("No URL to fetch");
        }
        List<String> failures = new ArrayList<>();
        for (String url : urls) {
            try {
                return new DataAndUrl(url, get(url));
            } catch (DSSExternalResourceException e) {
                failures.add(url + ": " + e.getMessage());
            }
        }
        throw new DSSExternalResourceException("All locations failed: " + String.join("; ", failures));
    }

    @Override
    public byte[] post(String url, byte[] content) {
        HttpPost post = new HttpPost(vet(url));
        ContentType type = contentType == null ? ContentType.APPLICATION_OCTET_STREAM : ContentType.create(contentType);
        post.setEntity(new ByteArrayEntity(content, type));
        return execute(post, url);
    }

    // ---- guards -------------------------------------------------------------------------------

    private URI vet(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw blocked(url, "malformed URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw blocked(url, "scheme '" + scheme + "' is not allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw blocked(url, "no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw blocked(url, "credentials in URL");
        }
        return uri;
    }

    /** Resolves and filters; the HTTP client connects only to what this returns. */
    InetAddress[] resolveVetted(String host) throws UnknownHostException {
        InetAddress[] all = InetAddress.getAllByName(host);
        List<InetAddress> ok = new ArrayList<>();
        for (InetAddress a : all) {
            Optional<String> reason = BlockedAddresses.blockReason(a);
            if (reason.isEmpty()) {
                ok.add(a);
            } else if (a.isLoopbackAddress() && policy.allowLoopback()) {
                ok.add(a);
            } else if (policy.allowPrivate()) {
                ok.add(a);
            } else {
                LOG.warn("Refusing to connect to {} ({}): {}", host, a.getHostAddress(), reason.get());
            }
        }
        if (ok.isEmpty()) {
            throw new UnknownHostException(host + " resolves only to blocked addresses");
        }
        return ok.toArray(new InetAddress[0]);
    }

    private static DSSExternalResourceException blocked(String url, String reason) {
        LOG.warn("Blocked fetch of {}: {}", url, reason);
        return new DSSExternalResourceException("Blocked fetch of " + url + ": " + reason);
    }

    // ---- transport ----------------------------------------------------------------------------

    private byte[] execute(ClassicHttpRequest request, String url) {
        long deadline = System.nanoTime() + policy.totalTimeout().toNanos();
        try {
            return client().execute(request, response -> {
                int code = response.getCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return new byte[0];
                }
                long declared = entity.getContentLength();
                if (declared > policy.maxBytes()) {
                    throw new IOException("response declares " + declared + " bytes, limit is " + policy.maxBytes());
                }
                return readLimited(entity, deadline);
            });
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.debug("Fetch of {} failed: {}", url, reason);
            throw new DSSExternalResourceException("Unable to fetch " + url + ": " + reason, e);
        }
    }

    private byte[] readLimited(HttpEntity entity, long deadline) throws IOException {
        long limit = policy.maxBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = entity.getContent()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("total timeout of " + policy.totalTimeout().toSeconds() + "s exceeded");
                }
                if (out.size() + n > limit) {
                    throw new IOException("response exceeds limit of " + limit + " bytes");
                }
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }

    private synchronized CloseableHttpClient client() {
        if (client == null) {
            DnsResolver resolver = new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    return resolveVetted(host);
                }

                @Override
                public String resolveCanonicalHostname(String host) {
                    return host;
                }
            };
            ConnectionConfig connection = ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(policy.connectTimeout()))
                    .setSocketTimeout(Timeout.of(policy.readTimeout()))
                    .build();
            PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(resolver)
                    .setDefaultConnectionConfig(connection)
                    .build();
            RequestConfig requests = RequestConfig.custom()
                    .setResponseTimeout(Timeout.of(policy.readTimeout()))
                    .setRedirectsEnabled(policy.maxRedirects() > 0)
                    .setMaxRedirects(policy.maxRedirects())
                    .setCircularRedirectsAllowed(false)
                    .build();
            client = HttpClients.custom()
                    .setConnectionManager(pool)
                    .setDefaultRequestConfig(requests)
                    .setUserAgent(policy.userAgent())
                    .disableCookieManagement()
                    .disableAuthCaching()
                    .build();
        }
        return client;
    }
}
