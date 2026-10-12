package org.eidasvalidation.explain;

import eu.europa.esig.dss.enumerations.SubIndication;
import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainerTest {

    private static final Instant AT = Instant.parse("2026-10-11T20:35:00Z");

    private static SignatureResult result(String indication, String sub, String qualification) {
        return new SignatureResult("S-1", "Ana", AT, "PAdES-BASELINE-B", indication, sub, qualification,
                List.of("engine said so"), List.of(), List.of(), List.of());
    }

    @Test
    void englishAndGermanSayTheSameThing() {
        SignatureResult s = result("TOTAL_FAILED", "HASH_FAILURE", "NA");
        Explanation en = new Explainer(Messages.english()).explain(s, AT, true);
        Explanation de = new Explainer(Messages.forTag("de")).explain(s, AT, true);
        assertEquals(Verdict.INVALID, en.verdict());
        assertEquals(Verdict.INVALID, de.verdict());
        assertEquals("The document was changed after it was signed.", en.headline());
        assertEquals("Das Dokument wurde nach der Signatur verändert.", de.headline());
        assertNotNull(de.nextStep());
    }

    @Test
    void validHeadlineCarriesLocalisedDate() {
        SignatureResult s = result("TOTAL_PASSED", null, "QESIG");
        assertEquals("Valid as of 11 October 2026, 20:35 UTC.", Explainer.inEnglish(s, AT).headline());
        assertEquals("Gültig zum Stand 11. Oktober 2026, 20:35 UTC.",
                new Explainer(Messages.forTag("de")).explain(s, AT, true).headline());
    }

    @Test
    void wordingDependsOnWhetherTrustedListsWereLoaded() {
        SignatureResult s = result("INDETERMINATE", "NO_CERTIFICATE_CHAIN_FOUND", "NA");
        Explanation with = Explainer.inEnglish(s, AT, true);
        Explanation without = Explainer.inEnglish(s, AT, false);
        assertTrue(with.detail().contains("EU trusted lists"));
        assertTrue(without.nextStep().contains("run again with trusted lists"));
        assertTrue(with.qualification().startsWith("Not qualified"));
        assertTrue(without.qualification().startsWith("Qualification not determined"));
    }

    @Test
    void aliasesShareWording() {
        Explanation a = Explainer.inEnglish(result("INDETERMINATE", "CERTIFICATE_CHAIN_GENERAL_FAILURE", "NA"), AT, true);
        Explanation b = Explainer.inEnglish(result("INDETERMINATE", "TRY_LATER", "NA"), AT, true);
        assertEquals(b.headline(), a.headline());
    }

    @Test
    void everyDssSubIndicationGetsASentenceInEveryLanguage() {
        for (Messages m : List.of(Messages.english(), Messages.forTag("de"), Messages.forTag("hr"))) {
            Explainer x = new Explainer(m);
            for (SubIndication sub : SubIndication.values()) {
                for (String ind : List.of("TOTAL_FAILED", "INDETERMINATE")) {
                    Explanation e = x.explain(result(ind, sub.name(), "NA"), AT, true);
                    assertFalse(e.headline().startsWith("["), sub + " in " + m.tag() + ": " + e.headline());
                    assertFalse(e.detail().startsWith("["), sub + " in " + m.tag() + ": " + e.detail());
                    assertFalse(e.detail().contains("{0}"), sub + " placeholder not filled");
                }
            }
        }
    }

    @Test
    void qualificationsAreAllWorded() {
        for (String q : java.util.Arrays.asList("QESIG", "QESEAL", "ADESIG_QC", "ADESEAL_QC", "ADESIG", "ADESEAL", "NOT_ADES",
                "NOT_ADES_QC", "NOT_ADES_QC_QSCD", "UNKNOWN", "NA", "INDETERMINATE_QESIG", "SOMETHING_NEW", null)) {
            Explanation e = Explainer.inEnglish(result("TOTAL_PASSED", null, q), AT, true);
            assertFalse(e.qualification().startsWith("["), String.valueOf(q));
            assertFalse(e.qualification().contains("{0}"), String.valueOf(q));
        }
    }
}
