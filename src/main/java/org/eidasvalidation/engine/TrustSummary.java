package org.eidasvalidation.engine;

import java.time.Instant;
import java.util.List;

/**
 * What trust data a report was produced against. Every report carries this so the reader knows
 * which trusted-list version was used and when it was fetched.
 *
 * @param source             human-readable origin, e.g. "EU trusted lists (LOTL)" or "none"
 * @param synchronisedAt     when the data was last fetched or confirmed up to date; null if unknown
 * @param lotlSequenceNumber sequence number of the list of trusted lists, null if not applicable
 * @param lotlIssued         issue date of the LOTL
 * @param lotlNextUpdate     date by which a newer LOTL is expected
 * @param lotlSignatureValid whether the LOTL signature verified against the Official Journal certificates
 * @param trustedLists       number of national trusted lists referenced by the LOTL
 * @param trustedListsValid  how many of those loaded, parsed and verified
 * @param certificates       number of trust anchors available to the engine
 * @param problems           per-list problems in plain words (download failed, invalid signature, expired)
 */
public record TrustSummary(String source,
                           Instant synchronisedAt,
                           Long lotlSequenceNumber,
                           Instant lotlIssued,
                           Instant lotlNextUpdate,
                           Boolean lotlSignatureValid,
                           int trustedLists,
                           int trustedListsValid,
                           int certificates,
                           List<String> problems) {

    public static TrustSummary none() {
        return new TrustSummary("none", null, null, null, null, null, 0, 0, 0, List.of());
    }

    public static TrustSummary anchors(int certificates) {
        return new TrustSummary("trust anchor files given on the command line; no EU trusted lists",
                null, null, null, null, null, 0, 0, certificates, List.of());
    }

    public boolean usesTrustedLists() {
        return lotlSequenceNumber != null;
    }

    /** One line for the text report. */
    public String describe() {
        if (!usesTrustedLists()) {
            return source + (certificates > 0 ? " (" + certificates + " certificate(s))" : "");
        }
        StringBuilder sb = new StringBuilder(source)
                .append(", LOTL no. ").append(lotlSequenceNumber)
                .append(" issued ").append(lotlIssued == null ? "?" : lotlIssued.toString().substring(0, 10))
                .append(", signature ").append(Boolean.TRUE.equals(lotlSignatureValid) ? "verified" : "NOT verified")
                .append("; ").append(trustedListsValid).append(" of ").append(trustedLists)
                .append(" national lists usable, ").append(certificates).append(" trust anchors");
        if (synchronisedAt != null) {
            sb.append("; synchronised ").append(synchronisedAt.toString().substring(0, 16)).append("Z");
        }
        return sb.toString();
    }
}
