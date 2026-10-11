package org.eidasvalidation.report;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.explain.Explanation;
import org.eidasvalidation.explain.Verdict;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link Report} as one self-contained HTML page: no scripts, no external resources,
 * semantic headings and lists, verdicts stated in words (colour is only an accent), and the
 * technical detail one click away behind {@code <details>}.
 */
public final class HtmlReport {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private HtmlReport() {
    }

    public static String render(Report r) {
        StringBuilder h = new StringBuilder(16 * 1024);
        h.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
         .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
         .append("<title>Signature check: ").append(esc(r.file())).append("</title>\n")
         .append("<style>\n").append(CSS).append("</style>\n</head>\n<body>\n<main>\n");

        h.append("<header>\n<h1>Signature check</h1>\n<p class=\"file\">File: <strong>").append(esc(r.file()))
         .append("</strong></p>\n</header>\n");

        renderOverall(h, r);
        renderSignatures(h, r);
        renderTrust(h, r);
        renderScope(h, r);
        renderTechnical(h, r);

        h.append("<footer>\n<p>This check is informational. It is not a qualified validation service in the sense ")
         .append("of the eIDAS Regulation and does not constitute legal advice. Produced by eidas-validator with ")
         .append(esc(r.engine())).append(".</p>\n</footer>\n</main>\n</body>\n</html>\n");
        return h.toString();
    }

    private static void renderOverall(StringBuilder h, Report r) {
        String cls = r.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-');
        h.append("<section class=\"verdict verdict-").append(cls).append("\" aria-labelledby=\"verdict-heading\">\n");
        switch (r.verdict()) {
            case NO_SIGNATURE -> h.append("<h2 id=\"verdict-heading\">No signature found</h2>\n")
                    .append("<p>This document carries no electronic signature, so there is nothing to verify.</p>\n");
            case VALID -> h.append("<h2 id=\"verdict-heading\">Valid as of ").append(TIME.format(r.validationTime()))
                    .append("</h2>\n<p>").append(r.signatures().size() == 1 ? "The signature is" : "All "
                            + r.signatures().size() + " signatures are")
                    .append(" intact and the signer's certificate chains to a trusted issuer at that time. ")
                    .append("Qualification is stated per signature below.</p>\n");
            case INVALID -> h.append("<h2 id=\"verdict-heading\">Invalid</h2>\n<p>At least one signature failed. ")
                    .append("Do not rely on this copy of the document. Details follow.</p>\n");
            case INDETERMINATE -> h.append("<h2 id=\"verdict-heading\">Could not be fully verified</h2>\n")
                    .append("<p>The check could not reach a definite answer for at least one signature. ")
                    .append("What is missing and what you can do about it is explained below.</p>\n");
        }
        h.append("</section>\n");
    }

    private static void renderSignatures(StringBuilder h, Report r) {
        if (r.signatures().isEmpty()) {
            return;
        }
        h.append("<section aria-labelledby=\"signatures-heading\">\n<h2 id=\"signatures-heading\">Signatures</h2>\n");
        int n = 1;
        for (SignatureReport sr : r.signatures()) {
            Explanation e = sr.explanation();
            SignatureResult s = sr.result();
            String cls = e.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-');
            h.append("<article class=\"signature verdict-").append(cls).append("\">\n");
            h.append("<h3>Signature ").append(n++).append(" of ").append(r.signatures().size());
            if (s.signedBy() != null) {
                h.append(" <span class=\"by\">by ").append(esc(s.signedBy())).append("</span>");
            }
            h.append("</h3>\n");
            h.append("<p class=\"headline\"><strong>").append(word(e.verdict())).append(":</strong> ")
             .append(esc(e.headline())).append("</p>\n");
            h.append("<p>").append(esc(e.detail())).append("</p>\n");
            h.append("<p>").append(esc(e.qualification())).append("</p>\n");
            if (!s.qualificationErrors().isEmpty() || !s.qualificationWarnings().isEmpty()) {
                h.append("<ul class=\"details\">\n");
                for (String m : s.qualificationErrors()) {
                    h.append("<li>").append(esc(m)).append("</li>\n");
                }
                for (String m : s.qualificationWarnings()) {
                    h.append("<li>").append(esc(m)).append("</li>\n");
                }
                h.append("</ul>\n");
            }
            if (e.nextStep() != null) {
                h.append("<p class=\"next\"><strong>What you can do:</strong> ").append(esc(e.nextStep())).append("</p>\n");
            }
            h.append("<dl class=\"tech\">\n");
            dt(h, "Format", s.format());
            dt(h, "Outcome", s.indication() + (s.subIndication() == null ? "" : " / " + s.subIndication()));
            dt(h, "Qualification", s.qualification());
            dt(h, "Claimed signing time", s.claimedSigningTime() == null ? "not stated"
                    : TIME.format(s.claimedSigningTime()) + " (as claimed by the signer; proven only if timestamped)");
            dt(h, "Signature id", s.id());
            h.append("</dl>\n");
            if (!s.errors().isEmpty() || !s.warnings().isEmpty()) {
                h.append("<details><summary>Engine messages for this signature</summary>\n<ul>\n");
                for (String m : s.errors()) {
                    h.append("<li>Error: ").append(esc(m)).append("</li>\n");
                }
                for (String m : s.warnings()) {
                    h.append("<li>Warning: ").append(esc(m)).append("</li>\n");
                }
                h.append("</ul>\n</details>\n");
            }
            h.append("</article>\n");
        }
        h.append("</section>\n");
    }

