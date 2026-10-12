package org.eidasvalidation.report;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.explain.Explanation;
import org.eidasvalidation.explain.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportTest {

    private static SignatureReport sig(Verdict v, String signedBy, String indication, String sub) {
        SignatureResult r = new SignatureResult("S-1", signedBy, Instant.parse("2026-01-02T10:30:55Z"),
                "PAdES-BASELINE-B", indication, sub, "NA", List.of(), List.of(),
                List.of("Unable to build a certificate chain up to a trusted list!"), List.of());
        Explanation e = new Explanation(v, "Headline <b>", "Detail & more", "Qualification text", "Next step");
        return new SignatureReport(e, r);
    }

    private static Report report(String language, SignatureReport... sigs) {
        List<SignatureReport> list = List.of(sigs);
        return new Report(Report.VERSION, language, "contract <1>.pdf", Instant.parse("2026-10-11T12:00:00Z"), false,
                "EU DSS 6.5", TrustSummary.none(), Report.overall(list), list, "<SimpleReport/>");
    }

    private static Report report(SignatureReport... sigs) {
        return report("en", sigs);
    }

    @Test
    void escapesEverythingFromTheDocumentAndEngine() {
        String html = HtmlReport.render(report(sig(Verdict.INDETERMINATE, "<script>alert(1)</script>",
                "INDETERMINATE", "NO_CERTIFICATE_CHAIN_FOUND")));
        assertFalse(html.contains("<script>"), "signer name must be escaped");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("contract &lt;1&gt;.pdf"));
        assertTrue(html.contains("Headline &lt;b&gt;"));
        assertTrue(html.contains("Detail &amp; more"));
        assertTrue(html.contains("&lt;SimpleReport/&gt;"));
    }

    @Test
    void statesVerdictInWordsAndKeepsTechnicalDetailBehindDetails() {
        String html = HtmlReport.render(report(sig(Verdict.VALID, "Alice", "TOTAL_PASSED", null)));
        assertTrue(html.contains("<h2 id=\"verdict-heading\">Valid as of 11 October 2026, 12:00 UTC</h2>"));
        assertTrue(html.contains("<strong>Valid:</strong>"));
        assertTrue(html.contains("<details><summary>Report as JSON"));
        assertTrue(html.contains("<details><summary>Engine report"));
        assertTrue(html.contains("lang=\"en\""));
        assertFalse(html.contains("<script"), "report must not need scripts");
        assertFalse(html.contains("http://") || html.contains("https://"), "no external resources");
    }

    @Test
    void rendersInGermanWhenTheReportIsGerman() {
        String html = HtmlReport.render(report("de", sig(Verdict.VALID, "Alice", "TOTAL_PASSED", null)));
        assertTrue(html.contains("lang=\"de\""));
        assertTrue(html.contains("<h1>Signaturprüfung</h1>"));
        assertTrue(html.contains("Gültig zum Stand 11. Oktober 2026, 12:00 UTC"));
        assertTrue(html.contains("Worauf sich diese Prüfung stützt"));
        var unresolved = java.util.regex.Pattern.compile("\\[[a-z]+(\\.[A-Za-z_]+)+\\]").matcher(html);
        assertFalse(unresolved.find(), () -> "unresolved catalog key in German report: " + unresolved.group());
    }

    @Test
    void overallVerdictIsTheWorstAcrossSignatures() {
        assertEquals(Verdict.NO_SIGNATURE, Report.overall(List.of()));
        assertEquals(Verdict.VALID, Report.overall(List.of(sig(Verdict.VALID, "a", "TOTAL_PASSED", null))));
        assertEquals(Verdict.INDETERMINATE, Report.overall(List.of(
                sig(Verdict.VALID, "a", "TOTAL_PASSED", null),
                sig(Verdict.INDETERMINATE, "b", "INDETERMINATE", "TRY_LATER"))));
        assertEquals(Verdict.INVALID, Report.overall(List.of(
                sig(Verdict.INDETERMINATE, "b", "INDETERMINATE", "TRY_LATER"),
                sig(Verdict.INVALID, "c", "TOTAL_FAILED", "HASH_FAILURE"),
                sig(Verdict.VALID, "a", "TOTAL_PASSED", null))));
    }

    @Test
    void noSignatureReportSaysSo() {
        String html = HtmlReport.render(report());
        assertTrue(html.contains("No signature found"));
        assertFalse(html.contains("id=\"signatures-heading\""));
    }

    @Test
    void jsonRoundTripCarriesVersionLanguageAndVerdict() {
        String json = ReportJson.write(report(sig(Verdict.INVALID, "Mallory", "TOTAL_FAILED", "HASH_FAILURE")));
        assertTrue(json.contains("\"reportVersion\" : " + Report.VERSION));
        assertTrue(json.contains("\"language\" : \"en\""));
        assertTrue(json.contains("\"verdict\" : \"INVALID\""));
        assertTrue(json.contains("\"subIndication\" : \"HASH_FAILURE\""));
        assertFalse(json.contains("exitCode"), "exit code is a CLI concern, not part of the report");
        assertFalse(json.contains("\"messages\""), "catalog accessor is not part of the report");
    }
}
