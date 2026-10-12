package org.eidasvalidation.engine;

import org.eidasvalidation.i18n.Messages;

import java.time.Instant;
import java.util.List;

/**
 * What trust data a report was produced against. Every report carries this so the reader knows
 * which trusted-list version was used and when it was fetched.
 *
 * <p>The {@code source} and {@code problems} fields hold catalog keys where a fixed phrase is
 * meant (see {@link #describe(Messages)}), and free text where the engine supplied it.
 *
 * @param source             origin: a catalog key such as {@code trust.tl}, or free text
 * @param synchronisedAt     when the data was last fetched or confirmed up to date; null if unknown
 * @param lotlSequenceNumber sequence number of the list of trusted lists, null if not applicable
 * @param lotlIssued         issue date of the LOTL
 * @param lotlNextUpdate     date by which a newer LOTL is expected
 * @param lotlSignatureValid whether the LOTL signature verified against the Official Journal certificates
 * @param trustedLists       number of national trusted lists referenced by the LOTL
 * @param trustedListsValid  how many of those loaded, parsed and verified
 * @param certificates       number of trust anchors available to the engine
 * @param extraAnchorFiles   anchor files given on the command line in addition to the lists
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
                           int extraAnchorFiles,
                           List<String> problems) {

    public static TrustSummary none() {
        return new TrustSummary("trust.none", null, null, null, null, null, 0, 0, 0, 0, List.of());
    }

    public static TrustSummary anchors(int certificates) {
        return new TrustSummary("trust.anchors", null, null, null, null, null, 0, 0, certificates, 0, List.of());
    }

    public TrustSummary withExtraAnchors(int files) {
        return new TrustSummary(source, synchronisedAt, lotlSequenceNumber, lotlIssued, lotlNextUpdate,
                lotlSignatureValid, trustedLists, trustedListsValid, certificates + files, files, problems);
    }

    public boolean usesTrustedLists() {
        return lotlSequenceNumber != null;
    }

    /** One line for the text report, in English. */
    public String describe() {
        return describe(Messages.english());
    }

    /** One line for the text report. */
    public String describe(Messages m) {
        String src = m.has(source) ? m.get(source) : source;
        if (extraAnchorFiles > 0 && usesTrustedLists()) {
            src = m.format("trust.plusAnchors", src, extraAnchorFiles);
        }
        if (!usesTrustedLists()) {
            if (certificates > 0 && "trust.anchors".equals(source)) {
                return m.format("trust.anchors.withCount", src, certificates);
            }
            return src;
        }
        String line = m.format("trust.tl.describe", src, lotlSequenceNumber,
                lotlIssued == null ? "?" : lotlIssued.toString().substring(0, 10),
                m.get(Boolean.TRUE.equals(lotlSignatureValid) ? "trust.signature.verified" : "trust.signature.notVerified"),
                trustedListsValid, trustedLists, certificates);
        if (synchronisedAt != null) {
            line += m.format("trust.tl.synchronised", synchronisedAt.toString().substring(0, 16) + "Z");
        }
        return line;
    }

    /** Problems with fixed phrases resolved through the catalog; engine text passed through. */
    public List<String> describeProblems(Messages m) {
        return problems.stream().map(p -> m.has(p) ? m.get(p) : p).toList();
    }
}
