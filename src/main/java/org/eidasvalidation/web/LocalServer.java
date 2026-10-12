package org.eidasvalidation.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.HtmlReport;
import org.eidasvalidation.report.Report;
import org.eidasvalidation.report.ReportJson;
import org.eidasvalidation.service.ValidationService;
import org.eidasvalidation.service.ValidatorOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local web interface: an upload form and the HTML (or JSON) report, served on the loopback
 * interface only.
 *
 * <p>Defences for a single-user local tool: bound to 127.0.0.1; the Host header must name the
 * loopback host (blocks DNS-rebinding pages that reach a local port through a public name); a
 * present Origin header must match (blocks cross-site form posts from other pages); uploads are
 * capped in size, written to a private temporary file and deleted after the check; responses
 * carry a strict Content-Security-Policy and are never cached.
 *
 * <p>Pages are served in the language the browser asks for via {@code Accept-Language} when it
 * is supported, otherwise in the server's default language.
 */
public final class LocalServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalServer.class);
    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final HttpServer server;
    private final ExecutorService executor;
    private final ValidatorOptions options;
    private final Messages defaultMessages;
    private final Object serviceLock = new Object();
    private ValidationService service;

    public LocalServer(ValidatorOptions options, int port) throws IOException {
        this(options, port, Messages.english());
    }

    /**
     * @param options         trust configuration; the trusted lists are loaded on {@link #start()}
     * @param port            TCP port on 127.0.0.1, or 0 for an ephemeral one
     * @param defaultMessages language used when the browser does not ask for a supported one
     */
    public LocalServer(ValidatorOptions options, int port, Messages defaultMessages) throws IOException {
        this.options = options;
        this.defaultMessages = defaultMessages;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        this.executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /** Loads trust data, then starts accepting connections. */
    public void start() throws IOException {
        synchronized (serviceLock) {
            service = ValidationService.create(options);
        }
        server.start();
        LOG.info("Listening on {}", url());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ValidationService service() throws IOException {
        synchronized (serviceLock) {
            if (service.isStale()) {
                LOG.info("Trusted lists older than {}; refreshing", options.tlMaxAge());
                service = ValidationService.create(options);
            }
            return service;
        }
    }

    private Messages messagesFor(HttpExchange ex) {
        return Messages.matchingAcceptLanguage(ex.getRequestHeaders().getFirst("Accept-Language"))
                .orElse(defaultMessages);
    }

    // ---- routing ------------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        Messages m = messagesFor(ex);
        try {
            if (!loopbackHost(ex)) {
                send(ex, 403, "text/plain; charset=utf-8", "Forbidden: unexpected Host header.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") && method.equals("GET")) {
                send(ex, 200, "text/html; charset=utf-8", formPage(m).getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/validate") && method.equals("POST")) {
                handleValidate(ex, m);
            } else if (path.equals("/healthz") && method.equals("GET")) {
                send(ex, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/") || path.equals("/validate")) {
                ex.getResponseHeaders().set("Allow", path.equals("/") ? "GET" : "POST");
                send(ex, 405, "text/plain; charset=utf-8", "Method not allowed.".getBytes(StandardCharsets.UTF_8));
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "Not found.".getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            LOG.error("Unhandled error serving {}", ex.getRequestURI(), e);
            send(ex, 500, "text/html; charset=utf-8", errorPage(m, m.get("web.error.generic.h"),
                    m.get("web.error.generic.p")).getBytes(StandardCharsets.UTF_8));
        } finally {
            ex.close();
        }
    }

    private void handleValidate(HttpExchange ex, Messages m) throws IOException {
        if (!sameOrigin(ex)) {
            send(ex, 403, "text/plain; charset=utf-8", "Forbidden: cross-origin request.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String tooLarge = errorPage(m, m.get("web.error.tooLarge.h"),
                m.format("web.error.tooLarge.p", MAX_UPLOAD_BYTES / (1024 * 1024)));
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && Long.parseLong(declared) > MAX_UPLOAD_BYTES) {
            send(ex, 413, "text/html; charset=utf-8", tooLarge.getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = readLimited(ex.getRequestBody(), MAX_UPLOAD_BYTES);
        if (body == null) {
            send(ex, 413, "text/html; charset=utf-8", tooLarge.getBytes(StandardCharsets.UTF_8));
            return;
        }

        Map<String, MultipartForm.Part> form;
        try {
            form = MultipartForm.parse(ex.getRequestHeaders().getFirst("Content-Type"), body);
        } catch (IllegalArgumentException e) {
            send(ex, 400, "text/html; charset=utf-8", errorPage(m, m.get("web.error.badRequest.h"),
                    m.format("web.error.badRequest.p", e.getMessage())).getBytes(StandardCharsets.UTF_8));
            return;
        }
        MultipartForm.Part file = form.get("document");
        if (file == null || file.data().length == 0) {
            send(ex, 400, "text/html; charset=utf-8", errorPage(m, m.get("web.error.noFile.h"),
                    m.get("web.error.noFile.p")).getBytes(StandardCharsets.UTF_8));
            return;
        }
        boolean online = form.containsKey("online");
        boolean json = form.containsKey("format") && form.get("format").asString().equalsIgnoreCase("json")
                || acceptsJsonOnly(ex);
        String displayName = file.filename() == null || file.filename().isBlank() ? "upload.pdf" : file.filename();

        Path dir = Files.createTempDirectory("eidas-validator-");
        Path tmp = dir.resolve("document.pdf");
        try {
            Files.write(tmp, file.data());
            Report report = service().validate(tmp, displayName, Instant.now(), online, m);
            if (json) {
                send(ex, 200, "application/json; charset=utf-8", ReportJson.write(report).getBytes(StandardCharsets.UTF_8));
            } else {
                send(ex, 200, "text/html; charset=utf-8", HtmlReport.render(report).getBytes(StandardCharsets.UTF_8));
            }
        } catch (ValidationException e) {
            send(ex, 422, "text/html; charset=utf-8", errorPage(m, m.get("web.error.unreadable.h"),
                    m.format("web.error.unreadable.p", e.getMessage())).getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(dir);
        }
    }

    // ---- guards -------------------------------------------------------------------------------

    private boolean loopbackHost(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && !h.endsWith("]")) {
            h = h.substring(0, colon);
        }
        return LOOPBACK_HOSTS.contains(h);
    }

    private boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.equals("null")) {
            return origin == null; // "null" origin means a sandboxed or file: page; refuse it
        }
        try {
            URI o = new URI(origin);
            String host = o.getHost() == null ? "" : o.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("::1")) {
                host = "[::1]";
            }
            return "http".equals(o.getScheme()) && LOOPBACK_HOSTS.contains(host)
                    && (o.getPort() == port() || o.getPort() == -1 && port() == 80);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean acceptsJsonOnly(HttpExchange ex) {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        return accept != null && accept.contains("application/json") && !accept.contains("text/html");
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (out.size() + n > limit) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("Vary", "Accept-Language");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    // ---- pages --------------------------------------------------------------------------------

    private String formPage(Messages m) {
        String trust;
        synchronized (serviceLock) {
            trust = service.trust().describe(m);
        }
        boolean offline = options.offline();
        return """
                <!doctype html>
                <html lang="%s">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                :root { color-scheme: light dark; }
                html { font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
                main { max-width: 40rem; margin: 0 auto; padding: 2rem 1rem; }
                h1 { font-size: 1.6rem; }
                label { display: block; margin: 1rem 0 .25rem; font-weight: 600; }
                input[type=file] { display: block; width: 100%%; padding: .5rem 0; }
                .check { display: flex; gap: .5rem; align-items: flex-start; margin: 1rem 0; }
                .check label { margin: 0; font-weight: normal; }
                button { font: inherit; padding: .6rem 1.2rem; cursor: pointer; }
                button:focus, input:focus { outline: 3px solid #8a5a00; outline-offset: 2px; }
                .muted { opacity: .8; font-size: .95rem; }
                .trust { border-left: .4rem solid #999; padding: .5rem .75rem; margin-top: 2rem; font-size: .95rem; }
                </style>
                </head>
                <body>
                <main>
                <h1>%s</h1>
                <p>%s</p>
                <p class="muted">%s</p>
                <form method="post" action="/validate" enctype="multipart/form-data">
                  <label for="document">%s</label>
                  <input id="document" name="document" type="file" accept=".pdf,application/pdf" required>
                  <div class="check">
                    <input id="online" name="online" type="checkbox" value="1" %s>
                    <label for="online">%s%s</label>
                  </div>
                  <button type="submit">%s</button>
                </form>
                <section class="trust" aria-labelledby="trust-heading">
                  <h2 id="trust-heading" style="font-size:1rem;margin:0 0 .25rem">%s</h2>
                  <p style="margin:0">%s</p>
                </section>
                </main>
                </body>
                </html>
                """.formatted(
                HtmlReport.esc(m.tag()),
                HtmlReport.esc(m.get("web.title")),
                HtmlReport.esc(m.get("web.h1")),
                HtmlReport.esc(m.get("web.intro")),
                HtmlReport.esc(m.get("web.privacy")),
                HtmlReport.esc(m.get("web.file")),
                offline ? "disabled" : "checked",
                HtmlReport.esc(m.get("web.online")),
                offline ? HtmlReport.esc(m.get("web.online.unavailable")) : "",
                HtmlReport.esc(m.get("web.submit")),
                HtmlReport.esc(m.get("web.trust.h")),
                HtmlReport.esc(trust));
    }

    private static String errorPage(Messages m, String title, String text) {
        return """
                <!doctype html>
                <html lang="%s"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>:root{color-scheme:light dark} html{font:16px/1.5 system-ui,sans-serif} main{max-width:40rem;margin:0 auto;padding:2rem 1rem}</style>
                </head><body><main>
                <h1>%s</h1>
                <p>%s</p>
                <p><a href="/">%s</a></p>
                </main></body></html>
                """.formatted(HtmlReport.esc(m.tag()), HtmlReport.esc(title), HtmlReport.esc(title), HtmlReport.esc(text),
                HtmlReport.esc(m.get("web.back")));
    }
}
