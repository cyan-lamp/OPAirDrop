package com.oshare.airdrop.core;

import java.util.Arrays;

/**
 * Builds AWDL PSF and MIF frames byte for byte.
 *
 * <p>The layouts follow the AWDL spec's TLV table, checked against real iOS captures,
 * with the differences a live iPhone 13 on iOS 27.2 showed kept visible in the comments
 * below. Every field is written through {@link Buf}, so offsets never have to be counted
 * by hand.
 *
 * <p>Three facts about the frame shape are worth having in one place:
 * <ul>
 *   <li>Address 1 is broadcast and address 3 is AWDL's fixed BSSID
 *       {@code 00:25:00:ff:94:73}; AWDL is separable at L2 by that BSSID alone.
 *   <li>Address 2 must be the address of the vdev the frame is sent on. The driver
 *       compares it against the vdev's own MAC and drops the frame otherwise
 *       ({@code opairshare_pas_mgmt_xmit}), so it is read from the interface rather
 *       than invented.
 *   <li>The action header is 16 bytes: category, OUI, type, version, subtype,
 *       reserved, then the sender's clock and the time it meant to send at, both
 *       microseconds, both 32-bit.
 * </ul>
 *
 * <p>This class keeps the clock too: sixty-four TU per slot, four availability windows
 * per slot, and the same two counters the Synchronization Parameters TLV announces
 * ({@code next_tu} down-counter and the AW sequence number).
 */
final class AwdlFrame {

    /** Microseconds in a 802.11 timing unit. */
    static final int TU_US = 1024;
    /** AWDL-10: an availability window is 16 TU. */
    static final int AW_PERIOD_TU = 16;
    /** Four AWs make one extended AW, one slot of the channel sequence. */
    static final int PRESENCE_MODE = 4;
    /** TU per slot. */
    static final int SLOT_TU = AW_PERIOD_TU * PRESENCE_MODE;
    /** Slots in a channel sequence. */
    static final int SLOTS = 16;
    /** How often a node sends its periodic frames, in TU (110 ≈ 112.6 ms). */
    static final int PSF_INTERVAL_TU = 110;
    /** AWDL-8's floor: the worst rank a node can hold, which loses to every Apple peer. */
    static final int METRIC_MIN = 60;
    /**
     * The rank this node starts at, and the one lever that decides whether its channel
     * sequence binds.
     *
     * <p>{@link #METRIC_MIN} is what AWDL-8 hands a node that has heard nobody, and it is
     * a decision to lose: a live iPhone announces 538, a Mac 1280, and the election keeps
     * the higher metric, so at 60 this node is outranked before the comparison starts.
     * Every frame that followed from that was a follower's frame -- the peer's five
     * slots of sixteen, the peer's master, the peer's map copied verbatim.
     *
     * <p>1024 is above the iPhone's 538 and below the Mac's 1280: it wins the election
     * against the device actually on the other end of this link, which is what makes our
     * sixteen-slot map a statement the peer has a reason to obey, and it still loses to
     * a laptop that announces more, so the node can be a follower when it meets one.
     * A rank that beats everything would be a different claim -- and one no Apple peer
     * would honour anyway, since it runs the same comparison on its own side.
     */
    static final int INIT_METRIC = 1024;
    /** One slot in microseconds; the mid-slot MIF is scheduled off this. */
    static final long SLOT_US = (long) SLOT_TU * TU_US;

    static final int SUBTYPE_PSF = 0;
    static final int SUBTYPE_MIF = 3;

    /** Apple's OUI, then AWDL's type within it (SPEC §3.2). */
    private static final byte[] OUI = {0x00, 0x17, (byte) 0xf2};
    private static final int CATEGORY_VENDOR = 0x7f;
    private static final int AWDL_TYPE = 0x08;
    /** AWDL 1.0 as the action header carries it; unrelated to the Version TLV. */
    private static final int ACTION_VERSION = 0x10;
    private static final int FC_ACTION = 0x00d0;
    /**
     * QoS data, no distribution system: AWDL carries IP between peers only.
     *
     * <p>0x0088 rather than 0x0008 is not a style choice. The stock driver's passthru
     * bridge accepts a whole frame on the raw path only for probe-request, action and
     * exactly 0x0088; any other non-management frame falls through to the ordinary
     * TCL path, where a passthru vdev owns no peer for the destination and the frame
     * is dropped without a trace. Plain 0x0008 therefore leaves the host silently.
     * QoS also matches what the driver itself expects to see: its A-MSDU and ACK
     * policy rewriting index the first QoS control byte at offset 24.
     */
    private static final int FC_QOS_DATA = 0x0088;
    /**
     * QoS control, 2 bytes: TID 0 and nothing else. The A-MSDU-present bit and the
     * ACK policy are left at zero so the driver's own aggregation decides, and so a
     * lone MSDU is acknowledged normally.
     */
    private static final int QOS_CONTROL = 0x0000;

    /**
     * The SNAP header an AWDL data frame carries (AWDL-13). It names Apple's OUI and
     * protocol 0x0800, but that pair is a fixed preamble rather than the real ethertype:
     * the actual type sits four bytes further on, after the AWDL data header.
     */
    private static final byte[] LLC_SNAP = {(byte) 0xaa, (byte) 0xaa, 0x03,
            0x00, 0x17, (byte) 0xf2, 0x08, 0x00};
    /** AWDL-13: little endian, and it is the third 16-bit word a receiver checks. */
    private static final int DATA_HEAD = 0x0403;
    /** The only ethertype AWDL data frames carry. */
    private static final int ETHERTYPE_IPV6 = 0x86dd;

