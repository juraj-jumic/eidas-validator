package org.eidasvalidation.web;

import org.eidasvalidation.engine.dss.TestPdfSigner;
import org.eidasvalidation.service.ValidatorOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end through the loopback server, without trusted lists or network. */
class LocalServerTest {

    @TempDir
    static Path dir;

    static LocalServer server;
    static HttpClient http;
    static byte[] signedPdf;
    static byte[] unsignedPdf;

    @BeforeAll
    static void start() throws Exception {
        TestPdfSigner signer = TestPdfSigner.selfSigned(dir, "Web Signer");
        Path unsigned = TestPdfSigner.createPdf(dir.resolve("unsigned.pdf"));
        Path signed = signer.signBaselineB(unsigned, dir.resolve("signed.pdf"));
        signedPdf = Files.readAllBytes(signed);
        unsignedPdf = Files.readAllBytes(unsigned);

        server = new LocalServer(ValidatorOptions.anchorsOnly(List.of()), 0);
        server.start();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static HttpResponse<String> post(byte[] file, String filename, String... extraFields) throws IOException, InterruptedException {
        return postFrom(null, file, filename, extraFields);
    }

    private static HttpResponse<String> postFrom(String origin, byte[] file, String filename, String... extraFields)
            throws IOException, InterruptedException {
        String boundary = "----eidasTest" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        var w = new java.io.PrintStream(body, true, StandardCharsets.ISO_8859_1);
        if (file != null) {
            w.print("--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + filename
                    + "\"\r\nContent-Type: application/pdf\r\n\r\n");
            w.flush();
            body.write(file);
            w.print("\r\n");
        }
        for (int i = 0; i + 1 < extraFields.length; i += 2) {
            w.print("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + extraFields[i] + "\"\r\n\r\n"
                    + extraFields[i + 1] + "\r\n");
        }
        w.print("--" + boundary + "--\r\n");
        w.flush();

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(server.url() + "validate"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (origin != null) {
            req.header("Origin", origin);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void servesTheUploadForm() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(server.url())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("<form method=\"post\" action=\"/validate\""));
        assertTrue(r.body().contains("name=\"document\""));
        assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void returnsHtmlReportForSignedPdf() throws Exception {
        HttpResponse<String> r = post(signedPdf, "my contract.pdf");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(r.body().contains("my contract.pdf"));
        assertTrue(r.body().contains("Web Signer"));
        assertTrue(r.body().contains("Could not be fully verified"), "self-signed with no anchors is indeterminate");
        assertTrue(r.body().contains("NO_CERTIFICATE_CHAIN_FOUND"));
    }

    @Test
    void returnsJsonWhenAsked() throws Exception {
        HttpResponse<String> r = post(signedPdf, "x.pdf", "format", "json");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(r.body().contains("\"verdict\" : \"INDETERMINATE\""));
        assertTrue(r.body().contains("\"reportVersion\" : 4"));
    }

    @Test
    void unsignedPdfIsReportedNotRejected() throws Exception {
        HttpResponse<String> r = post(unsignedPdf, "plain.pdf");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("No signature found"));
    }

    @Test
    void nonPdfIsExplainedWith422() throws Exception {
        HttpResponse<String> r = post("hello".getBytes(StandardCharsets.US_ASCII), "notes.txt");
        assertEquals(422, r.statusCode());
        assertTrue(r.body().contains("could not be checked"));
    }

    @Test
    void missingFileIs400() throws Exception {
        HttpResponse<String> r = post(null, null, "online", "1");
        assertEquals(400, r.statusCode());
    }

    @Test
    void crossOriginPostIsRefused() throws Exception {
        HttpResponse<String> r = postFrom("http://evil.example", signedPdf, "x.pdf");
        assertEquals(403, r.statusCode());
        HttpResponse<String> ok = postFrom("http://127.0.0.1:" + server.port(), signedPdf, "x.pdf");
        assertEquals(200, ok.statusCode());
    }

    @Test
    void unexpectedHostHeaderIsRefused() throws Exception {
        // Simulates a DNS-rebinding page: the TCP connection reaches 127.0.0.1 but the Host says otherwise.
        try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write(("GET /healthz HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 403"), response);
        }
    }

    @Test
    void speaksTheBrowsersLanguageWhenSupported() throws Exception {
        HttpResponse<String> de = http.send(HttpRequest.newBuilder(URI.create(server.url()))
                .header("Accept-Language", "de-AT,de;q=0.9,en;q=0.5").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, de.statusCode());
        assertTrue(de.body().contains("lang=\"de\""));
        assertTrue(de.body().contains("Signierte PDF-Datei prüfen"), de.body());

        HttpResponse<String> fr = http.send(HttpRequest.newBuilder(URI.create(server.url()))
                .header("Accept-Language", "fr-FR,fr;q=0.9").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(fr.body().contains("lang=\"en\""), "unsupported language falls back to the server default");

        HttpResponse<String> report = post(signedPdf, "x.pdf", "format", "json");
        assertTrue(report.body().contains("\"language\" : \"en\""));
    }

    @Test
    void allowedHostNamesPassHostAndOriginChecks() throws Exception {
        try (LocalServer proxied = new LocalServer(ValidatorOptions.anchorsOnly(List.of()),
                java.net.InetAddress.getLoopbackAddress(), 0, java.util.Set.of("Validator.Example:443"),
                org.eidasvalidation.i18n.Messages.english())) {
            proxied.start();
            try (var socket = new java.net.Socket("127.0.0.1", proxied.port())) {
                socket.getOutputStream().write(("GET /healthz HTTP/1.1\r\nHost: validator.example\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200"), response);
            }
            try (var socket = new java.net.Socket("127.0.0.1", proxied.port())) {
                socket.getOutputStream().write(("GET /healthz HTTP/1.1\r\nHost: other.example\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 403"), response);
            }
            // Origin from the proxied name, over https, is accepted for the upload.
            String boundary = "----eidasProxy";
            byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"u.pdf\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(body);
            b.write(unsignedPdf);
            b.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(proxied.url() + "validate"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Origin", "https://validator.example")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(b.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    @Test
    void sandboxedServerSurvivesAHangingParser() throws Exception {
        var settings = org.eidasvalidation.sandbox.SandboxedValidator.Settings.defaults()
                .withJobTimeout(java.time.Duration.ofSeconds(3)).withMaxHeapMb(256)
                .withExtraJvmArgs(List.of("-Deidas.worker.hangOn=hang.pdf"));
        var options = ValidatorOptions.anchorsOnly(List.of());
        try (LocalServer sandboxed = new LocalServer(options, java.net.InetAddress.getLoopbackAddress(), 0,
                java.util.Set.of(), org.eidasvalidation.i18n.Messages.english(),
                () -> org.eidasvalidation.sandbox.SandboxedValidator.start(options, settings))) {
            sandboxed.start();
            HttpResponse<String> hung = postTo(sandboxed, signedPdf, "hang.pdf");
            assertEquals(422, hung.statusCode(), hung.body());
            assertTrue(hung.body().contains("could not be checked safely"), hung.body());

            HttpResponse<String> next = postTo(sandboxed, signedPdf, "fine.pdf");
            assertEquals(200, next.statusCode(), next.body());
            assertTrue(next.body().contains("NO_CERTIFICATE_CHAIN_FOUND"));
        }
    }

    private static HttpResponse<String> postTo(LocalServer target, byte[] file, String filename) throws IOException, InterruptedException {
        String boundary = "----eidasSandbox" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return http.send(HttpRequest.newBuilder(URI.create(target.url() + "validate"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void unknownPathIs404() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(server.url() + "nope")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, r.statusCode());
    }
}
