package org.eidasvalidation.report;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.explain.Explanation;
import org.eidasvalidation.explain.Verdict;
import org.eidasvalidation.i18n.Messages;

import java.util.Locale;

/**
 * Renders a {@link Report} as one self-contained HTML page: no scripts, no external resources,
 * semantic headings and lists, verdicts stated in words (colour is only an accent), and the
 * technical detail one click away behind {@code <details>}. All text comes from the report’s
 * message catalog.
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static String render(Report r) {
        Messages m = r.messages();
        StringBuilder h = new StringBuilder(16 * 1024);
        h.append("<!doctype html>\n<html lang=\"").append(esc(m.tag())).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
         .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
         .append("<title>").append(esc(m.format("html.title", r.file()))).append("</title>\n")
         .append("<style>\n").append(CSS).append("</style>\n</head>\n<body>\n<main>\n");

        h.append("<header>\n<h1>").append(esc(m.get("html.h1"))).append("</h1>\n<p class=\"file\">")
         .append(esc(m.get("html.file"))).append(" <strong>").append(esc(r.file())).append("</strong></p>\n</header>\n");

        renderOverall(h, r, m);
        renderSignatures(h, r, m);
        renderTrust(h, r, m);
        renderScope(h, r, m);
        renderTechnical(h, r, m);

        h.append("<footer>\n<p>").append(esc(m.format("html.footer", r.engine()))).append("</p>\n</footer>\n</main>\n</body>\n</html>\n");
        return h.toString();
    }

    private static void renderOverall(StringBuilder h, Report r, Messages m) {
        String cls = r.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-');
        h.append("<section class=\"verdict verdict-").append(cls).append("\" aria-labelledby=\"verdict-heading\">\n");
        switch (r.verdict()) {
            case NO_SIGNATURE -> heading(h, m.get("html.overall.none.h"), m.get("html.overall.none.p"));
            case VALID -> heading(h, m.format("html.overall.valid.h", m.dateTime(r.validationTime())),
                    r.signatures().size() == 1 ? m.get("html.overall.valid.one")
                            : m.format("html.overall.valid.many", r.signatures().size()));
            case INVALID -> heading(h, m.get("html.overall.invalid.h"), m.get("html.overall.invalid.p"));
            case INDETERMINATE -> heading(h, m.get("html.overall.indeterminate.h"), m.get("html.overall.indeterminate.p"));
        }
        h.append("</section>\n");
    }

    private static void heading(StringBuilder h, String title, String text) {
        h.append("<h2 id=\"verdict-heading\">").append(esc(title)).append("</h2>\n<p>").append(esc(text)).append("</p>\n");
    }

    private static void renderSignatures(StringBuilder h, Report r, Messages m) {
        if (r.signatures().isEmpty()) {
            return;
        }
        h.append("<section aria-labelledby=\"signatures-heading\">\n<h2 id=\"signatures-heading\">")
         .append(esc(m.get("html.signatures"))).append("</h2>\n");
        int n = 1;
        for (SignatureReport sr : r.signatures()) {
            Explanation e = sr.explanation();
            SignatureResult s = sr.result();
            String cls = e.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-');
            h.append("<article class=\"signature verdict-").append(cls).append("\">\n");
            h.append("<h3>").append(esc(m.format("html.signature.n", n++, r.signatures().size())));
            if (s.signedBy() != null) {
                h.append(" <span class=\"by\">").append(esc(m.format("html.signature.by", s.signedBy()))).append("</span>");
            }
            h.append("</h3>\n");
            h.append("<p class=\"headline\"><strong>").append(esc(m.get("verdict." + e.verdict().name()))).append(":</strong> ")
             .append(esc(e.headline())).append("</p>\n");
            h.append("<p>").append(esc(e.detail())).append("</p>\n");
            h.append("<p>").append(esc(e.qualification())).append("</p>\n");
            if (!s.qualificationErrors().isEmpty() || !s.qualificationWarnings().isEmpty()) {
                h.append("<ul class=\"details\">\n");
                for (String msg : s.qualificationErrors()) {
                    h.append("<li>").append(esc(msg)).append("</li>\n");
                }
                for (String msg : s.qualificationWarnings()) {
                    h.append("<li>").append(esc(msg)).append("</li>\n");
                }
                h.append("</ul>\n");
            }
            if (e.nextStep() != null) {
                h.append("<p class=\"next\"><strong>").append(esc(m.get("html.next"))).append("</strong> ")
                 .append(esc(e.nextStep())).append("</p>\n");
            }
            h.append("<dl class=\"tech\">\n");
            dt(h, m.get("html.tech.format"), s.format());
            dt(h, m.get("html.tech.outcome"), s.indication() + (s.subIndication() == null ? "" : " / " + s.subIndication()));
            dt(h, m.get("html.tech.qualification"), s.qualification());
            dt(h, m.get("html.tech.claimedTime"), s.claimedSigningTime() == null ? m.get("html.tech.claimedTime.none")
                    : m.dateTime(s.claimedSigningTime()) + " " + m.get("html.tech.claimedTime.note"));
            dt(h, m.get("html.tech.id"), s.id());
            h.append("</dl>\n");
            if (!s.errors().isEmpty() || !s.warnings().isEmpty()) {
                h.append("<details><summary>").append(esc(m.get("html.engineMessages"))).append("</summary>\n<ul>\n");
                for (String msg : s.errors()) {
                    h.append("<li>").append(esc(m.get("html.error"))).append(' ').append(esc(msg)).append("</li>\n");
                }
                for (String msg : s.warnings()) {
                    h.append("<li>").append(esc(m.get("html.warning"))).append(' ').append(esc(msg)).append("</li>\n");
                }
                h.append("</ul>\n</details>\n");
            }
            h.append("</article>\n");
        }
        h.append("</section>\n");
    }

    private static void renderTrust(StringBuilder h, Report r, Messages m) {
        TrustSummary t = r.trust();
        h.append("<section aria-labelledby=\"trust-heading\">\n<h2 id=\"trust-heading\">").append(esc(m.get("html.relied.h")))
         .append("</h2>\n<dl>\n");
        dt(h, m.get("html.relied.checkedAt"), m.dateTime(r.validationTime()));
        dt(h, m.get("html.relied.trust"), t.describe(m));
        dt(h, m.get("html.relied.revocation"), m.get(r.online() ? "html.relied.revocation.online" : "html.relied.revocation.offline"));
        dt(h, m.get("html.relied.engine"), r.engine());
        h.append("</dl>\n");
        if (!t.problems().isEmpty()) {
            h.append("<p>").append(esc(m.get("html.relied.notes"))).append("</p>\n<ul>\n");
            for (String p : t.describeProblems(m)) {
                h.append("<li>").append(esc(p)).append("</li>\n");
            }
            h.append("</ul>\n");
        }
        h.append("</section>\n");
    }

    private static void renderScope(StringBuilder h, Report r, Messages m) {
        boolean tl = r.trust().usesTrustedLists();
        h.append("<section aria-labelledby=\"scope-heading\">\n<h2 id=\"scope-heading\">").append(esc(m.get("html.scope.h"))).append("</h2>\n");
        h.append("<h3>").append(esc(m.get("html.scope.checked"))).append("</h3>\n<ul>\n");
        li(h, m.get("html.scope.checked.integrity"));
        li(h, m.get("html.scope.checked.value"));
        li(h, m.get(tl ? "html.scope.checked.chain.tl" : "html.scope.checked.chain.notl"));
        li(h, m.get(r.online() ? "html.scope.checked.revocation.online" : "html.scope.checked.revocation.offline"));
        li(h, m.get("html.scope.checked.timestamps"));
        h.append("</ul>\n<h3>").append(esc(m.get("html.scope.notChecked"))).append("</h3>\n<ul>\n");
        li(h, m.get("html.scope.notChecked.identity"));
        li(h, m.get("html.scope.notChecked.authority"));
        li(h, m.get("html.scope.notChecked.outside"));
        if (!tl) {
            li(h, m.get("html.scope.notChecked.notl"));
        }
        h.append("</ul>\n</section>\n");
    }

    private static void renderTechnical(StringBuilder h, Report r, Messages m) {
        h.append("<section aria-labelledby=\"technical-heading\">\n<h2 id=\"technical-heading\">").append(esc(m.get("html.technical.h"))).append("</h2>\n");
        h.append("<details><summary>").append(esc(m.format("html.technical.json", r.reportVersion()))).append("</summary>\n<pre>")
         .append(esc(ReportJson.writeWithoutEngineReport(r))).append("</pre>\n</details>\n");
        if (r.engineReport() != null) {
            h.append("<details><summary>").append(esc(m.get("html.technical.engine"))).append("</summary>\n<pre>")
             .append(esc(r.engineReport())).append("</pre>\n</details>\n");
        }
        h.append("</section>\n");
    }

    private static void li(StringBuilder h, String text) {
        h.append("<li>").append(esc(text)).append("</li>\n");
    }

    private static void dt(StringBuilder h, String term, String value) {
        h.append("<div><dt>").append(esc(term)).append("</dt><dd>").append(esc(value == null ? "—" : value))
         .append("</dd></div>\n");
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

    private static final String CSS = """
            :root { color-scheme: light dark; --fg: #1a1a1a; --bg: #ffffff; --muted: #555; --line: #d0d0d0;
                    --valid: #1b6e2a; --invalid: #a3151b; --indet: #8a5a00; --none: #444; --panel: #f6f6f6; }
            @media (prefers-color-scheme: dark) { :root { --fg: #e8e8e8; --bg: #121212; --muted: #b0b0b0; --line: #3a3a3a;
                    --valid: #6fcf7f; --invalid: #ff7b7b; --indet: #f0b84c; --none: #bbb; --panel: #1e1e1e; } }
            html { font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: var(--fg); background: var(--bg); }
            body { margin: 0; }
            main { max-width: 48rem; margin: 0 auto; padding: 1.5rem 1rem 3rem; }
            h1 { font-size: 1.6rem; margin: 0 0 .25rem; }
            h2 { font-size: 1.25rem; margin: 2rem 0 .5rem; }
            h3 { font-size: 1.05rem; margin: 1.25rem 0 .5rem; }
            .file { color: var(--muted); margin: 0; word-break: break-all; }
            .verdict { border-left: .5rem solid var(--none); background: var(--panel); padding: 1rem 1.25rem; margin-top: 1.5rem; }
            .verdict h2 { margin-top: 0; }
            .verdict-valid { border-color: var(--valid); }
            .verdict-invalid { border-color: var(--invalid); }
            .verdict-indeterminate { border-color: var(--indet); }
            .verdict-no-signature { border-color: var(--none); }
            article.signature { border: 1px solid var(--line); border-left-width: .5rem; border-radius: .25rem; padding: .75rem 1rem; margin: 1rem 0; }
            .by { font-weight: normal; color: var(--muted); }
            .headline { font-size: 1.05rem; }
            .next { background: var(--panel); padding: .5rem .75rem; border-radius: .25rem; }
            ul.details { color: var(--muted); }
            dl { display: grid; grid-template-columns: max-content 1fr; gap: .25rem 1rem; margin: .75rem 0; }
            dl div { display: contents; }
            dt { color: var(--muted); }
            dd { margin: 0; word-break: break-word; }
            dl.tech { font-size: .95rem; }
            details { margin: .75rem 0; }
            summary { cursor: pointer; }
            pre { overflow: auto; background: var(--panel); padding: .75rem; border-radius: .25rem; font-size: .85rem; max-height: 30rem; }
            footer { margin-top: 3rem; color: var(--muted); font-size: .9rem; border-top: 1px solid var(--line); padding-top: 1rem; }
            a:focus, summary:focus { outline: 3px solid var(--indet); outline-offset: 2px; }
            @media (max-width: 40rem) { dl { grid-template-columns: 1fr; } }
            """;
}
