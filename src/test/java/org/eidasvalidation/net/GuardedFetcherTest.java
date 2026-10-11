package org.eidasvalidation.net;

import com.sun.net.httpserver.HttpServer;
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against a loopback HTTP server; no external network. */
class GuardedFetcherTest {

    static HttpServer server;
    static String base;
    static final AtomicInteger privateTargetHits = new AtomicInteger();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/small", ex -> respond(ex, 200, "hello".getBytes(StandardCharsets.US_ASCII)));
        server.createContext("/big", ex -> respond(ex, 200, new byte[200 * 1024]));
        server.createContext("/chunked-big", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked: no declared length
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = new byte[8 * 1024];
                for (int i = 0; i < 40; i++) {
                    out.write(chunk);
                }
            }
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "late".getBytes(StandardCharsets.US_ASCII));
        });
        server.createContext("/to-private", ex -> {
            ex.getResponseHeaders().add("Location", "http://10.255.255.1/secret");
            respond(ex, 302, new byte[0]);
        });
        server.createContext("/to-small", ex -> {
            ex.getResponseHeaders().add("Location", base + "/small");
            respond(ex, 302, new byte[0]);
        });
        server.createContext("/echo", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            respond(ex, 200, (type + "|" + new String(body, StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII));
        });
        server.createContext("/missing", ex -> respond(ex, 404, new byte[0]));
        // Handlers run serially on the dispatcher thread by default; a lingering /slow handler
        // would then stall the next test's request.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static FetchPolicy local() {
        return FetchPolicy.revocation().allowingLoopbackOnly()
                .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    @Test
    void defaultPolicyRefusesLoopback() {
        GuardedFetcher f = new GuardedFetcher(FetchPolicy.revocation());
        DSSExternalResourceException e = assertThrows(DSSExternalResourceException.class, () -> f.get(base + "/small"));
        assertTrue(e.getMessage().contains("blocked"), e.getMessage());
    }

    @Test
    void refusesNonHttpSchemes() {
        GuardedFetcher f = new GuardedFetcher(local());
        assertThrows(DSSExternalResourceException.class, () -> f.get("ldap://ldap.example/cn=crl"));
        assertThrows(DSSExternalResourceException.class, () -> f.get("file:///etc/passwd"));
        assertThrows(DSSExternalResourceException.class, () -> f.get("http://user:pw@127.0.0.1/x"));
    }

    @Test
    void fetchesSmallBody() {
        GuardedFetcher f = new GuardedFetcher(local());
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), f.get(base + "/small"));
    }

    @Test
    void rejectsDeclaredOversize() {
        GuardedFetcher f = new GuardedFetcher(local().withMaxBytes(100 * 1024));
        DSSExternalResourceException e = assertThrows(DSSExternalResourceException.class, () -> f.get(base + "/big"));
        assertTrue(e.getMessage().contains("limit"), e.getMessage());
    }

    @Test
    void rejectsStreamedOversize() {
        GuardedFetcher f = new GuardedFetcher(local().withMaxBytes(100 * 1024));
        DSSExternalResourceException e = assertThrows(DSSExternalResourceException.class,
                () -> f.get(base + "/chunked-big"));
        assertTrue(e.getMessage().contains("limit"), e.getMessage());
    }

    @Test
    void timesOutOnSlowServer() {
        GuardedFetcher f = new GuardedFetcher(local());
        assertThrows(DSSExternalResourceException.class, () -> f.get(base + "/slow"));
    }

    @Test
    void redirectToPrivateAddressIsBlocked() {
        GuardedFetcher f = new GuardedFetcher(local());
        DSSExternalResourceException e = assertThrows(DSSExternalResourceException.class,
                () -> f.get(base + "/to-private"));
        assertTrue(e.getMessage().contains("blocked") || e.getMessage().contains("10.255.255.1"), e.getMessage());
        assertEquals(0, privateTargetHits.get());
    }

    @Test
    void redirectToAllowedAddressIsFollowed() {
        GuardedFetcher f = new GuardedFetcher(local());
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), f.get(base + "/to-small"));
    }

    @Test
    void postSendsContentType() {
        GuardedFetcher f = GuardedFetcher.forOcsp(local());
        byte[] reply = f.post(base + "/echo", "req".getBytes(StandardCharsets.US_ASCII));
        assertEquals("application/ocsp-request|req", new String(reply, StandardCharsets.US_ASCII));
    }

    @Test
    void multipleUrlsReturnFirstSuccess() {
        GuardedFetcher f = new GuardedFetcher(local());
        var result = f.get(List.of(base + "/missing", "http://10.1.1.1/x", base + "/small"));
        assertEquals(base + "/small", result.getUrlString());
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), result.getData());
    }

    @Test
    void httpErrorIsReportedNotSwallowed() {
        GuardedFetcher f = new GuardedFetcher(local());
        DSSExternalResourceException e = assertThrows(DSSExternalResourceException.class, () -> f.get(base + "/missing"));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }
}
