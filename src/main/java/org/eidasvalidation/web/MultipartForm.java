package org.eidasvalidation.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal {@code multipart/form-data} parser for the upload form: one file plus a few fields,
 * body already bounded by the server's size limit. Not a general-purpose implementation.
 */
public final class MultipartForm {

    /** One form part; {@code filename} is null for ordinary fields. */
    public record Part(String name, String filename, String contentType, byte[] data) {
        public String asString() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private MultipartForm() {
    }

    /**
     * @param contentTypeHeader the request's Content-Type, which must carry the boundary
     * @param body              the whole request body
     * @return parts keyed by field name; a repeated name keeps the last part
     * @throws IllegalArgumentException if the body is not well-formed multipart data
     */
    public static Map<String, Part> parse(String contentTypeHeader, byte[] body) {
        String boundary = boundary(contentTypeHeader);
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] crlf = {'\r', '\n'};

        Map<String, Part> parts = new LinkedHashMap<>();
        int pos = indexOf(body, delimiter, 0);
        if (pos < 0) {
            throw new IllegalArgumentException("multipart boundary not found in body");
        }
        pos += delimiter.length;
        while (true) {
            if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') {
                break; // closing delimiter
            }
            pos = skip(body, pos, crlf);
            int headersEnd = indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}, pos);
            if (headersEnd < 0) {
                throw new IllegalArgumentException("part headers not terminated");
            }
            String headers = new String(body, pos, headersEnd - pos, StandardCharsets.ISO_8859_1);
            int dataStart = headersEnd + 4;
            int next = indexOf(body, delimiter, dataStart);
            if (next < 0) {
                throw new IllegalArgumentException("part not terminated by boundary");
            }
            int dataEnd = next;
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') {
                dataEnd -= 2;
            }
            byte[] data = Arrays.copyOfRange(body, dataStart, dataEnd);
            Part part = toPart(headers, data);
            if (part.name() != null) {
                parts.put(part.name(), part);
            }
            pos = next + delimiter.length;
        }
        return parts;
    }

    private static String boundary(String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw new IllegalArgumentException("not multipart/form-data");
        }
        for (String param : contentType.split(";")) {
            String p = param.trim();
            if (p.regionMatches(true, 0, "boundary=", 0, 9)) {
                String b = p.substring(9).trim();
                if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length() - 1);
                }
                if (!b.isEmpty()) {
                    return b;
                }
            }
        }
        throw new IllegalArgumentException("multipart boundary missing");
    }

    private static Part toPart(String headers, byte[] data) {
        String name = null;
        String filename = null;
        String contentType = null;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("content-disposition")) {
                name = attribute(value, "name");
                filename = attribute(value, "filename");
            } else if (key.equals("content-type")) {
                contentType = value;
            }
        }
        return new Part(name, filename, contentType, data);
    }

    private static String attribute(String disposition, String attr) {
        for (String piece : disposition.split(";")) {
            String p = piece.trim();
            if (p.regionMatches(true, 0, attr + "=", 0, attr.length() + 1)) {
                String v = p.substring(attr.length() + 1).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                // Browsers percent-encode or escape quotes in file names; keep only the base name.
                int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
                return slash >= 0 ? v.substring(slash + 1) : v;
            }
        }
        return null;
    }

    private static int skip(byte[] body, int pos, byte[] seq) {
        if (pos + seq.length <= body.length && Arrays.equals(Arrays.copyOfRange(body, pos, pos + seq.length), seq)) {
            return pos + seq.length;
        }
        return pos;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
