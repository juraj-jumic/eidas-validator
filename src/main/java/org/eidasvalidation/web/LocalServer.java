package org.eidasvalidation.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.HtmlReport;
import org.eidasvalidation.report.Report;
import org.eidasvalidation.report.ReportJson;
import org.eidasvalidation.report.Theme;
import org.eidasvalidation.sandbox.SandboxException;
import org.eidasvalidation.service.ValidationService;
import org.eidasvalidation.service.Validator;
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
 * <p>Defences for a single-user local tool: bound to 127.0.0.1 by default; the Host header must
 * name the loopback host or an explicitly allowed name (blocks DNS-rebinding pages that reach a
 * local port through a public name); a present Origin header must match the same names (blocks
 * cross-site form posts from other pages); uploads are capped in size, written to a private
 * temporary file and deleted after the check; responses carry a strict Content-Security-Policy
 * and are never cached. There is no authentication: when bound to a non-loopback address, as in
 * a container, expose it only through a loopback port mapping or an authenticating proxy.
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
    private final InetAddress bind;
    /** Host names (lower-case, without port) accepted in Host and Origin besides the loopback names. */
    private final Set<String> allowedHosts;
    private final ValidatorFactory factory;
    private final Object serviceLock = new Object();
    private Validator validator;

    /** Creates the validator the server uses; called at start and whenever trust data is stale. */
    @FunctionalInterface
    public interface ValidatorFactory {
        Validator create() throws IOException;
    }

    public LocalServer(ValidatorOptions options, int port) throws IOException {
        this(options, port, Messages.english());
    }

    /** Loopback only, no extra host names. */
    public LocalServer(ValidatorOptions options, int port, Messages defaultMessages) throws IOException {
        this(options, InetAddress.getLoopbackAddress(), port, Set.of(), defaultMessages);
    }

    /**
     * @param options         trust configuration; the trusted lists are loaded on {@link #start()}
     * @param bind            address to listen on; loopback for local use, {@code 0.0.0.0} inside a container
     * @param port            TCP port, or 0 for an ephemeral one
     * @param allowedHosts    host names (optionally with {@code :port}) accepted in Host and Origin
     *                        in addition to the loopback names, e.g. a reverse proxy's name
     * @param defaultMessages language used when the browser does not ask for a supported one
     */
    public LocalServer(ValidatorOptions options, InetAddress bind, int port, Set<String> allowedHosts,
                       Messages defaultMessages) throws IOException {
        this(options, bind, port, allowedHosts, defaultMessages, () -> ValidationService.create(options));
    }

    /**
     * @param factory how to create the validator: in-process ({@link ValidationService}) or a
     *                supervised worker ({@link org.eidasvalidation.sandbox.SandboxedValidator})
     */
    public LocalServer(ValidatorOptions options, InetAddress bind, int port, Set<String> allowedHosts,
                       Messages defaultMessages, ValidatorFactory factory) throws IOException {
        this.options = options;
        this.defaultMessages = defaultMessages;
        this.factory = factory;
        this.bind = bind;
        this.allowedHosts = allowedHosts.stream().map(LocalServer::hostOnly).collect(java.util.stream.Collectors.toSet());
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        this.executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /** Lower-cases a host[:port] value and strips the port; IPv6 literals keep their brackets. */
    private static String hostOnly(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && !h.endsWith("]") && h.indexOf(':') == colon) {
            h = h.substring(0, colon);
        } else if (h.startsWith("[") && h.contains("]:")) {
            h = h.substring(0, h.indexOf("]:") + 1);
        }
        return h;
    }

    private boolean hostAllowed(String host) {
        return LOOPBACK_HOSTS.contains(host) || allowedHosts.contains(host);
    }

    /** Loads trust data, then starts accepting connections. */
    public void start() throws IOException {
        synchronized (serviceLock) {
            validator = factory.create();
        }
        server.start();
        LOG.info("Listening on {}", url());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        String host = bind.isAnyLocalAddress() || bind.isLoopbackAddress() ? "127.0.0.1"
                : bind instanceof java.net.Inet6Address ? "[" + bind.getHostAddress() + "]" : bind.getHostAddress();
        return "http://" + host + ":" + port() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
        synchronized (serviceLock) {
            if (validator != null) {
                validator.close();
            }
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Validator validator() throws IOException {
        synchronized (serviceLock) {
            if (validator.isStale()) {
                LOG.info("Trusted lists older than {}; refreshing", options.tlMaxAge());
                Validator fresh = factory.create();
                validator.close();
                validator = fresh;
            }
            return validator;
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
            Report report = validator().validate(tmp, displayName, Instant.now(), online, m);
            if (json) {
                send(ex, 200, "application/json; charset=utf-8", ReportJson.write(report).getBytes(StandardCharsets.UTF_8));
            } else {
                send(ex, 200, "text/html; charset=utf-8", HtmlReport.render(report).getBytes(StandardCharsets.UTF_8));
            }
        } catch (SandboxException e) {
            send(ex, 422, "text/html; charset=utf-8", errorPage(m, m.get("web.error.sandbox.h"),
                    m.format("web.error.sandbox.p", e.getMessage())).getBytes(StandardCharsets.UTF_8));
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
        return host != null && hostAllowed(hostOnly(host));
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
            boolean loopback = LOOPBACK_HOSTS.contains(host);
            if (loopback) {
                return "http".equals(o.getScheme()) && (o.getPort() == port() || o.getPort() == -1 && port() == 80);
            }
            // A reverse proxy may terminate TLS and use its own port; the name is what we check.
            return ("http".equals(o.getScheme()) || "https".equals(o.getScheme())) && allowedHosts.contains(host);
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
            trust = validator.trust().describe(m);
        }
        boolean offline = options.offline();
        StringBuilder h = new StringBuilder(8 * 1024);
        h.append(Theme.head(m, m.get("web.title"))).append("</head>\n<body>\n").append(Theme.topbar(m));
        h.append("<main>\n<h1>").append(HtmlReport.esc(m.get("web.h1"))).append("</h1>\n<p class=\"lead\">")
         .append(HtmlReport.esc(m.get("web.intro"))).append("</p>\n");
        h.append("<form class=\"card\" method=\"post\" action=\"/validate\" enctype=\"multipart/form-data\">\n<div class=\"pad\">\n");
        h.append("<label class=\"drop\" for=\"document\">").append(Theme.ICON_UPLOAD)
         .append("<strong>").append(HtmlReport.esc(m.get("ui.form.drop"))).append("</strong>")
         .append("<small>").append(HtmlReport.esc(m.format("ui.form.hint", MAX_UPLOAD_BYTES / (1024 * 1024)))).append("</small>")
         .append("<input id=\"document\" name=\"document\" type=\"file\" accept=\".pdf,application/pdf\" required></label>\n");
        h.append("<div class=\"row\">\n<label class=\"check\"><input id=\"online\" name=\"online\" type=\"checkbox\" value=\"1\"")
         .append(offline ? " disabled" : " checked").append("> <span>").append(HtmlReport.esc(m.get("web.online")))
         .append(offline ? HtmlReport.esc(m.get("web.online.unavailable")) : "").append("</span></label>\n")
         .append("<button class=\"btn\" type=\"submit\">").append(Theme.ICON_CHECK).append(HtmlReport.esc(m.get("web.submit"))).append("</button>\n</div>\n")
         .append("<p class=\"lead\" style=\"margin:1rem 0 0;font-size:.9rem\">").append(HtmlReport.esc(m.get("web.privacy"))).append("</p>\n</div>\n");
        h.append("<div class=\"trustline\">").append(Theme.ICON_SHIELD).append("<div><strong>").append(HtmlReport.esc(m.get("web.trust.h")))
         .append("</strong><br>").append(HtmlReport.esc(trust)).append("</div></div>\n</form>\n</main>\n");
        h.append(Theme.footer(m, "EU DSS")).append("</body>\n</html>\n");
        return h.toString();
    }

    private static String errorPage(Messages m, String title, String text) {
        return Theme.head(m, title) + "</head>\n<body>\n" + Theme.topbar(m)
                + "<main>\n<section class=\"banner v-invalid\">" + Theme.ICON_CROSS + "<div><h2>" + HtmlReport.esc(title)
                + "</h2><p>" + HtmlReport.esc(text) + "</p></div></section>\n"
                + "<p style=\"margin-top:1rem\"><a class=\"btn secondary\" href=\"/\">" + HtmlReport.esc(m.get("web.back")) + "</a></p>\n"
                + "</main>\n</body>\n</html>\n";
    }
}
