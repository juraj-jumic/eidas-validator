package org.eidasvalidation.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.service.ValidatorOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Line-oriented JSON protocol between {@link SandboxedValidator} (parent) and {@link Worker}
 * (child): one message per line on the worker's stdin and stdout. The worker's stderr is the
 * engine's log and is passed through.
 *
 * <pre>
 * parent → child   {@code Hello} (trust configuration)
 * child  → parent  {@code READY <TrustSummary json>}  or  {@code FAILED <message>}
 * parent → child   {@code Request}            (repeated)
 * child  → parent  {@code Response}           (one per request)
 * </pre>
 */
public final class WorkerProtocol {

    public static final String READY = "READY ";
    public static final String FAILED = "FAILED ";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private WorkerProtocol() {
    }

    /** Trust configuration, with paths as strings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hello(boolean trustedLists, boolean offline, String cacheDir, long tlMaxAgeSeconds,
                        String lotlSigners, List<String> trustAnchors) {

        public static Hello from(ValidatorOptions o) {
            return new Hello(o.trustedLists(), o.offline(), o.cacheDir().toString(), o.tlMaxAge().toSeconds(),
                    o.lotlSigners() == null ? null : o.lotlSigners().toString(),
                    o.trustAnchors().stream().map(Path::toString).toList());
        }

        public ValidatorOptions toOptions() {
            return new ValidatorOptions(trustedLists, offline, Path.of(cacheDir), Duration.ofSeconds(tlMaxAgeSeconds),
                    lotlSigners == null ? null : Path.of(lotlSigners),
                    trustAnchors == null ? List.of() : trustAnchors.stream().map(Path::of).toList());
        }
    }

    /** One validation job. {@code at} is an ISO-8601 instant. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(String document, String displayName, String at, boolean online, String lang) {
    }

    /**
     * @param ok      whether {@code report} is present
     * @param kind    on failure: {@code unreadable} (document not parseable) or {@code failure} (anything else)
     * @param message on failure: what happened
     * @param report  on success: the compact JSON of the {@link org.eidasvalidation.report.Report}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(boolean ok, String kind, String message, String report) {
        public static Response success(String reportJson) {
            return new Response(true, null, null, reportJson);
        }

        public static Response failure(String kind, String message) {
            return new Response(false, kind, message, null);
        }
    }

    public static String write(Object message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode protocol message", e);
        }
    }

    public static <T> T read(String line, Class<T> type) {
        try {
            return MAPPER.readValue(line, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed protocol message: " + abbreviate(line), e);
        }
    }

    public static TrustSummary readTrust(String json) {
        return read(json, TrustSummary.class);
    }

    static String abbreviate(String s) {
        return s == null ? "null" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
