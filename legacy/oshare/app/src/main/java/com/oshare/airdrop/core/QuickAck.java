package com.oshare.airdrop.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

/**
 * Keeps {@code TCP_QUICKACK} armed on an inbound connection, one read at a time.
 *
 * <p><b>Measured, and off.</b> The hypothesis was that the kernel's 40 ms delayed
 * acknowledgement was costing a window per acknowledgement, so arming the option after
 * every read should have cut the round trip. It did not: over a 64-second inbound
 * transfer with the option armed 369 times and refused 0, {@code ack/seg} stayed at
 * 0.53 and 896 of the 1117 acknowledgements that advanced the sequence still advanced it
 * by two segments -- the two-segment rule fires before any timer, so there was never a
 * timer to remove for most of the traffic.
 *
 * <p>What it did instead was emit a duplicate. Of 1915 acknowledgements this side put on
 * the air, 798 (41.7%) carried the same sequence number as the one before them; on the
 * same path without the option, 0 of 446 did. The acknowledgement timer that had already
 * been set for the previous segment is not cancelled by the quick one, so the kernel
 * sent both. Three of those in a row is what a sender calls a duplicate-acknowledgement
 * signal, and the sender acted on it: 579 payload frames (783 KB, 13.6% of the stream)
 * came back as fast retransmissions and the transfer went from 109 KB/s to 77 KB/s.
 *
 * <p>So the option is armed only when {@code AWDL_QUICKACK=1} is in the environment, and
 * the status line says {@code quickack off} otherwise. What it established is worth more
 * than the option: the acknowledgement path is not this receiver's bottleneck. The
 * segment-to-acknowledgement delay measured from the trace is a median of 5 ms, while the
 * sender still sits idle for 94 ms after every window of ten frames.
 */
public final class QuickAck extends InputStream {

    /**
     * Off unless asked for. The measurement behind the class comment is why: the option
     * costs 41.7% duplicate acknowledgements and 13.6% retransmitted frames on this path,
     * and buys nothing that the two-segment rule was not already doing.
     */
    static final boolean ENABLED = envFlag("AWDL_QUICKACK");

    /** Arms this many times; every one of them confirmed by the kernel. */
    private static long armed;

    /** Attempts the kernel refused, or that never reached it. */
    private static long refused;

    private final InputStream in;
    private final Socket socket;

    /** Set after the first failure that will not change: a missing library, no fd. */
    private boolean idle;

    private QuickAck(InputStream in, Socket socket) {
        this.in = in;
        this.socket = socket;
    }

    /**
     * Wraps {@code stream} so that it re-arms the option after every read.
     *
     * <p>Returns the stream untouched when the option is not enabled, when the socket is
     * null, or when the stream is: a wrapper that cannot arm anything is a pass-through
     * with an extra call in it.
     */
    static InputStream wrap(Socket socket, InputStream stream) {
        if (!ENABLED || socket == null || stream == null) return stream;
        return new QuickAck(stream, socket);
    }

    /** {@code armed/refused}, cumulative, for the status line. */
    static synchronized String summary() {
        if (!ENABLED) return "off";
        return armed + "/" + refused;
    }

    private static boolean envFlag(String name) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() && !"0".equals(value.trim());
    }

    @Override
    public int read() throws IOException {
        int one = in.read();
        arm();
        return one;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = in.read(buffer, offset, length);
        arm();
        return count;
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /**
     * Re-arms once, and gives up for good on the first refusal.
     *
     * <p>The option is only meaningful on a TCP socket, and the descriptor behind a
     * Java socket is reachable only by reflection; both can fail in a way that no
     * amount of retrying fixes. One failure is worth one line in the trace, not one
     * failed syscall per read for the rest of the transfer.
     */
    private void arm() {
        if (idle) return;
        if (AwdlInj.quickAck(socket)) {
            synchronized (QuickAck.class) {
                armed++;
            }
            return;
        }
        idle = true;
        synchronized (QuickAck.class) {
            refused++;
        }
        Trace.write("! quickack unavailable on this connection; "
            + "the kernel's 40 ms delayed acknowledgement stands");
    }
}
