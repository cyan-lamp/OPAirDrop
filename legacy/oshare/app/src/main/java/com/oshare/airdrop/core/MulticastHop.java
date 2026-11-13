package com.oshare.airdrop.core;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.MulticastSocket;
import java.net.StandardSocketOptions;

/**
 * Raises the IPv6 multicast hop limit to the value mDNS requires.
 *
 * <p>RFC 6762 asks every query and announcement to leave with hop limit 255, and has
 * receivers discard anything that arrives with less; that check is what keeps the
 * protocol on one link. Linux keeps the value in {@code np->mcast_hops}, which starts
 * at 1 and which neither {@link MulticastSocket#setTimeToLive(int)} nor
 * {@link StandardSocketOptions#IP_MULTICAST_TTL} reaches — both of those land on the
 * IPv4 {@code inet->mc_ttl} instead. A socket left alone therefore advertises hop
 * limit 1, and a peer that checks the field drops the packet without answering, which
 * looks from this side like a peer that never heard the query.
 *
 * <p>{@code IPV6_MULTICAST_HOPS} has no public Java setter, so it goes through
 * {@link Os#setsockoptInt} on a descriptor borrowed from the socket.
 */
final class MulticastHop {
    /** The limit mDNS mandates; also the largest an 8-bit field can carry. */
    static final int MDNS = 255;

    /** {@code IPV6_MULTICAST_HOPS}: stable in Linux, absent from {@link OsConstants}. */
    private static final int IPV6_MULTICAST_HOPS = 18;

    private MulticastHop() {
    }

    /**
     * Applies {@code hops} to {@code socket}, reporting whether the platform agreed.
     *
     * <p>Callers log a refusal and carry on rather than fail: a packet with hop limit 1
     * still reaches a peer that does not check the field, while a send that never
     * happens reaches nobody.
     */
    static boolean raise(DatagramSocket socket, int hops) {
        if (socket == null) return false;
        // Dual-stack sockets also carry an IPv4 group membership, so the IPv4 setters
        // are worth calling even though they cannot affect the IPv6 header.
        try {
            socket.setOption(StandardSocketOptions.IP_MULTICAST_TTL, hops);
        } catch (IOException | RuntimeException unsupported) {
            // Not every runtime exposes the option; the call below is the one that matters.
        }
        if (socket instanceof MulticastSocket) {
            try {
                ((MulticastSocket) socket).setTimeToLive(hops);
            } catch (IOException | RuntimeException unsupported) {
                // Same reason.
            }
        }

        ParcelFileDescriptor descriptor = descriptorOf(socket);
        if (descriptor == null) return false;
        try {
            Os.setsockoptInt(descriptor.getFileDescriptor(), OsConstants.IPPROTO_IPV6,
                IPV6_MULTICAST_HOPS, hops);
            // Answers go unicast to whoever asked, and the same rule covers those sends.
            Os.setsockoptInt(descriptor.getFileDescriptor(), OsConstants.IPPROTO_IPV6,
                OsConstants.IPV6_UNICAST_HOPS, hops);
            return true;
        } catch (ErrnoException | RuntimeException refused) {
            return false;
        } finally {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // The duplicate is closing anyway; nothing depends on the result.
            }
        }
    }

    /**
     * Borrows a descriptor for the socket's open file, or {@code null} if the runtime
     * will not hand one out.
     *
     * <p>{@link ParcelFileDescriptor#fromDatagramSocket} has been deprecated since API
     * 30, and for a good reason in general: a managed socket's descriptor is not meant
     * to leave the runtime that owns it. It is used here because the option above has
     * no other public route — {@link StandardSocketOptions} exposes no v6 hop limit, and
     * {@link Os} cannot act without a descriptor. The concern behind the deprecation,
     * a descriptor outliving the socket it came from, does not arise: this is a
     * duplicate of the same open file, closed before {@link #raise} returns, and the
     * option it sets lives on the socket rather than on the duplicate.
     *
     * <p>The catch is {@link Exception} rather than a list of the declared types
     * because the declaration itself has moved: the older platform jar spells this
     * method {@code throws IOException}, the current one does not, and catching both
     * keeps the file compiling against either.
     */
    @SuppressWarnings("deprecation")
    private static ParcelFileDescriptor descriptorOf(DatagramSocket socket) {
        try {
            return ParcelFileDescriptor.fromDatagramSocket(socket);
        } catch (Exception unavailable) {
            return null;
        }
    }
}
