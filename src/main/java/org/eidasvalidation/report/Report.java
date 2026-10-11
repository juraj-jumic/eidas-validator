package org.eidasvalidation.report;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.engine.ValidationReport;
import org.eidasvalidation.explain.Explainer;
import org.eidasvalidation.explain.Verdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The versioned report. JSON is produced from this record and the HTML report is rendered from
 * it, so both views always say the same thing.
 *
 * @param reportVersion  bumped whenever the JSON shape changes
 * @param file           display name of the checked document
 * @param validationTime the time the verdicts refer to
 * @param online         whether revocation lookups over the network were allowed
 * @param engine         engine name and version
 * @param trust          trust data the verdicts rest on
 * @param verdict        overall verdict: the worst across signatures, or NO_SIGNATURE
 * @param signatures     per-signature explanation and result
 * @param engineReport   the engine's own report (DSS simple report XML)
 */
public record Report(int reportVersion,
                     String file,
                     Instant validationTime,
                     boolean online,
                     String engine,
                     TrustSummary trust,
                     Verdict verdict,
                     List<SignatureReport> signatures,
                     String engineReport) {

    public static final int VERSION = 3;

    public static Report from(ValidationReport vr, String displayName, boolean online) {
        boolean tl = vr.trust().usesTrustedLists();
        List<SignatureReport> sigs = new ArrayList<>();
        for (SignatureResult s : vr.signatures()) {
            sigs.add(new SignatureReport(Explainer.explain(s, vr.validationTime(), tl), s));
        }
        return new Report(VERSION, displayName, vr.validationTime(), online, vr.engine(), vr.trust(),
                overall(sigs), List.copyOf(sigs), vr.engineReport());
    }

    static Verdict overall(List<SignatureReport> sigs) {
        if (sigs.isEmpty()) {
            return Verdict.NO_SIGNATURE;
        }
        Verdict worst = Verdict.VALID;
        for (SignatureReport s : sigs) {
            Verdict v = s.explanation().verdict();
            if (v == Verdict.INVALID) {
                return Verdict.INVALID;
            }
            if (v == Verdict.INDETERMINATE) {
                worst = Verdict.INDETERMINATE;
            }
        }
        return worst;
    }

    /** Exit code contract of the CLI: 0 valid, 1 invalid, 2 indeterminate, 3 no signature. */
    @JsonIgnore
    public int exitCode() {
        return switch (verdict) {
            case VALID -> 0;
            case INVALID -> 1;
            case INDETERMINATE -> 2;
            case NO_SIGNATURE -> 3;
        };
    }
}
