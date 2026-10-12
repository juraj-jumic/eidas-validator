package org.eidasvalidation.corpus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * The test-corpus manifest: files with their expected validation results.
 *
 * @param schemaVersion manifest format version
 * @param cases         one entry per file and trust setup
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(int schemaVersion, List<Case> cases) {

    /** Marker in {@code trustAnchors} meaning "the EU trusted lists" rather than a certificate file. */
    public static final String EU_TRUSTED_LISTS = "EU trusted lists";

    /**
     * @param file            path relative to the corpus directory
     * @param validationTime  time the expected result refers to
     * @param trustAnchors    certificate files relative to the corpus directory, or {@link #EU_TRUSTED_LISTS}
     * @param online          whether revocation lookups were allowed for the expected result
     * @param expected        result with the setup above (mode {@code full})
     * @param offlineExpected overrides when the network is unavailable (mode {@code offline})
     * @param noTrustedListsExpected overrides when trusted lists are not loaded (mode {@code no-trusted-lists})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(String file,
                       String source,
                       Instant validationTime,
                       List<String> trustAnchors,
                       Boolean online,
                       Expectation expected,
                       Expectation offlineExpected,
                       Expectation noTrustedListsExpected,
                       String notes) {

        public boolean usesTrustedLists() {
            return trustAnchors != null && trustAnchors.contains(EU_TRUSTED_LISTS);
        }

        public List<String> anchorFiles() {
            return trustAnchors == null ? List.of()
                    : trustAnchors.stream().filter(a -> !EU_TRUSTED_LISTS.equals(a)).toList();
        }

        public boolean isOnline() {
            return Boolean.TRUE.equals(online);
        }
    }

    /**
     * Fields left null are not checked. {@code qualified} is {@code true}, {@code false} or the
     * string {@code "unknown"}; only the booleans are asserted.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expectation(Integer signatures,
                              String indication,
                              String subIndication,
                              String level,
                              JsonNode qualified) {

        /** This expectation with any non-null field of {@code override} replacing its own. */
        public Expectation overriddenBy(Expectation override) {
            if (override == null) {
                return this;
            }
            return new Expectation(
                    override.signatures != null ? override.signatures : signatures,
                    override.indication != null ? override.indication : indication,
                    override.indication != null ? override.subIndication : subIndication,
                    override.level != null ? override.level : level,
                    override.qualified != null && !override.qualified.isNull() ? override.qualified : qualified);
        }

        public Boolean qualifiedAsBoolean() {
            return qualified != null && qualified.isBoolean() ? qualified.asBoolean() : null;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    public static Manifest read(Path file) throws IOException {
        return MAPPER.readValue(Files.readString(file), Manifest.class);
    }
}
