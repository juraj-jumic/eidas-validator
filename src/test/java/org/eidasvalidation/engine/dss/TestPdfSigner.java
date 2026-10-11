package org.eidasvalidation.engine.dss;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import eu.europa.esig.dss.token.Pkcs12SignatureToken;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Builds throwaway test material: a self-signed certificate, an unsigned PDF, and PAdES-B
 * signed copies. This is the seed of the generated part of the test corpus.
 */
public final class TestPdfSigner {

    public static final String PAGE_TEXT = "Hello eIDAS validator";
    private static final char[] PASSWORD = "test".toCharArray();

    private final Path keystore;
    private final CertificateToken certificate;

    private TestPdfSigner(Path keystore, CertificateToken certificate) {
        this.keystore = keystore;
        this.certificate = certificate;
    }

    /** Creates a fresh RSA key and self-signed certificate valid from yesterday for one year. */
    public static TestPdfSigner selfSigned(Path dir, String subject) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        Instant now = Instant.now();
        X500Name name = new X500Name("CN=" + subject + ", O=eIDAS validator tests, C=EU");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(365, ChronoUnit.DAYS)),
                name,
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("signer", keyPair.getPrivate(), PASSWORD, new Certificate[]{cert});
        Path keystore = dir.resolve(subject.replace(' ', '_') + ".p12");
        try (OutputStream out = Files.newOutputStream(keystore)) {
            store.store(out, PASSWORD);
        }
        return new TestPdfSigner(keystore, new CertificateToken(cert));
    }

    public CertificateToken certificate() {
        return certificate;
    }

    /** Writes a one-page PDF with an uncompressed content stream so tests can tamper with the text. */
    public static Path createPdf(Path target) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(
                    pdf, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(PAGE_TEXT);
                content.endText();
            }
            pdf.save(target.toFile());
        }
        return target;
    }

    /** Signs {@code source} with a PAdES-BASELINE-B signature and writes it to {@code target}. */
    public Path signBaselineB(Path source, Path target) throws IOException {
        try (Pkcs12SignatureToken token = new Pkcs12SignatureToken(
                keystore.toFile(), new KeyStore.PasswordProtection(PASSWORD))) {
            DSSPrivateKeyEntry key = token.getKeys().get(0);

            PAdESSignatureParameters parameters = new PAdESSignatureParameters();
            parameters.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
            parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
            parameters.setSigningCertificate(key.getCertificate());
            parameters.setCertificateChain(key.getCertificateChain());
            parameters.bLevel().setSigningDate(new Date());

            PAdESService service = new PAdESService(new CommonCertificateVerifier());
            DSSDocument document = new FileDocument(source.toFile());
            ToBeSigned toBeSigned = service.getDataToSign(document, parameters);
            SignatureValue signatureValue = token.sign(toBeSigned, parameters.getDigestAlgorithm(), key);
            DSSDocument signed = service.signDocument(document, parameters, signatureValue);
            signed.save(target.toString());
        }
        return target;
    }

    /** Copies a signed PDF and flips the page text inside the signed byte range. */
    public static Path tamper(Path signed, Path target) throws IOException {
        byte[] bytes = Files.readAllBytes(signed);
        byte[] needle = PAGE_TEXT.getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(bytes, needle);
        if (at < 0) {
            throw new IllegalStateException("page text not found in plain form; cannot tamper");
        }
        bytes[at] = (byte) 'J'; // "Hello" -> "Jello"
        Files.write(target, bytes);
        return target;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
