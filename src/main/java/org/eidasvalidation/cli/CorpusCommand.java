package org.eidasvalidation.cli;

import org.eidasvalidation.corpus.CorpusRunner;
import org.eidasvalidation.corpus.Manifest;
import org.eidasvalidation.service.ValidatorOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Runs the test corpus against the manifest's expected results. */
@Command(name = "corpus", mixinStandardHelpOptions = true,
        description = "Validate every file in a corpus manifest and compare with the expected results.")
public final class CorpusCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<manifest>", description = "Path to manifest.json; files are relative to its directory.")
    Path manifest;

    @Option(names = "--mode", paramLabel = "<mode>", defaultValue = "full",
            description = "full (as declared), offline (lists from cache, no network), "
                    + "no-trusted-lists (anchor files only, deterministic). Default: full.")
    String mode;

    @Option(names = "--cache-dir", paramLabel = "<dir>",
            description = "Trusted-list cache (default: ~/.eidas-validator/tl-cache).")
    Path cacheDir = ValidatorOptions.defaultCacheDir();

    @Override
    public Integer call() throws IOException {
        CorpusRunner.Mode m;
        try {
            m = CorpusRunner.Mode.parse(mode);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return Main.EXIT_ERROR;
        }
        Manifest mf = Manifest.read(manifest);
        Path dir = manifest.toAbsolutePath().getParent();
        CorpusRunner.Summary summary = new CorpusRunner(dir, m, cacheDir).run(mf);

        System.out.println("Corpus: " + manifest + " (" + mf.cases().size() + " cases, mode " + m + ")");
        System.out.println();
        for (CorpusRunner.CaseResult r : summary.results()) {
            String anchors = r.testCase().usesTrustedLists() ? "trusted lists" :
                    r.testCase().anchorFiles().isEmpty() ? "no anchors" : "anchors";
            System.out.printf("%-4s %-52s %-14s %s%n", r.status(), r.testCase().file(), anchors, r.actual());
            for (String d : r.detail()) {
                System.out.println("       " + d);
            }
        }
        System.out.println();
        System.out.printf("%d passed, %d failed, %d skipped%n",
                summary.count(CorpusRunner.Status.PASS), summary.count(CorpusRunner.Status.FAIL),
                summary.count(CorpusRunner.Status.SKIP));
        return summary.ok() ? 0 : 1;
    }
}