    /** The fixed BSSID every AWDL frame carries (AWDL-1). */
    static final byte[] BSSID = {0x00, 0x25, 0x00, (byte) 0xff, (byte) 0x94, 0x73};
    private static final byte[] BROADCAST = {(byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff};

    /** A radiotap header with no fields at all: the driver takes the channel from its own state. */
    static final byte[] RADIOTAP = {0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00};

    /**
     * The rate every injected frame asks for, in Mb/s, unless the tuning file says otherwise.
     *
     * <p>Six was what the air showed and it was not a choice: a header with no rate field
     * leaves the number to the driver, and for a frame arriving through AF_PACKET the
     * driver picks its lowest rate and keeps it. Captured on the same channel, the iPhone
     * two metres away was sending at 12 Mb/s while ours carried no rate at all, and the
     * rate never moved across a whole transfer -- no adaptation, one fixed slow number.
     *
     * <p>Thirty-six is six times that, and six times is what the arithmetic asks for: a
     * 1390-byte frame needs 1.85 ms of airtime at 6 Mb/s, which is almost exactly the
     * 1.75 ms per frame the send loop was spending, so the link was limited by airtime and
     * not by the congestion window, the window size, or anything this side of the radio.
     */
    static final int DEFAULT_RATE_MBPS = 36;

    /**
     * The header every injected frame starts with, rebuilt whenever the rate changes.
     *
     * <p>Radiotap's rate is bit 2 of the present word: one byte, in units of 500 kb/s.
     * Having the field at all makes the header nine bytes instead of eight, so the whole
     * array is replaced on a change rather than patched in place -- a frame built while
     * the number was moving would otherwise carry one length and another rate.
     */
    private static volatile byte[] radiotapHeader = radiotapFor(DEFAULT_RATE_MBPS);

    /** The rate in the header, in Mb/s, or zero when the header carries none. */
    private static volatile int txRateMbps = DEFAULT_RATE_MBPS;

    /** Radiotap present bits for the fields this header can carry. */
    private static final int RT_RATE = 0x00000004;
    private static final int RT_MCS = 0x00080000;
    private static final int RT_VHT = 0x00200000;

    /**
     * The 802.11n/ac modulation the header asks for, or -1 for a legacy rate only.
     *
     * <p>This is the knob that matters. A legacy rate tops out at fifty-four Mb/s, which
     * is six and three quarter megabytes a second of airtime before any framing -- and
     * this path wastes thirty percent of that on the burst pattern, so the measured
     * ceiling was 3.2 MB/s no matter what the rate field said past thirty-six. A phone
     * carrying the same file to another phone does it in one to two seconds because it
     * is not using a legacy rate at all: it is on eighty-megahertz VHT, where one spatial
     * stream at MCS 9 is four hundred and thirty-three Mb/s.
     *
     * <p>Radiotap addresses that through two more fields: MCS (present bit 19, three
     * bytes: known, flags, index) for 802.11n, and VHT (bit 21, twelve bytes) for
     * 802.11ac, whose bandwidth byte is the only way to ask for eighty megahertz -- the
     * MCS field's flag can only say twenty or forty.
     */
    private static volatile int txMcs = -1;
    private static volatile int txWidthMhz = 20;
    private static volatile boolean txVht;
    private static volatile boolean txSgi;

    private static byte[] radiotapFor(int mbps) {
        if (mbps <= 0) {
            return RADIOTAP;
        }
        return new byte[] {0x00, 0x00, 0x09, 0x00, 0x04, 0x00, 0x00, 0x00, (byte) (mbps * 2)};
    }

    /**
     * Puts a transmit rate in the radiotap header of every frame built from now on.
     *
     * <p>Zero restores the empty header, which is how this sent before the rate was
     * understood; anything from one to fifty-four becomes that many Mb/s, and higher
     * numbers are held at fifty-four because that is the fastest legacy rate the channel
     * the radio is on can carry. Called from the tuning reader, on the same thread that
     * builds the frames, so the volatile write is the whole of the handover.
     */
    static void setTxRateMbps(int mbps) {
        int clamped = mbps <= 0 ? 0 : Math.min(mbps, 54);
        txRateMbps = clamped;
        rebuildRadiotap();
    }

    /**
     * Puts an 802.11n/ac modulation in the header, or takes it back out with {@code mcs < 0}.
     *
     * <p>The legacy rate field stays in the header underneath, because the driver reads it
     * first and a header that carries an MCS with no rate is a header some drivers refuse
     * outright. When the two disagree the MCS wins, which is what makes the knob testable:
     * setting {@code mcs=0} on a header that also says thirty-six Mb/s moves the link to
     * 6.5 Mb/s if the field is honoured and leaves it alone if it is not.
     */
    static void setTxPhy(int mcs, int widthMhz, boolean vht, boolean sgi) {
        txMcs = mcs < 0 ? -1 : Math.min(mcs, 9);
        txWidthMhz = widthMhz <= 20 ? 20 : widthMhz >= 80 ? 80 : 40;
        txVht = vht;
        txSgi = sgi;
        rebuildRadiotap();
    }

    private static void rebuildRadiotap() {
        radiotapHeader = buildRadiotap(txRateMbps, txMcs, txWidthMhz, txVht, txSgi);
    }

    /**
     * The header for one combination of legacy rate and MCS, or the bare one for neither.
     *
     * <p>Radiotap fields are packed in bit order with no padding between the three used
     * here, so the length is eight plus one, plus three, plus twelve. The VHT field's
     * {@code mcs_nss} byte is MCS in the low nibble and the stream count in the high one;
     * one stream is the honest description of a single injected frame, and asking for
     * more would need a sounding exchange this path does not do.
     */
    private static byte[] buildRadiotap(int mbps, int mcs, int width, boolean vht, boolean sgi) {
        if (mbps <= 0 && mcs < 0) {
            return RADIOTAP;
        }
        int present = 0;
        int off = 8;
        int rateOff = -1;
        int mcsOff = -1;
        int vhtOff = -1;
        if (mbps > 0) {
            present |= RT_RATE;
            rateOff = off;
            off += 1;
        }
        if (mcs >= 0) {
            present |= RT_MCS;
            mcsOff = off;
            off += 3;
            if (vht) {
                // The VHT field opens with a sixteen-bit word, so it wants an even
                // offset. It already has one whenever the rate byte is present, which it
                // is on every frame this path builds, but padding is cheaper than
                // depending on that.
                if ((off & 1) != 0) {
                    off++;
                }
                present |= RT_VHT;
                vhtOff = off;
                off += 12;
            }
        }
        byte[] h = new byte[off];
        h[2] = (byte) (off & 0xff);
        h[3] = (byte) ((off >> 8) & 0xff);
        h[4] = (byte) present;
        h[5] = (byte) (present >> 8);
        h[6] = (byte) (present >> 16);
        h[7] = (byte) (present >> 24);
        if (rateOff >= 0) {
            h[rateOff] = (byte) (mbps * 2);
        }
        if (mcsOff >= 0) {
            // The MCS field is a known mask followed by the values it vouches for, and
            // both bytes have to be filled in coherently. A zero known mask means the
            // driver drops the index on the floor and keeps doing what it was doing,
            // which looks exactly like "the chip ignored us" when in fact we asked for
            // nothing. Bit 0 of the mask is bandwidth, bit 1 the index, bit 2 the guard
            // interval; in the flag byte forty megahertz is bit 0 and a short guard
            // interval is bit 1.
            int known = 0x01 | 0x02;
            int flags = width >= 40 ? 0x01 : 0x00;
            if (sgi) {
                known |= 0x04;
                flags |= 0x02;
            }
            h[mcsOff] = (byte) known;
            h[mcsOff + 1] = (byte) flags;
            h[mcsOff + 2] = (byte) mcs;
        }
        if (vhtOff >= 0) {
            // Twelve bytes: known (2), flags (1), bandwidth (1), mcs_nss (4), coding (1),
            // group id (1), partial aid (2). The known word has to admit that the
            // bandwidth and the guard interval are stated, or the bandwidth byte is
            // decoration -- and it is the only place eighty megahertz can be asked for,
            // since the MCS field above can say no more than forty.
            int vhtKnown = 0x0004 | 0x0040;
            h[vhtOff] = (byte) vhtKnown;
            h[vhtOff + 1] = (byte) (vhtKnown >> 8);
            h[vhtOff + 2] = (byte) (sgi ? 0x04 : 0x00);
            h[vhtOff + 3] = (byte) (width >= 80 ? 2 : width >= 40 ? 1 : 0);
            h[vhtOff + 4] = (byte) (0x10 | mcs);
            h[vhtOff + 5] = 0;
            h[vhtOff + 6] = 0;
            h[vhtOff + 7] = 0;
            h[vhtOff + 8] = 0;
        }
        return h;
    }

    /** The rate currently written into the header, in Mb/s, or zero for none. */
    static int txRateMbps() {
        return txRateMbps;
    }

    /** The MCS currently written into the header, or a negative number for none. */
    static int txMcs() {
        return txMcs;
    }

    /** The channel width the header asks for, in MHz. */
    static int txWidthMhz() {
        return txWidthMhz;
    }

    /** Whether the header asks for 802.11ac rather than 802.11n. */
    static boolean txVht() {
        return txVht;
    }

    /**
     * The header every frame built from now on begins with.
     *
     * <p>Package visible so a test can read the bytes back. The two masks in the MCS and
     * VHT fields are the sort of thing that compiles, transmits, and is quietly ignored,
     * and the only way to tell that apart from a chip that refuses the field is to have
     * the exact bytes written down somewhere.
     */
    static byte[] radiotapHeader() {
        return radiotapHeader;
    }

    // TLV types (AWDL-6).
    private static final int TLV_SERVICE_PARAMS = 6;
    private static final int TLV_SYNC_PARAMS = 4;
    private static final int TLV_ELECTION_V1 = 5;
    private static final int TLV_CHANNEL_SEQUENCE = 18;
    private static final int TLV_ELECTION_V2 = 24;
    private static final int TLV_HT_CAPABILITIES = 7;
    private static final int TLV_ARPA = 16;
    private static final int TLV_DATA_PATH_STATE = 12;
    private static final int TLV_VERSION = 21;

    /** The address of the vdev we send on; it is also our AWDL address. */
    private final byte[] addr;
    /** The single DNS label announced in the ARPA TLV. */
    private final String name;
    /** AWDL version and device class, in the Version TLV. */
    private final int version;
    private final int deviceClass;
    /** The channel the radio is on now, and the one we serve in every slot. */
    private final int channel;
    private final int masterChannel;

    // Election state, as Election V1 and V2 advertise it.
    private byte[] master = new byte[6];
    private byte[] sync = new byte[6];
    private int height;
    private long masterMetric;
    private long masterCounter;
    private long selfMetric;
    private long selfCounter;

    /** Which channel each slot of the sequence names; 0 for none. */
    private final int[] slots = new int[SLOTS];

    /**
     * Address 1. Broadcast is what a node's periodic frames carry, but a unicast
     * destination is worth having for one measurement: the firmware reports whether
     * a frame was acked, and only a unicast frame is ever acked. Sending the same
     * frame to a peer's address and to one nobody holds separates "the peer heard
     * us" from "the frame left the antenna".
     */
    private byte[] dst = BROADCAST.clone();

    /**
     * Our slot boundary, in microseconds on {@link System#nanoTime()}'s scale.
     *
     * <p>Not final any more: the whole point of the sync TLV is that a follower does not
     * get to keep its own phase. A node that starts free-running and stays that way
     * announces a schedule nobody else is on, and a peer only listens inside its own
     * availability windows, so every frame lands in a window that is closed. See
     * {@link #anchor}.
     */
    private long baseUs;
    /** The AW index at that boundary. */
    private int baseAw;

    private int sequenceControl;

    /**
     * The 802.11 sequence number of the last frame built, so a caller can recognise that
     * same frame when the radio hands it back.
     *
     * <p>A passthru vif receives what it transmits, which turns our own frames into the
     * only clock available for the interval between "handed to the driver" and "on the
     * air": if that interval is a slot or more, then every guard this file computes is
     * aimed at a departure that the driver re-times, and an entire phase sweep measures
     * nothing. Reading the number here rather than parsing our own bytes back out is the
     * difference between a measurement and a reconstruction.
     */
    int lastSeq;

    /**
     * AWDL's own sequence number, one per data frame.
     *
     * <p>Separate from {@link #sequenceControl}, which numbers 802.11 MPDUs. A peer uses
     * this one to order and de-duplicate MAC service data units after reassembly, so it
     * must advance for every frame actually transmitted and must not repeat.
     */
    private int awdlSeq;

    AwdlFrame(byte[] addr, String name, int version, int deviceClass, int channel,
              long nowUs) {
        this.addr = addr.clone();
        this.name = name;
        this.version = version;
        this.deviceClass = deviceClass;
        this.channel = channel;
        this.masterChannel = channel;
        this.baseUs = nowUs;
        this.baseAw = 0;
        // A node that follows nobody but itself: AWDL-8's metric and counter, height 0.
        this.master = this.addr.clone();
        this.sync = this.addr.clone();
        this.selfMetric = INIT_METRIC;
        this.selfCounter = 0;
        this.masterMetric = this.selfMetric;
        this.masterCounter = this.selfCounter;
        Arrays.fill(slots, channel);
    }

    /**
     * Points the election at a master we follow instead of ourselves.
     *
     * <p>This is the only way a node becomes part of a peer's AWDL network, and it is
     * one-directional in practice. Election ranks by metric first (AWDL-9) and the
     * higher metric wins, so a node that starts at {@link #INIT_METRIC} never unseats an
     * Apple peer sitting at 1280 no matter what it hears: the peer runs the same
     * comparison, decides we rank lower, and keeps ignoring us. Following it is the only
     * move available -- and it is enough, because a follower inherits the master's
     * schedule, and being on the master's schedule is all a peer needs to hear us.
     *
     * <p>{@code hops} is the peer's height plus one; the peer's own master becomes ours.
     */
    void follow(byte[] masterMac, int hops, long masterMetric, long masterCounter) {
        this.master = masterMac.clone();
        this.sync = masterMac.clone();
        this.height = hops;
        this.masterMetric = masterMetric;
        this.masterCounter = masterCounter;
    }

    /**
     * True when the election we advertise names ourselves as master.
     *
     * <p>Not the same question as "am I root": a follower one hop under the real master
     * also reads {@code height > 0}. This is the narrower test used to decide whether a
     * peer's announcement may overwrite our own election state.
     */
    boolean isMaster() {
        return Arrays.equals(this.master, this.addr);
    }

    /**
     * Ranks this node above its peers instead of below all of them.
     *
     * <p>{@link #INIT_METRIC} is AWDL-8's worst rank, and it is deliberate: it makes this
     * node a follower of any Apple peer it meets, which is what the link has always
     * needed. The cost is that the peer keeps its own channel sequence, and that sequence
     * is the transfer rate -- five of sixteen slots on our channel, measured three times
     * at 31.2% of a 272 KB/s window rate.
     *
     * <p>So the option exists to do the opposite, and it is off unless the tuning file
     * asks for it. Everything the election advertises has to move together, because a
     * node that claims a high metric while still naming somebody else as its master is
     * advertising a follower with an impossible rank, and a peer that believes it would
     * be following a node that does not consider itself the root. Master, sync, height,
     * metric and counter are therefore rewritten as one act, in {@code takeMaster}.
     */
    void setSelfMetric(long metric) {
        this.selfMetric = metric;
        if (isMaster()) {
            this.masterMetric = metric;
        }
    }

    /** Claims the election: we are the root, height 0, our own clock and channel sequence. */
    void takeMaster() {
        this.master = this.addr.clone();
        this.sync = this.addr.clone();
        this.height = 0;
        this.masterMetric = this.selfMetric;
        this.masterCounter = this.selfCounter;
        ownSequence();
    }

    /**
     * Announces the channel we actually sit on in all sixteen slots.
     *
     * <p>This is the constructor's own fill and the opposite of copying a peer's map, and
     * which of the two is right is not a matter of taste: a peer sends to us in the slots
     * we announce, so the map is a statement about where we can be reached. The radio here
     * never leaves {@link #channel} -- receive has been measured across every phase of the
     * slot -- so announcing anything less is claiming to be absent from slots we are
     * standing in.
     */
    void ownSequence() {
        Arrays.fill(slots, channel);
    }

    /*
     * The sequence negotiation.
     *
     * What a peer announces is a statement about where the peer is, and copying it told
     * the peer where *we* are -- five slots of sixteen, because that is the iPhone's own
     * map. Two things were wrong with the copy. A follower's map is its own business in
     * AWDL: the binding statement is the clock (see anchor), not the channel list, so
     * inheriting the master's map bought nothing and cost eleven slots. And the copy was
     * unconditional, which meant one frame could undo a map the local node had reason to
     * believe.
     *
     * So the map is negotiated rather than imposed. Three rules, in the order they apply:
     *
     * <ul>
     *   <li><b>Role.</b> A node that names itself master owns the schedule and never
     *       adopts a peer's map. Only a follower is a candidate, and only for the peer it
     *       actually syncs to -- a stranger's map would describe a superframe we are not
     *       on.
     *   <li><b>Keep what is ours.</b> A slot the peer puts on a channel this radio never
     *       visits, or leaves empty, is still a slot we are standing in on our own
     *       channel; it stays. Only the slots the peer names on our channel are adopted,
     *       which on an untouched node is a no-op and on a node whose map was already
     *       overwritten is the repair.
     *   <li><b>Smoothing.</b> An announcement is believed after {@link #SEQ_CONFIRM}
     *       consecutive frames that agree, and no two changes land closer together than
     *       {@link #SEQ_HOLD_US}. The peer's map is a live thing -- one channel-6 slot
     *       moves through the sixteen -- and adopting each reading as it arrives would
     *       rewrite the schedule at frame rate.
     * </ul>
     */

    /** Identical announcements needed before a peer's map is believed. */
    private static final int SEQ_CONFIRM = 3;
    /** Shortest interval between two applied maps; a whole sequence is 1.05 s. */
    private static final long SEQ_HOLD_US = 1_100_000L;

    /** The last map offered, and how many consecutive frames have offered it. */
    private final int[] offered = new int[SLOTS];
    private int offeredStreak;
    private long seqAppliedUs = Long.MIN_VALUE / 4;
    /** Counters for the status line: maps adopted, kept, and refused outright. */
    private int seqAdopted;
    private int seqKept;
    private int seqRefused;
    /** Slots the peer names differently from us on the last offer: the divergence. */
    private int seqDivergent;

    /**
     * Weighs a peer's channel sequence and adopts the part of it that survives the rules
     * above.
     *
     * @param from          the address the announcement came from
     * @param seq           one channel per slot, 0 where the peer claims no channel
     * @param theirHeight   the peer's distance from its master, for the log only
     * @param nowUs         this frame's arrival on {@link System#nanoTime()}'s scale
     * @return a short verdict for the status line
     */
    String offerSequence(byte[] from, int[] seq, int theirHeight, long nowUs) {
        if (seq == null || seq.length != SLOTS) {
            seqRefused++;
            return "short";
        }
        if (isMaster()) {
            // We own the superframe, so no peer's map can describe it and there is nothing
            // to negotiate. This is asked before the sync test on purpose: a master syncs
            // to itself, so every peer frame would fail that test and the verdict would
            // read "not-sync" -- a refusal -- while the status line one field away says
            // elect=self. The two must not disagree about what happened.
            seqKept++;
            return "own";
        }
        if (from == null || !Arrays.equals(from, sync)) {
            // Not the node whose clock we keep, so its map describes a superframe we are
            // not on. Refusing here is the difference between following a node and
            // collecting maps from everyone in range.
            seqRefused++;
            return "not-sync";
        }
        if (!Arrays.equals(seq, offered)) {
            System.arraycopy(seq, 0, offered, 0, SLOTS);
            offeredStreak = 1;
            return "learn 1/" + SEQ_CONFIRM;
        }
        if (offeredStreak < SEQ_CONFIRM) {
            offeredStreak++;
            if (offeredStreak < SEQ_CONFIRM) {
                return "learn " + offeredStreak + "/" + SEQ_CONFIRM;
            }
        }
        if (nowUs - seqAppliedUs < SEQ_HOLD_US) {
            seqKept++;
            return "hold";
        }
        int adopted = 0;
        int divergent = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (seq[i] == channel) {
                if (slots[i] != channel) {
                    slots[i] = channel;
                    adopted++;
                }
            } else {
                // The peer is somewhere else in this slot; we are not, and we can only
                // announce where we are. Keeping the local value is not a disagreement
                // to be settled -- the radio here never leaves its channel, so a zero
                // would be a claim that we are absent from a slot we occupy.
                divergent++;
            }
        }
        seqDivergent = divergent;
        seqAppliedUs = nowUs;
        if (adopted == 0) {
            seqKept++;
            return "keep d=" + divergent;
        }
        seqAdopted++;
        return "adopt +" + adopted + " d=" + divergent;
    }

