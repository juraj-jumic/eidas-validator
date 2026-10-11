package org.eidasvalidation.net;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockedAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.8.8.8", "0.0.0.0", "10.0.0.1", "172.16.5.5", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "192.0.0.8", "198.18.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1",
            "::ffff:10.0.0.1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:c0a8:101::1"})
    void blocksNonPublicAddresses(String literal) throws Exception {
        InetAddress a = InetAddress.getByName(literal);
        assertTrue(BlockedAddresses.blockReason(a).isPresent(), literal + " should be blocked");
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "193.0.6.139", "2001:4860:4860::8888", "2a00:1450:4001::1",
            "::ffff:8.8.8.8", "2002:808:808::1"})
    void allowsPublicAddresses(String literal) throws Exception {
        InetAddress a = InetAddress.getByName(literal);
        assertFalse(BlockedAddresses.blockReason(a).isPresent(),
                literal + " should be allowed, got " + BlockedAddresses.blockReason(a));
    }
}
