package org.eidasvalidation.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed corpus must match its manifest. The deterministic mode runs always; the full
 * mode (live trusted lists, online revocation) only when {@code EIDAS_TL_ONLINE=true}.
 */
class CorpusTest {

    private static final Path MANIFEST = Path.of("corpus", "manifest.json");

    @Test
    void manifestMatchesWithoutTrustedLists() throws Exception {
        assertTrue(Files.isRegularFile(MANIFEST), "run from the project root");
        Manifest manifest = Manifest.read(MANIFEST);
        CorpusRunner.Summary summary = new CorpusRunner(MANIFEST.toAbsolutePath().getParent(),
                CorpusRunner.Mode.NO_TRUSTED_LISTS, null).run(manifest);

        String report = describe(summary);
        assertEquals(0, summary.count(CorpusRunner.Status.FAIL), report);
        assertTrue(summary.count(CorpusRunner.Status.PASS) >= 6, report);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EIDAS_TL_ONLINE", matches = "true")
    void manifestMatchesWithLiveTrustedLists() throws Exception {
        Manifest manifest = Manifest.read(MANIFEST);
        CorpusRunner.Summary summary = new CorpusRunner(MANIFEST.toAbsolutePath().getParent(),
                CorpusRunner.Mode.FULL, null).run(manifest);
        String report = describe(summary);
        assertEquals(0, summary.count(CorpusRunner.Status.FAIL), report);
        assertEquals(0, summary.count(CorpusRunner.Status.SKIP), report);
    }

    private static String describe(CorpusRunner.Summary summary) {
        StringBuilder b = new StringBuilder("\n");
        for (CorpusRunner.CaseResult r : summary.results()) {
            b.append(r.status()).append("  ").append(r.testCase().file()).append("  ").append(r.actual()).append('\n');
            for (String d : r.detail()) {
                b.append("      ").append(d).append('\n');
            }
        }
        return b.toString();
    }
}
