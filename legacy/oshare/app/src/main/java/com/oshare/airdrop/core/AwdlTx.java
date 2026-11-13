package com.oshare.airdrop.core;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An AWDL node: the whole MAC in user space, on one radio.
 *
 * <p>This replaces the earlier version, which sent a MIF every 112 ms and could not
 * receive. That version answered one question -- whether the driver accepts the frame --
 * and the answer was yes. It could not answer the next one, because a node that only
 * transmits is not joinable: it has its own clock, its own slot boundaries, and its own
 * cadence, so every frame it sends lands outside the window its peer happens to be
 * listening in. The peer is not being rude. It is on a schedule, and we are not on it.
 *
 * <p>So the loop here mirrors the peer's own node loop, reconstructed from real captures:
 *
 * <ul>
 *   <li>{@code receive()} parses a peer's action frame and absorbs what it says about
 *       itself. A peer is only <em>valid</em> once it has sent a MIF and announced a
 *       nonzero version and device class (AWDL-17), and only valid peers reach the
 *       election -- the frame a phone sends before that says nothing about how it keeps
 *       time.
 *   <li>{@code tick()} runs once a second: peers unheard for 5 s are dropped, then the
 *       election runs over what is left.
 *   <li>The election is one-directional and that is the whole point. It ranks masters by
 *       metric first (AWDL-9) and the higher metric wins, so a node that starts at
 *       {@link AwdlFrame#METRIC_MIN metric 60} never unseats an iPhone sitting at 1280:
 *       the phone runs the same comparison, decides we rank lower, and keeps ignoring us
 *       however clearly it hears us. Following it is the only move available, and it is
 *       enough. This probe keeps that floor on purpose -- it is the baseline the ranking
 *       path is measured against -- while the data path starts at
 *       {@link AwdlFrame#INIT_METRIC 1024} and expects to win.
 *   <li>Following means {@link AwdlFrame#anchor}: the peer's Synchronization Parameters
 *       say how far its next slot boundary is and which extended AW starts there, which
 *       together give the boundary's position on our own clock. Adopting it is what puts
 *       our frames inside the phone's availability windows. A node that <em>follows</em> a
 *       peer but keeps its own phase has done the paperwork and none of the work.
 *   <li>Transmission is then a schedule, not a timer: a PSF every 110 TU, and a MIF in the
 *       middle of every slot. The MIF interval is not 112 ms -- a slot is 64 TU, so a MIF
 *       comes roughly every 65.5 ms, twice as often, and at a phase that only means
 *       anything if the clock has been adopted.
 * </ul>
 *
 * <p>The channel sequence stays {@code fixed(149)} for the whole run, even after
 * following, which is what {@code node.rs} does for a radio that cannot hop
 * ({@code Presence::Fixed}): the driver's radio is pinned to 5745 MHz, so advertising the
 * master's 6/44/149 sequence would describe a radio we do not have (AWDL-12). The price is
 * that we only hear the phone in the slots its own sequence puts on 149. That price is
 * smaller than it looks: an iPhone 13 measured over a 45 s run declared 11 of its 16 slots
 * on 149, one on 6, and four with no channel at all, so the phone is on our frequency most
 * of the time. It is still a per-slot question rather than an always, which is why data has
 * to be placed deliberately once there is any.
 *
 * <p>Usage: {@code AwdlTx [iface] [seconds] [name] [--no-tx]}
 */
public final class AwdlTx {

    // --- what a node announces ---------------------------------------------------

    /** {@code tlv::VERSION}: AWDL 3.4, the version every peer observed announces. */
    private static final int AWDL_VERSION = 0x34;
    /** {@code tlv::devclass::MACOS}: an Apple peer treats a Mac as a peer. */
    private static final int DEVCLASS = 1;
    /** The radio is pinned to 5745 MHz by the vendor init, so channel 149. */
    private static final int CHANNEL = 149;

    // --- timings, straight from node.rs ------------------------------------------

    /** PSFs every 110 TU (§3.2). */
    private static final long PSF_INTERVAL_US = AwdlFrame.PSF_INTERVAL_TU * (long) AwdlFrame.TU_US;
    /** Peers unheard for this long are dropped; an iPhone can miss several slots in a row. */
    private static final long PEER_TIMEOUT_US = 5_000_000L;
    /** How often the peer table is swept and the election run. */
    private static final long TICK_US = 1_000_000L;
    /** Longest path to a master that is followed ({@code MAX_HEIGHT}). */
    private static final int MAX_HEIGHT = 10;
    /** How long a single read may block, and the floor that keeps it from spinning. */
    private static final int READ_MIN_MS = 1;
    private static final int READ_MAX_MS = 200;

    // --- the socket ---------------------------------------------------------------

    private static final int AF_PACKET = 17;
    private static final int ETH_P_ALL = 0x0003;
    /** Every AWDL frame carries this BSSID, so the check is cheap and exact. */
    private static final String AWDL_BSSID = "00:25:00:ff:94:73";

    private AwdlTx() {
    }

    public static void main(String[] args) throws Exception {
        String iface = args.length > 0 ? args[0] : "wondertap0";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        String name = "";
        boolean transmit = true;
        for (int i = 2; i < args.length; i++) {
            if ("--no-tx".equals(args[i])) {
                transmit = false;
            } else if (!args[i].isEmpty() && name.isEmpty()) {
                name = args[i];
            }
        }

        byte[] addr = AwdlTx.readAddress(iface);
        if (addr == null) {
            System.out.println("awdl: cannot read /sys/class/net/" + iface + "/address");
            System.out.println("VERDICT no-address");
            return;
        }
        if (name.isEmpty()) {
            name = defaultName(addr);
        }
        System.out.println("awdl: iface=" + iface + " addr=" + mac(addr) + " name=" + name
                + " version=0x" + Integer.toHexString(AWDL_VERSION) + " class=" + DEVCLASS
                + " channel=" + CHANNEL + " metric=" + AwdlFrame.METRIC_MIN
                + " tx=" + (transmit ? "psf+mif" : "off"));

        // The receive socket needs nothing from native code and must not be bound: an
        // unbound ETH_P_ALL socket sees every vif the radio feeds. Do not byte-swap the
        // protocol -- Os.socket() already applies htons() for AF_PACKET, and swapping it
        // too registers a hook no device ever matches, which fails silently.
        FileDescriptor rx = null;
        // Kept as a bare fd rather than wrapped in a FileDescriptor: the native shim
        // owns this socket and closing it through Os.close() would mean reconstructing
        // an android.system descriptor by reflection, which is the kind of thing that
        // works until the field is renamed.
        int tx = -1;
        try {
            rx = openRx();
            if (transmit) {
                tx = AwdlInj.open(iface);
                if (tx < 0) {
                    System.out.println("awdl: bind failed: " + describe(tx));
                    System.out.println("VERDICT no-socket");
                    return;
                }
                System.out.println("awdl: transmit socket bound to " + iface + ", fd=" + tx);
            }
            new Node(addr, name, rx, tx).run(seconds);
        } finally {
            closeQuietly(rx);
            if (tx >= 0) {
                AwdlInj.close(tx);
            }
        }
    }

    private static FileDescriptor openRx() throws ErrnoException {
        FileDescriptor fd = Os.socket(AF_PACKET, OsConstants.SOCK_RAW, ETH_P_ALL);
        Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1 << 20);
        Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(READ_MIN_MS));
        System.out.println("awdl: receive socket open, unbound (sees every vif)"
                + ", filter BSSID=" + AWDL_BSSID);
        return fd;
    }

    private static void closeQuietly(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // Nothing useful to do while shutting down.
        }
    }

    /** Microseconds on a monotonic clock; the same scale {@link AwdlFrame} was given. */
    private static long now() {
        return System.nanoTime() / 1000L;
    }

    // =================================================================================
    // The node
    // =================================================================================

    /** One run: a clock, an election, a peer table, and three deadlines. */
    private static final class Node {

        private final AwdlFrame frame;
        private final FileDescriptor rx;
        /** The native transmit fd, or -1 when running receive-only. */
        private final int tx;
        private final byte[] self;
        private final Election election;

        private final Map<String, Peer> peers = new LinkedHashMap<>();
        /** Frames already handled, so the three vif copies cost one decision each. */
        private final Set<String> recent = new LinkedHashSet<>();

        private long nextPsf;
        private long nextMif;
        private long nextTick;

        private int psfSent;
        private int mifSent;
        private int sendFailed;
        private int anchored;
        private int lastErrorTu;

        Node(byte[] addr, String name, FileDescriptor rx, int tx) {
            long start = now();
            this.self = addr.clone();
            this.rx = rx;
            this.tx = tx;
            this.frame = new AwdlFrame(addr, name, AWDL_VERSION, DEVCLASS, CHANNEL, start);
            this.election = new Election(addr);
            // node.rs: a MIF goes out at once, the first PSF one interval later. Both
            // deadlines then advance from there rather than from the wall clock, so a
            // late frame does not shift the cadence.
            this.nextPsf = start + PSF_INTERVAL_US;
            this.nextMif = start;
            this.nextTick = start + TICK_US;
        }

        void run(int seconds) throws ErrnoException {
            byte[] buf = new byte[8192];
            long deadline = now() + seconds * 1_000_000L;
            long nextReport = now() + 2_000_000L;
            long start = now();

            while (true) {
                long now = now();
                if (now >= deadline) {
                    break;
                }
                service(now);

                long next = Math.min(nextPsf, Math.min(nextMif, nextTick));
                long waitUs = next - now();
                if (waitUs <= 0) {
                    continue;
                }
                // Block on the socket for as long as there is nothing to do, so a frame
                // wakes us immediately and an idle radio costs one wakeup per wait.
                int timeoutMs = (int) Math.max(READ_MIN_MS,
                        Math.min(READ_MAX_MS, (waitUs + 999L) / 1000L));
                Os.setsockoptTimeval(rx, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(timeoutMs));
                receive(buf);

                long t = now();
                if (t >= nextReport) {
                    nextReport = t + 2_000_000L;
                    report(t, start);
                }
            }
            report(now(), start);
            System.out.println("awdl: psf=" + psfSent + " mif=" + mifSent
                    + " failed=" + sendFailed + " anchors=" + anchored
                    + " error_tu=" + lastErrorTu + " peers=" + peers.size()
                    + " valid=" + validCount());
            System.out.println(verdict());
        }

        /**
         * What the run actually established, in the one word the runner greps for.
         *
         * <p>The middle cases are the useful ones. {@code no-valid-peer} means frames
         * arrive but none passes AWDL-17 -- no MIF, or no version and device class TLV --
         * which points at our parser or at the phone answering only version-1 peers, not
         * at the clock. {@code outranked} means a valid peer exists and we stayed master
         * anyway, which an iPhone at metric 1280 makes impossible: if it appears, either
         * the ranking or the metric field is being read from the wrong offset.
         */
        private String verdict() {
            if (!election.isMaster()) {
                return "VERDICT followed";
            }
            if (peers.isEmpty()) {
                return "VERDICT silent";
            }
            if (validCount() == 0) {
                return "VERDICT no-valid-peer";
            }
            return "VERDICT outranked";
        }


        private void report(long now, long start) {
            StringBuilder sb = new StringBuilder();
            sb.append("  [").append((now - start) / 1_000_000L).append("s] psf=").append(psfSent)
                    .append(" mif=").append(mifSent)
                    .append(" anchors=").append(anchored)
                    .append(" err=").append(lastErrorTu).append("TU")
                    .append(" next_tu=").append(frame.nextSlotTu(now))
                    .append(" aw=").append(frame.currentAw(now))
                    .append(" master=").append(mac(frame.master()))
                    .append(" hops=").append(frame.height());
            for (Peer p : peers.values()) {
                sb.append("\n    ").append(p);
            }
            System.out.println(sb);
        }

        private int validCount() {
            int n = 0;
            for (Peer p : peers.values()) {
                if (p.valid) {
                    n++;
                }
            }
            return n;
        }

        // --- the schedule ---------------------------------------------------------

        /** {@code service()}: what is due now, in the order node.rs runs it. */
        private void service(long now) {
            if (now >= nextTick) {
                tick(now);
                while (nextTick <= now) {
                    nextTick += TICK_US;
                }
            }
            if (now >= nextPsf) {
                send(AwdlFrame.SUBTYPE_PSF, now);
                while (nextPsf <= now) {
                    nextPsf += PSF_INTERVAL_US;
                }
            }
            if (now >= nextMif) {
                // In the middle of the slot, not on the boundary: the boundary is where
                // the master's own PSF lives.
                send(AwdlFrame.SUBTYPE_MIF, now);
                nextMif = now + frame.nextSlotUs(now) + AwdlFrame.SLOT_US / 2;
            }
        }

        private void send(int subtype, long now) {
            if (tx < 0) {
                return;
            }
            byte[] bytes = subtype == AwdlFrame.SUBTYPE_PSF ? frame.psf(now) : frame.mif(now);
            int n = AwdlInj.send(tx, bytes);
            if (n < 0) {
                sendFailed++;
                if (sendFailed <= 3) {
                    System.out.println("awdl: send failed: " + describe(n)
                            + " (frame " + bytes.length + " bytes)");
                }
                return;
            }
            if (subtype == AwdlFrame.SUBTYPE_PSF) {
                psfSent++;
            } else {
                mifSent++;
            }
            if (psfSent + mifSent == 1) {
                System.out.println("awdl: first frame " + bytes.length + " bytes, action body "
                        + (bytes.length - 8 - 24) + " bytes");
                System.out.println("awdl: " + hex(bytes, 0, Math.min(bytes.length, 64)));
                try {
                    Files.write(Paths.get("/data/local/tmp/awdl-frame.hex"),
                            dump(bytes).getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                    System.out.println("awdl: frame dump failed: " + e);
                }
            }
        }

        // --- receive --------------------------------------------------------------

        private void receive(byte[] buf) throws ErrnoException {
            int n;
            try {
                n = Os.read(rx, buf, 0, buf.length);
            } catch (InterruptedIOException timeout) {
                return;
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EAGAIN) {
                    return;
                }
                throw e;
            }
            if (n <= 0) {
                return;
            }
            AwdlProbe.Frame f = AwdlProbe.Frame.parse(buf, n);
            if (f == null || !AWDL_BSSID.equals(f.bssid)) {
                return;
            }
            // node.rs drops a frame whose source is itself: a node that followed its own
            // echo would adopt its own phase and never move off it. Compared by value --
            // two byte[] are never == unless they are the same array.
            if (Arrays.equals(self, macBytes(f.sa))) {
                return;
            }
            // One receive path feeds several vifs, so each frame arrives once per vif.
            if (!recent.add(f.sa + '/' + f.seq)) {
                return;
            }
            if (recent.size() > 256) {
                recent.remove(recent.iterator().next());
            }
            if (f.awdlSubtype != AwdlFrame.SUBTYPE_PSF
                    && f.awdlSubtype != AwdlFrame.SUBTYPE_MIF) {
                return;
            }
            long now = now();
            onAction(now, f);
        }

        /** {@code rx_action()}: absorb what the frame said, then follow it if it is ours. */
        private void onAction(long now, AwdlProbe.Frame f) {
            byte[] src = macBytes(f.sa);
            Peer peer = peers.get(f.sa);
            if (peer == null) {
                peer = new Peer(src);
                peers.put(f.sa, peer);
            }
            peer.lastSeen = now;
            peer.absorb(f, f.awdlSubtype == AwdlFrame.SUBTYPE_MIF);

            if (f.nextTu >= 0 && f.awCounter >= 0) {
                peer.syncOffsetTu = frame.errorTu(now, f.nextTu, f.awCounter);
                // AWDL-7, and the reason this whole loop exists: adopt the schedule of
                // the node we sync to, and of nobody else.
                if (election.syncs(src)) {
                    frame.anchor(now, f.nextTu, f.awCounter);
                    anchored++;
                    lastErrorTu = peer.syncOffsetTu;
                    if (anchored <= 5) {
                        System.out.println("awdl: anchored to " + f.sa + " next_tu=" + f.nextTu
                                + " aw=" + f.awCounter + " error=" + peer.syncOffsetTu + "TU");
                    }
                }
            }
        }

        /** {@code tick()}: sweep, then elect over what is left. */
        private void tick(long now) {
            List<String> gone = new ArrayList<>();
            for (Map.Entry<String, Peer> e : peers.entrySet()) {
                if (now - e.getValue().lastSeen > PEER_TIMEOUT_US) {
                    gone.add(e.getKey());
                }
            }
            for (String key : gone) {
                Peer p = peers.remove(key);
                System.out.println("awdl: peer down " + key + " (was valid=" + p.valid + ")");
            }
            boolean changed = election.run(peers.values());
            if (changed) {
                frame.follow(election.master, election.height, election.masterMetric,
                        election.masterCounter);
                System.out.println("awdl: election master=" + mac(election.master)
                        + " sync=" + mac(election.sync) + " hops=" + election.height
                        + " metric=" + election.masterMetric);
            }
        }
    }

    // =================================================================================
    // The election, as election.rs runs it
    // =================================================================================

    private static final class Election {
        final byte[] own;
        byte[] master;
        byte[] sync;
        int height;
        long masterMetric;
        long masterCounter;
        long selfMetric = AwdlFrame.METRIC_MIN;
        long selfCounter = 0;

        Election(byte[] own) {
            this.own = own.clone();
            this.master = own.clone();
            this.sync = own.clone();
        }

        boolean isMaster() {
            return Arrays.equals(sync, own);
        }

        boolean syncs(byte[] mac) {
            return Arrays.equals(sync, mac);
        }

        private long[] rank(long metric, long counter) {
            // MetricFirst: what Apple devices rank by (AWDL-9).
            return new long[]{metric, counter};
        }

        boolean run(java.util.Collection<Peer> peerTable) {
            byte[] oldMaster = master;
            byte[] oldSync = sync;
            // Back to being our own master, at our own metric, before anything is weighed.
            height = 0;
            master = own.clone();
            sync = own.clone();
            masterMetric = selfMetric;
            masterCounter = selfCounter;

            byte[] bestMaster = master;
            byte[] bestSync = sync;
            byte[] bestOwn = own;
            int bestHeight = height;
            long bestMetric = masterMetric;
            long bestCounter = masterCounter;
            long[] bestRank = rank(bestMetric, bestCounter);

            for (Peer p : peerTable) {
                if (!p.valid) {
                    continue;
                }
                long[] r = rank(p.masterMetric, p.masterCounter);
                if (p.height >= MAX_HEIGHT) {
                    continue;
                }
                // No cycles: never follow a peer that follows us.
                if (Arrays.equals(p.sync, own) && !Arrays.equals(p.mac, own)) {
                    continue;
                }
                int cmp = compare(r, bestRank);
                if (cmp < 0) {
                    continue;
                }
                if (cmp == 0) {
                    if (p.height > bestHeight) {
                        continue;
                    }
                    // Equal rank and equal height: the higher address wins.
                    if (p.height == bestHeight && compareMac(p.mac, bestOwn) <= 0) {
                        continue;
                    }
                }
                bestMaster = p.master;
                bestSync = p.mac;
                bestOwn = p.mac;
                bestHeight = p.height;
                bestMetric = p.masterMetric;
                bestCounter = p.masterCounter;
                bestRank = r;
            }

            if (!Arrays.equals(bestOwn, own)) {
                master = bestMaster;
                sync = bestSync;
                masterMetric = bestMetric;
                masterCounter = bestCounter;
                height = bestHeight + 1;
            }
            return !Arrays.equals(master, oldMaster) || !Arrays.equals(sync, oldSync);
        }
    }

    /** What one peer has said about itself ({@code peer.rs}). */
    private static final class Peer {
        final byte[] mac;
        /**
         * The ARPA host name from TLV 16. Always empty for now: the MIF carries it and
         * nothing in this loop needs it, since the name AirDrop shows comes from the
         * mDNS TXT records, not from the MAC frame. Kept so the shape matches
         * {@code peer.rs} and so the parser slot is obvious when data frames land.
         */
        String name = "";
        int version;
        int devclass;
        long lastSeen;
        /** TU error against the frame's schedule; {@code error_tu} in the peer's stats. */
        int syncOffsetTu;
        boolean mif;
        boolean v2;
        boolean valid;

        byte[] master;
        byte[] sync;
        int height;
        long masterMetric;
        long masterCounter = 0;
        long selfMetric = AwdlFrame.METRIC_MIN;
        long selfCounter = 0;
        int[] seqSlots;
        /** The sequence as the frame stated it, encoding included. Its shape decides
         *  whether a zero in {@link #seqSlots} means "no channel" or "not decoded". */
        String seqText = "";
        long seqSeen;

        Peer(byte[] mac) {
            this.mac = mac.clone();
            this.master = mac.clone();
            this.sync = mac.clone();
        }

        void absorb(AwdlProbe.Frame f, boolean isMif) {
            if (f.version >= 0) {
                version = f.version;
                devclass = f.deviceClass;
            }
            if (f.masterMetric >= 0) {
                if (f.master != null && !"?".equals(f.master)) {
                    master = macBytes(f.master);
                }
                height = Math.max(0, f.height);
                masterMetric = f.masterMetric & 0xffffffffL;
                selfMetric = f.selfMetric & 0xffffffffL;
                if (f.v2) {
                    // Version 2 carries the node it syncs to and the counters; once a peer
                    // sends it, version 1 no longer wins for it.
                    sync = (f.sync != null && !"?".equals(f.sync)) ? macBytes(f.sync) : mac.clone();
                    masterCounter = f.masterCounter & 0xffffffffL;
                    v2 = true;
                }
            }
            if (f.seqSlots.length == AwdlFrame.SLOTS) {
                seqSlots = f.seqSlots;
                seqText = f.sequence;
                seqSeen = System.nanoTime() / 1000L;
            }
            mif |= isMif;
            // AWDL-17: a MIF and a nonzero version and device class, and only then is it a
            // node to talk to. Everything before that is a frame, not a peer.
            valid = mif && version != 0 && devclass != 0;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mac(mac)).append(valid ? " valid" : " heard");
            sb.append(" ver=0x").append(Integer.toHexString(version))
                    .append(" class=").append(devclass)
                    .append(" metric=").append(masterMetric).append('/').append(selfMetric)
                    .append(" hops=").append(height)
                    .append(" master=").append(mac(master))
                    .append(" counter=").append(masterCounter)
                    .append(" offset=").append(syncOffsetTu).append("TU");
            if (seqSlots != null) {
                sb.append(" seq=").append(Arrays.toString(seqSlots))
                        .append(" (").append(seqText).append(')');
            }
            return sb.toString();
        }
    }

    // =================================================================================
    // Small things
    // =================================================================================

    private static int compare(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Long.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** {@code MacAddr}'s ordering is the bytes', left to right, unsigned. */
    private static int compareMac(byte[] a, byte[] b) {
        for (int i = 0; i < 6; i++) {
            int c = Integer.compare(a[i] & 0xff, b[i] & 0xff);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * The interface's own MAC. It is not a free choice: the driver drops any frame whose
     * address 2 differs from the vdev's, so the frame has to announce this one.
     */
    static byte[] readAddress(String iface) {
        Path path = Paths.get("/sys/class/net/" + iface + "/address");
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
            return macBytes(text);
        } catch (IOException e) {
            return null;
        }
    }

    /** Parses {@code aa:bb:cc:dd:ee:ff}, or returns null if it isn't one. */
    private static byte[] macBytes(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(":");
        if (parts.length != 6) {
            return null;
        }
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** A stable single DNS label, since the ARPA TLV carries a host name and not a MAC. */
    private static String defaultName(byte[] addr) {
        return "oshare-" + String.format("%02x%02x", addr[4] & 0xff, addr[5] & 0xff);
    }

    private static String describe(int err) {
        int code = err < 0 ? -err : err;
        String text;
        try {
            text = AwdlInj.strerror(code);
        } catch (Throwable t) {
            text = "errno " + code;
        }
        return text + " (errno " + code + ")";
    }

    static String mac(byte[] a) {
        if (a == null) {
            return "?";
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", a[i] & 0xff));
        }
        return sb.toString();
    }

    private static String hex(byte[] p, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(i % 16 == 0 ? "\n  " : " ");
            }
            sb.append(String.format("%02x", p[off + i] & 0xff));
        }
        return sb.toString();
    }

    /** The whole frame, one byte offset per line item, so offsets can be read off. */
    private static String dump(byte[] p) {
        StringBuilder sb = new StringBuilder(p.length * 4);
        for (int i = 0; i < p.length; i += 16) {
            sb.append(String.format("%04x  ", i));
            for (int j = i; j < Math.min(i + 16, p.length); j++) {
                sb.append(String.format("%02x ", p[j] & 0xff));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
