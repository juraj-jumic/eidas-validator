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
                              List<String> qualificationWarnings) {
}
