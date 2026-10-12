package org.eidasvalidation.engine;

import java.time.Instant;
import java.util.List;

/**
 * Outcome for a single signature, using the standard ETSI EN 319 102-1 vocabulary.
 *
 * @param id            engine-assigned signature identifier
 * @param signedBy      subject name from the signing certificate, if available
 * @param claimedSigningTime the time the signer claimed; not proven unless covered by a timestamp
 * @param format        signature format and level, e.g. {@code PAdES-BASELINE-B}
 * @param indication    {@code TOTAL_PASSED}, {@code TOTAL_FAILED}, {@code INDETERMINATE} or {@code PASSED}/{@code FAILED}
 * @param subIndication reason behind a non-passed indication, or null
 * @param qualification engine's eIDAS qualification (e.g. {@code QESIG}, {@code ADESIG_QC}, {@code NA}), or null
 * @param errors        engine errors from the AdES validation itself (these explain a non-passed indication)
 * @param warnings      engine warnings from the AdES validation
 * @param qualificationErrors   errors from the eIDAS qualification step only; a TOTAL_PASSED signature can
 *                              still carry these when no trusted list is loaded
 * @param qualificationWarnings warnings from the qualification step
 * @param details       facts about the certificate, the chain, timing and PDF coverage, for the report
 */
public record SignatureResult(String id,
                              String signedBy,
                              Instant claimedSigningTime,
                              String format,
                              String indication,
                              String subIndication,
                              String qualification,
                              List<String> errors,
                              List<String> warnings,
                              List<String> qualificationErrors,
                              List<String> qualificationWarnings,
                              Details details) {

    /**
     * @param bestSignatureTime     earliest time the signature is proven to have existed (a timestamp), or
     *                              the validation time when nothing proves an earlier moment
     * @param certificateValidFrom  start of the signing certificate's validity
     * @param certificateValidUntil end of the signing certificate's validity
     * @param certificateChain      readable names from the signer up to the trust anchor, in that order
     * @param signatureField        name of the PDF signature field, if any
     * @param scope                 {@code FULL} when the signature covers the whole document, {@code PARTIAL}
     *                              when later revisions exist, or null when unknown
     * @param scopeDescription      the engine's description of the covered range
     * @param byteRange             the PDF ByteRange, e.g. {@code [0, 57303, 109305, 23559]}
     * @param modificationsDetected true when the engine found changes after this signature
     * @param algorithm             e.g. {@code RSA-SHA256 (2048 bit)}
     */
    public record Details(Instant bestSignatureTime,
                          Instant certificateValidFrom,
                          Instant certificateValidUntil,
                          List<String> certificateChain,
                          String signatureField,
                          String scope,
                          String scopeDescription,
                          String byteRange,
                          boolean modificationsDetected,
                          String algorithm) {

        public static Details none() {
            return new Details(null, null, null, List.of(), null, null, null, null, false, null);
        }
    }

    /** Result without details, for callers and tests that only need the outcome. */
    public static SignatureResult of(String id, String signedBy, Instant claimedSigningTime, String format,
                                     String indication, String subIndication, String qualification,
                                     List<String> errors, List<String> warnings,
                                     List<String> qualificationErrors, List<String> qualificationWarnings) {
        return new SignatureResult(id, signedBy, claimedSigningTime, format, indication, subIndication, qualification,
                errors, warnings, qualificationErrors, qualificationWarnings, Details.none());
    }

    public Details details() {
        return details == null ? Details.none() : details;
    }
}
