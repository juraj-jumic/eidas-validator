package org.eidasvalidation.explain;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.i18n.Messages;

import java.time.Instant;
import java.util.Map;

/**
 * Maps ETSI EN 319 102-1 indications and DSS qualifications to plain language, in the language
 * of the given {@link Messages}. Every sentence comes from the catalog, so adding a
 * sub-indication or a translation is a properties change, not a code change.
 */
public final class Explainer {

    /** Sub-indications that share wording with another one. */
    private static final Map<String, String> ALIASES = Map.of(
            "CERTIFICATE_CHAIN_GENERAL_FAILURE", "TRY_LATER",
            "OUT_OF_BOUNDS_NOT_REVOKED", "OUT_OF_BOUNDS_NO_POE",
            "NOT_ADES_QC", "NOT_ADES",
            "NOT_ADES_QC_QSCD", "NOT_ADES");

    private final Messages m;

    public Explainer(Messages messages) {
        this.m = messages;
    }

    /** English, for callers that have no language preference. */
    public static Explanation inEnglish(SignatureResult s, Instant validationTime) {
        return new Explainer(Messages.english()).explain(s, validationTime, false);
    }

    public static Explanation inEnglish(SignatureResult s, Instant validationTime, boolean trustedListsLoaded) {
        return new Explainer(Messages.english()).explain(s, validationTime, trustedListsLoaded);
    }

    /**
     * @param trustedListsLoaded whether EU trusted lists were available; changes how an undetermined
     *                           qualification is worded (data missing vs. provider not listed)
     */
    public Explanation explain(SignatureResult s, Instant validationTime, boolean trustedListsLoaded) {
        String indication = s.indication() == null ? "" : s.indication();
        String sub = s.subIndication() == null ? "" : s.subIndication();
        String q = qualification(s, trustedListsLoaded);

        return switch (indication) {
            case "TOTAL_PASSED", "PASSED" -> new Explanation(Verdict.VALID,
                    m.format("passed.headline", m.dateTime(validationTime)),
                    m.get("passed.detail"), q, null);
            case "TOTAL_FAILED", "FAILED" -> byKey("failed", sub, s, Verdict.INVALID, q, trustedListsLoaded);
            case "INDETERMINATE" -> byKey("indeterminate", sub, s, Verdict.INDETERMINATE, q, trustedListsLoaded);
            default -> new Explanation(Verdict.INDETERMINATE,
                    m.get("unexpected.headline"),
                    m.format("unexpected.detail", indication, sub), q,
                    m.get("unexpected.next"));
        };
    }

    private Explanation byKey(String group, String sub, SignatureResult s, Verdict verdict, String q, boolean tl) {
        String name = ALIASES.getOrDefault(sub, sub);
        String base = group + "." + name;
        if (!m.has(base + ".headline")) {
            base = group + ".default";
            String errors = String.join(" ", s.errors());
            return new Explanation(verdict,
                    m.get(base + ".headline"),
                    m.format(base + ".detail", sub, errors), q,
                    m.get(base + ".next"));
        }
        String suffix = tl ? ".tl" : ".notl";
        String detail = m.has(base + ".detail" + suffix) ? m.get(base + ".detail" + suffix) : m.get(base + ".detail");
        String next = m.has(base + ".next" + suffix) ? m.get(base + ".next" + suffix)
                : m.has(base + ".next") ? m.get(base + ".next") : null;
        return new Explanation(verdict, m.get(base + ".headline"), detail, q, next);
    }

    private String qualification(SignatureResult s, boolean tl) {
        String q = s.qualification() == null || s.qualification().isEmpty() ? "NA" : s.qualification();
        String name = ALIASES.getOrDefault(q, q);
        if (name.equals("NA")) {
            return m.get(tl ? "qualification.NA.tl" : "qualification.NA.notl");
        }
        if (m.has("qualification." + name)) {
            return m.get("qualification." + name);
        }
        if (name.startsWith("INDETERMINATE")) {
            return m.format("qualification.INDETERMINATE", q);
        }
        return m.format("qualification.other", q);
    }
}
