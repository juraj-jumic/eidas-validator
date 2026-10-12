package org.eidasvalidation.report;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.explain.Explanation;
import org.eidasvalidation.explain.Verdict;
import org.eidasvalidation.i18n.Messages;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link Report} as one self-contained HTML page in the shared {@link Theme}: a status
 * banner, one card per signature (badges, facts, certificate chain, plain-language reading,
 * collapsed technical details), what the check relied on, what was and was not checked, and the
 * raw reports. No scripts, no external resources; verdicts are stated in words, colour is an
 * accent. All text comes from the report’s message catalog.
 */
public final class HtmlReport {

    private static final Map<String, String> QUALIFICATION_ALIASES = Map.of(
            "NOT_ADES_QC", "NOT_ADES", "NOT_ADES_QC_QSCD", "NOT_ADES");

    private HtmlReport() {
    }

    public static String render(Report r) {
        Messages m = r.messages();
        StringBuilder h = new StringBuilder(24 * 1024);
        h.append(Theme.head(m, m.format("html.title", r.file()))).append("</head>\n<body>\n");
        h.append(Theme.topbar(m));
        h.append("<main>\n<h1>").append(esc(m.get("html.h1"))).append("</h1>\n");

        renderBanner(h, r, m);
        int n = 1;
        for (SignatureReport sr : r.signatures()) {
            renderSigner(h, r, m, sr, n++);
        }
        renderRelied(h, r, m);
        renderScope(h, r, m);
        renderRaw(h, r, m);
        h.append("</main>\n").append(Theme.footer(m, r.engine())).append("</body>\n</html>\n");
        return h.toString();
    }

    // ---- sections -----------------------------------------------------------------------------

    private static void renderBanner(StringBuilder h, Report r, Messages m) {
        String cls = cssClass(r.verdict());
        int total = r.signatures().size();
        long valid = r.signatures().stream().filter(s -> s.explanation().verdict() == Verdict.VALID).count();
        long invalid = r.signatures().stream().filter(s -> s.explanation().verdict() == Verdict.INVALID).count();
        long indet = r.signatures().stream().filter(s -> s.explanation().verdict() == Verdict.INDETERMINATE).count();
        String title;
        String sub;
        switch (r.verdict()) {
            case VALID -> { title = m.get("ui.banner.valid.h"); sub = m.format("ui.banner.valid.sub", valid, total); }
            case INVALID -> { title = m.get("ui.banner.invalid.h"); sub = m.format("ui.banner.invalid.sub", invalid, total); }
            case INDETERMINATE -> { title = m.get("ui.banner.indeterminate.h"); sub = m.format("ui.banner.indeterminate.sub", indet, total); }
            default -> { title = m.get("ui.banner.none.h"); sub = m.get("ui.banner.none.sub"); }
        }
        h.append("<section class=\"banner v-").append(cls).append("\" aria-labelledby=\"verdict-heading\">\n")
         .append(Theme.icon(cls))
         .append("<div><h2 id=\"verdict-heading\">").append(esc(title)).append("</h2>\n<p>").append(esc(sub)).append("</p></div>\n")
         .append("<div class=\"meta\"><span>").append(esc(m.get("ui.banner.file"))).append("</span><b>").append(esc(r.file())).append("</b>")
         .append("<span>").append(esc(m.get("ui.banner.checked"))).append("</span><b>").append(esc(m.dateTime(r.validationTime()))).append("</b></div>\n")
         .append("</section>\n");
    }

