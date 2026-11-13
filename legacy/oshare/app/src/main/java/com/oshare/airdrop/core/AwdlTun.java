package com.oshare.airdrop.core;

/**
 * A tun device, so the kernel does the parts of the stack that are not worth rewriting.
 *
 * <p>The raw path works: frames built here are acked, the phone answers them, and a
 * SYN to 8770 comes back as a SYN-ACK. What it does not have is a TCP. Following that
 * handshake through by hand means ACKs, sequence windows, retransmission timers and
 * reassembly -- a stack, written from scratch, to be debugged against a peer that
 * cannot tell us what it disliked.
 *
 * <p>The kernel has that stack already. What it lacks is a netdev that carries AWDL.
 * {@code wonder0} is not one: the driver accepts what the stack emits on it (its tx
 * counter rises with every ping) and delivers almost nothing back -- 8 frames reached
 * it during a run in which 9295 came in over the radio. So the packets are moved
 * through here instead: the kernel writes an IP packet, this hands it to the framer,
 * and received frames are handed back the same way.
 *
 * <p>Nothing above this class needs to know AWDL exists. A plain {@code Socket}
 * connected to the peer's link-local is enough.
 */
final class AwdlTun {

    private AwdlTun() {
    }

    /**
     * Creates and attaches {@code iface} as a tun; returns the fd, or a negated errno.
     * The name is taken from the request, so no separate {@code ip tuntap} is needed.
     */
    static native int open(String iface);

    /** One packet, or 0 when none is waiting, or a negated errno. */
    static native int read(int fd, byte[] buf);

    /** Hands one packet to the kernel; returns the byte count or a negated errno. */
    static native int write(int fd, byte[] buf, int len);

    /**
     * Addresses the device, points the fe80:: route at it, and routes {@code uid}'s
     * link-local traffic there; 0 on success. A non-positive uid skips the rule.
     */
    static native int configure(String iface, String addr, int uid);
}
