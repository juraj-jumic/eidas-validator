package org.eidasvalidation.explain;

import org.eidasvalidation.engine.SignatureResult;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Maps ETSI EN 319 102-1 indications and DSS qualifications to plain language.
 *
 * <p>Wording policy: say what was checked and found, never "legally valid". Messages are kept
 * here as plain strings for now; they will move into a translatable catalog.
 */
public final class Explainer {

    // English first; the locale moves into the message catalog together with the strings.
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private Explainer() {
    }

    public static Explanation explain(SignatureResult s, Instant validationTime) {
        return explain(s, validationTime, false);
    }

    /**
     * @param trustedListsLoaded whether EU trusted lists were available; changes how an undetermined
     *                           qualification is worded (data missing vs. provider not listed)
     */
    public static Explanation explain(SignatureResult s, Instant validationTime, boolean trustedListsLoaded) {
        String at = TIME.format(validationTime);
        String indication = s.indication() == null ? "" : s.indication();
        String sub = s.subIndication() == null ? "" : s.subIndication();

        return switch (indication) {
            case "TOTAL_PASSED", "PASSED" -> new Explanation(
                    Verdict.VALID,
                    "Valid as of " + at + ".",
                    "The document has not been changed since it was signed, and the signer's certificate "
                            + "chains to a trusted issuer at that time.",
                    qualification(s, trustedListsLoaded),
                    null);
            case "TOTAL_FAILED", "FAILED" -> failed(sub, s, trustedListsLoaded);
            case "INDETERMINATE" -> indeterminate(sub, s, trustedListsLoaded);
            default -> new Explanation(
                    Verdict.INDETERMINATE,
                    "Could not determine whether this signature is valid.",
                    "The engine returned an unexpected outcome (" + indication + "/" + sub + ").",
                    qualification(s, trustedListsLoaded),
                    "Open the technical report for details.");
        };
    }

    private static Explanation failed(String sub, SignatureResult s, boolean tl) {
        return switch (sub) {
            case "HASH_FAILURE" -> new Explanation(
                    Verdict.INVALID,
                    "The document was changed after it was signed.",
                    "The content covered by the signature no longer matches what the signer signed.",
                    qualification(s, tl),
                    "Do not rely on this copy. Ask the signer for the original file.");
            case "SIG_CRYPTO_FAILURE" -> new Explanation(
                    Verdict.INVALID,
                    "The signature does not match the signer's certificate.",
                    "The cryptographic signature value could not be verified with the certificate that "
                            + "claims to have produced it.",
                    qualification(s, tl),
                    "Do not rely on this file. Ask the signer to sign it again.");
            case "FORMAT_FAILURE" -> new Explanation(
                    Verdict.INVALID,
                    "The signature is malformed.",
                    "The signature structure does not follow the required format and cannot be checked.",
                    qualification(s, tl),
                    "Ask the signer to sign the document again with a standards-compliant tool.");
            case "REVOKED" -> new Explanation(
                    Verdict.INVALID,
                    "The signer's certificate was revoked before the document was signed.",
                    "The certificate authority withdrew the certificate, and the signature was made after that.",
                    qualification(s, tl),
                    "Treat the signature as invalid. Contact the signer.");
            case "EXPIRED" -> new Explanation(
                    Verdict.INVALID,
                    "The signer's certificate had expired when the document was signed.",
                    "The signature was made outside the certificate's validity period.",
                    qualification(s, tl),
                    "Ask the signer to sign again with a current certificate.");
            case "NOT_YET_VALID" -> new Explanation(
                    Verdict.INVALID,
                    "The signer's certificate was not yet valid when the document was signed.",
                    "The signature predates the start of the certificate's validity period.",
                    qualification(s, tl),
                    "Ask the signer to sign again.");
            default -> new Explanation(
                    Verdict.INVALID,
                    "This signature is not valid.",
                    "The engine reported " + sub + ". " + String.join(" ", s.errors()),
                    qualification(s, tl),
                    "Open the technical report for details.");
        };
    }