    private static void renderSigner(StringBuilder h, Report r, Messages m, SignatureReport sr, int n) {
        Explanation e = sr.explanation();
        SignatureResult s = sr.result();
        SignatureResult.Details d = s.details();
        String cls = cssClass(e.verdict());

        h.append("<article class=\"card signer\" aria-label=\"").append(esc(m.format("html.signature.n", n, r.signatures().size()))).append("\">\n");
        h.append("<div class=\"signer-head\"><div class=\"num\" aria-hidden=\"true\">").append(n).append("</div><div>\n");
        h.append("<h3>").append(esc(s.signedBy() == null ? m.format("html.signature.n", n, r.signatures().size()) : s.signedBy())).append("</h3>\n");
        h.append("<div class=\"badges\">");
        badge(h, cls, m.get("ui.badge." + e.verdict().name()));
        String q = s.qualification() == null ? "NA" : s.qualification();
        badge(h, qualificationBadgeClass(q), qualificationBadge(m, q));
        if (s.format() != null && !"null".equals(s.format())) {
            badge(h, "", s.format());
        }
        h.append("</div></div></div>\n");

        boolean noPoe = d.bestSignatureTime() == null
                || Math.abs(d.bestSignatureTime().getEpochSecond() - r.validationTime().getEpochSecond()) < 5;
        h.append("<div class=\"facts\">\n");
        fact(h, m.get("ui.fact.signingTime"), s.claimedSigningTime() == null ? m.get("ui.notStated") : m.dateTime(s.claimedSigningTime()), null);
        fact(h, m.get("ui.fact.bestTime"), d.bestSignatureTime() == null ? m.get("ui.notStated") : m.dateTime(d.bestSignatureTime()),
                noPoe ? m.get("ui.fact.bestTime.note") : null);
        fact(h, m.get("ui.fact.validUntil"), d.certificateValidUntil() == null ? m.get("ui.notStated") : m.dateTime(d.certificateValidUntil()), null);
        String scope = d.scope() == null ? m.get("ui.scope.unknown")
                : m.has("ui.scope." + d.scope()) ? m.get("ui.scope." + d.scope()) : d.scope();
        fact(h, m.get("ui.fact.scope"), scope, d.modificationsDetected() ? m.get("ui.modified") : null);
        h.append("</div>\n");

        if (!d.certificateChain().isEmpty()) {
            h.append("<div class=\"chain\"><div class=\"fact\" style=\"padding:0;border:0\"><div class=\"k\">").append(esc(m.get("ui.chain"))).append("</div></div><ol>");
            boolean first = true;
            for (String name : d.certificateChain()) {
                if (!first) {
                    h.append("<span class=\"sep\" aria-hidden=\"true\">›</span>");
                }
                h.append("<li>").append(esc(name)).append("</li>");
                first = false;
            }
            h.append("</ol></div>\n");
        }

        h.append("<div class=\"explain\">\n<p><strong>").append(esc(e.headline())).append("</strong></p>\n");
        h.append("<p>").append(esc(e.detail())).append("</p>\n<p>").append(esc(e.qualification())).append("</p>\n");
        if (!s.qualificationErrors().isEmpty() || !s.qualificationWarnings().isEmpty()) {
            h.append("<ul>");
            for (String msg : s.qualificationErrors()) {
                h.append("<li>").append(esc(msg)).append("</li>");
            }
            for (String msg : s.qualificationWarnings()) {
                h.append("<li>").append(esc(msg)).append("</li>");
            }
            h.append("</ul>\n");
        }
        if (e.nextStep() != null) {
            h.append("<div class=\"next\">").append(Theme.ICON_DOC).append("<div><strong>").append(esc(m.get("html.next")))
             .append("</strong> ").append(esc(e.nextStep())).append("</div></div>\n");
        }
        h.append("</div>\n");

        h.append("<details class=\"tech\"><summary>").append(Theme.ICON_DOC).append(esc(m.get("ui.technical"))).append("</summary>\n<dl class=\"kv\">\n");
        kv(h, m.get("ui.tech.format"), s.format());
        kv(h, m.get("ui.tech.outcome"), s.indication() + (s.subIndication() == null ? "" : " / " + s.subIndication()));
        kv(h, m.get("ui.tech.qualification"), s.qualification());
        kv(h, m.get("ui.tech.algorithm"), d.algorithm());
        kv(h, m.get("ui.tech.field"), d.signatureField());
        kv(h, m.get("ui.tech.byteRange"), d.byteRange());
        kv(h, m.get("ui.tech.reference"), s.id());
        if (!s.errors().isEmpty() || !s.warnings().isEmpty()) {
            h.append("<div><dt>").append(esc(m.get("ui.tech.engineMessages"))).append("</dt><dd><ul>");
            for (String msg : s.errors()) {
                h.append("<li>").append(esc(m.get("html.error"))).append(' ').append(esc(msg)).append("</li>");
            }
            for (String msg : s.warnings()) {
                h.append("<li>").append(esc(m.get("html.warning"))).append(' ').append(esc(msg)).append("</li>");
            }
            h.append("</ul></dd></div>\n");
        }
        h.append("</dl>\n</details>\n</article>\n");
    }

    private static void renderRelied(StringBuilder h, Report r, Messages m) {
        TrustSummary t = r.trust();
        h.append("<section class=\"plain\" aria-labelledby=\"trust-heading\">\n<h2 id=\"trust-heading\">").append(esc(m.get("html.relied.h")))
         .append("</h2>\n<div class=\"card pad\"><dl class=\"list\">\n");
        dt(h, m.get("html.relied.checkedAt"), m.dateTime(r.validationTime()));
        dt(h, m.get("html.relied.trust"), t.describe(m));
        dt(h, m.get("html.relied.revocation"), m.get(r.online() ? "html.relied.revocation.online" : "html.relied.revocation.offline"));
        dt(h, m.get("html.relied.engine"), r.engine());
        h.append("</dl>\n");
        if (!t.problems().isEmpty()) {
            h.append("<p class=\"note\">").append(esc(m.get("html.relied.notes"))).append("</p>\n<ul>\n");
            for (String p : t.describeProblems(m)) {
                h.append("<li>").append(esc(p)).append("</li>\n");
            }
            h.append("</ul>\n");
        }
        h.append("</div>\n</section>\n");
    }

