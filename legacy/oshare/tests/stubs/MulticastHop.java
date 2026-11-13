package com.oshare.airdrop.core;

import java.net.DatagramSocket;

/**
 * Stand-in for the real {@link MulticastHop} when the core is compiled on a plain JDK.
 *
 * <p>The real one sets {@code IPV6_MULTICAST_HOPS} through {@code android.system.Os},
 * which no desktop JVM has, so it cannot be part of a runner that has no SDK on its
 * class path. Whatever it does is invisible to these tests — none of them joins a
 * link or asks a real peer anything — but the two classes that call it,
 * {@link AirDropBrowser} and {@link MdnsResponder}, are very much under test, so the
 * name has to resolve.
 *
 * <p>{@link #raise} reports {@code false}: on a desktop JVM the option genuinely cannot
 * be applied, and both callers already treat a refusal as "log it and carry on", so
 * this exercises their fallback path rather than pretending the hop limit was raised.
 */
final class MulticastHop {
    /** The limit mDNS mandates; unused here, but named by both callers. */
    static final int MDNS = 255;

    private MulticastHop() {
    }

    static boolean raise(DatagramSocket socket, int hops) {
        return false;
    }
}