    /** Maps adopted, kept and refused, and the divergence of the last offer. */
    String sequenceVerdict() {
        return "a" + seqAdopted + "/k" + seqKept + "/r" + seqRefused + " d" + seqDivergent;
    }

    /**
     * AWDL-9's ordering, applied to the rank a peer announced.
     *
     * <p>Metric first and higher wins; a tie falls to the counter, then to the shorter
     * path to the root, then to the higher master address. This is the same comparison
     * {@code AwdlTx}'s election runs over its peer table, narrowed to the one peer whose
     * frames are in hand -- which is all this loop needs, and the only place where not
     * following is a decision rather than an omission.
     *
     * <p>Without it the follow was unconditional, so a node that outranked its peer
     * still advertised the peer's rank as its master's. That is a follower's claim with a
     * winner's metric, and no peer can make sense of it.
     */
    boolean outranks(long theirMetric, byte[] theirMaster, long theirCounter, int theirHeight) {
        long theirM = theirMetric & 0xffffffffL;
        long theirC = theirCounter & 0xffffffffL;
        if (selfMetric != theirM) {
            return selfMetric > theirM;
        }
        if (selfCounter != theirC) {
            return selfCounter > theirC;
        }
        if (height != theirHeight) {
            return height < theirHeight;
        }
        return theirMaster == null || macCompare(addr, theirMaster) > 0;
    }

