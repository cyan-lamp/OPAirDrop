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
     * Loads the shim, once. The node is started as its own root process, so it cannot
     * ask a {@code Context} where the app's native libraries live: the path arrives on
     * the command line and is handed over here before anything touches a native method.
     * A null path keeps the deployed copy the probe scripts use.
     */
    static synchronized void use(String path) {
        if (loaded)
            return;
        System.load(path != null && !path.isEmpty() ? path : LIBRARY);
        loaded = true;
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

    /**
     * Re-arms {@code TCP_QUICKACK} on a connection; true when the kernel took it.
     *
     * <p>The receiver's delayed-acknowledgement timer is 40 ms on this kernel and there
     * is no sysctl to change it ({@code /proc/sys/net/ipv4/tcp_delack_min} does not
     * exist). The option is the only way in, and it is one-shot: the kernel clears the
     * flag the moment it makes an acknowledgement decision, so the caller has to call
     * this again after every read that may have consumed a segment.
     *
     * <p>Why it matters here: one AWDL slot is 64 ms and carries one window, so a
     * 40 ms timer decides whether an acknowledgement catches this window or the next.
     * Measured on an inbound phone-to-phone transfer, the median acknowledgement left
     * this process 5 ms after the segment it answered -- but 4.7% of them left after
     * 84 ms, which is the timer plus the wait for the following window, and those are
     * the samples that keep a sender's round-trip estimate high.
     */
    static boolean quickAck(java.net.Socket socket) {
        int fd = fdOf(socket);
        if (fd < 0) return false;
        if (!loadFromApk()) return false;
        return armQuickAck(fd) == 0;
    }

    /**
     * Loads the shim from the installed APK, for callers that run inside the app.
     *
     * <p>{@link #use(String)} exists because the node is a root process started from a
     * shell and has to be told where the library is. The receiver runs in the app's own
     * process, where the packaging system has already put the library on
     * {@code java.library.path} and {@code System.loadLibrary} is the whole answer. One
     * attempt, remembered either way: if the APK was built without the {@code jniLibs}
     * directory, every later call would pay for the same failure.
     */
    private static synchronized boolean loadFromApk() {
        if (loaded) return true;
        if (apkTried) return false;
        apkTried = true;
        try {
            System.loadLibrary("awdl_inject");
            loaded = true;
            return true;
        } catch (Throwable unpackaged) {
            // Not shipped in this build, or shipped for another ABI. The caller falls
            // back to the kernel's own acknowledgement policy, which is what every
            // transfer before this change ran on.
            return false;
        }
    }

    private static boolean apkTried;

    /**
     * Arms {@code TCP_QUICKACK} on an already-open descriptor; 0 or a negated errno.
     *
     * <p>Named apart from {@link #quickAck(java.net.Socket)} on purpose: a native method
     * that shares its name with an ordinary one makes the symbol it is looked up under
     * ambiguous, and the failure mode there is an {@code UnsatisfiedLinkError} at first
     * call rather than anything readable.
     */
    static native int armQuickAck(int fd);

    /**
     * The descriptor behind a {@link java.net.Socket}, by reflection.
     *
     * <p>There is no public way to reach it, and the option has to be set on the very
     * socket the kernel accepted rather than on anything derived from it. Two fields
     * deep -- {@code Socket.impl.fd}, then {@code FileDescriptor.descriptor} -- and both
     * are looked up once. A failure is not fatal: the caller reports it and carries on
     * with the kernel's default behaviour, which is what every transfer before this
     * change ran on.
     */
    private static int fdOf(java.net.Socket socket) {
        if (socket == null) return -1;
        try {
            if (implField == null || fdField == null || descField == null) {
                java.lang.reflect.Field impl = java.net.Socket.class.getDeclaredField("impl");
                impl.setAccessible(true);
                java.lang.reflect.Field fd = java.net.SocketImpl.class.getDeclaredField("fd");
                fd.setAccessible(true);
                java.lang.reflect.Field desc = java.io.FileDescriptor.class
                        .getDeclaredField("descriptor");
                desc.setAccessible(true);
                implField = impl;
                fdField = fd;
                descField = desc;
            }
            Object impl = implField.get(socket);
            if (impl == null) return -1;
            Object descriptor = fdField.get(impl);
            if (descriptor == null) return -1;
            Object value = descField.get(descriptor);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable unreachable) {
            // Hidden fields move, and a reflection failure must never cost a transfer.
            return -1;
        }
    }

    private static java.lang.reflect.Field implField;
    private static java.lang.reflect.Field fdField;
    private static java.lang.reflect.Field descField;
}
