package org.eidasvalidation.service;

import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.x509.CertificateSource;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.engine.ValidationEngine;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.engine.ValidationReport;
import org.eidasvalidation.engine.ValidationRequest;
import org.eidasvalidation.engine.dss.DssValidationEngine;
import org.eidasvalidation.engine.dss.EuTrustedLists;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.Report;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads trust data once and validates documents against it. The CLI uses one instance per run;
 * the local web server keeps one and recreates it when the trusted lists are due for refresh.
 */
public final class ValidationService {

    private final ValidatorOptions options;
    private final List<CertificateSource> sources;
    private final TrustSummary trust;
    private final Instant createdAt = Instant.now();

    private ValidationService(ValidatorOptions options, List<CertificateSource> sources, TrustSummary trust) {
        this.options = options;
        this.sources = List.copyOf(sources);
        this.trust = trust;
    }

    /**
     * @throws IOException if the trusted lists or a trust anchor file cannot be loaded
     */
    public static ValidationService create(ValidatorOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        List<CertificateSource> sources = new ArrayList<>();
        TrustSummary summary;

        if (options.trustedLists()) {
            EuTrustedLists.Loaded loaded = EuTrustedLists
                    .configure(options.cacheDir(), options.tlMaxAge(), options.offline(), options.lotlSigners())
                    .load();
            sources.add(loaded.source());
            summary = loaded.summary();
        } else {
            summary = TrustSummary.none();
        }

        if (!options.trustAnchors().isEmpty()) {
            CommonTrustedCertificateSource extra = new CommonTrustedCertificateSource();
            for (Path anchor : options.trustAnchors()) {
                try {
                    extra.addCertificate(DSSUtils.loadCertificate(anchor.toFile()));
                } catch (RuntimeException e) {
                    throw new IOException("Cannot read trust anchor " + anchor + ": " + e.getMessage(), e);
                }
            }
            sources.add(extra);
            summary = options.trustedLists() ? summary.withExtraAnchors(options.trustAnchors().size())
                    : TrustSummary.anchors(options.trustAnchors().size());
        }
        if (sources.isEmpty()) {
            sources.add(new CommonTrustedCertificateSource());
        }
        return new ValidationService(options, sources, summary);
    }

    public ValidatorOptions options() {
        return options;
    }

    public TrustSummary trust() {
        return trust;
    }

    /** True when the trusted lists loaded by this instance are older than the configured maximum age. */
    public boolean isStale() {
        return options.trustedLists() && !options.offline()
                && Instant.now().isAfter(createdAt.plus(options.tlMaxAge()));
    }

    /** English report; see {@link #validate(Path, String, Instant, boolean, Messages)}. */
    public Report validate(Path document, String displayName, Instant at, boolean online) {
        return validate(document, displayName, at, online, Messages.english());
    }

    /**
     * @param document    the file to validate
     * @param displayName name shown in the report (the original file name for uploads)
     * @param at          validation time
     * @param online      allow CRL/OCSP/AIA lookups; ignored when the service is offline
     * @param messages    language of the explanations
     * @throws ValidationException if the document cannot be parsed at all
     */
    public Report validate(Path document, String displayName, Instant at, boolean online, Messages messages) {
        boolean allowNetwork = online && !options.offline();
        ValidationEngine engine = new DssValidationEngine(sources, trust, allowNetwork);
        ValidationReport vr = engine.validate(new ValidationRequest(document, at));
        return Report.from(vr, displayName, allowNetwork, messages);
    }
}
