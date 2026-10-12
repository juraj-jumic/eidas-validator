package org.eidasvalidation.sandbox;

import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.engine.dss.TestPdfSigner;
import org.eidasvalidation.explain.Verdict;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.Report;
import org.eidasvalidation.service.ValidatorOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spawns real worker JVMs; the hang and crash hooks stand in for hostile PDFs. */
class SandboxedValidatorTest {

    @TempDir
    static Path dir;

    static Path unsigned;
    static Path signed;
    static Path anchor;
    static SandboxedValidator sandbox;

    @BeforeAll
    static void start() throws Exception {
        TestPdfSigner signer = TestPdfSigner.selfSigned(dir, "Sandbox Signer");
        unsigned = TestPdfSigner.createPdf(dir.resolve("unsigned.pdf"));
        signed = signer.signBaselineB(unsigned, dir.resolve("signed.pdf"));
        anchor = dir.resolve("anchor.pem");
        Files.writeString(anchor, eu.europa.esig.dss.spi.DSSUtils.convertToPEM(signer.certificate()));

        SandboxedValidator.Settings settings = SandboxedValidator.Settings.defaults()
                .withJobTimeout(Duration.ofSeconds(10)) // generous: the first job in a fresh worker is a cold JVM
                .withMaxHeapMb(256)
                .withExtraJvmArgs(List.of("-Deidas.worker.hangOn=hang.pdf", "-Deidas.worker.crashOn=crash.pdf"));
        sandbox = SandboxedValidator.start(ValidatorOptions.anchorsOnly(List.of(anchor)), settings);
    }

    @AfterAll
    static void stop() {
        sandbox.close();
    }

    @Test
    void validatesInTheWorkerAndReturnsTheSameReportShape() {
        Report r = sandbox.validate(signed, "contract.pdf", Instant.now(), false, Messages.forTag("de"));
        assertEquals(Verdict.VALID, r.verdict());
        assertEquals("de", r.language());
        assertEquals("contract.pdf", r.file());
        assertEquals(1, r.signatures().size());
        assertTrue(r.signatures().get(0).result().signedBy().contains("Sandbox Signer"));
        assertTrue(r.signatures().get(0).explanation().headline().startsWith("Gültig"));
        assertTrue(r.engineReport().contains("SimpleReport"));
        assertEquals(1, sandbox.trust().certificates());

        Report none = sandbox.validate(unsigned, "plain.pdf", Instant.now(), false, Messages.english());
        assertEquals(Verdict.NO_SIGNATURE, none.verdict());
    }

    @Test
    void unreadableFileIsAValidationExceptionNotASandboxFailure() throws Exception {
        Path garbage = Files.writeString(dir.resolve("garbage.bin"), "not a pdf");
        ValidationException e = assertThrows(ValidationException.class,
                () -> sandbox.validate(garbage, "garbage.bin", Instant.now(), false, Messages.english()));
        assertFalse(e instanceof SandboxException, "a bad document is not a sandbox failure");
        assertTrue(sandbox.isWorkerAlive(), "the worker survives a bad document");
    }

    @Test
    void hangingParserIsKilledAndTheNextJobGetsAFreshWorker() {
        SandboxException e = assertThrows(SandboxException.class,
                () -> sandbox.validate(signed, "hang.pdf", Instant.now(), false, Messages.english()));
        assertEquals(SandboxException.Kind.TIMEOUT, e.kind());
        assertFalse(sandbox.isWorkerAlive(), "the hung worker must be gone");

        Report r = sandbox.validate(signed, "after-hang.pdf", Instant.now(), false, Messages.english());
        assertEquals(Verdict.VALID, r.verdict());
        assertTrue(sandbox.isWorkerAlive());
    }

    @Test
    void crashingParserIsReportedAndTheNextJobGetsAFreshWorker() {
        SandboxException e = assertThrows(SandboxException.class,
                () -> sandbox.validate(signed, "crash.pdf", Instant.now(), false, Messages.english()));
        assertEquals(SandboxException.Kind.CRASHED, e.kind());
        assertTrue(e.getMessage().contains("exit code 3"), e.getMessage());

        Report r = sandbox.validate(signed, "after-crash.pdf", Instant.now(), false, Messages.english());
        assertEquals(Verdict.VALID, r.verdict());
    }

    @Test
    void startFailureIsReportedClearly() {
        ValidatorOptions bad = ValidatorOptions.anchorsOnly(List.of(dir.resolve("missing.pem")));
        SandboxException e = assertThrows(SandboxException.class,
                () -> SandboxedValidator.start(bad, SandboxedValidator.Settings.defaults().withMaxHeapMb(128)));
        assertEquals(SandboxException.Kind.START_FAILED, e.kind());
        assertTrue(e.getMessage().contains("missing.pem"), e.getMessage());
    }
}
