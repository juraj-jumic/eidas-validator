package org.eidasvalidation.corpus;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.report.Report;
import org.eidasvalidation.service.ValidationService;
import org.eidasvalidation.service.ValidatorOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runs every case in a {@link Manifest} and compares the outcome with the expectation for the
 * chosen mode. Trust data is loaded once per distinct setup, not once per case.
 */
public final class CorpusRunner {

    /** How much of the manifest's declared setup is honoured. */
    public enum Mode {
        /** Trusted lists and online lookups exactly as each case declares. */
        FULL,
        /** Trusted lists from the local cache only, no network at all. */
        OFFLINE,
        /** No trusted lists and no network; only anchor files. Deterministic, for CI. */
        NO_TRUSTED_LISTS;

        public static Mode parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "full" -> FULL;
                case "offline" -> OFFLINE;
                case "no-trusted-lists", "no-tl" -> NO_TRUSTED_LISTS;
                default -> throw new IllegalArgumentException("unknown mode: " + s);
            };
        }
    }

    public enum Status { PASS, FAIL, SKIP }

    /**
     * @param status   outcome of the comparison
     * @param actual   one-line summary of what the validator produced, for the table
     * @param detail   mismatches, or the reason for skipping; empty on PASS
     */
    public record CaseResult(Manifest.Case testCase, Mode mode, Status status, String actual, List<String> detail) {
    }

    public record Summary(List<CaseResult> results) {
        public long count(Status s) {
            return results.stream().filter(r -> r.status() == s).count();
        }

        public boolean ok() {
            return count(Status.FAIL) == 0;
        }
    }

    private final Path corpusDir;
    private final Mode mode;
    private final Path cacheDir;
    private final Map<String, ValidationService> services = new HashMap<>();

    public CorpusRunner(Path corpusDir, Mode mode, Path cacheDir) {
        this.corpusDir = Objects.requireNonNull(corpusDir);
        this.mode = Objects.requireNonNull(mode);
        this.cacheDir = cacheDir == null ? ValidatorOptions.defaultCacheDir() : cacheDir;
    }

    public Summary run(Manifest manifest) {
        List<CaseResult> results = new ArrayList<>();
        for (Manifest.Case c : manifest.cases()) {
            results.add(runCase(c));
        }
        return new Summary(List.copyOf(results));
    }

    CaseResult runCase(Manifest.Case c) {
        Manifest.Expectation expected = expectationFor(c);
        if (expected == null) {
            return new CaseResult(c, mode, Status.SKIP, "",
                    List.of("no expectation for mode " + mode + " (case relies on trusted lists)"));
        }
        Path file = corpusDir.resolve(c.file());
        if (!Files.isRegularFile(file)) {
            return new CaseResult(c, mode, Status.FAIL, "", List.of("file not found: " + file));
        }

        ValidationService service;
        try {
            service = serviceFor(c);
        } catch (IOException | RuntimeException e) {
            return new CaseResult(c, mode, Status.FAIL, "", List.of("cannot load trust data: " + e.getMessage()));
        }
        if (c.usesTrustedLists() && mode != Mode.NO_TRUSTED_LISTS && !service.trust().usesTrustedLists()) {
            // Offline with an empty cache: the expectation cannot be checked, and a comparison
            // would only measure the missing cache. Say so instead.
            return new CaseResult(c, mode, Status.SKIP, "", List.of("trusted lists not available: "
                    + String.join("; ", service.trust().problems()) + " (cache: " + cacheDir + ")"));
        }

        Report report;
        try {
            report = service.validate(file, c.file(), c.validationTime(), c.isOnline() && mode == Mode.FULL);
        } catch (ValidationException e) {
            return new CaseResult(c, mode, Status.FAIL, "unreadable", List.of("validation error: " + e.getMessage()));
        }

        List<String> mismatches = compare(expected, report);
        return new CaseResult(c, mode, mismatches.isEmpty() ? Status.PASS : Status.FAIL, summarise(report), mismatches);
    }

    private Manifest.Expectation expectationFor(Manifest.Case c) {
        return switch (mode) {
            case FULL -> c.expected();
            case OFFLINE -> {
                if (!c.isOnline() || c.offlineExpected() != null) {
                    yield c.expected().overriddenBy(c.offlineExpected());
                }
                yield null;
            }
            case NO_TRUSTED_LISTS -> {
                if (!c.usesTrustedLists()) {
                    yield c.expected();
                }
                yield c.noTrustedListsExpected() == null ? null : c.expected().overriddenBy(c.noTrustedListsExpected());
            }
        };
    }

    private ValidationService serviceFor(Manifest.Case c) throws IOException {
        boolean trustedLists = c.usesTrustedLists() && mode != Mode.NO_TRUSTED_LISTS;
        boolean offline = mode != Mode.FULL;
        List<Path> anchors = c.anchorFiles().stream().map(corpusDir::resolve).toList();
        String key = trustedLists + "|" + offline + "|" + anchors;
        ValidationService s = services.get(key);
        if (s == null) {
            s = ValidationService.create(new ValidatorOptions(trustedLists, offline, cacheDir, Duration.ofHours(24),
                    null, anchors));
            services.put(key, s);
        }
        return s;
    }

    static List<String> compare(Manifest.Expectation e, Report r) {
        List<String> out = new ArrayList<>();
        int n = r.signatures().size();
        if (e.signatures() != null && e.signatures() != n) {
            out.add("signatures: expected " + e.signatures() + ", got " + n);
        }
        if (n == 0) {
            return out;
        }
        SignatureResult s = r.signatures().get(0).result();
        if (e.indication() != null && !e.indication().equals(s.indication())) {
            out.add("indication: expected " + e.indication() + ", got " + s.indication()
                    + (s.subIndication() == null ? "" : " / " + s.subIndication()));
        } else if (e.indication() != null && !Objects.equals(e.subIndication(), s.subIndication())) {
            out.add("subIndication: expected " + e.subIndication() + ", got " + s.subIndication());
        }
        if (e.level() != null && !e.level().equals(s.format())) {
            out.add("level: expected " + e.level() + ", got " + s.format());
        }
        Boolean q = e.qualifiedAsBoolean();
        if (q != null) {
            boolean isQualified = s.qualification() != null && s.qualification().startsWith("QES");
            if (q != isQualified) {
                out.add("qualified: expected " + q + ", got " + s.qualification());
            }
        }
        return out;
    }

    private static String summarise(Report r) {
        if (r.signatures().isEmpty()) {
            return "no signature";
        }
        SignatureResult s = r.signatures().get(0).result();
        return s.format() + " " + s.indication() + (s.subIndication() == null ? "" : "/" + s.subIndication())
                + (s.qualification() == null ? "" : " q=" + s.qualification());
    }
}
