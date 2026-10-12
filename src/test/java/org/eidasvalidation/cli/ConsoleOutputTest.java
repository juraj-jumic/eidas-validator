package org.eidasvalidation.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConsoleOutputTest {

    @Test
    void utf8PassesEverythingThrough() {
        String s = "Juraj Jumić – signer’s “quote”";
        assertSame(s, ConsoleOutput.fit(s, StandardCharsets.UTF_8));
    }

    @Test
    void legacyCodePageKeepsItsOwnLettersAndDowngradesTheRest() {
        Charset cp852 = Charset.forName("IBM852"); // Central European OEM code page, as on a Croatian console
        String fitted = ConsoleOutput.fit("Juraj Jumić – signer’s “quote” … → ok", cp852);
        assertEquals("Juraj Jumić - signer's \"quote\" ... -> ok", fitted);
    }

    @Test
    void asciiConsoleStillReadsSensibly() {
        assertEquals("signer's - \"quote\" ...", ConsoleOutput.fit("signer’s — “quote” …", StandardCharsets.US_ASCII));
        assertEquals("Jumi?", new String(ConsoleOutput.fit("Jumić", StandardCharsets.US_ASCII)
                .getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII).replace('�', '?'),
                "letters with no ASCII form are left to the encoder's replacement");
    }

    @Test
    void withoutAConsoleTheCharsetIsUtf8() {
        // Surefire runs without a console, so this is the piped case.
        assertEquals(StandardCharsets.UTF_8, ConsoleOutput.charset());
    }

    @Test
    void wrappedStreamAppliesTheFallbackOnPrintln() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Charset ascii = StandardCharsets.US_ASCII;
        PrintStream out = new PrintStream(bytes, true, ascii) {
            @Override
            public void print(String s) {
                super.print(ConsoleOutput.fit(s, ascii));
            }
        };
        out.println("signer’s");
        assertEquals("signer's" + System.lineSeparator(), bytes.toString(ascii));
    }
}
