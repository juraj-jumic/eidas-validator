package org.eidasvalidation.engine.dss;

import org.eidasvalidation.engine.TrustSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Downloads the real LOTL and national lists. Slow and network-bound, so it only runs when
 * {@code EIDAS_TL_ONLINE=true} is set.
 */
@EnabledIfEnvironmentVariable(named = "EIDAS_TL_ONLINE", matches = "true")
class EuTrustedListsOnlineTest {

    @TempDir
    Path cache;

    @Test
    void loadsLotlVerifiesSignatureAndWorksOfflineAfterwards() throws Exception {
        TrustSummary online = EuTrustedLists.configure(cache, Duration.ofHours(1), false, null).load().summary();

        assertEquals(Boolean.TRUE, online.lotlSignatureValid(), "LOTL signature must verify: " + online.problems());
        assertNotNull(online.lotlSequenceNumber());
        assertTrue(online.trustedLists() >= 25, "expected the EU/EEA lists, got " + online.trustedLists());
        assertTrue(online.trustedListsValid() >= 20, "too few usable lists: " + online.problems());
        assertTrue(online.certificates() > 500, "expected many trust anchors, got " + online.certificates());

        TrustSummary offline = EuTrustedLists.configure(cache, Duration.ofHours(1), true, null).load().summary();
        assertEquals(online.lotlSequenceNumber(), offline.lotlSequenceNumber());
        assertEquals(online.certificates(), offline.certificates());
        assertEquals(Boolean.TRUE, offline.lotlSignatureValid());
    }
}