    private static void renderTrust(StringBuilder h, Report r) {
        TrustSummary t = r.trust();
        h.append("<section aria-labelledby=\"trust-heading\">\n<h2 id=\"trust-heading\">What this check relied on</h2>\n<dl>\n");
        dt(h, "Checked at", TIME.format(r.validationTime()));
        dt(h, "Trust data", t.describe());
        dt(h, "Revocation lookups", r.online() ? "allowed (CRL/OCSP fetched over the network)"
                : "not performed (offline); only revocation data embedded in the document was used");
        dt(h, "Engine", r.engine());
        h.append("</dl>\n");
        if (!t.problems().isEmpty()) {
            h.append("<p>Notes on the trust data:</p>\n<ul>\n");
            for (String p : t.problems()) {
                h.append("<li>").append(esc(p)).append("</li>\n");
            }
            h.append("</ul>\n");
        }
        h.append("</section>\n");
    }

    private static void renderScope(StringBuilder h, Report r) {
        boolean tl = r.trust().usesTrustedLists();
        h.append("<section aria-labelledby=\"scope-heading\">\n<h2 id=\"scope-heading\">What was checked, and what was not</h2>\n");
        h.append("<h3>Checked</h3>\n<ul>\n")
         .append("<li>That the signed parts of the document are unchanged since signing.</li>\n")
         .append("<li>That each signature value matches the signer's certificate.</li>\n")
         .append(tl ? "<li>That the signer's certificate chains to a provider on the EU trusted lists, and whether that provider is qualified.</li>\n"
                    : "<li>That the signer's certificate chains to one of the trust anchors supplied for this run.</li>\n")
         .append(r.online() ? "<li>Whether the certificates had been revoked, using current CRL/OCSP data.</li>\n"
                            : "<li>Revocation status, but only from data embedded in the document.</li>\n")
         .append("<li>Timestamps, where present.</li>\n</ul>\n");
        h.append("<h3>Not checked</h3>\n<ul>\n")
         .append("<li>Who the signer really is beyond what the certificate's issuer asserts.</li>\n")
         .append("<li>Whether the signer was entitled to sign this document, or what the document means.</li>\n")
         .append("<li>Parts of the document outside the signed byte ranges (for example later annotations), beyond what the engine reports.</li>\n")
         .append(tl ? "" : "<li>EU trusted lists were not used, so qualification under eIDAS could not be assessed.</li>\n")
         .append("</ul>\n</section>\n");
    }

    private static void renderTechnical(StringBuilder h, Report r) {
        h.append("<section aria-labelledby=\"technical-heading\">\n<h2 id=\"technical-heading\">Technical detail</h2>\n");
        h.append("<details><summary>Report as JSON (version ").append(r.reportVersion()).append(")</summary>\n<pre>")
         .append(esc(ReportJson.writeWithoutEngineReport(r))).append("</pre>\n</details>\n");
        if (r.engineReport() != null) {
            h.append("<details><summary>Engine report (DSS simple report, XML)</summary>\n<pre>")
             .append(esc(r.engineReport())).append("</pre>\n</details>\n");
        }
        h.append("</section>\n");
    }

    private static void dt(StringBuilder h, String term, String value) {
        h.append("<div><dt>").append(esc(term)).append("</dt><dd>").append(esc(value == null ? "—" : value))
         .append("</dd></div>\n");
    }

    private static String word(Verdict v) {
        return switch (v) {
            case VALID -> "Valid";
            case INVALID -> "Invalid";
            case INDETERMINATE -> "Undetermined";
            case NO_SIGNATURE -> "No signature";
        };
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