    private static Explanation indeterminate(String sub, SignatureResult s, boolean tl) {
        return switch (sub) {
            case "NO_CERTIFICATE_CHAIN_FOUND" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The document is intact, but the signer is not linked to any trusted issuer.",
                    "The signature matches the document, but the signer's certificate could not be traced "
                            + (tl ? "to any provider on the EU trusted lists."
                                  : "to a certificate authority this tool trusts."),
                    qualification(s, tl),
                    tl ? "The signer may have used a non-EU or self-issued certificate. Ask the signer which "
                            + "provider issued it."
                       : "No EU trusted list was loaded; run again with trusted lists enabled.");
            case "TRY_LATER", "CERTIFICATE_CHAIN_GENERAL_FAILURE" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The document is intact, but the signer's certificate status could not be checked.",
                    "The signature matches the document and the signer's certificate chains to a trusted "
                            + "provider, but revocation information (CRL or OCSP) was not available, so it is "
                            + "unknown whether the certificate had been withdrawn.",
                    qualification(s, tl),
                    "Run again with network access (--online), or try later.");
            case "OUT_OF_BOUNDS_NO_POE", "OUT_OF_BOUNDS_NOT_REVOKED" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The signer's certificate is expired, and there is no proof the signature was made earlier.",
                    "Without a trusted timestamp the tool cannot show that the document was signed while the "
                            + "certificate was still valid.",
                    qualification(s, tl),
                    "Ask the signer for a version with a qualified timestamp, or validate at an earlier date "
                            + "if you have independent proof of when it was signed.");
            case "REVOKED_NO_POE" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The signer's certificate was revoked, and there is no proof the signature was made earlier.",
                    "Without a trusted timestamp the tool cannot show that the document was signed before the "
                            + "revocation.",
                    qualification(s, tl),
                    "Treat with caution. Contact the signer.");
            case "NO_POE" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The signature cannot be placed in time.",
                    "No trusted proof of when the signature was made is available.",
                    qualification(s, tl),
                    "Ask the signer for a timestamped version.");
            case "CRYPTO_CONSTRAINTS_FAILURE_NO_POE" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The signature uses an algorithm that is no longer considered secure.",
                    "The algorithm or key length was acceptable at some point, but there is no proof the "
                            + "signature was made while it still was.",
                    qualification(s, tl),
                    "Ask the signer to sign again with a current algorithm.");
            case "NO_SIGNING_CERTIFICATE_FOUND" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The signer's certificate is missing from the document.",
                    "The signature does not include or reference a certificate that can be checked.",
                    qualification(s, tl),
                    "Ask the signer for a version that embeds the certificate.");
            case "SIGNED_DATA_NOT_FOUND" -> new Explanation(
                    Verdict.INDETERMINATE,
                    "The signed content could not be located.",
                    "The signature refers to data that is not present in the file.",
                    qualification(s, tl),
                    "Ask the signer for the complete file.");
            default -> new Explanation(
                    Verdict.INDETERMINATE,
                    "Could not determine whether this signature is valid.",
                    "The engine reported " + sub + ". " + String.join(" ", s.errors()),
                    qualification(s, tl),
                    "Open the technical report for details.");
        };
    }

    private static String qualification(SignatureResult s, boolean tl) {
        String q = s.qualification() == null ? "" : s.qualification();
        return switch (q) {
            case "QESIG" -> "Qualified electronic signature: the certificate is qualified and listed on the EU "
                    + "trusted lists, and it was used on a qualified signature creation device.";
            case "QESEAL" -> "Qualified electronic seal: a qualified certificate issued to an organisation, "
                    + "used on a qualified device.";
            case "ADESIG_QC" -> "Advanced signature with a qualified certificate, but no evidence of a qualified "
                    + "signature creation device. Not a qualified electronic signature.";
            case "ADESEAL_QC" -> "Advanced seal with a qualified certificate, but no evidence of a qualified device.";
            case "ADESIG" -> "Advanced signature. The certificate is not qualified under eIDAS.";
            case "ADESEAL" -> "Advanced seal. The certificate is not qualified under eIDAS.";
            case "NOT_ADES", "NOT_ADES_QC", "NOT_ADES_QC_QSCD" ->
                    "Not an advanced electronic signature: the signature does not meet the baseline requirements.";
            case "UNKNOWN" -> "Not a qualified signature. The certificate is listed on an EU trusted list, but "
                    + "as a service recognised at national level rather than as a qualified certificate issuer, "
                    + "so eIDAS qualification does not apply. See the trusted-list details below.";
            case "NA", "" -> tl
                    ? "Not qualified: the certificate does not chain to any provider listed on the EU trusted lists, "
                            + "so eIDAS qualification does not apply."
                    : "Qualification not determined. No EU trusted list data was available, so whether "
                            + "the certificate is qualified could not be checked.";
            default -> q.startsWith("INDETERMINATE")
                    ? "Qualification could not be confirmed (" + q + "): the trusted-list information was "
                            + "incomplete or the signature could not be fully validated."
                    : "Qualification: " + q + ".";
        };
    }
}
