package org.eidasvalidation.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.x509.CertificateSource;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.engine.ValidationEngine;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.engine.ValidationReport;
import org.eidasvalidation.engine.ValidationRequest;
import org.eidasvalidation.engine.dss.DssValidationEngine;
import org.eidasvalidation.engine.dss.EuTrustedLists;
import org.eidasvalidation.explain.Explanation;
import org.eidasvalidation.explain.Explainer;
import org.eidasvalidation.explain.Verdict;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Command line entry point.
 *
 * <p>Exit codes: 0 all signatures valid, 1 at least one invalid, 2 indeterminate, 3 no signature,
 * 4 could not validate.
 */
@Command(name = "eidas-validator",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Checks the electronic signatures in a PDF and explains the result in plain language.")
public final class Main implements Callable<Integer> {

    static final int EXIT_VALID = 0;
    static final int EXIT_INVALID = 1;
    static final int EXIT_INDETERMINATE = 2;
    static final int EXIT_NO_SIGNATURE = 3;
    static final int EXIT_ERROR = 4;

    @Parameters(index = "0", description = "PDF file to validate.")
    Path document;

    @Option(names = "--at", description = "Validation time as ISO-8601 instant (default: now).")
    Instant validationTime;

    @Option(names = "--trusted-lists", negatable = true, defaultValue = "true", fallbackValue = "true",
            description = "Use the EU trusted lists (LOTL) as trust anchors (default: on; --no-trusted-lists to skip).")
    boolean trustedLists;

    @Option(names = "--offline", description = "Never use the network: trusted lists from cache only, no revocation lookups.")
    boolean offline;

    @Option(names = "--online", description = "Allow CRL, OCSP and AIA lookups over the network for the signer's "
            + "certificate chain (default: off).")
    boolean online;

    @Option(names = "--cache-dir", paramLabel = "<dir>",
            description = "Where downloaded trusted lists are kept (default: ~/.eidas-validator/tl-cache).")
    Path cacheDir = Path.of(System.getProperty("user.home"), ".eidas-validator", "tl-cache");

    @Option(names = "--tl-max-age", paramLabel = "<hours>", defaultValue = "24",
            description = "Re-download trusted lists older than this (default: 24).")
    long tlMaxAgeHours;

    @Option(names = "--lotl-signers", paramLabel = "<file>",
            description = "PEM bundle with the LOTL signing certificates from the Official Journal "
                    + "(default: the bundled copy).")
    Path lotlSigners;

    @Option(names = "--trust", paramLabel = "<cert>",
            description = "Additional certificate file (PEM or DER) to accept as a trust anchor. Repeatable.")
    List<Path> trustAnchors = new ArrayList<>();

    @Option(names = "--json", description = "Print the report as JSON instead of text.")
    boolean json;

    @Option(names = "--engine-report", description = "Write the engine's own report (DSS simple report XML) to this file.")
    Path engineReportOut;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws IOException {
        PrintStream out = System.out;
        if (!Files.isRegularFile(document)) {
            System.err.println("Not a file: " + document);
            return EXIT_ERROR;
        }
        if (offline && online) {
            System.err.println("--offline and --online cannot be combined.");
            return EXIT_ERROR;
        }

        List<CertificateSource> sources = new ArrayList<>();
        TrustSummary summary;

        if (trustedLists) {
            EuTrustedLists.Loaded loaded;
            try {
                loaded = EuTrustedLists
                        .configure(cacheDir, Duration.ofHours(tlMaxAgeHours), offline, lotlSigners)
                        .load();
            } catch (IOException | RuntimeException e) {
                System.err.println("Cannot load the EU trusted lists: " + e.getMessage());
                System.err.println("Use --no-trusted-lists to validate integrity only.");
                return EXIT_ERROR;
            }
            sources.add(loaded.source());
            summary = loaded.summary();
        } else {
            summary = TrustSummary.none();
        }

        if (!trustAnchors.isEmpty()) {
            CommonTrustedCertificateSource extra = new CommonTrustedCertificateSource();
            for (Path anchor : trustAnchors) {
                try {
                    extra.addCertificate(DSSUtils.loadCertificate(anchor.toFile()));
                } catch (RuntimeException e) {
                    System.err.println("Cannot read trust anchor " + anchor + ": " + e.getMessage());
                    return EXIT_ERROR;
                }
            }
            sources.add(extra);
            summary = trustedLists ? withExtraAnchors(summary, trustAnchors.size())
                    : TrustSummary.anchors(trustAnchors.size());
        }
        if (sources.isEmpty()) {
            sources.add(new CommonTrustedCertificateSource());
        }

        ValidationEngine engine = new DssValidationEngine(sources, summary, online && !offline);
        ValidationRequest request = new ValidationRequest(document,
                validationTime == null ? Instant.now() : validationTime);

        ValidationReport report;
        try {
            report = engine.validate(request);
        } catch (ValidationException e) {
            System.err.println(e.getMessage());
            return EXIT_ERROR;
        }

        if (engineReportOut != null) {
            Files.writeString(engineReportOut, report.engineReport(), StandardCharsets.UTF_8);
        }

        boolean tl = report.trust().usesTrustedLists();
        List<Explanation> explanations = new ArrayList<>();
        for (SignatureResult s : report.signatures()) {
            explanations.add(Explainer.explain(s, report.validationTime(), tl));
        }

        if (json) {
            printJson(out, report, explanations);
        } else {
            printText(out, report, explanations);
        }
        return exitCode(explanations);
    }

    private static TrustSummary withExtraAnchors(TrustSummary tl, int extra) {
        return new TrustSummary(tl.source() + " plus " + extra + " anchor file(s) from the command line",
                tl.synchronisedAt(), tl.lotlSequenceNumber(), tl.lotlIssued(), tl.lotlNextUpdate(),
                tl.lotlSignatureValid(), tl.trustedLists(), tl.trustedListsValid(), tl.certificates() + extra,
                tl.problems());
    }

    private void printText(PrintStream out, ValidationReport report, List<Explanation> explanations) {
        out.println("File:       " + document.getFileName());
        out.println("Checked at: " + report.validationTime());
        out.println("Engine:     " + report.engine());
        out.println("Trust data: " + report.trust().describe());
        for (String problem : report.trust().problems()) {
            out.println("            note: " + problem);
        }
        out.println();
        if (!report.hasSignatures()) {
            out.println("No signature found in this document.");
            return;
        }
        int n = 1;
        for (int i = 0; i < explanations.size(); i++) {
            SignatureResult s = report.signatures().get(i);
            Explanation e = explanations.get(i);
            out.println("Signature " + n++ + " of " + explanations.size()
                    + (s.signedBy() == null ? "" : " by " + s.signedBy()));
            out.println("  " + e.verdict() + ": " + e.headline());
            out.println("  " + e.detail());
            out.println("  " + e.qualification());
            for (String m : s.qualificationErrors()) {
                out.println("    trusted-list detail: " + m);
            }
            for (String m : s.qualificationWarnings()) {
                out.println("    trusted-list detail: " + m);
            }
            if (e.nextStep() != null) {
                out.println("  What you can do: " + e.nextStep());
            }
            out.println("  Technical: " + s.format() + ", " + s.indication()
                    + (s.subIndication() == null ? "" : " / " + s.subIndication())
                    + (s.qualification() == null ? "" : ", qualification " + s.qualification()));
            out.println();
        }
    }

    private void printJson(PrintStream out, ValidationReport report, List<Explanation> explanations)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("reportVersion", 2);
        doc.put("file", document.getFileName().toString());
        doc.put("validationTime", report.validationTime());
        doc.put("engine", report.engine());
        doc.put("trust", report.trust());
        List<Map<String, Object>> sigs = new ArrayList<>();
        for (int i = 0; i < explanations.size(); i++) {
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("explanation", explanations.get(i));
            sig.put("result", report.signatures().get(i));
            sigs.add(sig);
        }
        doc.put("signatures", sigs);
        out.println(mapper.writeValueAsString(doc));
    }

    static int exitCode(List<Explanation> explanations) {
        if (explanations.isEmpty()) {
            return EXIT_NO_SIGNATURE;
        }
        int code = EXIT_VALID;
        for (Explanation e : explanations) {
            if (e.verdict() == Verdict.INVALID) {
                return EXIT_INVALID;
            }
            if (e.verdict() == Verdict.INDETERMINATE) {
                code = EXIT_INDETERMINATE;
            }
        }
        return code;
    }

    /** Reads the version from the filtered engine.properties resource. */
    public static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() throws IOException {
            var props = new java.util.Properties();
            try (var in = Main.class.getResourceAsStream("/engine.properties")) {
                if (in != null) {
                    props.load(in);
                }
            }
            return new String[]{
                    "eidas-validator " + props.getProperty("project.version", "dev"),
                    "engine: EU DSS " + props.getProperty("dss.version", "unknown")};
        }
    }
}
