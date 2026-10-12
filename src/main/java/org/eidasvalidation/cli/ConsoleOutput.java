package org.eidasvalidation.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Standard output and error streams that produce readable text wherever they go.
 *
 * <p>Attached to a console, the stream encodes in the console's own code page, which the JVM
 * reports in {@code stdout.encoding} (JDK 19+) or {@code sun.stdout.encoding} (JDK 17 on
 * Windows); a legacy code page such as CP852 then shows accented letters correctly. Piped or
 * redirected, the stream writes UTF-8, which is what shells, files and other programs expect.
 * Characters the chosen code page cannot encode, mostly typographic punctuation from the
 * message catalog, are replaced by their plain ASCII counterparts rather than by question marks.
 */
final class ConsoleOutput {

    private static final Map<Character, String> ASCII_FALLBACK = Map.ofEntries(
            Map.entry('’', "'"), Map.entry('‘', "'"),
            Map.entry('“', "\""), Map.entry('”', "\""), Map.entry('„', "\""),
            Map.entry('–', "-"), Map.entry('—', "-"),
            Map.entry('…', "..."), Map.entry(' ', " "),
            Map.entry('›', ">"), Map.entry('‹', "<"), Map.entry('•', "*"),
            Map.entry('→', "->"));

    private ConsoleOutput() {
    }

    static PrintStream stdout() {
        return wrap(FileDescriptor.out, charset());
    }

    static PrintStream stderr() {
        return wrap(FileDescriptor.err, charset());
    }

    /** The console's code page when attached to one, otherwise UTF-8. */
    static Charset charset() {
        if (System.console() == null) {
            return StandardCharsets.UTF_8;
        }
        for (String key : List.of("stdout.encoding", "sun.stdout.encoding")) {
            String name = System.getProperty(key);
            if (name != null && !name.isBlank()) {
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException ignored) {
                    // fall through to the next candidate
                }
            }
        }
        return Charset.defaultCharset();
    }

    static PrintStream wrap(FileDescriptor fd, Charset cs) {
        return new PrintStream(new FileOutputStream(fd), true, cs) {
            @Override
            public void print(String s) {
                super.print(fit(s, cs));
            }

            @Override
            public void print(char c) {
                super.print(fit(String.valueOf(c), cs));
            }
        };
    }

    /** Replaces characters the charset cannot encode with ASCII equivalents where one exists. */
    static String fit(String s, Charset cs) {
        if (s == null || StandardCharsets.UTF_8.equals(cs)) {
            return s;
        }
        CharsetEncoder enc = cs.newEncoder();
        if (enc.canEncode(s)) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (enc.canEncode(c)) {
                b.append(c);
            } else {
                String r = ASCII_FALLBACK.get(c);
                b.append(r != null ? r : c);
            }
        }
        return b.toString();
    }
}
