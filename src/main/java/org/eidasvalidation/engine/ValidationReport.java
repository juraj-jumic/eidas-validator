package org.eidasvalidation.engine;

import java.time.Instant;
import java.util.List;

/**
 * Engine-neutral result for one document.
 *
 * @param engine         engine name and version that produced the report
 * @param validationTime the time the verdicts refer to
 * @param trust          which trust data the verdicts rest on
 * @param signatures     one entry per signature found; empty if the document is unsigned
 * @param engineReport   the engine's own report (DSS: simple report XML), kept verbatim so the
 *                       technical detail is one click away in the UI
 */
public record ValidationReport(String engine, Instant validationTime, TrustSummary trust,
                               List<SignatureResult> signatures, String engineReport) {

    public boolean hasSignatures() {
        return !signatures.isEmpty();
    }
}
