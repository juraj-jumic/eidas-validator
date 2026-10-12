package org.eidasvalidation.i18n;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");

    @Test
    void everyTranslationHasExactlyTheBaseKeys() {
        Messages en = Messages.english();
        for (Locale l : Messages.SUPPORTED) {
            if (l.equals(Locale.ENGLISH)) {
                continue;
            }
            Messages t = Messages.forLocale(l);
            List<String> missing = new ArrayList<>();
            List<String> extra = new ArrayList<>(t.keys());
            extra.removeAll(en.keys());
            for (String k : en.keys()) {
                // keys() on a child bundle includes parent keys; check the child file itself
                if (!t.keys().contains(k)) {
                    missing.add(k);
                }
            }
            assertTrue(missing.isEmpty(), l + " is missing: " + missing);
            assertTrue(extra.isEmpty(), l + " has keys unknown to the base bundle: " + extra);
        }
    }

    @Test
    void placeholdersMatchAcrossLanguages() {
        Messages en = Messages.english();
        for (Locale l : Messages.SUPPORTED) {
            Messages t = Messages.forLocale(l);
            for (String k : en.keys()) {
                assertEquals(placeholders(en.get(k)), placeholders(t.get(k)),
                        "placeholders differ for " + k + " in " + l);
                assertFalse(t.get(k).isBlank(), k + " is blank in " + l);
            }
        }
    }

    @Test
    void formatsDatesInTheLanguage() {
        Instant t = Instant.parse("2026-10-11T20:35:00Z");
        assertEquals("11 October 2026, 20:35 UTC", Messages.english().dateTime(t));
        assertEquals("11. Oktober 2026, 20:35 UTC", Messages.forTag("de").dateTime(t));
        assertEquals("11. listopada 2026., 20:35 UTC", Messages.forTag("hr").dateTime(t));
        assertEquals("11 October 2026, 20:35 UTC", Messages.forTag("fr").dateTime(t), "unsupported falls back to English");
    }

    @Test
    void picksLanguageFromAcceptLanguage() {
        assertEquals("de", Messages.forAcceptLanguage("de-AT,de;q=0.9,en;q=0.8").tag());
        assertEquals("en", Messages.forAcceptLanguage("fr-FR,fr;q=0.9").tag());
        assertEquals("de", Messages.forAcceptLanguage("fr;q=0.9, de;q=0.8").tag());
        assertEquals("en", Messages.forAcceptLanguage("en-GB,de;q=0.5").tag());
        assertEquals("en", Messages.forAcceptLanguage("").tag());
    }

    @Test
    void unknownKeyIsVisibleNotSilent() {
        assertEquals("[no.such.key]", Messages.english().get("no.such.key"));
    }

    @Test
    void apostrophesSurviveFormatting() {
        // Keys with placeholders are run through MessageFormat, which treats ' as quoting.
        Messages en = Messages.english();
        for (String k : en.keys()) {
            String raw = en.get(k);
            if (raw.indexOf('{') >= 0) {
                assertFalse(raw.matches(".*(?<!')'(?!').*"),
                        k + " uses a lone apostrophe in a MessageFormat pattern; write '' or use ’");
            }
        }
    }

    private static List<Integer> placeholders(String s) {
        List<Integer> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out.stream().sorted().toList();
    }
}
