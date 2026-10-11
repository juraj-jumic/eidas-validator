package org.eidasvalidation.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartFormTest {

    private static final String BOUNDARY = "----WebKitFormBoundaryX7";

    static byte[] body(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            b.append("--").append(BOUNDARY).append("\r\n").append(p).append("\r\n");
        }
        b.append("--").append(BOUNDARY).append("--\r\n");
        return b.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void parsesFileAndFields() {
        byte[] body = body(
                "Content-Disposition: form-data; name=\"document\"; filename=\"C:\\Users\\me\\a b.pdf\"\r\n"
                        + "Content-Type: application/pdf\r\n\r\n%PDF-1.7\r\nbinary\u00ff\u0000data",
                "Content-Disposition: form-data; name=\"online\"\r\n\r\n1",
                "Content-Disposition: form-data; name=\"format\"\r\n\r\njson");
        Map<String, MultipartForm.Part> parts = MultipartForm.parse(
                "multipart/form-data; boundary=" + BOUNDARY, body);

        assertEquals(3, parts.size());
        MultipartForm.Part doc = parts.get("document");
        assertEquals("a b.pdf", doc.filename(), "only the base name is kept");
        assertEquals("application/pdf", doc.contentType());
        assertArrayEquals("%PDF-1.7\r\nbinary\u00ff\u0000data".getBytes(StandardCharsets.ISO_8859_1), doc.data());
        assertEquals("1", parts.get("online").asString());
        assertNull(parts.get("online").filename());
        assertEquals("json", parts.get("format").asString());
    }

    @Test
    void acceptsQuotedBoundary() {
        byte[] body = body("Content-Disposition: form-data; name=\"x\"\r\n\r\ny");
        Map<String, MultipartForm.Part> parts = MultipartForm.parse(
                "multipart/form-data; boundary=\"" + BOUNDARY + "\"", body);
        assertEquals("y", parts.get("x").asString());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class,
                () -> MultipartForm.parse("application/x-www-form-urlencoded", "a=b".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class,
                () -> MultipartForm.parse("multipart/form-data; boundary=zzz", "nothing here".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class,
                () -> MultipartForm.parse("multipart/form-data", body("Content-Disposition: form-data; name=\"x\"\r\n\r\ny")));
    }
}