    private static void renderScope(StringBuilder h, Report r, Messages m) {
        boolean tl = r.trust().usesTrustedLists();
        h.append("<section class=\"plain\" aria-labelledby=\"scope-heading\">\n<h2 id=\"scope-heading\">").append(esc(m.get("html.scope.h")))
         .append("</h2>\n<div class=\"card pad cols\">\n<div><h3>").append(esc(m.get("html.scope.checked"))).append("</h3>\n<ul>\n");
        li(h, m.get("html.scope.checked.integrity"));
        li(h, m.get("html.scope.checked.value"));
        li(h, m.get(tl ? "html.scope.checked.chain.tl" : "html.scope.checked.chain.notl"));
        li(h, m.get(r.online() ? "html.scope.checked.revocation.online" : "html.scope.checked.revocation.offline"));
        li(h, m.get("html.scope.checked.timestamps"));
        h.append("</ul></div>\n<div><h3>").append(esc(m.get("html.scope.notChecked"))).append("</h3>\n<ul>\n");
        li(h, m.get("html.scope.notChecked.identity"));
        li(h, m.get("html.scope.notChecked.authority"));
        li(h, m.get("html.scope.notChecked.outside"));
        if (!tl) {
            li(h, m.get("html.scope.notChecked.notl"));
        }
        h.append("</ul></div>\n</div>\n</section>\n");
    }

    private static void renderRaw(StringBuilder h, Report r, Messages m) {
        h.append("<section class=\"plain\" aria-labelledby=\"technical-heading\">\n<h2 id=\"technical-heading\">").append(esc(m.get("html.technical.h"))).append("</h2>\n");
        h.append("<div class=\"card\"><details class=\"tech\"><summary>").append(esc(m.format("html.technical.json", r.reportVersion()))).append("</summary>\n<div class=\"kv\"><pre>")
         .append(esc(ReportJson.writeWithoutEngineReport(r))).append("</pre></div>\n</details>\n");
        if (r.engineReport() != null) {
            h.append("<details class=\"tech\"><summary>").append(esc(m.get("html.technical.engine"))).append("</summary>\n<div class=\"kv\"><pre>")
             .append(esc(r.engineReport())).append("</pre></div>\n</details>\n");
        }
        h.append("</div>\n</section>\n");
    }

    // ---- helpers ------------------------------------------------------------------------------

    static String cssClass(Verdict v) {
        return v.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String qualificationBadge(Messages m, String q) {
        String name = QUALIFICATION_ALIASES.getOrDefault(q, q);
        if (name.startsWith("INDETERMINATE")) {
            name = "INDETERMINATE";
        }
        return m.has("ui.badge.q." + name) ? m.get("ui.badge.q." + name) : q;
    }

    private static String qualificationBadgeClass(String q) {
        return q.startsWith("QES") ? "q" : "";
    }

    private static void badge(StringBuilder h, String cls, String text) {
        h.append("<span class=\"badge").append(cls.isEmpty() ? "" : " " + cls).append("\">").append(esc(text)).append("</span>");
    }

    private static void fact(StringBuilder h, String key, String value, String note) {
        h.append("<div class=\"fact\"><div class=\"k\">").append(esc(key)).append("</div><div class=\"v\">").append(esc(value)).append("</div>");
        if (note != null) {
            h.append("<div class=\"n\">").append(esc(note)).append("</div>");
        }
        h.append("</div>\n");
    }

    private static void kv(StringBuilder h, String key, String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return;
        }
        h.append("<div><dt>").append(esc(key)).append("</dt><dd>").append(esc(value)).append("</dd></div>\n");
    }

    private static void dt(StringBuilder h, String term, String value) {
        h.append("<dt>").append(esc(term)).append("</dt><dd>").append(esc(value == null ? "—" : value)).append("</dd>\n");
    }

    private static void li(StringBuilder h, String text) {
        h.append("<li>").append(esc(text)).append("</li>\n");
    }

    /** HTML-escapes text for element content and attribute values. */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Kept for callers that formatted dates through this class. */
    static String date(Messages m, Instant i) {
        return i == null ? "" : m.dateTime(i);
    }

    static boolean any(List<?> l) {
        return l != null && !l.isEmpty();
    }
}
