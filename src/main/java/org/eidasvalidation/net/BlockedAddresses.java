package org.eidasvalidation.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Classifies IP addresses that a validator must never connect to when following URLs taken from
 * untrusted documents: loopback, private, link-local (which includes cloud metadata endpoints),
 * multicast, reserved and the IPv6 forms that embed such IPv4 addresses.
 */
public final class BlockedAddresses {

    private BlockedAddresses() {
    }

    /** @return why the address must not be contacted, or empty if it is a public address */
    public static Optional<String> blockReason(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return Optional.of("unspecified address");
        }
        if (address.isLoopbackAddress()) {
            return Optional.of("loopback address");
        }
        if (address.isLinkLocalAddress()) {
            return Optional.of("link-local address");
        }
        if (address.isSiteLocalAddress()) {
            return Optional.of("private address");
        }
        if (address.isMulticastAddress()) {
            return Optional.of("multicast address");
        }
        if (address instanceof Inet4Address) {
            return ipv4Reason(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return ipv6Reason(address.getAddress());
        }
        return Optional.of("unknown address family");
    }

    private static Optional<String> ipv4Reason(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        int b2 = b[2] & 0xff;
        if (b0 == 0) {
            return Optional.of("\"this network\" address");
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return Optional.of("shared address space (carrier-grade NAT)");
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return Optional.of("IETF protocol assignment address");
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return Optional.of("benchmarking address");
        }
        if (b0 >= 240) {
            return Optional.of("reserved address");
        }
        return Optional.empty();
    }

    private static Optional<String> ipv6Reason(byte[] b) {
        int b0 = b[0] & 0xff;
        if ((b0 & 0xfe) == 0xfc) {
            return Optional.of("unique local address");
        }
        // Forms that embed an IPv4 address: judge the embedded address.
        if (isPrefix(b, 10, 0x00) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return embedded(b, 12, "IPv4-mapped");
        }
        if (isPrefix(b, 12, 0x00)) {
            return embedded(b, 12, "IPv4-compatible");
        }
        if (b0 == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && isPrefix(b, 4, 12, 0x00)) {
            return embedded(b, 12, "NAT64");
        }
        if (b0 == 0x20 && (b[1] & 0xff) == 0x02) {
            return embedded(b, 2, "6to4");
        }
        return Optional.empty();
    }

    private static Optional<String> embedded(byte[] b, int offset, String form) {
        try {
            InetAddress v4 = InetAddress.getByAddress(new byte[]{b[offset], b[offset + 1], b[offset + 2], b[offset + 3]});
            return blockReason(v4).map(r -> form + " IPv6 address embedding a " + r);
        } catch (UnknownHostException e) {
            return Optional.of("malformed " + form + " address");
        }
    }

    private static boolean isPrefix(byte[] b, int length, int value) {
        return isPrefix(b, 0, length, value);
    }

    private static boolean isPrefix(byte[] b, int from, int to, int value) {
        for (int i = from; i < to; i++) {
            if ((b[i] & 0xff) != value) {
                return false;
            }
        }
        return true;
    }
}