    /** Unsigned, byte by byte: {@code MacAddr}'s ordering, which is the election's. */
    private static int macCompare(byte[] a, byte[] b) {
        for (int i = 0; i < 6 && i < a.length && i < b.length; i++) {
            int c = Integer.compare(a[i] & 0xff, b[i] & 0xff);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** The metric this node advertises for itself. */
    long selfMetric() {
        return selfMetric;
    }

    /**
     * Adopts a peer's slot phase, which is what makes our frames land in its windows.
     *
     * <p>The peer announces the time left to its next slot boundary in TU ({@code next_tu})
     * and the extended-AW index that boundary starts ({@code aw_counter}). Both fall out
     * into a single subtraction: the boundary is that many TU in the past. Whichever copy
     * of the frame is used, the result is the same, so a stale frame degrades to a small
     * phase error rather than a wrong one.
     *
     * <p>Called only for the peer we follow -- {@code node.rs} does the same with its
     * {@code if ours} guard, and for a good reason: adopting a stranger's phase would put
     * us on a schedule our master is not on.
     */
    void anchor(long nowUs, int nextTu, int awCounter) {
        int next = Math.min(nextTu, SLOT_TU);
        this.baseUs = nowUs - (long) (SLOT_TU - next) * TU_US;
        this.baseAw = awCounter & ~(PRESENCE_MODE - 1);
    }

    /**
     * How far off we were, in TU, before {@link #anchor}: positive means the peer's
     * boundary is later than ours. Only used for the log, and it is the one number that
     * says whether the clock is actually converging.
     */
    int errorTu(long nowUs, int nextTu, int awCounter) {
        final int wrap = 1 << 14; // AW counters are 16 bit and four AWs make a slot
        int theirs = awCounter / PRESENCE_MODE;
        int ours = currentAw(nowUs) / PRESENCE_MODE;
        int slots = Math.floorMod(theirs - ours + wrap / 2, wrap) - wrap / 2;
        return slots * SLOT_TU - (nextTu - nextSlotTu(nowUs));
    }

    /** The master we follow, or ourselves. */
    byte[] master() {
        return master.clone();
    }

    /** The node whose clock we adopt, or ourselves. */
    byte[] sync() {
        return sync.clone();
    }

    int height() {
        return height;
    }

    long masterMetric() {
        return masterMetric;
    }

    /*
     * There is deliberately no per-slot writer any more. The map has exactly two authors
     * left -- ownSequence, which fills it, and offerSequence, which repairs it under the
     * rules above -- and the setter that let a caller write one slot at a time is how a
     * peer's map got in without passing either. Removing it makes the negotiation the
     * only path, which is the property worth having.
     */

    /**
     * How many of the sixteen slots we announce as usable on our own channel, and the map
     * itself in the same comma form the peer's is logged in.
     *
     * <p>Read from the frame rather than from a copy of the tuning that produced it,
     * because the two disagree exactly when it matters: the peer's map overwrites this
     * array fifty times a second, and a status line that printed the intent instead of the
     * state would report sixteen slots while the air carried five.
     */
    String sequenceSummary() {
        int onChannel = 0;
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] == channel) {
                onChannel++;
            }
            map.append(i == 0 ? "" : ",").append(slots[i]);
        }
        return onChannel + "/" + SLOTS + " " + map;
    }

    /**
     * Which of the sixteen slots the clock is inside now.
     *
     * <p>The AW counter is the only thing in the frame that says where in the sequence a
     * node is: one slot is four AWs, and sixteen slots make a sequence. Reading it is
     * the whole point of having adopted the counter at {@link #anchor}, and until now
     * nothing did.
     */
    int currentSlotIndex(long nowUs) {
        return Math.floorMod(currentAw(nowUs) / PRESENCE_MODE, SLOTS);
    }

    /**
     * The channel the current slot sits on, or 0 if the sequence has no slot there.
     *
     * <p>This is the half of the schedule that was announced but never obeyed. The
     * iPhone spends one of its sixteen slots on channel 6 and the rest on 149; a frame
     * transmitted during the channel-6 slot is not lost to interference or to a bad
     * address field, it is lost because nothing is listening on 149 at that moment.
     */
    int currentSlotChannel(long nowUs) {
        return slots[currentSlotIndex(nowUs)];
    }

    /** This node's own channel: the one it is tuned to and announces. */
    int channel() {
        return channel;
    }

    /** Sets address 1: a peer's AWDL address, or null to go back to broadcast. */
    void to(byte[] mac) {
        this.dst = mac == null ? BROADCAST.clone() : mac.clone();
    }

    /** A Master Indication Frame: everything a PSF has, plus our host name. */
    byte[] mif(long nowUs) {
        return build(SUBTYPE_MIF, nowUs);
    }

    /** A Periodic Synchronization Frame. */
    byte[] psf(long nowUs) {
        return build(SUBTYPE_PSF, nowUs);
    }

    /**
     * An AWDL data frame carrying one IPv6 packet (AWDL-13).
     *
     * <p>This is the layer whose absence made every earlier attempt fail in the same
     * silent way. A frame that leaves the radio with an ordinary LLC/SNAP header reaches
     * the peer's antenna, passes its FCS check and is even acked -- but it never reaches
     * the peer's IP stack, because an AWDL data frame is not an 802.11 data frame with a
     * SNAP header. Between the SNAP header and the packet sits a sixteen-byte AWDL data
     * header:
     *
     * <pre>
     *   LLC/SNAP   aa aa 03 00 17 f2 08 00
     *   data head  03 04            little endian 0x0403, AWDL-13's marker
     *   awdl seq   nn nn            little endian, one per MSDU
     *   reserved   00 00
     *   ethertype  86 dd            big endian, the one field in network order
     * </pre>
     *
     * <p>That layout was read off the field order a live iPhone's MSDUs show on the air,
     * which pins the same sequence, and it is what separates "the iPhone heard us" from
     * "the iPhone's AirDrop saw us".
     *
     * <p>{@code packet} is the bare IPv6 packet: no Ethernet header, since the LLC/SNAP
     * pair stands in for it. Address 1 is the peer's AWDL address, or broadcast -- AWDL
     * interfaces are not in a distribution system, so a broadcast MSDU is delivered to
     * every peer on the air and needs no ACK.
     */
    byte[] dataFrame(byte[] src, byte[] dst, byte[] packet) {
        Buf out = new Buf(64 + packet.length);
        out.bytes(radiotapHeader);

        out.le16(FC_QOS_DATA);
        out.le16(0);
        out.bytes(dst);
        out.mac(src);
        out.bytes(BSSID);
        lastSeq = sequenceControl & 0x0fff;
        out.le16(lastSeq << 4);
        sequenceControl++;
        // QoS control: part of the MAC header, so the SNAP below lands at offset 26,
        // which is where the driver looks for it.
        out.le16(QOS_CONTROL);

        out.bytes(LLC_SNAP);
        out.le16(DATA_HEAD);
        out.le16(awdlSeq++ & 0xffff);
        out.le16(0);
        out.be16(ETHERTYPE_IPV6);
        out.bytes(packet);
        return out.toArray();
    }

    private byte[] build(int subtype, long nowUs) {
        Buf out = new Buf(384);
        out.bytes(radiotapHeader);

        // 802.11 header: action frame, broadcast to the AWDL "network" that the BSSID names.
        out.le16(FC_ACTION);
        out.le16(0);
        out.bytes(dst);
        out.mac(addr);
        out.bytes(BSSID);
        out.le16((sequenceControl++ & 0x0fff) << 4);

        // Action header (AWDL-3).
        out.u8(CATEGORY_VENDOR);
        out.bytes(OUI);
        out.u8(AWDL_TYPE);
        out.u8(ACTION_VERSION);
        out.u8(subtype);
        out.u8(0);
        out.le32(nowUs & 0xffffffffL);
        out.le32(nowUs & 0xffffffffL);

        int nextTu = nextSlotTu(nowUs);
        int awCounter = currentAw(nowUs);

        putSyncParams(out, nowUs, nextTu, awCounter);
        putElectionV1(out);
        putChannelSequence(out);
        putElectionV2(out);
        putServiceParams(out);
        putHtCapabilities(out);
        if (subtype == SUBTYPE_MIF) {
            putArpa(out);
        }
        putDataPathState(out);
        putVersion(out);
        return out.toArray();
    }

    /**
     * Time since our slot boundary. Clamped at zero: a frame that arrives a hair before
     * the boundary it announces (rounding in the peer's own clock) would otherwise make
     * the modulo negative and push {@code next_tu} past a whole slot.
     */
    private long sinceUs(long nowUs) {
        long delta = nowUs - baseUs;
        return delta > 0 ? delta : 0;
    }

    /** Microseconds to the next slot boundary, in {@code 1..=64}. */
    int nextSlotTu(long nowUs) {
        long tu = sinceUs(nowUs) / TU_US;
        return (int) (SLOT_TU - tu % SLOT_TU);
    }

    /** Microseconds to the next slot boundary; the MIF is sent half a slot past it. */
    long nextSlotUs(long nowUs) {
        return SLOT_US - sinceUs(nowUs) % SLOT_US;
    }

    /** The AW count now: one per availability window. */
    int currentAw(long nowUs) {
        long tu = sinceUs(nowUs) / TU_US;
        int aw = baseAw + (int) ((tu % SLOT_TU) / AW_PERIOD_TU + PRESENCE_MODE * (tu / SLOT_TU));
        return aw & 0xffff;
    }

    /**
     * The Synchronization Parameters TLV. {@code remaining_aw} is what is left of the
     * AW that {@code next_tu} lands in, which is why it is usually 0: an iPhone that
     * announces {@code next_tu=43} has three AWs of the slot behind it and reports 0.
     */
    private void putSyncParams(Buf out, long nowUs, int nextTu, int awCounter) {
        tlv(out, TLV_SYNC_PARAMS, v -> {
            v.u8(channel);
            v.le16(nextTu);
            v.u8(masterChannel);
            v.u8(0); // guard time
            v.le16(AW_PERIOD_TU);
            v.le16(PSF_INTERVAL_TU);
            v.le16(0x1800); // flags: reserved
            v.le16(AW_PERIOD_TU); // extended AW length
            v.le16(AW_PERIOD_TU); // common AW length
            int elapsed = AW_PERIOD_TU * PRESENCE_MODE - Math.min(nextTu, SLOT_TU);
            v.le16(Math.max(0, AW_PERIOD_TU - elapsed)); // remaining AW length
            v.bytes(new byte[]{PRESENCE_MODE - 1, PRESENCE_MODE - 1, PRESENCE_MODE - 1,
                    PRESENCE_MODE - 1}); // min ext, max ext, multicast, AF
            v.mac(master);
            v.u8(PRESENCE_MODE);
            v.u8(0);
            v.le16(awCounter); // next AW sequence number
            v.le16(awCounter); // AP alignment
            putSequence(v);
            v.bytes(new byte[]{0, 0});
        });
    }

    /**
     * Election Parameters, version 1. Its layout puts the height at offset 3 and the
     * master's metric at 11, which is why {@code AwdlProbe} reads them from there.
     */
    private void putElectionV1(Buf out) {
        tlv(out, TLV_ELECTION_V1, v -> {
            v.u8(0); // flags
            v.le16(0); // ID
            v.u8(Math.min(height, 255));
            v.u8(0);
            v.mac(master);
            v.le32(masterMetric);
            v.le32(selfMetric);
            v.bytes(new byte[]{0, 0});
        });
    }

    /** The sequence structure on its own, as TLVs 4 and 18 both carry it. */
    private void putSequence(Buf out) {
        out.u8(SLOTS - 1);
        out.u8(3); // encoding: opclass, so each slot is [channel, operating class]
        out.u8(0); // duplicate count
        out.u8(PRESENCE_MODE - 1); // AWs per slot, less one
        out.le16(0xffff); // fill channel
        for (int slot : slots) {
            out.u8(slot);
            out.u8(slot == 0 ? 0 : slot <= 14 ? 0x51 : 0x80);
        }
    }

    private void putChannelSequence(Buf out) {
        tlv(out, TLV_CHANNEL_SEQUENCE, v -> {
            putSequence(v);
            v.bytes(new byte[]{0, 0, 0});
        });
    }

    /** Election Parameters, version 2, which supersedes V1 when both are present. */
    private void putElectionV2(Buf out) {
        tlv(out, TLV_ELECTION_V2, v -> {
            v.mac(master);
            v.mac(sync);
            v.le32(masterCounter);
            v.le32(height);
            v.le32(masterMetric);
            v.le32(selfMetric);
            v.le32(0);
            v.le32(0);
            v.le32(selfCounter);
        });
    }

    /**
     * The AWDL services this node runs, transcribed from a live iPhone's own frame.
     *
     * <p>Nine bytes, and the only non-zero one is {@code 0x08} in the middle word. This
     * side used to send nine zeros on the theory that the field was a service user
     * identifier and that leaving it alone avoided making peers flush their mDNS caches.
     * The theory cost the whole feature: an iPhone in the share sheet answered our echo
     * requests for three minutes -- ninety-eight frames, every one of them a reply to a
     * ping we had sent -- and put not a single mDNS packet on the air, inbound or out.
     * It was reachable and it was not talking, which is what a peer that has decided
     * this node runs no service worth resolving looks like.
     *
     * <p>The bytes here are the iPhone's, verbatim, because the middle word is a service
     * bitmask rather than an identifier and there is no way to derive which bit is
     * AirDrop from this side of the link. Advertising a service we do not implement is a
     * smaller lie than advertising none while asking the peer to resolve us; if the bit
     * turns out to be something else, the run that follows will say so by staying just
     * as silent.
     */
    private void putServiceParams(Buf out) {
        tlv(out, TLV_SERVICE_PARAMS, v -> {
            v.bytes(new byte[]{0, 0, 0});
            v.le16(0x0008);
            v.le32(0);
        });
    }

    /** One spatial stream, every MCS of it. */
    private void putHtCapabilities(Buf out) {
        tlv(out, TLV_HT_CAPABILITIES, v -> {
            v.le16(0);
            v.le16(0x11ce);
            v.u8(0x1b);
            v.u8(0xff);
            v.le16(0);
        });
    }

    /**
     * The host name, then {@code .local} as a DNS compression pointer. AWDL-17 requires a
     * MIF to carry this and the Version TLV before a peer will treat us as a node.
     */
    private void putArpa(Buf out) {
        byte[] label = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        tlv(out, TLV_ARPA, v -> {
            v.u8(3); // the one flag value IO80211Family accepts
            v.u8(Math.min(label.length, 64));
            v.bytes(Arrays.copyOf(label, Math.min(label.length, 64)));
            v.u8(0xc0);
            v.u8(0x0c);
        });
    }

    /**
     * Data Path State: legacy's flags, country {@code X0}, the social channel we serve,
     * and our address. A live iPhone on iOS 27.2 sends {@code 0x9500} for the social map
     * where this sends 1/2/4; the field is not one a peer acts on.
     */
    private void putDataPathState(Buf out) {
        tlv(out, TLV_DATA_PATH_STATE, v -> {
            v.le16(0x8f24);
            v.bytes(new byte[]{'X', '0', 0});
            v.le16(masterChannel == 6 ? 0x0001 : masterChannel == 44 ? 0x0002 : 0x0004);
            v.mac(addr);
            v.le16(0);
        });
    }

    private void putVersion(Buf out) {
        tlv(out, TLV_VERSION, v -> {
            v.u8(version);
            v.u8(deviceClass);
        });
    }

    private interface TlvBody {
        void write(Buf out);
    }

    /** A TLV with its length filled in afterwards, as the layout requires. */
    private static void tlv(Buf out, int kind, TlvBody body) {
        int at = out.length();
        out.u8(kind);
        out.le16(0);
        body.write(out);
        int len = out.length() - at - 3;
        out.set(at + 1, len & 0xff);
        out.set(at + 2, (len >> 8) & 0xff);
    }

    /** A growable little-endian byte buffer; the whole frame is built through it. */
    static final class Buf {
        private byte[] bytes;
        private int count;

        Buf(int capacity) {
            bytes = new byte[capacity];
        }

        int length() {
            return count;
        }

        void set(int index, int value) {
            bytes[index] = (byte) value;
        }

        void u8(int value) {
            reserve(1);
            bytes[count++] = (byte) value;
        }

        void le16(int value) {
            u8(value & 0xff);
            u8((value >> 8) & 0xff);
        }

        /** Big endian, for the one field AWDL keeps in network order: the ethertype. */
        void be16(int value) {
            u8((value >> 8) & 0xff);
            u8(value & 0xff);
        }

        void le32(long value) {
            u8((int) (value & 0xff));
            u8((int) ((value >> 8) & 0xff));
            u8((int) ((value >> 16) & 0xff));
            u8((int) ((value >> 24) & 0xff));
        }

        void mac(byte[] address) {
            reserve(6);
            System.arraycopy(address, 0, bytes, count, 6);
            count += 6;
        }

        void bytes(byte[] data) {
            reserve(data.length);
            System.arraycopy(data, 0, bytes, count, data.length);
            count += data.length;
        }

        byte[] toArray() {
            return Arrays.copyOf(bytes, count);
        }

        private void reserve(int extra) {
            if (count + extra <= bytes.length) {
                return;
            }
            int size = bytes.length;
            while (size < count + extra) {
                size *= 2;
            }
            bytes = Arrays.copyOf(bytes, size);
        }
    }
}
