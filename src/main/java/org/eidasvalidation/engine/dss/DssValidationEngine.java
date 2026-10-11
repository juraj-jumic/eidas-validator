package org.eidasvalidation.engine.dss;

import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.SignatureQualification;
import eu.europa.esig.dss.enumerations.SubIndication;
import eu.europa.esig.dss.jaxb.object.Message;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CertificateVerifier;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CertificateSource;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;
import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.engine.ValidationEngine;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.engine.ValidationReport;
import org.eidasvalidation.engine.ValidationRequest;
import org.eidasvalidation.net.FetchPolicy;
import org.eidasvalidation.net.GuardedFetcher;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * {@link ValidationEngine} backed by EU DSS.
 *
 * <p>DSS is used unmodified as a library. Trust anchors come from the supplied
 * {@link CertificateSource}; with an empty source every signature ends up INDETERMINATE
 * (no certificate chain found), which is the honest answer when no trusted list is loaded.
 */
public final class DssValidationEngine implements ValidationEngine {

    private static final String DSS_VERSION = loadDssVersion();

    private final List<CertificateSource> trustedCertificates;
    private final TrustSummary trustSummary;
    private final boolean online;

    /**
     * @param trustedCertificates trust anchor sources (e.g. an EU trusted-lists source plus extra anchors)
     * @param trustSummary        description of those anchors, copied into every report
     * @param online              whether CRL, OCSP and AIA lookups over the network are allowed
     */
    public DssValidationEngine(List<CertificateSource> trustedCertificates, TrustSummary trustSummary,
                               boolean online) {
        this.trustedCertificates = List.copyOf(trustedCertificates);
        this.trustSummary = Objects.requireNonNull(trustSummary, "trustSummary");
        this.online = online;
    }

    public DssValidationEngine(CertificateSource trustedCertificates, TrustSummary trustSummary, boolean online) {
        this(List.of(trustedCertificates), trustSummary, online);
    }

    public DssValidationEngine(CertificateSource trustedCertificates, boolean online) {
        this(trustedCertificates, TrustSummary.anchors(trustedCertificates.getCertificates().size()), online);
    }

    /** Engine with no trust anchors and no network access; useful for integrity-only checks and tests. */
    public static DssValidationEngine offlineWithoutTrust() {
        return new DssValidationEngine(new CommonTrustedCertificateSource(), TrustSummary.none(), false);
    }

    @Override
    public String name() {
        return "EU DSS " + DSS_VERSION;
    }

    @Override
    public ValidationReport validate(ValidationRequest request) {
        DSSDocument document = new FileDocument(request.document().toFile());
        SignedDocumentValidator validator;
        try {
            validator = SignedDocumentValidator.fromDocument(document);
        } catch (RuntimeException e) {
            throw new ValidationException("Document format not recognised: " + request.document(), e);
        }
        validator.setCertificateVerifier(certificateVerifier());
        validator.setValidationTime(Date.from(request.validationTime()));

        Reports reports;
        try {
            reports = validator.validateDocument();
        } catch (RuntimeException e) {
            throw new ValidationException("Engine failed while validating " + request.document(), e);
        }

        SimpleReport simple = reports.getSimpleReport();
        List<SignatureResult> results = new ArrayList<>();
        for (String id : simple.getSignatureIdList()) {
            results.add(toResult(simple, id));
        }
        return new ValidationReport(name(), request.validationTime(), trustSummary, List.copyOf(results),
                reports.getXmlSimpleReport());
    }

    private CertificateVerifier certificateVerifier() {
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        verifier.setTrustedCertSources(trustedCertificates.toArray(new CertificateSource[0]));
        if (online) {
            // URLs for these come from the certificates in the document, i.e. from untrusted input,
            // so every fetch goes through the guarded fetcher.
            FetchPolicy policy = FetchPolicy.revocation();
            verifier.setCrlSource(new OnlineCRLSource(new GuardedFetcher(policy)));
            verifier.setOcspSource(new OnlineOCSPSource(GuardedFetcher.forOcsp(policy)));
            verifier.setAIASource(new DefaultAIASource(new GuardedFetcher(policy)));
        }
        return verifier;
    }

    private static SignatureResult toResult(SimpleReport simple, String id) {
        Indication indication = simple.getIndication(id);
        SubIndication subIndication = simple.getSubIndication(id);
        SignatureQualification qualification = simple.getSignatureQualification(id);
        Date signingTime = simple.getSigningTime(id);

        return new SignatureResult(
                id,
                simple.getSignedBy(id),
                toInstant(signingTime),
                String.valueOf(simple.getSignatureFormat(id)),
                indication == null ? null : indication.name(),
                subIndication == null ? null : subIndication.name(),
                qualification == null ? null : qualification.name(),
                messages(simple.getAdESValidationErrors(id)),
                messages(simple.getAdESValidationWarnings(id)),
                messages(simple.getQualificationErrors(id)),
                messages(simple.getQualificationWarnings(id)));
    }

    private static List<String> messages(List<Message> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream().map(Message::getValue).filter(Objects::nonNull).toList();
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private static String loadDssVersion() {
        Properties props = new Properties();
        try (InputStream in = DssValidationEngine.class.getResourceAsStream("/engine.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // fall through to "unknown"
        }
        return props.getProperty("dss.version", "unknown");
    }
}
