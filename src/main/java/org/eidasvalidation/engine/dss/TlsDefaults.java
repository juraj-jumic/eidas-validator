package org.eidasvalidation.engine.dss;

/**
 * JVM-wide TLS defaults needed to reach the trusted-list servers reliably.
 *
 * <ul>
 *   <li>Several national trusted-list hosts send incomplete certificate chains. Java does not fetch
 *       missing intermediates unless {@code com.sun.security.enableAIAcaIssuers} is set.</li>
 *   <li>On Windows, the JDK's bundled {@code cacerts} can lag behind the operating system's root
 *       store; using {@code WINDOWS-ROOT} makes Java trust what the OS trusts.</li>
 * </ul>
 *
 * <p>Both are only applied when the user has not set the property themselves, and must be applied
 * before the first TLS connection of the process.
 */
public final class TlsDefaults {

    private static volatile boolean applied;

    private TlsDefaults() {
    }

    public static void apply() {
        if (applied) {
            return;
        }
        setIfAbsent("com.sun.security.enableAIAcaIssuers", "true");
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            setIfAbsent("javax.net.ssl.trustStoreType", "WINDOWS-ROOT");
        }
        applied = true;
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
