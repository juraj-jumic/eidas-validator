package org.eidasvalidation.engine.dss;

import eu.europa.esig.dss.spi.DSSUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regenerates the generated part of the test corpus under {@code corpus/generated/}.
 *
 * <p>Run from the project root:
 * <pre>mvn -q test-compile exec:java -Dexec.mainClass=org.eidasvalidation.engine.dss.GenerateCorpus -Dexec.classpathScope=test</pre>
 *
 * <p>A fresh throwaway certificate is created on every run, so the files change each time; the
 * expected outcomes in {@code corpus/manifest.json} do not. The certificate is exported as PEM so
 * the CLI can use it as a trust anchor.
 */
public final class GenerateCorpus {

    private GenerateCorpus() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "corpus/generated");
        Files.createDirectories(out);

        TestPdfSigner signer = TestPdfSigner.selfSigned(out, "Test Signer");
        Path unsigned = TestPdfSigner.createPdf(out.resolve("unsigned.pdf"));
        Path signed = signer.signBaselineB(unsigned, out.resolve("signed-b.pdf"));
        TestPdfSigner.tamper(signed, out.resolve("signed-b-tampered.pdf"));
        Files.writeString(out.resolve("test-signer.pem"),
                DSSUtils.convertToPEM(signer.certificate()), StandardCharsets.US_ASCII);
        // the private key is only needed while signing; do not leave it in the repo
        Files.deleteIfExists(out.resolve("Test_Signer.p12"));

        System.out.println("Generated corpus in " + out.toAbsolutePath());
    }
}
