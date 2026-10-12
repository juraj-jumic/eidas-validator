package org.eidasvalidation.engine.dss;

import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.engine.ValidationReport;
import org.eidasvalidation.engine.ValidationRequest;
import org.eidasvalidation.explain.Explainer;
import org.eidasvalidation.explain.Verdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end spike: generated PAdES-B signatures through the DSS engine and the explainer.
 * Every expected outcome here is also a candidate row for the corpus manifest.
 */
class DssValidationEngineTest {

    @TempDir
    static Path dir;

    static TestPdfSigner signer;
    static Path unsigned;
    static Path signed;
    static Path tampered;

    @BeforeAll
    static void generateMaterial() throws Exception {
        signer = TestPdfSigner.selfSigned(dir, "Test Signer");
        unsigned = TestPdfSigner.createPdf(dir.resolve("unsigned.pdf"));
        signed = signer.signBaselineB(unsigned, dir.resolve("signed-b.pdf"));
        tampered = TestPdfSigner.tamper(signed, dir.resolve("signed-b-tampered.pdf"));
    }

    @Test
    void unsignedPdfYieldsNoSignatures() {
        ValidationReport report = DssValidationEngine.offlineWithoutTrust().validate(ValidationRequest.now(unsigned));
        assertFalse(report.hasSignatures());
        assertTrue(report.engine().startsWith("EU DSS "));
    }

    @Test
    void signerNotTrustedIsIndeterminateNoChain() {
        ValidationReport report = DssValidationEngine.offlineWithoutTrust().validate(ValidationRequest.now(signed));
        SignatureResult s = single(report);
        assertEquals("INDETERMINATE", s.indication());
        assertEquals("NO_CERTIFICATE_CHAIN_FOUND", s.subIndication());
        assertEquals("PAdES-BASELINE-B", s.format());
        assertTrue(s.signedBy().contains("Test Signer"), s.signedBy());
        assertEquals(Verdict.INDETERMINATE, Explainer.inEnglish(s, report.validationTime()).verdict());
    }

    @Test
    void signerTrustedAsAnchorIsTotalPassed() {
        CommonTrustedCertificateSource trust = new CommonTrustedCertificateSource();
        trust.addCertificate(signer.certificate());
        ValidationReport report = new DssValidationEngine(trust, false).validate(ValidationRequest.now(signed));
        SignatureResult s = single(report);
        assertEquals("TOTAL_PASSED", s.indication(), () -> s.subIndication() + " " + s.errors());
        assertEquals(Verdict.VALID, Explainer.inEnglish(s, report.validationTime()).verdict());

        SignatureResult.Details d = s.details();
        assertNotNull(d.bestSignatureTime(), "best signature time");
        assertNotNull(d.certificateValidUntil(), "certificate validity");
        assertEquals(1, d.certificateChain().size(), "self-signed: chain is the signer alone " + d.certificateChain());
        assertTrue(d.certificateChain().get(0).contains("Test Signer"), d.certificateChain().toString());
        assertEquals("FULL", d.scope(), "signature covers the whole document");
        assertTrue(d.byteRange().startsWith("[0, "), d.byteRange());
        assertFalse(d.modificationsDetected());
        assertTrue(d.algorithm().startsWith("RSA-SHA256"), d.algorithm());
        assertEquals("Signature1", d.signatureField());
    }

    @Test
    void tamperedDocumentIsTotalFailedHashFailure() {
        CommonTrustedCertificateSource trust = new CommonTrustedCertificateSource();
        trust.addCertificate(signer.certificate());
        ValidationReport report = new DssValidationEngine(trust, false).validate(ValidationRequest.now(tampered));
        SignatureResult s = single(report);
        assertEquals("TOTAL_FAILED", s.indication(), () -> s.subIndication() + " " + s.errors());
        assertEquals("HASH_FAILURE", s.subIndication());
        assertEquals(Verdict.INVALID, Explainer.inEnglish(s, report.validationTime()).verdict());
    }

    @Test
    void validationTimeIsEchoedAndEngineReportKept() {
        Instant at = Instant.parse("2026-10-11T12:00:00Z");
        ValidationReport report = DssValidationEngine.offlineWithoutTrust()
                .validate(new ValidationRequest(signed, at));
        assertEquals(at, report.validationTime());
        assertNotNull(report.engineReport());
        assertTrue(report.engineReport().contains("SimpleReport"));
    }

    @Test
    void nonPdfIsRejectedWithValidationException() throws Exception {
        Path garbage = Files.writeString(dir.resolve("garbage.bin"), "not a pdf");
        assertThrows(ValidationException.class,
                () -> DssValidationEngine.offlineWithoutTrust().validate(ValidationRequest.now(garbage)));
    }

    private static SignatureResult single(ValidationReport report) {
        assertEquals(1, report.signatures().size(), "expected exactly one signature");
        return report.signatures().get(0);
    }
}
