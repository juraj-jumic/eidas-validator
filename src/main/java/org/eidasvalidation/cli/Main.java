package org.eidasvalidation.cli;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.explain.Explanation;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.HtmlReport;
import org.eidasvalidation.report.Report;
import org.eidasvalidation.report.ReportJson;
import org.eidasvalidation.report.SignatureReport;
import org.eidasvalidation.service.ValidationService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * Command line entry point: {@code eidas-validator <file>} validates a document;
 * {@code eidas-validator serve} starts the local web interface;
 * {@code eidas-validator corpus} runs the test corpus.
 *
 * <p>Exit codes: 0 all signatures valid, 1 at least one invalid, 2 indeterminate, 3 no signature,
 * 4 could not validate.
 */
@Command(name = "eidas-validator",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        subcommands = {ServeCommand.class, CorpusCommand.class},
        description = "Checks the electronic signatures in a PDF and explains the result in plain language.")
public final class Main implements Callable<Integer> {

    static final int EXIT_VALID = 0;
    static final int EXIT_INVALID = 1;
    static final int EXIT_INDETERMINATE = 2;
    static final int EXIT_NO_SIGNATURE = 3;
    static final int EXIT_ERROR = 4;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<document>", description = "PDF file to validate.")
    Path document;

    @Option(names = "--at", description = "Validation time as ISO-8601 instant (default: now).")
    Instant validationTime;

    @Option(names = "--online", description = "Allow CRL, OCSP and AIA lookups over the network for the signer's "
            + "certificate chain (default: off).")
    boolean online;

    @Option(names = "--lang", paramLabel = "<tag>", description = "Language of the explanations, e.g. en or de "
            + "(default: en; unknown languages fall back to English).")
    String lang;

    @Mixin
    TrustOptions trust;

    @Option(names = "--json", description = "Print the report as JSON instead of text.")
    boolean json;

    @Option(names = "--html", paramLabel = "<file>", description = "Also write the readable HTML report to this file.")
    Path htmlOut;

    @Option(names = "--engine-report", paramLabel = "<file>",
            description = "Write the engine's own report (DSS simple report XML) to this file.")
    Path engineReportOut;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        // JDK 17 encodes System.out in the platform code page; explanations carry accents in
        // several languages, and modern terminals expect UTF-8.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws IOException {
        if (document == null) {
            spec.commandLine().usage(System.err);
            return EXIT_ERROR;
        }
        if (!Files.isRegularFile(document)) {
            System.err.println("Not a file: " + document);
            return EXIT_ERROR;
        }
        if (trust.offline && online) {
            System.err.println("--offline and --online cannot be combined.");
            return EXIT_ERROR;
        }
        Messages messages = Messages.forTag(lang);

        ValidationService service;
        try {
            service = ValidationService.create(trust.toOptions());
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot load trust data: " + e.getMessage());
            System.err.println("Use --no-trusted-lists to validate integrity only.");
            return EXIT_ERROR;
        }

        Report report;
        try {
            report = service.validate(document, document.getFileName().toString(),
                    validationTime == null ? Instant.now() : validationTime, online, messages);
        } catch (ValidationException e) {
            System.err.println(e.getMessage());
            return EXIT_ERROR;
        }

        if (engineReportOut != null) {
            Files.writeString(engineReportOut, report.engineReport(), StandardCharsets.UTF_8);
        }
        if (htmlOut != null) {
            Files.writeString(htmlOut, HtmlReport.render(report), StandardCharsets.UTF_8);
        }
        if (json) {
            System.out.println(ReportJson.write(report));
        } else {
            printText(System.out, report, messages);
        }
        return report.exitCode();
    }

    private static void printText(PrintStream out, Report report, Messages m) {
        String[] labels = {m.get("cli.file"), m.get("cli.checkedAt"), m.get("cli.engine"), m.get("cli.trust")};
        int width = 1 + java.util.Arrays.stream(labels).mapToInt(String::length).max().orElse(10);
        out.println(pad(labels[0], width) + report.file());
        out.println(pad(labels[1], width) + report.validationTime());
        out.println(pad(labels[2], width) + report.engine());
        out.println(pad(labels[3], width) + report.trust().describe(m));
        for (String problem : report.trust().describeProblems(m)) {
            out.println(" ".repeat(width) + m.get("cli.note") + " " + problem);
        }
        out.println();
        if (report.signatures().isEmpty()) {
            out.println(m.get("cli.noSignature"));
            return;
        }
        int n = 1;
        for (SignatureReport sr : report.signatures()) {
            SignatureResult s = sr.result();
            Explanation e = sr.explanation();
            out.println(m.format("cli.signature.n", n++, report.signatures().size())
                    + (s.signedBy() == null ? "" : m.format("cli.signature.by", s.signedBy())));
            out.println("  " + m.get("verdict." + e.verdict().name()) + ": " + e.headline());
            out.println("  " + e.detail());
            out.println("  " + e.qualification());
            for (String msg : s.qualificationErrors()) {
                out.println("    " + m.get("cli.tlDetail") + " " + msg);
            }
            for (String msg : s.qualificationWarnings()) {
                out.println("    " + m.get("cli.tlDetail") + " " + msg);
            }
            if (e.nextStep() != null) {
                out.println("  " + m.get("cli.next") + " " + e.nextStep());
            }
            out.println("  " + m.get("cli.technical") + " " + s.format() + ", " + s.indication()
                    + (s.subIndication() == null ? "" : " / " + s.subIndication())
                    + (s.qualification() == null ? "" : ", " + m.get("cli.qualification") + " " + s.qualification()));
            out.println();
        }
    }

    private static String pad(String label, int width) {
        return label + " ".repeat(Math.max(1, width - label.length()));
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
