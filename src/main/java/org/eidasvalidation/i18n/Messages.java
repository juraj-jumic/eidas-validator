package org.eidasvalidation.i18n;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * The message catalog. Every string a reader sees comes from
 * {@code org/eidasvalidation/messages[_lang].properties}; English is the base bundle and the
 * fallback for any key a translation lacks.
 */
public final class Messages {

    private static final String BASE = "org.eidasvalidation.messages";
    /** Languages with a complete translation, in preference order. */
    public static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, Locale.GERMAN);

    private final Locale locale;
    private final ResourceBundle bundle;
    private final DateTimeFormatter dateTime;

    private Messages(Locale locale) {
        this.locale = locale;
        // Control.getNoFallbackControl: never fall back to the JVM default locale, only to the base bundle.
        this.bundle = ResourceBundle.getBundle(BASE, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        this.dateTime = DateTimeFormatter.ofPattern(bundle.getString("format.datetime"), locale).withZone(ZoneOffset.UTC);
    }

    public static Messages english() {
        return new Messages(Locale.ENGLISH);
    }

    /** The catalog for a supported language, or English for anything else. */
    public static Messages forLocale(Locale locale) {
        return new Messages(supported(locale));
    }

    /** Parses a BCP 47 tag such as {@code de} or {@code de-AT}; unknown tags give English. */
    public static Messages forTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return english();
        }
        return forLocale(Locale.forLanguageTag(tag.trim()));
    }

    /**
     * Picks the best supported language from an HTTP {@code Accept-Language} header,
     * honouring order and q-values; English when nothing matches.
     */
    public static Messages forAcceptLanguage(String header) {
        return matchingAcceptLanguage(header).orElseGet(Messages::english);
    }

    /** As {@link #forAcceptLanguage}, but empty when the header names no supported language. */
    public static Optional<Messages> matchingAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        List<String[]> candidates = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pieces = part.trim().split(";");
            String tag = pieces[0].trim();
            double q = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String p = pieces[i].trim();
                if (p.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(p.substring(2));
                    } catch (NumberFormatException ignored) {
                        q = 0;
                    }
                }
            }
            if (!tag.isEmpty() && q > 0) {
                candidates.add(new String[]{tag, Double.toString(q)});
            }
        }
        candidates.sort((a, b) -> Double.compare(Double.parseDouble(b[1]), Double.parseDouble(a[1])));
        for (String[] c : candidates) {
            Locale l = Locale.forLanguageTag(c[0]);
            if (isSupported(l)) {
                return Optional.of(new Messages(supported(l)));
            }
        }
        return Optional.empty();
    }

    public static boolean isSupported(Locale locale) {
        return locale != null && SUPPORTED.stream().anyMatch(s -> s.getLanguage().equals(locale.getLanguage()));
    }

    private static Locale supported(Locale locale) {
        return SUPPORTED.stream().filter(s -> s.getLanguage().equals(locale.getLanguage())).findFirst()
                .orElse(Locale.ENGLISH);
    }

    public Locale locale() {
        return locale;
    }

    /** BCP 47 tag for {@code lang} attributes and JSON. */
    public String tag() {
        return locale.toLanguageTag();
    }

    public boolean has(String key) {
        return bundle.containsKey(key);
    }

    /** The raw text for a key; the key itself, in brackets, if it is missing. */
    public String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "[" + key + "]";
        }
    }

    /** The text for a key with {@link MessageFormat} placeholders filled in. */
    public String format(String key, Object... args) {
        String pattern = get(key);
        if (args.length == 0 || pattern.indexOf('{') < 0) {
            return pattern;
        }
        return new MessageFormat(pattern, locale).format(args);
    }

    /** Date and time in the catalog’s format, always UTC. */
    public String dateTime(Instant instant) {
        return dateTime.format(Objects.requireNonNull(instant));
    }

    /** The bundle’s keys, for parity tests. */
    public List<String> keys() {
        return bundle.keySet().stream().sorted().toList();
    }
}
