package org.eidasvalidation.engine.dss;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled LOTL signing certificates must be exactly those the European Commission published
 * in the Official Journal. The digests below are copied from section 1 of notice C/2026/1944 of
 * 15 April 2026 (ELI http://data.europa.eu/eli/C/2026/1944/oj), in the notice's order.
 */
class LotlSignersTest {

    static final List<String> OJ_C_2026_1944_SHA256 = List.of(
            "c0641c4f7d56c431b1c924742db7fce9c1eef7d7fd212113a2768486b3abcdc5", // Ioanna Kalogeropoulou
            "e0a620fbb6747362bb933ac44169d676a553444716cf5f31605f12a22b8396b1", // European Commission (DIGIT)
            "df7e29360c34b2b8d6d5f40325c1d4d12c9922cecd33b7407674a74b2b3ca1e5", // Vicente Andreu Navarro
            "b63d416744e7098bf9ec2caa596a93bc2468e37f8284ba65ecc061711bcbaa18", // Apostolos Apladas
            "236103f03a8031ae8f47f9059bf8de38564cdbfebedde4a597d50f8980aa653b", // European Commission (EU-TRUST)
            "d2064fdd70f6982dcc516b86d9d5c56aea939417c624b2e478c0b29de54f8474"); // Patrick Kremer

    @Test
    void bundleMatchesTheOfficialJournalNotice() throws Exception {
        List<String> found = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        try (InputStream in = EuTrustedLists.class.getResourceAsStream("/lotl-signers.pem")) {
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                found.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded())));
                subjects.add(((X509Certificate) c).getSubjectX500Principal().getName());
            }
        }
        assertEquals(new TreeSet<>(OJ_C_2026_1944_SHA256), new TreeSet<>(found),
                "bundled certificates differ from OJ C/2026/1944; subjects: " + subjects);
        assertEquals(OJ_C_2026_1944_SHA256.size(), found.size(), "duplicate or missing certificate");
        assertTrue(subjects.stream().allMatch(s -> s.contains("EUROPEAN COMMISSION") || s.contains("Kremer")),
                subjects.toString());
    }
}
