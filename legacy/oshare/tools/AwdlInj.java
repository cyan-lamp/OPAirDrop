package com.oshare.airdrop.core;

/**
 * The transmit half of the raw 802.11 path, in the small amount of native code it
 * needs.
 *
 * <p>{@link AwdlProbe} shows that receiving needs nothing from native code: an unbound
 * {@code AF_PACKET} socket sees every virtual interface on the radio. Sending is
 * different. The driver has to know which vdev a frame belongs to, and it learns that
 * from the netdev the skb arrived on -- which means the socket must be bound to an
 * ifindex, which means a {@code sockaddr_ll}, which {@code android.system.Os} cannot
 * express: its {@code bind} only knows {@code InetSocketAddress}, and it has no
 * {@code setsockoptBytes}, so {@code SO_BINDTODEVICE} is out of reach as well.
 *
 * <p>{@code libawdl_inject.so} is that one missing call plus {@code send}. Everything
 * above it -- the TLVs, the schedule, the election -- stays in Java.
 */
final class AwdlInj {

    /** Where the probe scripts put the library on the device. */
    static final String LIBRARY = "/data/local/tmp/libawdl_inject.so";

    private static boolean loaded;

    /**
     * Loads the shim, once.
     *
     * <p>The class used to load it from a static initialiser, which assumed the only
     * caller was a probe reading {@link #LIBRARY}. The node that ships in the app is its
     * own root process and cannot ask a {@code Context} for the app's native library
     * directory, so the path arrives on its command line and is handed over here before
     * anything touches a native method. Keeping the initialiser as well and routing it
     * through this method means both callers load exactly one copy.
     *
     * <p>A null or empty path keeps the deployed copy the probe scripts use.
     */
    static synchronized void use(String path) {
        if (loaded)
            return;
        System.load(path != null && !path.isEmpty() ? path : LIBRARY);
        loaded = true;
    }

    static {
        use(LIBRARY);
    }

    private AwdlInj() {
    }

    /** Opens a transmit socket bound to {@code iface}; returns the fd or a negated errno. */
    static native int open(String iface);

    /**
     * Binds a receive socket to {@code iface}; returns 0 or a negated errno.
     *
     * <p>Needed for the same reason as {@link #open}: a {@code sockaddr_ll} cannot be
     * built from Java. The difference is what it buys. Unbound, an {@code AF_PACKET}
     * socket on this radio sees every frame three times, once per vif, and each of
     * those copies reaches the scheduler as an arrival -- one more read on the thread
     * that aims the frames, and one more burst cut short. Binding is not required for
     * correctness, so a failure here is reported and stepped over.
     */
    static native int bind(java.io.FileDescriptor fd, String iface);

    /** Sends one radiotap-prefixed frame; returns the byte count or a negated errno. */
    static native int send(int fd, byte[] frame);

    /** The errno of the last failed call. */
    static native int lastError();

    /** The C library's wording for {@code err}. */
    static native String strerror(int err);

    static native void close(int fd);
}
