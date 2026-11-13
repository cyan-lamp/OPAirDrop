package com.oshare.airdrop.core;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructTimeval;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The data half of AWDL: puts a real IPv6 packet inside an AWDL data frame and sends it,
 * then reports whatever comes back.
 *
 * <p>Everything before this file proved the radio could carry our bytes and still left
 * the phone silent. The reason was one missing header: an AWDL data frame is not an
 * 802.11 data frame with a SNAP header, it is a SNAP header followed by sixteen bytes of
 * AWDL's own ({@link AwdlFrame#dataFrame}). Frames without them pass the phone's FCS
 * check and are even acked, and then are dropped one layer above, which looks exactly
 * like a phone that cannot hear us.
 *
 * <p>Three details of when to send matter as much as the bytes, and they come from the
 * node and sync behaviour a live peer shows on the air:
 *
 * <ul>
 *   <li>Multicast goes out clear of a slot boundary by {@code 16 TU} (AWDL-16), and on a
 *       radio pinned to one channel that is the only timing constraint -- the
 *       slots-0-and-10 rule exists for radios that hop.
 *   <li>A frame sent while the receiver is not in an availability window is lost with no
 *       trace, so sending is gated on the schedule and repeated rather than fired once.
 *   <li>mDNS is IPv6 multicast, so address 1 is the multicast MAC {@code 33:33:00:00:00:fb},
 *       not broadcast. {@code node.rs} passes the packet's own Ethernet destination
 *       straight through as the frame's receiver.
 * </ul>
 *
 * <p>Run against {@code wondertap0} -- the passthru vdev. The driver picks the vdev from
 * the netdev an skb arrives on, so sending through {@code wonder0} loses the frame.
 *
 * <pre>
 *   tools/awdl-data.sh [iface] [seconds] [dst-mac] [follow-mac|auto]
 * </pre>
 */
public final class AwdlData {

    private static final int AF_PACKET = 17;
    private static final int ETH_P_ALL = 0x0003;

    private static final int FC_TYPE_MGMT = 0;
    private static final int FC_TYPE_DATA = 2;
    private static final int FC_SUBTYPE_ACTION = 13;

    /** AMSDU present in a QoS control word; we never set it, but a peer may. */
    private static final int QOS_AMSDU = 0x0080;

    private static final String AWDL_BSSID = "00:25:00:ff:94:73";
    /** IPv6 multicast MAC for ff02::/16: what mDNS is addressed to at L2. */
    private static final String MDNS_MAC = "33:33:00:00:00:fb";
    /**
     * The same address as bytes, for the probe that has to address a frame to it.
     *
     * <p>Held separately from the string because a probe builds its own destination
     * rather than inheriting one, and parsing the string at the point of use would put
     * the one address this diagnosis depends on behind a text parse.
     */
    private static final byte[] MDNS_GROUP_MAC = {0x33, 0x33, 0x00, 0x00, 0x00, (byte) 0xfb};

    private static final int TU_US = AwdlFrame.TU_US;
    /** AWDL-16: unicast keeps 3 TU clear of a slot edge, multicast 16 TU. */
    private static final long UNICAST_GUARD_US = 3L * TU_US;
    /** AWDL-16: multicast's edge guard. */
    private static final long MULTICAST_GUARD_US = 16L * TU_US;

    /**
     * Extra slot phase, in TU, added to every guard before a data frame goes out.
     *
     * <p>The guard is not the phase, and this run made the difference measurable. Every
     * frame left at exactly three TU past our slot boundary -- {@code ph=3-3/3TU}, ten
     * frames, one bucket -- so the aim works and the clock is steady. Every frame the
     * peer sent arrived between ten and forty-seven TU past that same boundary, peaking
     * around thirty-six. Both readings are of the same slot and they disagree by half of
     * it, which is the whole loss: a receiver that is awake from thirty-two to
     * forty-eight is not listening at three, and a frame sent at three TU is heard only
     * when the window happens to be elsewhere in the sequence -- one frame in four, which
     * is exactly the rate measured over twenty attempts.
     *
     * <p>A constant rather than a computation because the two clocks are being related
     * empirically here, not derived: the anchor comes from a frame that arrived over the
     * air, and its alignment to the peer's own slot boundary is an offset this process
     * cannot see from inside. {@code AWDL_PHASE_TU} makes it a parameter so a sweep can
     * find it rather than an argument having to.
     *
     * <p>And because the process is what moves it, it is read back from {@link #PHASE_FILE}
     * rather than fixed at startup: see {@link #phaseOffsetTu}.
     */
    private static final String PHASE_FILE = "/data/local/tmp/awdl-phase";

    private static int phaseOffsetTu = envInt("AWDL_PHASE_TU", 0);
    private static long phaseReadAt;

    /**
     * How many availability windows of one slot a handshake frame is repeated into.
     *
     * <p>A slot is four availability windows and the guard puts every frame in the first
     * one. Everything measured so far is consistent with that being legal and useless:
     * the aim is exact to a single TU, the sweep across seven offsets found no peak at
     * all, and the delivery rate stayed where it started. The reading that fits is that
     * the peer is not listening in the window we always use -- and a constant offset
     * cannot repair that if the window it listens in is not constant.
     *
     * <p>So instead of guessing which of the four it is, this sends the same packet in
     * all four, sixteen TU apart, and stays inside the one slot. Four chances per slot
     * against one is the difference between a rate that cannot carry a handshake and one
     * that can, and it costs three extra frames on a link that is already sending six.
     *
     * <p>Four by default, because the log settled the question the sweep could not: a
     * relayed SYN left this process at 38.112 and the peer's SYN-ACK for it came back at
     * 63.794 -- twenty-five seconds and eight kernel retransmissions later -- while a bare
     * UDP datagram sent a second earlier came back as an ICMP port-unreachable in under a
     * second. Same builder, same addresses, same size. The frame was not lost on the way
     * out and the peer was not asleep: each frame was heard only if it happened to fall in
     * one of the four windows of the slot, and one frame per event meant three of the four
     * were never tried. A kernel that answers within a second when asked four times and
     * not within twenty-five when asked once is a kernel being asked too rarely.
     *
     * <p>So the burst is now the default for every packet the kernel cannot retransmit
     * cheaply -- the handshake and the payload-free control segments -- and one for
     * everything else, because a repeated data segment is air spent twice on bytes the
     * receiver already has. {@code aw_burst} in the tuning file turns it down without a
     * rebuild, which is what keeps the earlier one-copy runs reproducible.
     */
    private static volatile int awBurst = Math.max(1, Math.min(
            AwdlFrame.PRESENCE_MODE, envInt("AWDL_AW_BURST", AwdlFrame.PRESENCE_MODE)));

    /**
     * The size below which a relayed segment is repeated across the slot.
     *
     * <p>First set to one hundred, which admitted header-only segments and nothing else,
     * on the argument that a repeated data segment is air spent on bytes the receiver
     * already has. The next run showed the argument was wrong about which segments are
     * cheap to lose: the 281-byte segment carrying {@code POST /Discover} was handed over
     * at 13.603, again at 14.410, again at 15.977, and the peer's acknowledgement stayed
     * at the byte before it through all three. Four hundred milliseconds of link time per
     * attempt, three attempts, and the request that decides whether a name is offered at
     * all never arrived. A handshake is not only its SYN: it is a thousand bytes of
     * records in each direction, every one of them as unrepeated as the SYN was, and the
     * kernel's own retransmission timer is far slower than the four windows this loop can
     * reach in one slot.
     *
     * <p>So the line is drawn at four hundred and ninety-six rather than at the end of the
     * header. That covers the TLS records and the small POSTs of the discovery exchange
     * and stops well short of the 1.4 KB segments a file transfer uses, which are the one
     * case where a duplicate really is waste. {@code burst_max} moves the line without a
     * rebuild for whoever wants to spend the air differently.
     */
    private static volatile int burstMaxBytes = Math.max(0, envInt("AWDL_BURST_MAX", 496));

    /**
     * Copies for a payload-free segment, which is an acknowledgement and nothing else.
     *
     * <p>Zero follows {@link #awBurst}, so a run that does not name this key behaves
     * exactly as every run before it did. The key exists because the measurement that
     * set the four copy default was taken over the discovery exchange, where a lost
     * segment is a request that never arrives -- and that reasoning was then inherited
     * by the acknowledgement stream of a file transfer, where it does not hold.
     *
     * <p>Measured on 2026-11-12, a 24 MB upload at mss=1398: 178 KB crossed the air in
     * 1629 frames, which is 109 bytes a frame against a 1418 byte segment. The peer had
     * sent 38 KB, which needs about 296 acknowledgements to answer; 296 x awBurst(4) is
     * 1184, so 73% of everything this tool transmitted was a repeated acknowledgement.
     * The window never held more than two frames (wmax=2, skip 13 a second against 15.3
     * slots a second) and the transfer ran at 147 B/s.
     *
     * <p>Unlike a data segment, an acknowledgement is cumulative: the next arrival
     * supersedes it, and only the last one before the sender runs out of window has to
     * arrive at all. Four copies buy protection for the one case in the stream that is
     * already the cheapest to lose. {@code ack_burst} in the tuning file moves it down
     * without a rebuild, which is what makes the trade measurable against a run.
     */
    private static volatile int ackBurst = Math.max(0, Math.min(
            AwdlFrame.PRESENCE_MODE, envInt("AWDL_ACK_BURST", 0)));

    /**
     * How many bytes at the head of a fresh connection are exempt from
     * {@link #burstMaxBytes}.
     *
     * <p>Measured on 2026-11-12, opening the discovery connection: six attempts
     * produced five failures -- 4609, 4668, 4718 and 4731 ms of "Connection reset"
     * plus one 12010 ms read timeout -- before one succeeded in 479 ms. Four and a
     * half seconds is three of the peer's own retransmission timers (1.52 s, read
     * off three ClientHellos that carried one sequence number 1.522 s and 1.534 s
     * apart), which is what a peer does when the answer never arrives. The answer
     * is our TLS flight: four or five segments of about 894 bytes, every one of
     * them above burstMaxBytes and therefore sent exactly once. A five-segment
     * flight cannot produce the three duplicate acknowledgements a fast retransmit
     * needs, so one lost segment costs a whole timer and the attempt dies.
     *
     * <p>Raising burstMaxBytes to cover them was tried twice and is wrong: it lifts
     * the line for the file transfer as well, where a repeated 894-byte segment
     * spends the air the previous segment's acknowledgement needed -- 1104 ms and
     * then 3807 ms "Connection reset", and a name that never appeared. Splitting by
     * position instead of by size keeps the two apart: the flight is about 7 KB,
     * a transfer is megabytes. Zero turns it off without a rebuild.
     */
    private static volatile int youngBytes = Math.max(0, envInt("AWDL_YOUNG_BYTES", 0));

    /** Exempt bytes left in the connection that is being opened; see youngBytes. */
    private static int youngLeft;

    /** Segments actually repeated because of youngLeft, so the state line can prove it. */
    private static long youngTx;

    /**
     * How many copies a segment that TCP has already given up on is worth.
     *
     * <p>This is the one place where repeating a large segment is affordable, and the
     * reason is arithmetic about the moment rather than about the bytes. A retransmission
     * only exists after a full retransmission timer has expired, and a timer expires
     * because that segment was the only thing in flight: the congestion window had
     * already been cut to one segment, so the air was empty for the whole timeout and
     * this segment is the only thing that wants it. Repeating it costs nothing that
     * anything else was using.
     *
     * <p>The alternative is what the link has been doing. Frame delivery measures 59%
     * (see {@link #RELAY_COPIES}), so a single copy of the segment that ends an RTO
     * succeeds 59% of the time; the other 41% spends another whole timer, and each of
     * those timers doubles. A 24 MB upload advanced by 13396 bytes in 26.7 s -- sixteen
     * segments, one every 1.6 s, which is one expired timer per segment and nothing else.
     * Four copies turn 59% into 97.7% of one shot, and the timer that follows is the
     * last one rather than the next one in a series.
     *
     * <p>Different from raising {@link #burstMaxBytes}, which was tried twice and failed
     * (1104 ms then 3807 ms of "Connection reset", and a name that never appeared):
     * that line repeats segments that cost the peer's acknowledgement its air. This one
     * never fires on a segment the kernel still believes in. Zero follows
     * {@code aw_burst}.
     */
    private static volatile int retxBurst = Math.max(0, envInt("AWDL_RETX_BURST", 0));

    /** Segments repeated because TCP had already retransmitted them, for the state line. */
    private static long retxTx;

    /**
     * Lines the control connection is allowed to print, each direction.
     *
     * <p>Separate from the {@code relay #} budget on purpose. That one exists so a twenty
     * megabyte transfer does not fill the log with its own segments, and it is spent
     * within seconds of a transfer starting -- which is exactly what happened here: the
     * {@code relay #} counter had reached 5393 while its print gate was still set at 400,
     * and the {@code relay in} counter 943 against a gate of 200. Every line describing
     * how the kernel answered the phone's handshake had therefore been dropped for hours,
     * and "the log does not show a SYN-ACK" was being read as "no SYN-ACK was sent".
     *
     * <p>A handshake is tens of packets and a stalled control connection is a few per
     * second, so a budget of a few thousand covers the window this needs to be read from
     * without ever competing with the bulk stream for space. The counters are not
     * budgeted at all -- once the lines stop, the status line still reports how many
     * SYN-ACKs and RSTs crossed, which is the question that has to survive the budget.
     */
    private static volatile int ctlLogMax = Math.max(0, envInt("AWDL_CTL_LOG", 2000));

    /**
     * Whether an inbound packet with a bad transport checksum is repaired before it is
     * injected, or injected as it arrived.
     *
     * <p>Off by default, and it has to stay off for the run that measures the verdict:
     * repairing and reporting at once would make a repaired packet and a packet that was
     * never wrong read identically in the log, and the whole point of the counter is to
     * tell those two apart. The send path has always repaired -- see
     * {@link #checksumVerdict} -- so if the phone's chip offloads the same way, this
     * direction has been the one asymmetry left in the pipe.
     */
    private static volatile boolean fixInboundCksum = envInt("AWDL_FIX_INBOUND_CKSUM", 0) != 0;

    /**
     * How long to stop transmitting after a handshake record arrives from the peer.
     *
     * <p>Zero by default, because this is a measurement before it is a policy: the run
     * that decides it has to be able to turn it on and off through the tuning file
     * without rebuilding, and the run before it has to be the same binary with the
     * number at zero.
     *
     * <p>The reason it exists: this radio is half duplex, so every frame it sends is a
     * frame it cannot hear. A peer's TLS flight is five frames sent back to back, and
     * a burst answering it -- an ACK, three relay copies, the PSF every 110 TU, the MIF
     * every slot -- sits precisely on top of it. One connection lost the middle 3745
     * bytes of a 4177-byte flight that way while the head and tail arrived, which is a
     * hole no amount of sending-side redundancy can fill: the missing frames are the
     * peer's, and only the peer can send them again.
     */
    private static volatile long listenNs = Math.max(0, envInt("AWDL_LISTEN_MS", 0)) * 1_000_000L;

    /** Wall clock until which this process keeps its transmitter quiet; see {@link #listenNs}. */
    private static volatile long quietUntil;

    /** How many times the quiet window was armed, for the status line. */
    private static long quietHits;

    /** Names for {@link #ctlKind()}'s six answers, in its own order. */
    private static final String[] CTL_KINDS = {
            "SYN", "SYN-ACK", "RST", "FIN", "data", "ack-only",
    };

    /** One-letter forms of {@link #CTL_KINDS} for the status line, which has no room. */
    private static final String[] CTL_SHORT = {"syn", "sa", "rst", "fin", "d", "a"};

    /**
     * How many recently relayed segments are remembered, to tell a retransmission from a
     * first attempt.
     *
     * <p>An open-addressed table of 1024 slots, addressed by a hash of everything that
     * identifies a data segment: both ports, the sequence number, and the payload length.
     * Only ever touched from the transmit loop -- the same thread that owns
     * {@link #youngLeft} -- so it needs no locking. A collision evicts, which at worst
     * forgets a segment that was already old and turns one repeated segment back into a
     * single one; it can never invent a retransmission that did not happen, because the
     * stored key is the full mix rather than a bucket index.
     */
    private static final long[] segmentKeys = new long[1024];

    /** Where the next segment key goes when its slot is taken; see {@link #segmentKeys}. */
    private static int segmentNext;

    /**
     * Whether this datagram is TCP retransmitting a segment it already sent.
     *
     * <p>Remembers the segment on the way through, so the answer is "seen before" rather
     * than a second table. Pure acknowledgements and empty segments are skipped: they
     * carry no sequence space the peer could have dropped, and they are the bulk of what
     * crosses the link in the other direction.
     */
    private static boolean isRetransmission(byte[] p, int len) {
        int l4 = tcpAt(p, len);
        if (l4 < 0) {
            return false;
        }
        int hlen = (p[l4 + 12] & 0xf0) >> 2;
        if (hlen < 20 || len - l4 < hlen) {
            return false;
        }
        int dataLen = len - l4 - hlen;
        if (dataLen <= 0) {
            return false;
        }
        long seq = ((long) (p[l4 + 4] & 0xff) << 24) | ((long) (p[l4 + 5] & 0xff) << 16)
                | ((long) (p[l4 + 6] & 0xff) << 8) | (long) (p[l4 + 7] & 0xff);
        long ports = ((long) (p[l4] & 0xff) << 24) | ((long) (p[l4 + 1] & 0xff) << 16)
                | ((long) (p[l4 + 2] & 0xff) << 8) | (long) (p[l4 + 3] & 0xff);
        long key = (seq * 0x9E3779B97F4A7C15L) ^ (ports * 0xC2B2AE3D27D4EB4FL)
                ^ (dataLen * 0x165667B19E3779F9L);
        if (key == 0) {
            // The empty marker. One sequence number in four billion mixes to zero, and a
            // segment that did would be remembered as absent for as long as it repeated.
            key = 1;
        }
        int mask = segmentKeys.length - 1;
        int slot = (int) (key ^ (key >>> 32)) & mask;
        for (int i = 0; i < 8; i++) {
            long held = segmentKeys[slot];
            if (held == key) {
                return true;
            }
            if (held == 0) {
                break;
            }
            slot = (slot + 1) & mask;
        }
        // Not found within the probe, so this is a first attempt: record it. The probe
        // limit is deliberate -- a table this size is emptied by the eviction below long
        // before it is full, and an unbounded probe would turn a full table into a scan.
        int place = (int) (key ^ (key >>> 32)) & mask;
        for (int i = 0; i < 8; i++) {
            if (segmentKeys[place] == key) {
                return true;
            }
            if (segmentKeys[place] == 0) {
                segmentKeys[place] = key;
                return false;
            }
            place = (place + 1) & mask;
        }
        segmentKeys[segmentNext] = key;
        segmentNext = (segmentNext + 1) & mask;
        return false;
    }

    /**
     * Whether a relayed packet is given the two header fields the aim path always sets.
     *
     * <p>Both payloads leave through the same builder, from the same source address, to
     * the same receiver, and only one of them is ever answered. The two differ in four
     * things, and all four are values the kernel chose: a hop limit of 64 instead of 255,
     * a flow label instead of zero, forty bytes of header length instead of twenty
     * because the kernel fills in its TCP options, and a window scale of nine.
     *
     * <p>Two of those four can be changed without rebuilding the packet, and they are
     * also the only two that no transport would ever notice: the hop limit and the flow
     * label sit outside every checksum, so rewriting them leaves a packet that is still
     * valid and still says what the kernel said. An interface that quietly discards
     * anything arriving with a hop limit below 255 -- which is a thing IPv6 requires of
     * neighbour discovery and that some stacks apply to the whole link-local scope --
     * would look from here exactly like a phone that has stopped answering, and one run
     * is the cheapest way to find out which of the two it is.
     *
     * <p>Both default to the aim's values, so a run that says nothing is the experiment.
     * Set {@code AWDL_RELAY_HL=0} or {@code AWDL_RELAY_FL=0} to send the kernel's own
     * header through untouched and get the old behaviour back.
     */
    private static final int RELAY_HOP_LIMIT = envInt("AWDL_RELAY_HL", 255);
    private static final boolean RELAY_FLOW_ZERO = envInt("AWDL_RELAY_FL", 1) != 0;

    /**
     * How often to repeat the query when there is nothing else to carry.
     *
     * <p>mDNS itself would not ask more than once a second, but a frame only lands if it
     * arrives inside the peer's availability window, and four tries a second across a
     * sixteen-slot sequence is enough to hit every slot.
     *
     * <p>This interval used to bound two different things, and separating them is the
     * change: it paced the probe, and it also paced how often the loop looked at the
     * kernel. Four times a second is right for the first and hopeless for the second --
     * see {@link #idleGapNs}.
     */
    private static final long PROBE_INTERVAL_NS = 250_000_000L;

    /**
     * How long to wait before asking the kernel again when it had nothing to give.
     *
     * <p>The kernel has nothing queued for exactly one reason during a transfer: the
     * peer's receive window is full. Its ACK arrives a few milliseconds later, the window
     * opens, and the kernel produces the next segments immediately. The loop, though, had
     * already committed to a next send a quarter of a second away -- and nothing wakes it
     * early, because the poll set holds the radio and not the tun. So the sequence was:
     * send a window of data, find nothing queued, emit a probe, and set the next send to
     * four tries a second. Every window was therefore followed by two hundred and fifty
     * milliseconds of silence that no buffer, no segment size and no radio setting
     * explains. Measured: four segments of five hundred and twelve bytes per two hundred
     * and fifty milliseconds is eight kilobytes a second, and the three runs that stalled
     * a twenty-four-megabyte video measured ten.
     *
     * <p>A tick with nothing to carry puts no frame on the air, so a short interval here
     * costs wake-ups and no airtime. The loop is already a spin between sends; this makes
     * it look at the kernel four hundred times a second instead of four, which is what
     * turns the peer's window into a rate rather than a pause. The probe keeps its own
     * interval -- {@link #PROBE_INTERVAL_NS} -- so a quiet link is still quiet.
     *
     * <p>Nanoseconds, because everything the scheduler compares is on
     * {@link System#nanoTime()}'s scale -- mixing the two units here cost a run that
     * sent 7956 frames in thirty seconds and learned nothing from any of them.
     */
    private static volatile long idleGapNs = Math.max(1, envInt("AWDL_IDLE_MS", 3)) * 1_000_000L;

    /**
     * How many copies of each kernel packet go on the air.
     *
     * <p>Three, because the alternative is waiting for TCP to do it. A frame the driver
     * does not ack is lost with no trace at all -- the peer was on another channel for
     * that slot, and nothing about the frame will ever be heard again. Measured over a
     * run, 59% of frames are acked, so one copy delivers 59%, two 83%, three 93%, and
     * the cost is two extra ticks at {@link #idleGapNs}: under ten milliseconds, against
     * the kernel's own first retransmission at one second and its second at three.
     *
     * <p>Duplicates are harmless where it counts. TCP drops a repeated segment by
     * sequence number, and an echoed ICMPv6 request is answered twice, which is legal and
     * is also the cheapest possible delivery confirmation.
     */
    /**
     * One, not three.
     *
     * <p>The copies were a bet on a two-second availability cycle: three frames eight
     * hundred milliseconds apart were three chances to catch the peer awake. The cost is
     * measurable on the wire: relay=3084/3981 over 44 s is 1.29 frames per packet, and
     * those extra 25 frames a second were 22% of everything this end transmitted. During
     * the transfer outbound retransmissions stayed at two, so the redundancy was covering
     * a loss rate that was not there.
     *
     * <p>Whether the copies helped the rate is a separate question, and the answer is no.
     * The same 5.1 MB photo, three runs: three copies 113.9 KB/s, one copy 118.0 KB/s,
     * one copy with a 1 MB receive buffer 119.9 KB/s. That is a spread of five per cent
     * across a change that removed a fifth of the egress, which means the rate was never
     * set by anything this end transmits. Kept at one rather than restored to three
     * because the copies demonstrably buy nothing, not because they bought speed.
     *
     * <p>Recorded because it was read wrong once: {@code win4} is the turn histogram,
     * binned four turns wide, and {@code rt} is the number of read turns, not a round
     * trip. An earlier note in this file took win4=13 for a thirteen-segment TCP window
     * and rt=59235 for a 59 ms round trip, multiplied the two into a rate that happened
     * to match the measurement, and concluded the window was the gate. The window was
     * never thirteen. What the arrival stream actually shows is in {@link #RELAY_COPIES}
     * neighbours -- see the note on {@code AirDropServer}'s receive buffer.
     */
    private static final int RELAY_COPIES = 1;

    /**
     * How far apart those copies go out.
     *
     * <p>Spacing is the whole point of them. Three frames 250 ms apart all land inside
     * the same slice of the peer's availability cycle, so they are either all heard or
     * all lost together -- a measured round trip of 1859 ms says the cycle is about two
     * seconds long, which means four tries a second is not four chances, it is one
     * chance four times. Eight hundred milliseconds puts the copies in different phases
     * of the cycle, which is what turns three transmissions into three chances.
     */
    private static final long RELAY_SPACING_NS = 800_000_000L;

    /**
     * How long the same (transmitter, sequence) pair is treated as one transmission.
     *
     * <p>Every vif tuned to the channel hears the same frame, and the three copies differ
     * only in which receive descriptor carried them. Fifty milliseconds is longer than
     * the spread between those copies -- microseconds to a couple of milliseconds -- and
     * shorter than any interval at which a transmitter would legitimately reuse a sequence
     * number for a frame that says something new. A hardware retry of the same frame keeps
     * its sequence number and arrives inside this window, which is right: it is the same
     * frame, and the stack above dedupes what it repeats.
     */
    private static final long DUP_WINDOW_US = 50_000L;

    /**
     * How long a phase sample stays eligible to anchor the clock.
     *
     * <p>The peer announces where its next slot boundary is, but that announcement is
     * read after the frame has waited in the receive queue, and this loop also sleeps on
     * transmit edges for up to a whole slot. The wait is therefore up to one slot long
     * and one-sided, so the frame that waited least is the best clock sample available
     * and every other frame is the same clock seen through a longer queue. Anchoring on
     * the newest frame instead anchors on that wait: one run measured err as 0, -2, -18,
     * -26, -44, -60, -63 TU, which is not a residual converging, it is the queue.
     *
     * <p>Two seconds is chosen to be longer than the loop's own period many times over
     * -- a handful of frames per second gives tens of samples -- and short enough that a
     * peer which actually moved its clock is followed before the window closes.
     */
    private static final long ANCHOR_WINDOW_NS = 2_000_000_000L;

    /**
     * How many whole-slot samples may be refused in a row before one is taken anyway.
     *
     * <p>A sample that reads a full slot of error is not a phase reading at all: the
     * boundary is reconstructed from the moment this loop *read* the frame, and a read
     * that was one slot late moves the aim one slot late. {@link AwdlFrame#errorTu} is
     * exactly the size of that wait, which is why a run on this link alternates between
     * {@code 0} and {@code -48} TU once a second rather than converging.
     *
     * <p>The guard still has to be escapable. A peer that genuinely moved by a slot -- or
     * this node's own counter slipping -- produces the same reading on every sample, and
     * a receiver that refused all of them would hold a clock it should have abandoned.
     * Eight consecutive refusals is roughly a second of frames, after which the next
     * sample is trusted whatever it says.
     */
    private static final int ANCHOR_REJECT_LIMIT = 8;

    /**
     * Reference point for the millisecond stamps on the relay lines.
     *
     * <p>Without them a handshake cannot be reconstructed at all. The interesting
     * question is never "did a SYN leave and a SYN-ACK arrive" -- both of those were
     * true in every relay run -- but whether the ACK that answers it left while the
     * peer's socket still existed. That window is a couple of seconds wide, and a log
     * with no clock in it cannot say whether a missing ACK was late or never sent.
     */
    private static long clockStart = System.nanoTime();

    private static String stamp() {
        long ms = (System.nanoTime() - clockStart) / 1_000_000L;
        return String.format(Locale.US, "%6.3f", ms / 1000.0);
    }

    /**
     * How long ago a {@link System#nanoTime()} reading was, in whole seconds.
     *
     * <p>{@code "?"} rather than a large number when the thing never happened: a run that
     * has heard nothing yet and a run that heard something an hour ago are different
     * findings, and the second cannot occur inside one run.
     */
    private static String age(long now, long then) {
        return then == 0 ? "?" : ((now - then) / 1_000_000_000L) + "s";
    }

    /**
     * How much of the run is still ahead, as the report's opening field.
     *
     * <p>{@code "no limit"} rather than a number when the run was started with no duration,
     * which is how the app starts it. The deadline in that case is {@link Long#MAX_VALUE},
     * a sentinel and not an instant, and subtracting the clock from it produced
     * {@code [9223336564s left]} -- two hundred and ninety-two years. A report that opens
     * with an impossible number is a report whose other numbers get doubted too.
     */
    private static String left(long deadline, long now) {
        if (deadline == Long.MAX_VALUE) {
            return "no limit";
        }
        long ns = deadline - now;
        return (ns < 0 ? 0 : ns / 1_000_000_000L) + "s left";
    }

    /**
     * How many handshake frames to send before settling on the query.
     *
     * <p>One should be enough: the driver creates the peer synchronously on the ordered
     * bridge worker, so the very frame that names the peer is followed by a peer object
     * before the next one is built. Six covers a radio that was mid-hop on the first try,
     * and the burst is short enough that the query still gets the rest of the run.
     */
    private static final int SYN_BURST = 6;

    /**
     * How often to ask again once the burst is spent.
     *
     * <p>The burst used to be the whole story, and a sixty-second run therefore asked
     * its question in the first two seconds and then spent fifty-eight seconds
     * describing the silence. That is only sound if the receiver is listening at the
     * start -- and the whole reason this stack advertises over BLE (BLE-1) is that a
     * phone decides to bring its AirDrop server up some seconds *after* it hears us.
     * Every log where the SYN burst finished before the server existed reads identically
     * to a port with nothing behind it, so the retry is what separates the two.
     *
     * <p>Four seconds: slow enough that a real SYN-ACK in the receive path is not
     * competing with our own flood, fast enough that a fifteen-second wake-up still
     * leaves forty-five seconds of the run to see what it opened.
     */
    private static final long SYN_RETRY_NS = 4_000_000_000L;

    /**
     * The ceiling the retry interval backs off to.
     *
     * <p>A fixed four-second retry is right for the first half-minute -- the peer's AirDrop
     * server either comes up shortly after the BLE advertisement or it does not -- and
     * wrong forever after. The node runs with no duration, so "forever after" is the normal
     * case: a phone left in "receiving from contacts" mode answers nothing, and the run
     * prints {@code syn retry #114}, {@code #115}, {@code #116} until the log stops being
     * readable. Thirty seconds keeps the late-listener case covered while spending one
     * frame every seven and a half minutes on a peer that is never going to answer.
     */
    private static final long SYN_RETRY_MAX_NS = 30_000_000_000L;

    /**
     * How many retries to narrate before falling silent.
     *
     * <p>The first few are the diagnosis -- they say the burst went out and nothing came
     * back -- and every one after that says the same thing. Retries continue; only the
     * printing stops, and resumes once the interval has reached its ceiling so a peer that
     * answers after a minute is still visible in the log.
     */
    private static final int SYN_RETRY_LOUD = 3;

    /**
     * How many times one run may move its target address.
     *
     * <p>Every move is spent against the driver, not against this process. The bridge
     * learns the address it is aiming at -- address 1 of a unicast TCP frame -- into a
     * table of eight slots fixed at compile time, and that table has no eviction:
     * {@code opairshare_target_reserve()} only ever takes a free slot, and the comment
     * above it says why ("live peers and their RX BA state survive nearby discovery").
     * When all eight are used the function returns silently, no peer is created for the
     * new address, {@code opairshare_target_ensure()} answers 0 so the frame is
     * transmitted anyway, and the firmware has nobody to send it to. The completion is
     * status3, forever: only a vdev stop clears the table.
     *
     * <p>Measured on the phone, both directions. Table cleared, one run: {@code peers=1},
     * status0 +67 / status3 +18, an mDNS reply, an ICMPv6 echo reply, and a TCP RST from
     * the AirDrop port. Table at {@code peers=8}: 84% of data frames status3, 96% of
     * ADDBA requests failed with -ENOENT, and not one reply to anything. The difference
     * between a working link and a dead one is this table, so the budget here is small
     * on purpose and refusing a move is the cheap direction to be wrong in.
     */
    private static final int MAX_AIMS = 2;

    /**
     * AirDrop's conventional TCP port, used when the caller names no other.
     *
     * <p>8770 is a convention and not a promise. SPEC MDNS-1 says a sender that has the
     * SRV record MUST use the port it names, and the phone does publish its own -- in
     * the Service Response TLV of its action frames. A SYN aimed at a port nobody bound
     * earns an RST, which proves only that a kernel exists, so the port has to be a
     * parameter: the difference between RST and SYN-ACK is the whole question here.
     */
    private static final int TCP_PORT = 8770;

    /**
     * The source port every SYN this tool fabricates claims to come from.
     *
     * <p>It has to be a named constant because the kernel answers for it. A SYN built
     * here goes on the air with no socket behind it, so when the phone replies SYN-ACK
     * that reply arrives at the tun, is handed to the local stack, and finds nobody
     * bound to this port. The stack does what it is required to do and emits a RST --
     * and that RST was then relayed back to the phone as if it were ours, three copies
     * of it at 800ms apart, closing the half-open connection the SYN had just opened.
     * Measured: SYN-ACK at t=0.9s, RST at t=1.33 / 2.32 / 3.23s, and every run after
     * that opened the same connection and killed it the same way. The handshake was
     * never slow; it was being cancelled.
     *
     * <p>So the port is also the marker that lets the relay path recognise the kernel
     * refusing a connection it did not open, and stay quiet about it. That makes the
     * value load-bearing in a second way: it must never be a port the kernel could hand
     * to a real socket, or a reply meant for `nc` would be swallowed here as if it were
     * our own. The device reads {@code 32768 60999} from
     * {@code /proc/sys/net/ipv4/ip_local_port_range}, so 49152 -- the number this used
     * to send -- is inside it. 61001 is just past the top, which is why it is the value.
     */
    private static final int SPOOF_SPORT = 61001;

    /**
     * Largest IP packet from the tun that still becomes a frame the driver takes.
     *
     * <p>1512 bytes is the longest frame measured on the air, and framing adds 51 --
     * radiotap, 802.11 header, QoS control, SNAP and the data head -- so 1461 is where
     * the air stops accepting. A packet above this one cannot be relayed at all, and
     * the only ways to get one are segmentation offload and an MSS nobody clamped.
     */
    private static final int MAX_TUN_PACKET = 1461;

    /**
     * The largest MSS the air path survives, whatever anyone asks for.
     *
     * <p>A segment of {@code m} bytes leaves as a frame of {@code m + 111}: 40 of IPv6,
     * 20 of TCP, and 51 of radiotap, 802.11 header, QoS control, SNAP and the data head.
     * So the MSS is the packet limit less the sixty header bytes and nothing else -- one
     * number follows from the other instead of being tuned beside it.
     *
     * <p>Those 51 bytes of framing are the legacy figure. An HT or VHT header carries an
     * MCS field and an HT field the legacy one does not, so framing costs 54 and a
     * segment of 1401 leaves as a 1515-byte frame -- three bytes past what the driver
     * takes. Every full-size segment is then refused as EMSGSIZE, the opening burst
     * never reaches the air, and the send stalls until a lone retransmission small
     * enough to pass gets through. Three bytes of headroom is the whole difference
     * between a link that starts and one that appears to hang at 128 kB.
     *
     * <p>The cap is enforced here rather than left to the caller because the value is
     * also readable from {@code awdl-tuning.conf}, which shipped with 1440: a segment of
     * that size leaves as a 1551-byte frame, the driver refuses anything past 1512, and
     * every segment of the opening burst died as EMSGSIZE. None of them reached the air,
     * so the send spent its whole retransmission backoff waiting for ACKs of packets that
     * were never sent, and only resumed four seconds later when a retransmitted single
     * segment -- 1461 bytes, small enough to pass -- got through.
     */
    private static final int MSS_CEILING = MAX_TUN_PACKET - 60 - 3;

    /**
     * The segment size that actually completes a handshake, as opposed to the largest the
     * driver accepts.
     *
     * <p>{@link #MSS_CEILING} bounds what the kernel will hand over, not what the air
     * delivers. Measured against an iPhone on the receiving side: a 1232-byte segment
     * leaves as a 1358-byte frame, is sent once and never acknowledged, and the peer
     * resets 0.9s later with its ack still on the segment boundary -- iOS draws that as
     * a red "declined" under the device's avatar, which is why this hid for so long
     * behind a word that means refusal. The same handshake with the clamp at 900 leaves
     * as 888-byte segments in 1011-byte frames: the TLS 1.3 exchange finished in 2654ms,
     * {@code /Discover} answered 200, and the connection then closed cleanly from the
     * peer's side. 512 was already known to be too small because the peer sizes its
     * receive window from the MSS it is given; 900 sits between that floor and the frame
     * length the air starts eating.
     *
     * <p>Two hundred, and the nine hundred above is history rather than a default. The
     * run that produced that paragraph had a relay that could keep a frame on the air;
     * with the relay as it stands today the handshake never once completed at four
     * hundred over a hundred and ninety seconds of the peer retrying on a fresh source
     * port every five seconds -- every attempt stalled with the peer's {@code ack} still
     * sitting on the boundary of our SYN-ACK, which is the same signature the paragraph
     * above attributes to a long first segment. At two hundred the first two handshakes
     * of the session completed, 2764 ms and 1741 ms, TLS 1.3, and the peer went on to
     * post {@code /Discover}. The frame that changed is the one carrying the server's
     * handshake flight: at four hundred it left as a 514-byte frame and was never
     * acknowledged, at two hundred the same flight leaves as 314-byte frames and four of
     * them were acknowledged in a row. So the ceiling here is not the kernel's, it is
     * whatever the peer's presence window can hold in one burst, and today that is
     * nearer 300 bytes than 1400.
     */
    private static final int MSS_HANDSHAKE = 200;

    /**
     * Segment size the TCP handshake is allowed to name; see {@link #clampMss}.
     *
     * <p>The peer only has its receiver open for a sixteen-TU window per slot, and a
     * frame longer than that window's airtime is lost whole -- not trimmed, not
     * retried by the radio, gone. The link therefore behaves like a wire that carries
     * short frames and eats long ones, which is survivable for a handshake of a few
     * hundred bytes and fatal for the first full-size segment: measured runs complete
     * the TLS exchange and then stall with a 1428-byte hole in the stream, waiting for
     * a retransmission that is itself too long to arrive.
     *
     * <p>This is the one lever that changes what the peer puts on the air, and it has
     * to be applied in both directions -- the MSS in our SYN caps what it sends, the
     * MSS in its SYN-ACK caps what we send -- because neither stack will pick a small
     * value from an MTU of 1500.
     *
     * <p>Five hundred and twelve was too small, and it cost more than the header
     * overhead. Measured on the device: the peer advertises a receive window of 2041
     * bytes and never grows it, which is almost exactly four segments of 512 -- the
     * window a stack sizes at handshake time from the MSS it was given. So the clamp did
     * not merely make each frame carry less, it made the peer's buffer smaller, and a
     * two-kilobyte buffer on a fifty-millisecond round trip is a ten-kilobyte-per-second
     * link no matter how well the frames are timed. The three transfer runs before this
     * one all died there, and each of them looked like a radio problem.
     *
     * <p>The default is now {@link #MSS_CEILING}, the largest the air path takes. The run
     * that "proved" long frames are eaten used 1428 -- a 1539-byte frame -- and lost every
     * one of them whole, which is the same failure one step further out.
     *
     * <p>This clamp is not handshake-only, which is the mistake that put a two hundred in
     * here and cost a whole session. {@link #clampMss} rewrites the MSS option of any SYN
     * <em>and</em> SYN-ACK we carry, so the number also lands in the peer's answer and
     * caps what this side may send. At two hundred the link measured ninety-two kilobytes
     * in two hundred and forty-four seconds -- six phone connections, every one of them
     * opened and then reset, and the transfer that was supposed to be running never got
     * past its first buffered window. The same phone and the same radio move two point
     * eight megabytes a second when this reads 1398, so the value here is a throughput
     * decision and only incidentally a handshake one.
     */
    private static volatile int mssClamp = envInt("AWDL_MSS", MSS_CEILING);

    /**
     * How many data frames one availability window may carry.
     *
     * <p>The guard aims a frame at three TU past a slot boundary and the loop then waits
     * for the next boundary before aiming again -- one frame per forty-eight-TU slot.
     * That is a considered pace for a query, which is asked again and again for a whole
     * minute and only has to land once, and it is a hard ceiling for a transfer: five
     * hundred bytes per forty-nine milliseconds is ten kilobytes a second, so a text
     * file arrives and a video does not. The receiver's window is open for sixteen TU,
     * which is a third of the slot, so the frames that fit behind the guard go out back
     * to back instead of one per slot.
     *
     * <p>Sixteen, and it is a soft limit: past it the loop falls back to pacing on the
     * window edge instead of stopping, so the real gate is {@link #windowLastTu} rather
     * than this count.
     */
    private static volatile int windowFrames = Math.max(1, envInt("AWDL_WINDOW_FRAMES", 16));

    /**
     * Last TU of a slot a burst frame may start in.
     *
     * <p>Three TU of guard at the front, and the slot is sixty-four TU, so the value is
     * how much of that slot this side is willing to transmit in. It was fourteen for a
     * long time on the arithmetic that a fourth of the slot matched the peer's window,
     * and the link settled at four hundred and ten frames a second -- about 550 KB/s,
     * which is two and a half megabytes a second of in-window throughput times the
     * twenty-two percent the limit allowed. Measured, not guessed: the send phase sat in
     * the first eight TU and never used the rest of the slot.
     *
     * <p>Twenty-eight doubles that share and the same transfer went from 452 to 723 KB/s
     * with {@code fail=0 drop=0 rst=0} and no retransmission. The phase histogram reached
     * twenty-three TU afterwards, so the peer does keep listening past fourteen.
     *
     * <p>Forty takes sixty-two percent of the slot and the same video went from 35 s to
     * 18.0 s -- 24.89 MB at 1.35 MB/s, still {@code fail=0 drop=0 rst=0} and the phase
     * histogram topped out at forty.
     *
     * <p>Forty-eight takes three quarters and the same video took 15.6 s, 24.89 MB at
     * 1.81 MB/s, again {@code fail=0 drop=0 rst=0}, with the phase histogram reaching
     * sixty-two TU -- the peer is awake for very nearly the whole slot, so the limit here
     * was never the peer's window. Past this the guard at the end of the slot is what
     * gets thin, and a frame that lands after the slot closes is airtime thrown away.
     *
     * <p>Fifty-six is seven eighths and the same video took 8.04 s, 24.89 MB at 3.10 MB/s,
     * {@code fail=0 drop=0 rst=0} again and the phase histogram filled the slot to
     * sixty-three. Eight TU of guard is still enough for a frame in flight at VHT rates,
     * and the peer never stopped listening. What is left above this is under three TU of
     * margin, so a value past fifty-six is an experiment, not a default: raise it one step
     * and read {@code fail}, drop and the phase histogram, not the clock.
     */
    private static volatile int windowLastTu = Math.max(4, envInt("AWDL_WINDOW_LAST_TU", 56));

    /*
     * Where the remaining gap actually lives, measured so that it is not re-diagnosed as
     * a client-side ramp for the tenth time. Read this before touching any value above.
     *
     * Measured twice, rounds apart, same shape and different numbers. The larger run --
     * 23.74 MB uploaded in 9.48 s after the user accepts, 2.51 MB/s -- stalled ten times
     * for 1304 ms in total, 13.8% of the transfer. The earlier one stalled nine times for
     * 1877 ms of 9.78 s, 19.2%. Both produced onsets at 1.05 s spacing from the first
     * second to the last: 0.95, 2.10, 3.00, 4.05, 5.10, 6.15, 7.20, 8.25, 9.30 s in one,
     * +0.29, 1.35, 2.38, 2.48, 3.45, 4.48, 5.61, 6.58, 7.63, 8.68 s in the other. They are
     * not a start-up cost, they never stop, and they are not where the client code appears
     * to be slow. The second run moved 2.2 MB in its first second, so the "1.7 MB first
     * second" of the earliest reports was this same loss landing early, not a ramp.
     *
     * 1.05 s is the tell. An AWDL sequence is sixteen slots of 64 ms, 1.024 s, so the
     * pattern is one sequence in every sequence losing one to four of its slots: fifty to
     * three hundred and fifty milliseconds of silence, once per 1.024 s round. The
     * throughput we have measured all along is this loss subtracted from the in-slot rate,
     * which is why pushing {@link #windowLastTu} from 28 to 56 bought 1.35 to 3.10 MB/s
     * and then stopped buying anything: the slots are already full, there are just fewer
     * of them than the slot clock says.
     *
     * Which slot, answered rather than inferred. The report carries {@code sl=}, sixteen
     * counts of turns attributed to each slot index, and in every interval of a completed
     * transfer exactly one entry is zero while the other fifteen carry one hundred and
     * thirty to three hundred each: index eight, every time. The peer's announced
     * sequence, printed once per run, reads {@code 6,0,149,0,0,0,0,0,6,0,149,0,0,0,0,0}
     * against our channel 149. So the loss is one channel-6 slot per sequence, 65.5 ms of
     * 1.0486 s, 6.25% of the air, and it is not recoverable by scheduling: during that
     * slot the other end is tuned away from us, and a frame aimed at it is not late, it is
     * sent to nobody. {@code offch}, once keyed on absolute slots instead of indices,
     * advances exactly once per sequence -- the count and sl agree on the same slot.
     *
     * The rest of the accounting, because 6.25% does not explain a link at 2.5 MB/s when a
     * single slot measures 3.2. Two candidates were checked and one was wrong. First, the
     * window ceiling: {@code win=} looked saturated, but its top bin was a catch-all, and
     * the {@code burst} flag falling through to {@code edgeWaitUs} -- which returns zero
     * for most of a slot -- means turns per window routinely pass sixteen and the ceiling
     * is never enforced. That is why raising {@code windowFrames} never changed a rate.
     * The histogram now bins four turns wide and the report prints {@code wmax=}, the
     * largest window actually closed, so the question cannot be misread twice.
     *
     * Second, and now closed: the fifteen live slots are not equal, but not for the reason
     * that was suspected. Slots zero to seven close around three hundred turns each; slot
     * nine closes at a hundred and forty and slot ten at two hundred and twenty, and from
     * eleven on the counts are back within two per cent of the head. So the deficit is not
     * a tail at all -- it is two slots of recovery after the channel-6 slot, and {@code
     * skip=} settles which: during a transfer it rises by exactly one a second, one per
     * sequence, and that one is the dead slot. No other slot is being skipped whole. The
     * two light slots are the loop and TCP finding the air again after 65.5 ms of nothing,
     * worth about 3.8% a sequence on top of the 6.25% the schedule charges.
     *
     * With those two measured, the budget closes on the number the link actually delivers.
     * One slot sustains 3.2 MB/s; fifteen of sixteen slots are usable, 3.03; the window
     * spends its last eight TU waiting for a boundary rather than sending, {@code
     * windowLastTu} of 64, so 2.65; and the recovery above takes three per cent, 2.57 --
     * which is the mean of the run that measured 9.43 s for 23.74 MB. The only lever left
     * in that product is the window ceiling, and it has been tried: raising
     * {@code windowLastTu} from 56 towards 60 moved the rate backwards, because the frames
     * it adds land where the peer has already stopped listening for this slot. A link at
     * 97% of its own budget is finished, and the remaining gap to 3.0 is the schedule's,
     * not the loop's.
     *
     * One dead end, recorded so it is not walked twice. The aiming arithmetic in {@link
     * #edgeWaitUs} does have a hole: a frame arriving while the loop stands in the first
     * three TU of a slot is told to wait for the next boundary -- sixty-two to sixty-five
     * TU, a whole slot -- instead of the one to three TU left before the guard expires, and
     * nothing interrupts that wait because while nothing is sent no ACK comes back to
     * interrupt it. It matches the measured stall signature exactly, {@code cuts 0 polls
     * 0}, so it was closed: wait the remainder of the guard instead of the slot. The same
     * file then took 10.22 s against 9.43 s, with client stalls at 2090 ms against 1304 ms
     * and the air silence here unchanged. Arriving at the first TU the guard allows means
     * arriving at the window edge with this pair's clock error, minus one to minus two TU,
     * still unspent -- the frame lands before the peer is listening, is lost, and costs the
     * very slot the change was written to save. The hole is real and it stays open on
     * purpose: the wait is shared with every other boundary frame, and it is cheaper than
     * arriving early.
     *
     * What the status line did rule out. {@code offch} was keyed on slot indices and read
     * as a constant for a whole run -- index fifteen appears once per sequence, so the
     * counter recorded a permanently off-channel slot exactly once. Keyed on absolute
     * slots it advances once per sequence, which is the dead slot and nothing more, so
     * channel switching is not stealing airtime from another interface. The queue agrees:
     * during every data-phase stall the kernel held nineteen to fifty-nine packets while
     * {@code polls} and {@code cuts} both stayed at zero -- not idle-polling, not
     * interrupted, waiting on a transmit moment that had been aimed at badly. Source
     * reading is not in the path either: the twenty megabyte file reads off the device in
     * 20 ms.
     *
     * Note that this is our code, not the driver. The data plane here is this class
     * running as its own process under {@code app_process} -- the moving of packets
     * between {@code awdltun0} and the radio, the window scheduling and the burst logic
     * are all the loop below. The retired conclusion that the engine layer "cannot be
     * fixed" came from a time when the loss was being read from the client side; it does
     * not apply to slot scheduling.
     *
     * Where the evidence runs out: {@code relay #} still prints only the first samples of
     * a run, around a hundred and twelve frames out of a quarter million, so per-frame
     * forensics are not available -- only the aggregate that says which slot carried how
     * much. That was enough to name the dead slot, to size the recovery behind it, and --
     * with {@code skip=} counting one per sequence during a transfer -- to rule out whole
     * slots going missing.
     *
     * One method note, because it cost a day: counting the *arrival* spacing of inbound
     * ACKs shows no gaps at all during these stalls. Duplicate ACKs fill the silence. What
     * exposes the stall is the advance of the {@code ack=} value itself, bucketed per
     * hundred milliseconds; a bucket with zero advance is a stalled link no matter how
     * many packets arrived carrying the same number.
     */

    /**
     * How many frames the driver packs into one air frame, mirrored so the send loop can
     * hand them over as a group.
     *
     * <p>Aggregation does not happen here. {@code passthru_awdl_amsdu=2} makes the driver
     * hold injected frames for up to {@code collect_us} and seal up to
     * {@code amsdu_max} of them into one A-MSDU, so the fill of an air frame is a property
     * of <em>how the frames arrive</em>, not of how many are queued. One frame per loop
     * turn produced the fill only when several turns happened to land inside one collect
     * window, and most do not: a transfer measured 5.08 KB per air frame against a driver
     * that would take five 1440 B MSDUs (7.2 KB), and the fastest run of the same code
     * recorded 7.13 KB. Same slot, same phase histogram, same window -- only the fill
     * differed, and 7.13/5.08 is the whole 2.0 to 3.1 MB/s gap.
     *
     * <p>So the rest of the group is handed over inside one turn. Five is
     * {@code amsdu_max}'s default and a group deeper than the driver's limit would only
     * spill into the next air frame, which is what the loop already did.
     */
    private static volatile int aggDepth = Math.max(1, envInt("AWDL_AGG_DEPTH", 5));

    /**
     * How many kernel packets may wait here for a legal transmit moment.
     *
     * <p>Was thirty-two, and thirty-two is the wrong number for a transfer even though it
     * looks generous. A TCP stream is not a queue of independent packets: dropping any one
     * of them punches a hole the receiver cannot fill, so it stops acknowledging, and the
     * sender sits still until its retransmission timer expires -- which is not the
     * round trip of a wired link but one of Apple's own backoff steps. The queue is
     * therefore not a buffer to trade against latency, it is the only thing standing
     * between a burst of kernel work and a multi-second freeze in the progress bar.
     *
     * <p>Thirty-two packets is forty-six kilobytes, which is the same order as a
     * congestion window on this link -- so an ordinary window's worth of packets could
     * overflow it, and once the window had grown past it every burst did. Two hundred and
     * fifty-six packets is three hundred and fifty kilobytes: several windows, one whole
     * slot fed without a gap, and still a quarter of a megabyte of memory in the worst
     * case. The ceiling exists at all because a backlog older than the peer's window is
     * worth less than the kernel's own fresher retransmission; it is no longer a ceiling
     * that a normal transfer can reach.
     */
    private static volatile int queuePkts = Math.max(32, envInt("AWDL_QUEUE_PKTS", 256));

    /**
     * Gap between two frames of one burst, in nanoseconds.
     *
     * <p>Zero, and the value it replaced was one millisecond, chosen on the arithmetic
     * that a frame occupies the air in under a millisecond at six megabits -- so a one
     * millisecond pause between frames would still let eight of them fill the sixteen-TU
     * window. The arithmetic was right about the air and wrong about the loop. Measured
     * end to end, a frame cost four milliseconds with the gap in place: one of waiting and
     * three of everything else one pass does between two sends. Four milliseconds is three
     * frames in a fourteen-TU window, not eight, and the burst histogram said exactly that
     * for five minutes of video -- two, three, four, never more -- while the window field
     * held eight the whole time.
     *
     * <p>Three frames a window is not merely slower, it is slow enough to look like a
     * broken radio. A third of the frames per window is a third of the bytes per round
     * trip, which leaves the congestion window pinned wherever the previous loss put it:
     * twenty-eight segments, one retransmission every two seconds, forty-eight kilobytes
     * a second, nine minutes for a file the progress bar had already given up on.
     *
     * <p>Set to zero the same pass costs one and three-quarter milliseconds a frame and
     * fills the window, and the window grows because the losses stop. Measured on a
     * twenty-four megabyte send, forty-eight kilobytes a second became five hundred and
     * thirty, and the file finished in the time the bar had left. The driver paces itself:
     * a frame handed over before the radio is ready is queued, not dropped, and send
     * failures stayed at zero across the run. There was nothing here for a fixed sleep to
     * protect.
     *
     * <p>The one millisecond it replaces was itself a fix for a twenty-millisecond interval
     * chosen when the relay carried nothing but a handshake: a phone answered our SYN in
     * six milliseconds and then waited more than a second for our ACK, because we had
     * stopped answering. At a two-kilobyte window the same interval stops being a reply
     * latency and becomes a transfer rate -- and it was the rate, not the radio, that
     * stalled the video every time.
     */
    private static volatile long windowGapNs =
            Math.max(0, envInt("AWDL_WINDOW_GAP_MS", 0)) * 1_000_000L;

    /**
     * Whether the slot's channel still has to match before a data frame may go out.
     *
     * <p>The availability mask the peer publishes is mostly dots and sixes: in a measured
     * run only five of sixteen slots named channel 149, and the transfer sent in almost
     * exactly those five. If the peer is in fact listening on 149 in more of the sequence
     * than it announces -- and its own frames arrived spread across eight of the sixteen
     * -- then honouring the mask costs two thirds of the link for nothing.
     *
     * <p>Off by default: it is a hypothesis, not a finding, and it needs measuring rather
     * than assuming. {@code AWDL_IGNORE_CHANNEL=1} in the tuning file turns it on.
     */
    private static volatile boolean ignoreChannel = envInt("AWDL_IGNORE_CHANNEL", 0) != 0;

    /**
     * The modulation the tuning file last asked for, or a negative MCS for legacy only.
     *
     * <p>Three knobs, and they are separate because they fail separately: the MCS index
     * says how dense the constellation is, the width says how much spectrum one frame
     * occupies, and VHT says which of the two radiotap fields carries that answer. A
     * driver that honours the MCS field and ignores the VHT one is a real outcome, and
     * the way to see it is to move one at a time.
     *
     * <p>The defaults are HT, twenty megahertz, MCS 7, short guard. The driver does honour
     * the field -- the frame rate and the byte rate both moved the first time the numbers
     * were tried with a segment size that left room for the extra framing. An MCS of -1
     * still selects legacy, which is what a tuning file written against the old defaults
     * expects.
     */
    private static volatile int phyMcs = envInt("AWDL_MCS", 7);
    private static volatile int phyWidthMhz = envInt("AWDL_BW", 20);
    private static volatile boolean phyVht = envInt("AWDL_VHT", 0) != 0;
    private static volatile boolean phySgi = envInt("AWDL_SGI", 1) != 0;

    private static void applyPhy() {
        AwdlFrame.setTxPhy(phyMcs, phyWidthMhz, phyVht, phySgi);
    }

    /**
     * Where the link parameters can be rewritten without rebuilding anything.
     *
     * <p>Every question left in this file is a number: how big a segment, how many per
     * window, how late in the slot. Answering them by editing a constant, waiting for a
     * build, reinstalling and asking for another twenty-four-megabyte transfer is a way
     * of never answering them. The node already runs as root, so a file in
     * {@code /data/local/tmp} is readable to it, writable from one adb command, and
     * costs nothing to poll once a second.
     *
     * <p>Format is one {@code key=value} per line:
     * {@code mss}, {@code window_frames}, {@code window_last_tu}, {@code window_gap_ms},
     * {@code idle_ms}, {@code agg_depth}, {@code ignore_channel}. Anything else is ignored,
     * and a missing file means the defaults above.
     */
    private static final String TUNING_FILE = "/data/local/tmp/awdl-tuning.conf";
    private static long tuningCheckedAt;
    private static long tuningStamp = -1L;

    /**
     * The one node this process runs, so the tuning file can reach the election.
     *
     * <p>{@link #parseTuning} is static and the election lives on an instance, and the
     * alternative -- threading {@code awdl} through every call between the two -- buys
     * nothing for a process that runs exactly one node.
     */
    private static volatile AwdlFrame awdlRef;

    /**
     * Force the announcement to all sixteen slots, bypassing the negotiation.
     *
     * <p>Not needed for the normal case any more: {@link AwdlFrame#offerSequence} adopts
     * a peer's map only where it agrees with ours and keeps the slots where it does not,
     * so an untouched node already announces sixteen. The key remains as an override for
     * the one experiment the negotiation cannot express -- announcing sixteen while the
     * election still names the peer, which is the "is the peer's send gated on our map or
     * on its own schedule" question isolated from the rank.
     */
    private static volatile boolean keepOwnSequence = envInt("AWDL_SEQ_OWN", 0) != 0;

    /**
     * Claim the master role, which is the only way the announced sequence can bind.
     *
     * <p>A follower's map is its own business; nobody's send is aimed at it if the peer
     * ranks higher and keeps its own schedule. Winning the election is what would make
     * the peer adopt our slots -- and it is off by default because the failure mode is
     * total: if the peer refuses to follow a non-Apple master, the two ends anchor on
     * different superframes and the transfer does not slow down, it stops.
     */
    private static volatile boolean masterTake = envInt("AWDL_MASTER_TAKE", 0) != 0;

    /** The rank to claim when {@link #masterTake} is on. A peer measured at 538. */
    private static volatile long selfMetricWanted = envInt("AWDL_SELF_METRIC", 0);
    /** What to claim when {@code master_take=1} and no {@code self_metric} is given. */
    private static final long DEF_TAKE_METRIC = 2000;

    /*
     * The driver's own side of the same question.
     *
     * Every number this file prints before these two describes what was handed to the
     * radio. None of them describes what the radio put on the air, and the difference
     * between the two is the whole of the throughput problem: frames leaving the loop
     * at a rate that would fill the link, arriving as air frames at two thirds of the
     * payload. The driver counts both, under a parameter directory that only root can
     * read -- and this process runs as root, so it can.
     */
    private static final String AGG_STATS =
            "/sys/module/qca_cld3_peach_v2/parameters/opairshare_agg_stats";
    private static final String AGG_COLLECT =
            "/sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_collect_us";
    private static final String AGG_MAX =
            "/sys/module/qca_cld3_peach_v2/parameters/passthru_awdl_amsdu_max";
    /**
     * What the tuning file asked for; 1500 is the default the driver is asked to take.
     *
     * <p>A millisecond and a half of collecting fills an A-MSDU to the driver's own
     * ceiling of five segments, and the driver packs one aggregate per cycle whatever
     * is asked of it -- 600 µs of collecting produced the same aggregates per second
     * with less in each. Negative means leave the driver alone.
     */
    private static volatile int collectUsWanted = 1500;
    private static int collectUsApplied = -1;

    private static final int DNS_TYPE_PTR = 12;

    /**
     * Ends the node when the app that started it is gone.
     *
     * <p>The node is a root process started through {@code su}, and nothing takes it down
     * when the app dies. {@code am force-stop} removes the app, but a shell that was
     * still between its {@code pkill} and its {@code app_process} survives the kill and
     * goes on to launch a node afterwards -- orphaned from the moment it exists. That
     * node then holds {@code awdltun0}, or grabs the name a tick after the next app has
     * deleted it, and the next share sheet binds an endpoint to a tunnel whose owner is
     * about to be reaped. Measured on the device: a tunnel created 700 ms after one
     * force-stop and deleted by the following launch 1.5 ms later, with the endpoint then
     * failing with "no interface named awdltun0" and the device list staying empty.
     *
     * <p>Watching {@code /proc} is the portable test: the pid is checked ten times a
     * second and the process leaves as soon as it is gone. Absent or unparsable means the
     * node was started by hand, which is how every measurement before this one was taken,
     * and then no watchdog runs at all.
     *
     * <p>The checker deliberately runs <em>before</em> the tunnel is built as well as
     * after it. A ghost launcher is a shell that outlived its app by a few hundred
     * milliseconds, so it is already past the kill and about to exec a node; asking
     * whether the app still exists at that point costs one {@code stat} and stops the
     * tunnel from ever being created. Killing it a quarter of a second later instead is
     * not equivalent: the endpoint binds the moment the interface appears, and a tunnel
     * that disappears between bind and first packet fails with "no interface named
     * awdltun0" -- measured, one launch in eight, before this check existed.
     *
     * @return whether the node should go on; false when its app is already gone
     */
    private static boolean watchApp(String pidText) {
        int pid;
        try {
            pid = Integer.parseInt(pidText.trim());
        } catch (NumberFormatException absent) {
            return true;
        }
        if (pid <= 0)
            return true;
        File alive = new File("/proc/" + pid);
        if (!alive.exists()) {
            System.out.println("awdl-data: app " + pid + " was gone before the node started");
            return false;
        }
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException stop) {
                    return;
                }
                if (!alive.exists()) {
                    System.out.println("awdl-data: app " + pid + " is gone, exiting");
                    System.exit(0);
                }
            }
        }, "app-watch");
        watchdog.setDaemon(true);
        watchdog.start();
        return true;
    }

    private AwdlData() {
    }

    /**
     * Drops this process's timer slack to a microsecond.
     *
     * <p>Android's default is 50 ms, and it is not a property of sleeping -- it is a
     * rounding applied to every deadline the kernel arms for the process, including the
     * socket read timeout that paces the send loop. The loop is built around a TU of
     * 1.024 ms and a sixteen-TU window, so a 50 ms round-up is fifty times the unit it
     * works in.
     *
     * <p>Measured with SO_RCVTIMEO set to 1 ms and the default slack: 26 turns a second,
     * 1.2 turns per 65.5 ms slot, wmax=2, and a 24 MB upload that never got past its
     * first 128 KB. The same tuning file with the sleep it replaced at 50 ms produced
     * the identical rate, which is what says the value written into SO_RCVTIMEO was
     * never the value the loop ran at.
     *
     * <p>Written through /proc because android.system.Os has no prctl, and this process
     * runs as root so the file is writable. A failure is printed and ignored: the loop
     * still works, it just keeps running at the slack the platform chose.
     */
    private static void relaxTimerSlack() {
        String path = "/proc/" + Os.getpid() + "/timerslack_ns";
        try {
            FileDescriptor fd = Os.open(path, OsConstants.O_WRONLY, 0);
            try {
                byte[] want = "1000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                Os.write(fd, want, 0, want.length);
            } finally {
                Os.close(fd);
            }
            System.out.println("awdl-data: timerslack=1000ns");
        } catch (Exception e) {
            System.out.println("awdl-data: timerslack unset (" + e + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        // Before anything else, because every deadline in the send loop is measured in
        // fractions of a millisecond and the kernel rounds all of them up by the timer
        // slack. See relaxTimerSlack.
        relaxTimerSlack();
        String iface = args.length > 0 ? args[0] : "wondertap0";
        // Zero or negative means "until killed": the node runs as a service, and a
        // deadline is a property of a measurement, not of a link.
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        // The app starts this as its own root process, so the native library cannot be
        // found from a Context -- the path is the eighth argument.
        AwdlInj.use(args.length > 7 ? args[7] : null);
        String dstMac = args.length > 2 && !args[2].isEmpty() ? args[2] : MDNS_MAC;
        String follow = args.length > 3 ? args[3] : "auto";

        // The tenth argument is the pid of the app that started this node; naming it is
        // what makes the node leave when the app does. A node whose app is already gone
        // stops here, before it builds anything. See {@link #watchApp}.
        if (!watchApp(args.length > 9 ? args[9] : "")) {
            System.out.println("VERDICT orphan");
            return;
        }

        byte[] own = macOf(iface);
        if (own == null) {
            System.out.println("awdl-data: no MAC for " + iface + " (does the vif exist?)");
            System.out.println("VERDICT no-vif");
            return;
        }
        System.out.println("awdl-data: iface=" + iface + " own=" + mac(own)
                + " dst=" + dstMac + " follow=" + follow);

        // The name asked about is a parameter because the phone publishes some services
        // and not others: if it answers for _appsvcprepair but not for _airdrop, then mDNS
        // over AWDL works and only the AirDrop service is absent -- a different problem
        // from "the phone ignores our queries", and the two have nothing in common.
        String[] qname = labels(args.length > 5 ? args[5] : "_airdrop._tcp.local");
        byte[] dns = dnsQuery(qname, DNS_TYPE_PTR, 1);
        System.out.println("awdl-data: dns " + dns.length + "B for " + join(qname, "."));
        byte[] src6 = linkLocal(own);
        System.out.println("awdl-data: src " + ipv6(src6, 0));
        byte[] packet = ipv6Udp(src6, mdnsGroup(), 5353, 5353, dns);
        System.out.println("awdl-data: query " + ipv6(src6, 0) + " > ff02::fb/5353, "
                + dns.length + "B dns in " + packet.length + "B ipv6");
        AwdlFrame awdl = new AwdlFrame(own, "Android", 0xa2, 2, 149,
                System.nanoTime() / 1000L);
        // Published before anything reads the tuning file, because two of its keys rewrite
        // the election and the first tick of the loop is already a tick too late for that:
        // the peer's frames start arriving while the file is still one second away.
        awdlRef = awdl;

        byte[] dst = macOfHex(dstMac);
        if (dst == null) {
            System.out.println("awdl-data: bad dst mac " + dstMac);
            System.out.println("VERDICT bad-dst");
            return;
        }
        // The unicast probes are not built here any more. They are built against the
        // address the last action frame carried, because the one on the command line is
        // frequently stale -- see Aim.
        int[] ports = ports(args.length > 4 ? args[4] : "");

        int injFd = AwdlInj.open(iface);
        if (injFd < 0) {
            System.out.println("awdl-data: inject open failed: "
                    + AwdlInj.strerror(-injFd));
            System.out.println("VERDICT no-tx-socket");
            return;
        }

        FileDescriptor rxFd = openReceiver(iface);

        // A seventh argument names a tun to carry the kernel's own IPv6. The relay is
        // not a second protocol: the same framing and the same receive path, with the
        // packet boundary moved from this file to the kernel. "none" keeps the pure
        // probe, which is what every measurement up to here was taken with.
        int tunFd = -1;
        if (args.length > 6 && !"none".equals(args[6])) {
            String tunName = args[6];
            tunFd = AwdlTun.open(tunName);
            if (tunFd < 0) {
                System.out.println("awdl-data: tun open failed: "
                        + AwdlInj.strerror(-tunFd));
            } else {
                byte[] ourLink = linkLocal(own);
                // The ninth argument is the app's uid, and it is required: the route the
                // tun needs lives in a table this Android reaches only through a policy
                // rule, and a rule has to name who it applies to. This process is root,
                // so it cannot work that uid out for itself.
                int appUid = -1;
                if (args.length > 8) {
                    try {
                        appUid = Integer.parseInt(args[8].trim());
                    } catch (NumberFormatException bad) {
                        // Left at -1: only the policy rule is skipped. The tun still
                        // carries the probe, which is how it was measured.
                    }
                }
                int cfg = AwdlTun.configure(tunName, ipv6(ourLink, 0), appUid);
                System.out.println("awdl-data: tun " + tunName + " fd=" + tunFd
                        + " addr=" + ipv6(ourLink, 0) + " uid=" + appUid
                        + " configure=" + cfg);
            }
        }

        try {
            run(awdl, own, dst, packet, src6, ports, qname, follow, injFd, rxFd, seconds,
                    tunFd);
        } finally {
            AwdlInj.close(injFd);
            if (tunFd >= 0) {
                AwdlInj.close(tunFd);
            }
            try {
                Os.close(rxFd);
            } catch (ErrnoException ignored) {
                // Shutting down.
            }
        }
    }

    /** Queue depth for the report, read under the same lock the writers take. */
    private static int outboundSize(ArrayDeque<byte[]> queue) {
        synchronized (queue) {
            return queue.size();
        }
    }

    private static void run(AwdlFrame awdl, byte[] own, byte[] dst, byte[] packet,
                            byte[] src6, int[] ports, String[] qname, String follow, int injFd,
                            FileDescriptor rxFd, int seconds, int tunFd)
            throws ErrnoException {
        byte[] buf = new byte[8192];
        long deadline = seconds > 0
                ? System.nanoTime() + seconds * 1_000_000_000L
                : Long.MAX_VALUE;
        long nextSend = System.nanoTime();
        // When the next probe may go out. Kept apart from nextSend because the two answer
        // different questions: nextSend is when to look at the kernel again, this is when
        // it is worth spending a window on a query nobody asked for.
        long nextProbe = System.nanoTime();
        // Ticks that found nothing to carry and left without transmitting. If this number
        // is small while a transfer is running, the loop is not looking at the kernel
        // often enough and the idle interval is still costing throughput.
        long idlePolls = 0;
        // Turns that reached the send stage inside a quiet window and went back to the
        // receiver instead; see listenNs. Counted so a run can say whether the window was
        // actually taken or whether listen_ms was set on a node that never saw a handshake.
        long quietPolls = 0;
        long nextReport = System.nanoTime() + 5_000_000_000L;
        // Rates are per report interval, not from the start of the run. A transfer that
        // moves a megabyte in its first five seconds and then nothing for thirty averages
        // to a number that describes neither half, and the second half is the failure.
        long prevReportAt = System.nanoTime();
        long prevTxBytes = 0;
        long prevRxBytes = 0;
        // A node that only sends data is invisible: the phone's peer table is built from
        // PSF and MIF, and its data path drops frames from addresses it has never seen.
        // So this process announces itself the same way AwdlTx does -- a PSF every
        // 110 TU and a MIF in the middle of every slot -- and carries data alongside.
        long nextPsf = System.nanoTime();
        long nextMif = System.nanoTime() + AwdlFrame.SLOT_US * 1000L / 2;

        // The instant the guard is aiming at, kept as an absolute deadline rather than a
        // duration. Aiming again from the top of the loop is what made a burst impossible:
        // an arriving frame cut the wait short, the loop went round, and the next
        // edgeWaitUs named the boundary after the one it had just given up on. The peer's
        // ACKs arrive faster than a slot, so every send waited three or four slots and
        // the air carried a fifth of what the link could.
        long edgeDeadlineUs = 0;
        // Which slot the last frame left in, and how many have left in it. A burst is
        // only a burst inside one window; a different slot index starts the count again.
        int lastSendSlot = -1;
        int windowSent = 0;
        long lastSendUs = 0;
        // Turns that handed the driver more than one frame, and how many extra frames they
        // carried. Printed as {@code bat=<turns>+<extra>} so the fill can be read from the
        // report instead of inferred from a byte rate: extra/turns is the group depth the
        // link actually reached, and it should sit near {@link #aggDepth} minus one.
        int batchTurns = 0;
        int batchExtra = 0;
        // Whether the radio has been silent for long enough to be worth a line, and what
        // the kernel's queue looked like while it was. A transfer that stalls nineteen
        // percent of its life has to be told apart from a transfer that is merely being
        // paced, and the one number that does it is whether the outbound queue had
        // anything in it at the time: a full queue means this loop withheld the frames,
        // an empty one means the kernel had nothing to give and the wait is upstream of
        // here entirely. Reading the byte rate cannot answer that -- a rate is the same
        // whether nothing was available or something available was not sent.
        boolean quiet = false;
        long quietSinceUs = 0;
        long quietMaxQueue = 0;
        int quietReports = 0;
        long quietIdleFirst = 0;
        int quietEdgeFirst = 0;
        // Frames sent per slot index across the whole run, sixteen entries, never reset.
        //
        // <p>The burst histogram cannot answer the question that matters here, because it
        // is keyed by how many frames a window carried, so every window that never opened
        // folds into its zero bin along with every window that opened and was skipped.
        // This one is keyed by where in the sequence the window sits, so a slot the
        // schedule never lets us use shows up as one small number beside fifteen large
        // ones instead of being averaged away. The distinction matters because "the radio
        // is slow" and "one of sixteen slots is on another channel" want opposite
        // responses and produce the same byte rate.
        final int[] slotFrames = new int[16];
        // The peer's channel sequence, printed once when it is first adopted. That is the
        // schedule we obey; reading it out of the log beats reconstructing it from which
        // slots happened to stay silent.
        boolean slotsAnnounced = false;

        int sent = 0;
        int synSent = 0;
        long nextSynRetry = 0;
        // Which diagnosis probe goes out on the next idle tick. Its own counter, because
        // the rotation used to be derived from {@link #sent} -- and that number also
        // advances on every relayed kernel packet, so with a live link the parity is
        // whatever the traffic made it rather than a round robin. The run before this one
        // sent one unicast mDNS query in two hundred and thirty-nine seconds for exactly
        // that reason, and one sample is not a test.
        int probeSeq = 0;
        // The interval the next retry will use. Grows towards SYN_RETRY_MAX_NS and stays
        // there; kept as state rather than recomputed from synSent because the growth is
        // per retry, not a function of how many have been sent.
        long synRetryNs = SYN_RETRY_NS;
        int txErrors = 0;
        int control = 0;
        int frames = 0;
        int dataFrames = 0;
        int duplicates = 0;
        // Frames from the peer that arrived and were not relayed. Kept separate from
        // duplicates because the two would otherwise be read as the same fact: one is a
        // second copy of a frame this tool sent, the other is an answer that was thrown
        // away, and a relay run is judged on which of those happened.
        int peerDupes = 0;
        int replies = 0;
        // Target moves taken, and moves refused once the budget or the proof ran out.
        // Two counters rather than one because they answer different questions: aims
        // is how much of the driver's table this run spent, aimRefused is how often the
        // peer's address churned under a target that was already answering.
        int aims = 0;
        int aimRefused = 0;
        int anchored = 0;
        // How far off our slot boundary was from the peer's, in TU, at the last anchor.
        //
        // AwdlTx has printed this from its first run and this probe never did, which is
        // how "anchored=857" came to be read as "synchronised" when all it says is
        // "performed 857 times". The two are not the same claim: adopting a phase that is
        // wrong by more than 16 TU -- the length of one availability window -- puts every
        // transmission in the guard band between windows, and the only symptom is frames
        // that leave the antenna perfectly formed and are answered by nobody.
        int phaseErrTu = Integer.MIN_VALUE;
        // The best |err| this window has offered, and the window's own clock. Kept apart
        // from phaseErrTu because they answer different questions: phaseErrTu is what the
        // last announcement said, bestAnchorTu is how well the phase has ever been known
        // in the current window -- and only the second one predicts whether frames land.
        int bestAnchorTu = Integer.MAX_VALUE;
        long anchorWindow = 0;
        int anchorSamples = 0;
        // Samples turned away for reading a whole slot of error, and how many of those
        // came in a row. The running total is the evidence that the guard is doing
        // something at all; the streak is what stops it from becoming a lock. Anchors
        // adopted still land in {@code anchored}, so the two numbers split every
        // announcement into "used for the clock" and "used only for its error".
        int anchorRejects = 0;
        int anchorRefused = 0;
        // Edge waits that a frame arriving cut short. Before the wait became a poll this
        // number could not exist, because the wait was a sleep and a frame landing inside
        // it was simply read late; every one of those frames carried its lateness into
        // the clock as a phase error. Counted so the size of the problem is on the record
        // rather than inferred from how badly the link behaved.
        int edgeCut = 0;
        // Frames that left without waiting for a boundary because the window they were
        // aimed at was still open when the packet arrived. This is the number that says
        // whether the relay is pacing itself by the peer's availability or by the slot
        // clock: if it stays near zero the loop is still paying a whole slot for every
        // round trip, and a round trip is two kilobytes, which is the whole reason the
        // video stalled. Counted separately from edgeCut because the two are alternatives
        // -- a wait either happened and was cut short, or was not needed at all.
        int edgeOpen = 0;
        // How long the blocking read at the head of each turn actually blocked, summed
        // and counted. This is the loop's real clock, and it is not the number the tuning
        // file asks for: SO_RCVTIMEO says 1 ms, but the kernel rounds the underlying
        // deadline up by the process's timer slack, so the value written and the value
        // run at are two different numbers. Without this reading the only visible symptom
        // was 26 turns a second, which looks like a busy radio and was a sleeping loop.
        long readNsSum = 0;
        int readTurns = 0;
        // Slots that were skipped because the peer spends them on another channel, counted
        // once per slot rather than once per turn of the loop. It used to count turns, and
        // with a 250 ms send interval that read plausibly; the moment the interval after a
        // relayed packet dropped to 20 ms it would have counted the same slot forty times
        // and reported a link that spends all its time off channel. The number is only
        // worth reading if it is a number of slots.
        int offChannel = 0;
        int lastOffSlot = -1;
        // Where in the slot our frames actually leave, in TU since a boundary.
        //
        // The guard aims for three TU past a boundary and the peer listens for sixteen
        // of the sixty-four, so the aim either puts every frame inside that window or it
        // does not, and the two look identical from the outside: a link that answers
        // one frame in four. A single averaged number cannot tell them apart, so this is
        // a range plus an eight-bucket histogram, cleared with each report -- the counts
        // are per five seconds, which is the only scale on which a change is visible.
        int phCount = 0;
        int phMin = Integer.MAX_VALUE;
        int phMax = Integer.MIN_VALUE;
        long phSum = 0;
        int[] phHist = new int[8];
        // The same reading for frames that arrive from the peer. If the peer transmits at
        // a fixed phase of the slot, that phase is where it is awake, and anywhere else
        // is where it is not -- which is the measurement the transmit sweep would
        // otherwise have to make by hand, one offset per run.
        int rxCount = 0;
        int rxMin = Integer.MAX_VALUE;
        int rxMax = Integer.MIN_VALUE;
        int[] rxHist = new int[8];
        // The offset the two histograms above were filled under. A swept offset that
        // changes mid-window makes them a mixture of two aims, which is a number that
        // describes neither, so a change clears them and says so on the log.
        int poffApplied = phaseOffsetTu();
        // Frame number to the microsecond it was handed to the driver. Our own frames
        // come back through the same receive path as anybody else's, so this is the only
        // way to date the interval between submitting a frame and hearing it leave.
        Map<Integer, Long> txAt = new LinkedHashMap<>();
        int echoCount = 0;
        long echoSumUs = 0;
        long echoMinUs = Long.MAX_VALUE;
        long echoMaxUs = 0;
        // Whether the peer is still there, which is a different question from whether it
        // answered. Every summary printed so far has been read as "the phone ignored us"
        // while nothing in it could tell that apart from "the phone stopped transmitting",
        // and the two have nothing in common: one is a receive path to repair, the other
        // is an availability window to stay inside. Counted rather than printed, because
        // the printer dedupes action frames by TLV content and a phone repeating the same
        // MIF a thousand times therefore appears exactly once.
        int peerActions = 0;
        long lastPeerAction = 0;
        // Every action frame from somebody else, whoever it is, plus the address of the
        // most recent one. peerActions answers "is the peer I am aiming at still there";
        // this answers "is anything on the air at all". A phone that came back with a new
        // randomised address leaves peerActions flat while this climbs, and that reading
        // -- present but moved -- is the one has been mistaken for absence twice.
        int foreignActions = 0;
        long lastForeignAction = 0;
        String lastForeignSa = "?";
        int foreignData = 0;
        // Data frames from someone else whose third address is not the AWDL BSSID.
        // Every frame this run cares about should be one or the other, so these are
        // frames we have no model of -- and the only way to get a model is to print
        // the bytes instead of guessing at them.
        int oddData = 0;
        int overlong = 0;
        int relayedTx = 0;
        int relayedRx = 0;
        // The peer's real 802.11 address, keyed by the IPv6 address it spoke from.
        //
        // <p>Outbound frames used to take their destination from receiverOf(), which
        // reverses the modified-EUI-64 rule and so turns fe80::c45b:f9ff:fea2:c4b6 into
        // c6:5b:f9:a2:c4:b6. That rule holds for this device -- its own address was built
        // from its own MAC -- and it does not hold for the phone. On one capture the
        // phone's data frames carried sa c6:5b:3e:fc:65:9b while its address implied
        // c6:5b:f9:a2:c4:b6: AWDL assigns the IPv6 interface identifier independently of
        // the data-path MAC, so the address is not a picture of the hardware. Every frame
        // aimed at the implied MAC was therefore transmitted, acknowledged by the radio
        // for having been transmitted, and heard by nobody -- which is exactly what
        // ETIMEDOUT after 33.7 s looks like from inside the kernel.
        //
        // <p>Inbound frames carry the address for free: they arrive with sa set to the
        // MAC the peer actually uses, and with an IPv6 header naming where they came
        // from. Recording that pair is the only way this side can learn an address it
        // cannot compute. Bounded because a peer that rotates its address every few
        // seconds would otherwise grow this table for as long as the link is up.
        final java.util.Map<String, byte[]> peerMacs = new java.util.HashMap<>();
        int relayErrors = 0;
        final int[] queueDrops = new int[1];
        // Packets the tun handed over that no frame can carry. A count here means
        // segmentation offload came back on, and each one costs a retransmission
        // timer: it is the difference between a send that stalls for four seconds
        // and one that does not, so it gets its own counter instead of hiding
        // inside txErrors as an anonymous EMSGSIZE.
        final int[] overMtu = new int[1];
        // RSTs from the local stack aimed at the port this tool fabricates SYNs from.
        // Counted and shown, not relayed: see isKernelRefusal.
        final int[] rstRefused = new int[1];
        // Answers to the SYNs this tool fabricated. Each one is proof the frame crossed
        // the air and the phone's stack accepted it -- a cleaner signal than the RST the
        // kernel used to send back, because it says nothing about the kernel at all.
        int spoofHits = 0;
        int relayPackets = 0;
        // Control-connection packets, counted by shape in each direction: [out][kind] and
        // [in][kind], ordered as CTL_KINDS. Unbudgeted, unlike the lines below, because
        // after the print budget is spent these counts are the only remaining answer to
        // "did our kernel ever send the SYN-ACK the phone's ClientHello proves it got".
        int[] ctlTx = new int[CTL_KINDS.length];
        int[] ctlRx = new int[CTL_KINDS.length];
        int ctlTxLines = 0;
        int ctlRxLines = 0;
        // Inbound control packets whose checksum did not sum to zero. A non-zero value
        // here is a packet the local kernel will drop below TCP without telling anyone,
        // which is the only failure mode left that explains a connection being accepted
        // while the handshake on it never sees a byte.
        int ctlBadCks = 0;
        // What the link is actually moving, in payload bytes rather than frames. A frame
        // count says the relay is alive; only a rate says whether it is a transfer. Both
        // directions, because an upload and a download stall for different reasons and
        // the log could not previously tell which one had been measured.
        long relayTxBytes = 0;
        long relayRxBytes = 0;
        // ---- The acknowledgement loop, timed where it can be seen ----
        //
        // When the peer uploads, the transfer rate is the peer's congestion window over
        // the round trip, and the round trip is mostly this side: a frame arrives, the
        // kernel answers it, and the answer has to be on the air before the peer's window
        // opens again. Two numbers bound that interval and neither existed in any earlier
        // report, so every conclusion about "the ACK path" until now was an inference
        // from a byte rate:
        //
        //   atu  the gap between handing an inbound segment to the kernel and putting the
        //        acknowledgement for it on the air. Zero to a few milliseconds is a loop
        //        that is not in the way; twenty to forty is the kernel's delayed-ack timer
        //        and belongs to a socket option, not to this loop.
        //   wnd  the receive window this side advertises. A peer that is flow-controlled
        //        sends one window per round trip no matter how large its congestion
        //        window has grown, and the symptom is a steady rate with no retransmission
        //        at all -- which is exactly the shape that was being read as a small
        //        congestion window on the phone.
        long lastSegUs = 0;
        long segsIn = 0;
        long acksOut = 0;
        long atuN = 0;
        long atuSumUs = 0;
        long atuMaxUs = 0;
        int[] atuHist = new int[6];
        long winMin = Long.MAX_VALUE;
        long winSum = 0;
        long winN = 0;
        int wscaleOwn = -1;
        long prevSegsIn = 0;
        long prevAcksOut = 0;
        // Where the interval's frame count started. Frames per slot is the only number
        // that separates "the slots are too few" from "the slots are too empty", and the
        // report could not tell them apart before: a rate in kilobytes per second is the
        // product of the two and says nothing about which one moved.
        int prevRelayPackets = 0;
        // The driver's aggregation counters as they were at the previous report. Null when
        // the parameter file is not readable, which is also how "this run had no evidence
        // either way" is distinguished from "the fill was zero".
        long[] prevAgg = aggStats();
        // Turns per availability window, indexed by count: [0] is unused, [1] is a window
        // that carried one turn, [16] is sixteen or more. This is the number that says
        // whether a burst is happening at all -- a run whose bins peak at 1 is a run where
        // every turn still paid a full slot, whatever the interval constant said.
        //
        // Seventeen bins rather than nine, and the reason is a misreading worth recording:
        // the old catch-all bin could not be told apart from a window that was merely
        // full, so "everything lands in [8]" read as "eight frames a window" when the
        // ceiling was sixteen. Nothing here is a frame count anyway -- a turn hands the
        // driver a whole group -- which is why the report now also prints frames per slot
        // and the two numbers only make sense together.
        int[] burstHist = new int[17];
        // The histogram above is shape, not count. Binning is four turns wide and the top
        // bin is a catch-all again -- sixteen turns is not a ceiling this loop actually
        // obeys, and a run with thirty turns a window would have landed in the same bin as
        // one with sixteen, which reads as "the ceiling is binding" when nothing is. The
        // exact numbers are accumulated at the close of every window and printed beside
        // it, so the shape can be trusted for the tail and the count is never inferred.
        int spanSlots = 0;
        long spanTurns = 0;
        int spanMaxTurns = 0;
        // Slots jumped over between two consecutive turns. One per sequence is the
        // channel-6 slot and is expected; anything above that is a slot the loop should
        // have used and did not, which is the only way a whole slot can go missing
        // without appearing in any per-slot average.
        long skippedSlots = 0;
        // Where in its slot each window stopped, summed per slot index. The counts alone
        // show the tail slots carrying a fifth less than the head ones and cannot say why,
        // because "fewer turns" is the same number whether the source ran dry or the guard
        // cut the window short. The phase separates them: a closing phase near
        // {@link #windowLastTu} means the tail slots were as full as the head ones and the
        // deficit is elsewhere, while a lower phase means those windows were abandoned with
        // air still on the clock.
        int[] slotClosePh = new int[16];
        int[] slotCloseN = new int[16];
        // The one measurement this run exists to make: the kernel's SYN and the aim's
        // SYN go out through the same builder to the same receiver, and only one of them
        // is ever answered. If the two payloads differ, the difference is in a byte; if
        // they do not, it is not in the packet at all.
        int relaySynDumps = 0;
        // Packets from the kernel waiting for a legal transmit moment. Bounded because a
        // backlog older than the peer's window is worse than a drop: the kernel's own
        // retransmission will be fresher than anything that has been sitting here.
        ArrayDeque<byte[]> outbound = new ArrayDeque<>();
        // A relayed packet is sent more than once, because one frame is not a delivery.
        // The driver acked 59% of this run's frames (status0 +68, status3 +99), and the
        // frames it did not ack were lost without a trace -- which is fine for a probe
        // that repeats anyway and fatal for a TCP handshake, whose retry ladder is
        // 1, 3, 7, 15, 31 seconds. Three copies turn a 59% chance into 93%, and at four
        // packets a second that costs under a second of latency instead of fifteen.
        byte[] resend = null;
        byte[] resendTo = null;
        int resendLeft = 0;
        long resendDue = 0;
        // One transmission is heard by every vif that is tuned to the channel, and the
        // sequence control is identical across those copies -- so the same (transmitter,
        // sequence) meeting twice within a few microseconds is one frame, not two. That is
        // the only question this table answers, and it used to answer a different one: it
        // remembered the last 128 keys for the whole run. A peer that reuses a sequence
        // number therefore had the second and every later frame silently counted as a
        // duplicate. A pure TCP ACK is exactly that shape -- it carries no payload, so
        // nothing about it advances -- and a run whose peer "answered the SYN-ACK and then
        // nothing else" is what that bug looks like from the outside. The window is wider
        // than the gap between copies and far narrower than the gap between two frames
        // that mean different things.
        Map<String, Long> recent = new LinkedHashMap<>();
        Set<String> senders = new LinkedHashSet<>();
        // Who transmitted on this channel at all, by kind. A run that ends in silence
        // cannot tell "the peer said nothing" from "we were listening to the wrong
        // thing", and this census is the difference: a phone that is awake on AWDL
        // leaves beacons and action frames even when it sends no data.
        Map<String, Integer> census = new LinkedHashMap<>();
        // Distinct TLV sets seen from the peer. The phone repeats its MIF every slot,
        // so without this each one would print and bury the interesting one.
        Set<String> seenAdv = new LinkedHashSet<>();
        String peer = "auto".equals(follow) ? null : follow;
        boolean following = peer != null || "auto".equals(follow);
        // Our own PSFs come back on the receive path, and treating one of them as the
        // peer is worse than hearing nothing: the node locks onto its own clock, believes
        // it is synchronised, and never adopts the phone's schedule at all.
        String ownMac = mac(own);
        // What every unicast frame is addressed to, and the probes shaped for that
        // address. Both follow the peer: AWDL-16 guards unicast and multicast differently,
        // and the guard only applies to the address actually used, so this cannot be a
        // decision taken once at startup.
        byte[] target = dst;
        Aim aim = (target[0] & 1) == 0 ? new Aim(src6, target, ports, qname) : null;
        if (aim != null) {
            System.out.println("awdl-data: syn " + ipv6(src6, 0) + " > "
                    + ipv6(linkLocal(target), 0) + ":{" + join(ports) + "} tcp "
                    + aim.syns[0].length + "B");
        }

        if (tunFd >= 0) {
            // Reading the tun on the scheduling thread would put a blocking call in the
            // one place that cannot afford one, so the kernel's packets are harvested
            // here and handed to the loop that already knows when the radio is awake.
            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] pkt = new byte[2048];
                    while (!Thread.currentThread().isInterrupted()) {
                        int n = AwdlTun.read(tunFd, pkt);
                        if (n > 0) {
                            byte[] one = new byte[n];
                            System.arraycopy(pkt, 0, one, 0, n);
                            if (n > MAX_TUN_PACKET) {
                                // Bigger than any frame the driver will take, so it is
                                // leaving here rather than a millisecond later behind an
                                // EMSGSIZE that says nothing about why. Reported, not
                                // counted as an ordinary failure: the cause is upstream
                                // of this loop and the fix is at the tun, not the queue.
                                int seen;
                                synchronized (overMtu) {
                                    seen = ++overMtu[0];
                                }
                                if (seen <= 3) {
                                    System.out.println("  [" + stamp() + "] tun packet "
                                            + n + "B is too long for the air (limit "
                                            + MAX_TUN_PACKET + "B); segmentation "
                                            + "offload is on: " + describeIpv6(one, 0, n));
                                }
                                continue;
                            }
                            if (isKernelRefusal(one, n)) {
                                // The phone answered our SYN and the kernel is declining
                                // the connection on our behalf. Relaying this is what
                                // closed every previous run's handshake, so it stops here
                                // -- before the queue, so the tick it would have taken
                                // goes to a packet that can still accomplish something.
                                int seen;
                                synchronized (rstRefused) {
                                    seen = ++rstRefused[0];
                                }
                                if (seen <= 3) {
                                    System.out.println("  [" + stamp() + "] rst held back "
                                            + describeIpv6(one, 0, n));
                                }
                                continue;
                            }
                            synchronized (outbound) {
                                if (outbound.size() >= queuePkts) {
                                    // The oldest packet goes, not the newest, and the choice
                                    // is what makes the loss survivable at all. A stream that
                                    // is missing its oldest queued segment still has every
                                    // later one behind it, and those later ones are what
                                    // produce the duplicate acknowledgements that make the
                                    // sender retransmit at once. Dropping the newest would
                                    // leave the peer with nothing to complain about, so the
                                    // sender would hear silence until its timer fired.
                                    outbound.removeFirst();
                                    queueDrops[0]++;
                                }
                                outbound.addLast(one);
                            }
                        } else if (n < 0) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                return;
                            }
                        } else {
                            try {
                                Thread.sleep(2);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                }
            }, "awdl-tun-in");
            pump.setDaemon(true);
            pump.start();
        }

        while (System.nanoTime() < deadline) {
            long readBegan = System.nanoTime();
            int n;
            try {
                n = Os.read(rxFd, buf, 0, buf.length);
            } catch (InterruptedIOException timeout) {
                n = 0;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EAGAIN) {
                    throw e;
                }
                n = 0;
            }
            readNsSum += System.nanoTime() - readBegan;
            readTurns++;

            if (n > 0) {
                // A read that exactly fills the buffer is not a frame that happens to be
                // that long: the vendor node copies the raw MPDU and the copy is shaped by
                // the buffer it was handed. Three such reads in one run had radiotap
                // headers claiming 4150, 1105 and 6144 with a length of 8192, and every
                // field parsed out of them was noise -- noise that was counted as a
                // sender and printed as someone else's data frame.
                if (n == buf.length) {
                    overlong++;
                    continue;
                }
                frames++;
                AwdlProbe.Frame f = AwdlProbe.Frame.parse(buf, n);
                if (f != null) {
                    census.merge(censusKey(f, ownMac), 1, Integer::sum);
                    if (f.mgmtType == FC_TYPE_MGMT && f.subtype == FC_SUBTYPE_ACTION
                            && f.category == 0x7f && f.ouiType == 0x08
                            && !ownMac.equalsIgnoreCase(f.sa)) {
                        foreignActions++;
                        lastForeignAction = System.nanoTime();
                        lastForeignSa = f.sa;
                        // Adopting the peer's slot phase is what puts our frames inside
                        // its availability windows; without it we are broadcasting into
                        // a receiver that is listening somewhere else in time.
                        if (following && (peer == null || peer.equals(f.sa))
                                && f.nextTu >= 0 && f.awCounter >= 0
                                && f.master != null && !f.master.equals("?")) {
                            if (peer == null) {
                                peer = f.sa;
                                System.out.println("awdl-data: following " + peer);
                            }
                            peerActions++;
                            lastPeerAction = System.nanoTime();
                            // Where in the slot the peer is on the air. A frame it sent is
                            // a frame during which its radio was on and its clock running,
                            // and an AWDL device receives in the same window it transmits
                            // in, so the phase of these arrivals is the slot map of the
                            // only place worth aiming at. Read per frame and bucketed,
                            // because the anchor already reduces these to a single number
                            // for the clock and a single number cannot show a window.
                            int rxPh = phaseTu(awdl, System.nanoTime() / 1000L);
                            rxCount++;
                            if (rxPh < rxMin) {
                                rxMin = rxPh;
                            }
                            if (rxPh > rxMax) {
                                rxMax = rxPh;
                            }
                            rxHist[Math.min(7, rxPh >> 3)]++;
                            // The address in the frame beats the address on the command
                            // line, always: this phone answered as a6:d7:... in one capture
                            // and 7a:f0:... in the next, and every probe aimed at the old
                            // one kept leaving the antenna perfectly formed and reached
                            // nobody. Re-aiming is therefore not an optimisation -- it is
                            // the difference between asking a question and asking the air.
                            //
                            // What that first version got wrong was the price. A move is
                            // billed to the driver's eight-slot target table and the bill
                            // is never refunded (see MAX_AIMS), so following every change
                            // of an address that has already answered is how the link went
                            // permanently silent: eight runs, eight addresses, no slots
                            // left, and every frame after that transmitted to nobody.
                            byte[] learned = macOfHex(f.sa);
                            if (learned != null && !sameMac(learned, target)) {
                                if (driverHolds(learned)) {
                                    // The driver already has a slot for this address, so
                                    // moving onto it is free -- and a frame that names the
                                    // address is better authority on where the peer is than
                                    // anything decided before the frame arrived.
                                    target = learned;
                                    aim = new Aim(src6, target, ports, qname);
                                    System.out.println("awdl-data: aim "
                                            + ipv6(linkLocal(target), 0)
                                            + " (peer moved onto a slot the driver holds)");
                                } else if (replies > 0 || aims >= MAX_AIMS) {
                                    if (aimRefused++ == 0) {
                                        System.out.println("awdl-data: keeping " + mac(target)
                                                + ", not following " + f.sa
                                                + " (replies=" + replies + " aims=" + aims
                                                + "; a move costs a driver target slot)");
                                    }
                                } else {
                                    target = learned;
                                    aim = new Aim(src6, target, ports, qname);
                                    aims++;
                                    System.out.println("awdl-data: aim "
                                            + ipv6(linkLocal(target), 0)
                                            + " (peer moved, aim " + aims + "/" + MAX_AIMS + ")");
                                }
                            }
                                byte[] master = macOfHex(f.master);
                                if (master != null) {
                                    // Following is a decision now, not a reflex. The frame
                                    // names the master its sender believes in, and copying
                                    // that name unconditionally is what kept this node a
                                    // follower of a peer it could outrank: AWDL-9 ranks by
                                    // metric first, and at INIT_METRIC it wins against the
                                    // 538 the iPhone announces. A node that has won the
                                    // comparison must not then advertise the loser as its
                                    // master -- that is a follower's frame carrying a
                                    // winner's rank, which the peer cannot reconcile and
                                    // resolves by ignoring us.
                                    //
                                    // A tuning-file claim of mastery still overrides both
                                    // sides of the comparison: master_take exists to force
                                    // the state, not to guess at it.
                                    //
                                    // The anchor below still runs either way, and that is
                                    // deliberate: it is the clock, not the rank. Staying on
                                    // the peer's superframe is what keeps two ends able to
                                    // hear each other while the election sorts itself out.
                                    if (!masterTake && !awdl.outranks(f.masterMetric, master,
                                            f.masterCounter, Math.max(0, f.height))) {
                                        awdl.follow(master, Math.max(1, f.height + 1),
                                                f.masterMetric, f.masterCounter);
                                    }
                                    // Measured before the anchor, not after: errorTu compares
                                    // the announcement against the clock that is about to be
                                    // replaced, so calling it afterwards would compare the new
                                    // clock with itself and read zero every time. AwdlTx's
                                    // onAction takes the same order for the same reason.
                                    long stampUs = System.nanoTime();
                                    int err = awdl.errorTu(stampUs / 1000L,
                                            f.nextTu, f.awCounter);
                                    phaseErrTu = err;
                                    // A new window forgets the previous best, because the
                                    // peer's clock is allowed to move and a best that can
                                    // never be beaten would stop following it.
                                    if (stampUs - anchorWindow > ANCHOR_WINDOW_NS) {
                                        anchorWindow = stampUs;
                                        bestAnchorTu = Integer.MAX_VALUE;
                                        anchorSamples = 0;
                                    }
                                    anchorSamples++;
                                    // Kept as a reading and not as a gate. err is the
                                    // difference between this frame's queue delay and the
                                    // last one's, so its spread is how stale a clock sample
                                    // can be -- and the smallest |err| in a window is the
                                    // floor that delay can reach. Anchoring on it instead of
                                    // on every frame was tried and rejected: it converges
                                    // only while each new sample waits less than the one it
                                    // replaced, and it cannot tell "less" from "later".
                                    if (Math.abs(err) < Math.abs(bestAnchorTu)) {
                                        bestAnchorTu = err;
                                    }
                                    // The one class of sample that is measured rather than
                                    // estimated, and therefore can be refused: a whole slot
                                    // of error says the frame was read a slot after it
                                    // arrived, which is this loop's own queue and not the
                                    // peer's clock. Adopting it moves the aim a slot off
                                    // the peer's window for as long as it takes the next
                                    // sample to undo it, and that half-cycle is exactly the
                                    // stall the transfer shows. See ANCHOR_REJECT_LIMIT for
                                    // why the refusal cannot be permanent.
                                    boolean wholeSlot = Math.abs(err) >= AwdlFrame.SLOT_TU / 2;
                                    if (wholeSlot && anchorRejects < ANCHOR_REJECT_LIMIT) {
                                        anchorRejects++;
                                        anchorRefused++;
                                    } else {
                                        anchorRejects = 0;
                                        awdl.anchor(stampUs / 1000L, f.nextTu, f.awCounter);
                                        anchored++;
                                    }
                                }
                        }
                        // A peer's channel sequence is evidence, not an order. It used to
                        // be copied unconditionally, which is how this node ended up
                        // announcing five slots of sixteen: the iPhone's map is its own,
                        // the copy told the iPhone where *we* are, and the peer's send was
                        // then aimed at slots we had just claimed to be absent from.
                        //
                        // The negotiation in AwdlFrame decides what survives: our own map
                        // when the election names us, the peer's when it does not, a
                        // three-frame confirmation before either changes, and a held
                        // interval so a map that moves cannot rewrite the schedule at
                        // frame rate. A tuning file that says seq_own still forces the
                        // whole sixteen, because that is what the key means.
                        if (f.seqSlots.length == AwdlFrame.SLOTS && peer != null
                                && peer.equals(f.sa)) {
                            String verdict = "forced";
                            if (!keepOwnSequence && !masterTake) {
                                verdict = awdl.offerSequence(macOfHex(f.sa), f.seqSlots,
                                        Math.max(0, f.height), System.nanoTime() / 1000L);
                            }
                            // Printed once, because the map is what every later slot
                            // decision is measured against. A schedule that names a
                            // channel other than ours in one of sixteen slots is a
                            // permanent sixth of the link gone, and until it is on the
                            // page each run rediscovers it as "the transfer pauses once a
                            // second" and looks for a cause in the wrong module.
                            if (!slotsAnnounced) {
                                slotsAnnounced = true;
                                StringBuilder map = new StringBuilder();
                                for (int i = 0; i < AwdlFrame.SLOTS; i++) {
                                    map.append(i == 0 ? "" : ",").append(f.seqSlots[i]);
                                }
                                System.out.println("  [" + stamp() + "] peer sequence slots="
                                        + map + " own channel=" + awdl.channel()
                                        + " elect=" + (awdl.isMaster() ? "self" : "peer")
                                        + "/h" + awdl.height() + "/m" + awdl.selfMetric()
                                        + " ours=" + awdl.sequenceSummary()
                                        + " verdict=" + verdict);
                            }
                        }
                        // SPEC MDNS-2 carries a node's DNS-SD records in the Service
                        // Response TLV of its action frames, so a peer that never answers
                        // mDNS may still be publishing its port and host name here --
                        // and this is the only place those bytes are seen at all.
                        //
                        // Keyed on the *shape* of the TLV list rather than on the whole
                        // frame, and no longer capped at eight. The old key was the full
                        // TLV string, which changes whenever a counter inside Data Path
                        // State ticks -- so eight lines went by in the first seconds of a
                        // run, all of them the same shape, the cap was spent, and the one
                        // event this line exists to catch (the peer starting to advertise
                        // a service) would have been swallowed as the ninth. Keeping only
                        // the shape means every distinct set of TLV types is printed once,
                        // and the run that adds Service Response to that set is the run
                        // that answers the question this whole path has been asking.
                        //
                        // The bracketed hex dumps are stripped along with the lengths for
                        // the same reason: those bytes carry live counters, so leaving them
                        // in would make every frame look like a new shape and the cap would
                        // be spent again on one shape's worth of noise. The values are
                        // still printed -- just once per shape, on the line that announces
                        // it, which is where a changing counter is visible anyway.
                        if (!ownMac.equalsIgnoreCase(f.sa)
                                && seenAdv.add(f.tlvs.replaceAll("\\([0-9]+\\)", "")
                                        .replaceAll("\\[[0-9a-f]*\\]", ""))
                                && seenAdv.size() <= 32) {
                            System.out.println("  adv " + f);
                        }
                    } else if (f.mgmtType == FC_TYPE_DATA) {
                        // Classified by what the frame carries, not by the addresses it
                        // names. The AWDL group address in address 3 was the filter here
                        // until a run showed the phone's replies arriving with address 3
                        // holding something else entirely -- so the gate was rejecting
                        // the very frames the run existed to find, and the silence it
                        // produced was indistinguishable from a peer that never answered.
                        // The SNAP and the 0x0403 head cannot be misread that way.
                        String report = describeData(buf, n);
                        if (!report.contains("dataHead=403")
                                || !report.contains("ethertype=86dd")) {
                            // Not an AWDL-13 frame. If it came from somebody else it is
                            // still the only copy of that shape we will ever see, so
                            // print the bytes rather than guess at them.
                            if (!ownMac.equalsIgnoreCase(f.sa) && oddData < 4) {
                                oddData++;
                                int rtLen = (buf[2] & 0xff) | ((buf[3] & 0xff) << 8);
                                System.out.println("  odd data da=" + f.da + " sa=" + f.sa
                                        + " bssid=" + f.bssid + " rt=" + rtLen + " len=" + n
                                        + " " + report);
                                System.out.println("    "
                                        + hex(buf, rtLen, Math.min(rtLen + 72, n)));
                            }
                        } else if (!seenRecently(recent, segmentKey(buf, n, f))) {
                            // A frame this vif has not delivered before within the window
                            // that separates copies of one transmission.
                            dataFrames++;
                            senders.add(f.sa);
                            if (!ownMac.equalsIgnoreCase(f.sa)) {
                                foreignData++;
                                System.out.println("  [" + stamp() + "] data " + f.sa
                                        + " -> " + f.da
                                        + " bssid=" + f.bssid + " len=" + f.bodyLength
                                        + " " + report);
                                if (isReply(report)) {
                                    replies++;
                                }
                                // The other half of the relay. Everything above this line
                                // is the probe talking to itself; this is the first place
                                // where a packet from the peer is handed to something that
                                // can answer it. The length comes from the IPv6 header, not
                                // from the frame: 802.11 pads short frames out to its own
                                // minimum and the kernel would see the padding as payload.
                                if (tunFd >= 0) {
                                    int ip = ipv6At(buf, n);
                                    int total = ip < 0 ? 0 : ipv6Total(buf, ip, n);
                                    if (total > 0) {
                                        byte[] one = new byte[total];
                                        System.arraycopy(buf, ip, one, 0, total);
                                        // Its SYN-ACK names how much we may send; cap it
                                        // before the kernel reads the option.
                                        clampMss(one, total);
                                        // Learn where this peer actually lives before the
                                        // packet is filed. Nothing else on the link reports
                                        // the pair: mDNS gives an address and no MAC, the
                                        // frame gives a MAC and an address, and only held
                                        // together do they let an outbound packet be aimed
                                        // at something that will hear it. Filed even when
                                        // the packet is later held back as a spoofed reply,
                                        // because the address is true either way and this
                                        // is the only frame of that shape that arrives.
                                        byte[] spokeFrom = ipv6Source(one, total);
                                        byte[] spokeAs = macOfHex(f.sa);
                                        if (spokeFrom != null && spokeAs != null
                                                && !Arrays.equals(spokeAs, own)) {
                                            String key = hex(spokeFrom, 0, 16);
                                            if (peerMacs.size() < 32 || peerMacs.containsKey(key)) {
                                                peerMacs.put(key, spokeAs);
                                            }
                                        }
                                        if (isSpoofedReply(one, total)) {
                                            // The phone answered a SYN this tool built. There
                                            // is no socket on that port, so the kernel can
                                            // only answer it with a RST -- and relaying that
                                            // RST is what closed the half-open connection the
                                            // SYN had just opened, in every run so far. The
                                            // answer is the finding; it stops here.
                                            spoofHits++;
                                            if (spoofHits <= 12) {
                                                System.out.println("  [" + stamp()
                                                        + "] spoof hit #" + spoofHits + " "
                                                        + total + "B "
                                                        + describeIpv6(one, 0, total));
                                            }
                                        } else {
                                            int wrote2 = AwdlTun.write(tunFd, one, total);
                                            if (wrote2 < 0) {
                                                relayErrors++;
                                                if (relayErrors <= 3) {
                                                    System.out.println("  relay in failed: "
                                                            + AwdlInj.strerror(-wrote2));
                                                }
                                            } else {
                                                relayedRx++;
                                                relayRxBytes += total;
                                                // The clock the acknowledgement loop is measured
                                                // against: the instant the peer's segment reached
                                                // our kernel. Taken here rather than at the top of
                                                // the loop because the parse above already told the
                                                // difference between a segment with a payload and an
                                                // acknowledgement, and only the first one starts a
                                                // round trip.
                                                int[] in = tcpFields(one, total);
                                                if (in != null && in[3] > 0) {
                                                    segsIn++;
                                                    lastSegUs = System.nanoTime() / 1000L;
                                                }
                                                // The control connection answers to its own
                                                // budget. relayedRx passed its gate of 200
                                                // nine hundred packets ago, so every line
                                                // about the phone's handshake has been
                                                // missing from this log for hours -- and a
                                                // missing line was being read as a packet
                                                // that never arrived.
                                                int kind = ctlKind(one, total);
                                                if (kind >= 0) {
                                                    ctlRx[kind]++;
                                                }
                                                // The mirror image of the outbound repair at
                                                // the top of the send path. A tun hands out
                                                // packets the kernel has not finished, and
                                                // the send path completes their checksum
                                                // before transmitting -- but nothing ever
                                                // checked the ones arriving from the phone.
                                                // If the phone's chip offloads the same way
                                                // its segments arrive with a partial sum,
                                                // the local kernel drops them silently below
                                                // TCP: the control connection is accepted,
                                                // startHandshake() blocks for four seconds,
                                                // the phone gives up and sends a RST, and
                                                // every counter in this file looks healthy.
                                                // That is the exact shape of the failure
                                                // being chased, so the verdict is printed
                                                // and counted here rather than assumed.
                                                String ckr = "";
                                                if (kind >= 0) {
                                                    ckr = checksumVerdict(one, total);
                                                    if ("bad".equals(ckr)) {
                                                        ctlBadCks++;
                                                        if (fixInboundCksum) {
                                                            completeTransportChecksum(one, total);
                                                            ckr = "fixed";
                                                        }
                                                    }
                                                }
                                                // A handshake flight is several frames sent
                                                // back to back with no acknowledgement
                                                // between them, and this radio cannot receive
                                                // while it transmits. One connection measured
                                                // the cost exactly: the client's flight spanned
                                                // 4177 bytes from seq 858654941, the first 64
                                                // and the last 368 arrived, and the 3745 bytes
                                                // between them never did -- one contiguous hole
                                                // with our own burst sitting inside it. Every
                                                // later retransmission of that hole landed in
                                                // the same window and was lost the same way,
                                                // and the peer reset the connection 4.6 s in.
                                                // Holding our transmissions still for a moment
                                                // after each handshake record is the only thing
                                                // that lets the rest of the burst land. Applied
                                                // to handshake, change-cipher-spec and alert
                                                // records only: an application-data record is a
                                                // transfer, and pausing on every one of those
                                                // would silence the acknowledgements the
                                                // transfer runs on.
                                                if (listenNs > 0 && isHandshakeRecord(one, total)) {
                                                    quietUntil = System.nanoTime() + listenNs;
                                                    quietHits++;
                                                }
                                                if (kind >= 0 && ctlRxLines < ctlLogMax) {
                                                    ctlRxLines++;
                                                    System.out.println("  [" + stamp()
                                                            + "] ctl in " + CTL_KINDS[kind]
                                                            + " #" + ctlRxLines
                                                            + " (" + relayedRx + ")"
                                                            + " " + total + "B cks=" + ckr
                                                            + " " + describeIpv6(one, 0, total));
                                                } else if (relayedRx <= 200) {
                                                    System.out.println("  [" + stamp()
                                                            + "] relay in #" + relayedRx
                                                            + " " + total + "B "
                                                            + describeIpv6(one, 0, total));
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Our own frame, back through the radio, which is the one
                                // direct reading of what the driver did with it: it was
                                // handed over at a known instant and it is being heard at
                                // this one. An interval near zero means the guard really
                                // does decide the phase; an interval of whole slots means
                                // the driver re-times every frame and no amount of aiming
                                // in this file can matter.
                                Long at = txAt.remove(f.seq);
                                if (at != null) {
                                    long dt = System.nanoTime() / 1000L - at;
                                    echoCount++;
                                    echoSumUs += dt;
                                    if (dt < echoMinUs) {
                                        echoMinUs = dt;
                                    }
                                    if (dt > echoMaxUs) {
                                        echoMaxUs = dt;
                                    }
                                    if (echoCount <= 30) {
                                        System.out.println("  [" + stamp() + "] echo seq="
                                                + f.seq + " dt=" + (dt / 1000L) + "ms");
                                    }
                                }
                            }
                        } else if (ownMac.equalsIgnoreCase(f.sa)) {
                            // A second or third vif's copy of a frame this tool sent. Counted
                            // apart from the branch below because the two numbers mean
                            // opposite things: this one is the receive path working, and that
                            // one is a peer frame arriving and being dropped.
                            duplicates++;
                        } else {
                            peerDupes++;
                            if (peerDupes <= 12) {
                                // A peer frame that was not relayed. Whether the peer answered
                                // is the whole question a relay run is asked, so a frame from
                                // it that stops here is printed with what it was rather than
                                // folded into a counter: "no ACK came back" and "an ACK came
                                // back and was not passed on" are different findings.
                                System.out.println("  [" + stamp() + "] peer frame not "
                                        + "relayed #" + peerDupes + " sa=" + f.sa
                                        + " seq=" + f.seq + " " + describeData(buf, n));
                            }
                        }
                    }
                }
            }

            long nowUs = System.nanoTime() / 1000L;
            long now = System.nanoTime();

            // A swept offset changes what the phase histograms are measuring, so the
            // change is applied at a window edge rather than in the middle of one, and
            // announced. Without this the sweep would be read as scatter.
            int poff = phaseOffsetTu();
            if (poff != poffApplied) {
                poffApplied = poff;
                System.out.println("  [" + stamp() + "] phase offset " + poff + "TU");
                phCount = 0;
                phMin = Integer.MAX_VALUE;
                phMax = Integer.MIN_VALUE;
                phSum = 0;
                phHist = new int[8];
                rxCount = 0;
                rxMin = Integer.MAX_VALUE;
                rxMax = Integer.MIN_VALUE;
                rxHist = new int[8];
            }

            if (now >= nextPsf) {
                int wrote = AwdlInj.send(injFd, awdl.psf(nowUs));
                if (wrote < 0) {
                    txErrors++;
                } else {
                    control++;
                }
                nextPsf = now + (long) AwdlFrame.PSF_INTERVAL_TU * TU_US * 1000L;
                // The MIF rides the slot boundary the PSF just announced.
                nextMif = now + awdl.nextSlotUs(nowUs) * 1000L + AwdlFrame.SLOT_US * 1000L / 2;
            }
            if (now >= nextMif) {
                int wrote = AwdlInj.send(injFd, awdl.mif(nowUs));
                if (wrote < 0) {
                    txErrors++;
                } else {
                    control++;
                }
                nextMif = now + AwdlFrame.SLOT_US * 1000L;
            }

            long guard = (target[0] & 1) == 0 ? UNICAST_GUARD_US : MULTICAST_GUARD_US;
            // Aim, and do not merely permit.
            //
            // edgeWaitUs returns how long to wait before the next instant a data frame
            // belongs on the air, and the version before this one only asked whether that
            // number was zero -- that is, whether now was legal. It is legal for sixty-one
            // of the sixty-four TU in a slot, so the guard never guarded anything and the
            // phase printout said so: 17, 14, 7, 33, 9, 32, 47, 6, 49, 31 TU, a uniform
            // scatter. A frame sent at a random phase of the slot is heard only when the
            // peer happens to be in its sixteen-TU window, which is a quarter of the time
            // and is exactly what the link delivered: one sixteen-byte ping out of two,
            // and nothing above a hundred bytes for the rest of the run.
            if (now >= nextSend) {
                // One window can carry several frames; the guard only decides when the
                // first of them goes. After that the slot index and the TU inside it say
                // whether the window is still open, and a frame that starts inside it is
                // worth more than the tick spent waiting for the boundary behind it.
                boolean burst = windowSent > 0
                        && windowSent < windowFrames
                        && awdl.currentSlotIndex(nowUs) == lastSendSlot
                        && (AwdlFrame.SLOT_TU - awdl.nextSlotTu(nowUs)) < windowLastTu;
                long waitUs;
                if (burst) {
                    long gapUs = windowGapNs / 1000L;
                    long sinceUs = nowUs - lastSendUs;
                    waitUs = sinceUs >= gapUs ? 0 : gapUs - sinceUs;
                } else {
                    // The deadline is absolute, and it survives a frame arriving. Aiming
                    // again from the top of the loop after every interruption is what made
                    // this a one-frame-per-slot loop: each cut named the boundary after the
                    // one just given up on, and the peer's ACKs arrive faster than a slot,
                    // so the aim kept moving forward and the send kept not happening. Only
                    // a deadline more than a slot stale is dropped -- by then the boundary
                    // it named is gone and the number would be an accident, not an aim.
                    if (edgeDeadlineUs == 0 || nowUs > edgeDeadlineUs + AwdlFrame.SLOT_US) {
                        long aimUs = edgeWaitUs(awdl, nowUs, guard);
                        if (aimUs == 0) {
                            edgeOpen++;
                        }
                        edgeDeadlineUs = nowUs + aimUs;
                    }
                    waitUs = edgeDeadlineUs - nowUs;
                }
                if (waitUs > 0) {
                    // Wait for the edge with the receiver open. Sleeping here was the
                    // single largest source of phase error in the whole run: a frame that
                    // landed during the sleep sat in the receive queue for up to a slot,
                    // and when it was finally parsed its announcement was that much stale,
                    // so anchoring on it put our slot boundary a slot away from the
                    // peer's. Nothing about the frame was wrong; it had simply been read
                    // too late to be a clock. Returning early costs one loop turn -- the
                    // deadline has not moved, so the wait resumes where it was -- and it
                    // keeps the delay between the air and the clock at one frame.
                    if (waitEdge(rxFd, waitUs)) {
                        edgeCut++;
                        now = System.nanoTime();
                        nowUs = now / 1000L;
                        continue;
                    }
                    now = System.nanoTime();
                    nowUs = now / 1000L;
                } else if (!burst) {
                    edgeDeadlineUs = 0;
                }
            }
            // The schedule has a channel dimension and the guard only has a time one. A
            // frame sent while the peer is on channel 6 -- or in one of the seven slots its
            // own sequence leaves empty -- goes to a receiver that is not on 149 at that
            // moment, and repetition cannot repair that. Deferring is free: the loop comes
            // back at the next boundary, which is at most one slot away.
            int slotNow = awdl.currentSlotIndex(nowUs);
            int slotCh = awdl.currentSlotChannel(nowUs);
            boolean slotSendable = ignoreChannel || (slotCh != 0 && slotCh == awdl.channel());
            // A silence is only visible from here. The report line prints once every few
            // seconds, which is longer than the gaps being chased, and a byte rate cannot
            // tell "nothing was available" from "something available was not sent" -- both
            // read as a slow second. So the gap is timed as it happens and the kernel's
            // queue is sampled while it lasts: sixty milliseconds is chosen because a
            // stalled window at sixteen frames is forty milliseconds of air, so anything
            // past it is at least one window that carried nothing at all.
            if (lastSendUs != 0 && nowUs - lastSendUs >= 60_000L) {
                if (!quiet) {
                    quiet = true;
                    quietSinceUs = lastSendUs;
                    quietMaxQueue = 0;
                    quietIdleFirst = idlePolls;
                    quietEdgeFirst = edgeCut;
                }
                int queued;
                synchronized (outbound) {
                    queued = outbound.size();
                }
                if (queued > quietMaxQueue) {
                    quietMaxQueue = queued;
                }
            }
            // Counted per slot occurrence, not per slot index. The index runs 0..15 and
            // repeats every sequence, so a counter keyed on it recorded a permanently
            // off-channel slot exactly once in the whole run and then looked constant --
            // which is how a sixth of the link stayed invisible in a number that was
            // supposed to be measuring it. The absolute slot is the AW counter divided by
            // the four windows in a slot, and that one never repeats.
            int slotAbs = awdl.currentAw(nowUs) / AwdlFrame.PRESENCE_MODE;
            if (!slotSendable && now >= nextSend && slotAbs != lastOffSlot) {
                offChannel++;
                lastOffSlot = slotAbs;
            }
            if (now >= nextSend && slotSendable) {
                // The handshake goes first, and it is not a nicety. Until the bridge has
                // created a peer for this receiver, every data frame is ACKed by nobody
                // and dropped one layer above the radio without a single log line -- so
                // the SYN is what makes everything after it deliverable at all.
                //
                // The burst is not the last word either. A phone brought up by the BLE
                // advertisement comes to listen on its own schedule, so a run that asks
                // its one question in the first two seconds and then watches for a
                // minute is really reporting on when we asked, not on whether anything
                // is there. After the burst, one SYN every few seconds for the rest of
                // the run.
                boolean retry = aim != null && synSent >= SYN_BURST && now >= nextSynRetry;
                boolean handshake = aim != null && (synSent < SYN_BURST || retry);
                // Three probes rotate on the data ticks: the query to the group, the same
                // query addressed to the peer, and an echo request. Which of them comes
                // back is the diagnosis -- silence on all three is not an mDNS problem.
                byte[] payload;
                String tag;
                byte[] to = target;
                // Set only by the probes that have to be addressed somewhere other than
                // the peer -- the half of the diagnosis that goes to the group's own L2
                // form. Null means "the peer", which is what every relayed packet uses.
                byte[] probeTo = null;
                byte[] relayed = null;
                // Whether this tick is carrying something the kernel has only just
                // produced, as opposed to a copy of an older packet. Only the first kind
                // is allowed to set its own pace; see windowGapNs.
                boolean urgent = false;
                byte[] derived = null;
                String ck = "";
                String hdr = "";
                // A probe that has come due takes the tick, ahead of anything the kernel
                // has queued. Without this the kernel's own chatter -- a re-announce every
                // second, a browse query every few seconds -- filled every iteration, and
                // a five-frame diagnosis that should run four times a second produced
                // eighteen frames in two and a half minutes: three of the five were sent
                // once each and the rest were whatever the rotation had stuck on. The
                // cost of deferring the kernel's packet is one poll interval, three
                // milliseconds, and it is the difference between a measurement and an
                // anecdote.
                boolean probeDue = !handshake && now >= nextProbe;
                // The quiet window, taken before the outbound queue is touched rather than
                // after: a packet already polled off that queue would have to be put back or
                // dropped, and dropping the oldest is the one thing the queue's own overflow
                // rule is careful not to do. Coming back through the read at the top is also
                // what keeps the receiver draining while the transmitter rests -- resting
                // means listening, and a window spent busy elsewhere would be no window.
                if (listenNs > 0 && now < quietUntil) {
                    nextSend = quietUntil;
                    quietPolls++;
                    continue;
                }
                if (tunFd >= 0 && !probeDue) {
                    byte[] fresh = null;
                    synchronized (outbound) {
                        fresh = outbound.pollFirst();
                    }
                    if (fresh != null) {
                        // Before anything else: the kernel may have handed this over
                        // unfinished, and an unfinished checksum is rejected by the peer
                        // below TCP while the radio still acks it.
                        ck = checksumVerdict(fresh, fresh.length);
                        if ("bad".equals(ck)) {
                            completeTransportChecksum(fresh, fresh.length);
                            ck = "bad-fixed";
                        }
                        // Our SYN names how much the peer may send; cap it on the way
                        // out, after the checksum is whole so the rewrite is the last
                        // word on the bytes.
                        clampMss(fresh, fresh.length);
                        derived = receiverOf(fresh, fresh.length);
                        // The rule is the answer here, and a measurement of "who sent
                        // this" is not. It looked like one: the phone's frames arrive with
                        // sa c6:5b:3e:fc:65:9b while the address in the packet reads
                        // c6:5b:f9:a2:c4:b6, and preferring the frame's sa sent every
                        // SYN-ACK of a restart to a place the driver had no target slot
                        // for -- 137 unicast frames, zero ACKs, and a phone that also
                        // stopped hearing the multicast replies it used to answer.
                        //
                        // The authority against it was in the phone's own advertisement
                        // all along: its DataPathState TLV carries c65bf9a2c4b6, the exact
                        // bytes receiverOf() derives. The peer states where its data path
                        // lives; a frame's source address is where one radio chose to
                        // transmit from, which on this link is a different field. Kept the
                        // learning table for the diagnostics that read it, not for this.
                        // The group address is left alone. Rewriting it to the peer's
                        // unicast address was tried, on the theory that a bridge which
                        // learns peers from unicast receivers would drop a frame addressed
                        // to 33:33 -- and that theory was wrong in the one way that
                        // matters: the run that preceded the rewrite had the phone
                        // answering our advertisements within two seconds of link-up,
                        // and the run after it had the phone answering nothing at all
                        // for the whole window. The phone's own replies are all unicast
                        // to us, which needs no explanation other than the ordinary one:
                        // once a resolver has our address it answers directly, and the
                        // earlier "silence" it was blamed on a broken group path was
                        // simply a phone that had already stopped asking.
                        // A connection that has just been accepted opens the one window
                        // in which repetition is worth more than bytes: the flight
                        // that follows decides whether the peer ever gets an answer,
                        // and a lost segment there costs a full retransmission timer
                        // rather than the fast retransmit a long transfer can fall
                        // back on. See youngBytes.
                        if (youngBytes > 0 && isAcceptSynAck(fresh, fresh.length)) {
                            youngLeft = youngBytes;
                        }
                        if (derived == null || Arrays.equals(derived, own)) {
                            // Either the destination carries no MAC at all (a privacy
                            // address) or the kernel addressed itself. Sending it to
                            // whoever the probes go to would put a packet on the air that
                            // the peer's stack must reject, and that rejection would read
                            // as silence.
                            relayErrors++;
                            if (relayErrors <= 3) {
                                System.out.println("  [" + stamp() + "] relay drop "
                                        + describeIpv6(fresh, 0, fresh.length));
                            }
                            fresh = null;
                        } else {
                            // A packet the kernel has just emitted outranks whatever copies of
                            // an older one are still queued for the air. The first version of
                            // this loop spent the copies first and polled afterwards, which
                            // made the queue as slow as the copy schedule: a handshake is four
                            // packets, so the ACK that answers a SYN-ACK waited up to two
                            // copies behind it and the peer had already retransmitted by then.
                            // Freshness is the whole value of a relayed packet -- a stale copy
                            // arriving after the connection moved on is answered with a RST,
                            // and a RST reads exactly like a refusal.
                            relayed = fresh;
                            urgent = true;
                            resend = fresh;
                            resendTo = derived;
                            resendLeft = RELAY_COPIES - 1;
                            resendDue = now + RELAY_SPACING_NS;
                            relayPackets++;
                        }
                    }
                    if (relayed == null && resendLeft > 0 && now >= resendDue) {
                        // Only when the kernel has nothing new: then the copies take the tick
                        // instead of it being wasted, and they still sample different phases
                        // of the peer's availability cycle.
                        relayed = resend;
                        derived = resendTo;
                        resendLeft--;
                        resendDue = now + RELAY_SPACING_NS;
                    }
                }
                // A tick with nothing to relay and no probe due is a poll, not a
                // transmission. Sending the probe instead is what put two hundred and fifty
                // milliseconds of silence after every windowful of data: the probe pushed
                // the next send a quarter of a second out, and the peer's ACK -- the one
                // thing that could give the kernel something new to send -- was already on
                // its way when that happened. Come back in a few milliseconds and look at
                // the kernel again; the probe will still be there when it is due.
                if (relayed == null && !handshake && now < nextProbe) {
                    nextSend = now + idleGapNs;
                    idlePolls++;
                    continue;
                }
                if (relayed != null) {
                    // Before the first copy, so that all of them carry the same header:
                    // a burst whose copies disagree is a burst that measures the
                    // difference between its own copies.
                    hdr = alignRelayHeader(relayed, relayed.length);
                    payload = relayed;
                    to = derived;
                    tag = null;
                } else if (handshake) {
                    // Round-robin across the candidate ports: each port deserves more than
                    // one try, since a radio mid-hop on the first one is indistinguishable
                    // from a port that answers nothing.
                    int which = synSent % aim.ports.length;
                    payload = aim.syns[which];
                    tag = "syn:" + aim.ports[which];
                } else if (aim != null) {
                    // Seven frames leave here on a round robin, and the seven are one
                    // experiment rather than seven features. They differ in exactly one
                    // thing each, so whichever of them is answered names the layer that
                    // works:
                    //
                    //   query-mac  the mDNS question, group at L2 and at IP
                    //   query-uni  the same bytes, single peer at L2, group still at IP
                    //   query-u    the same question aimed at the peer's address, QU set
                    //   echo-mac   an echo request to ff02::fb, group at L2 and at IP
                    //   echo-uni   the same echo, single peer at L2, group still at IP
                    //   query-enum the peer's own service list, unicast, QU set
                    //   udp-dead   the same UDP shape, aimed at a port with no owner
                    //
                    // The last one is the control the rest have been missing. A run that
                    // answered echo-mac a hundred and four times while 5353 stayed silent
                    // proved the group address is deliverable but left the reason for the
                    // silence open: either our datagrams are unreadable to the peer's stack
                    // or nothing is bound to 5353 to read them. Port 9 forces the stack to
                    // speak -- it owes an ICMPv6 port unreachable for any datagram it
                    // actually parsed -- so the two cases separate instead of being argued.
                    int which = probeSeq++ % 7;
                    if (which == 0) {
                        payload = packet;
                        probeTo = MDNS_GROUP_MAC;
                        tag = "query-mac";
                    } else if (which == 1) {
                        payload = packet;
                        tag = "query-uni";
                    } else if (which == 2) {
                        payload = aim.queryU;
                        tag = "query-u";
                    } else if (which == 3) {
                        payload = aim.echoMdns;
                        probeTo = MDNS_GROUP_MAC;
                        tag = "echo-mac";
                    } else if (which == 4) {
                        payload = aim.echoMdns;
                        tag = "echo-uni";
                    } else if (which == 5) {
                        // The only probe that asks about the resolver itself rather than
                        // about AirDrop, and the only one whose answer is meaningful even
                        // when the peer has nothing registered. See queryEnum in Aim.
                        payload = aim.queryEnum;
                        tag = "query-enum";
                    } else {
                        // The control that makes the other six readable: same bytes at L3,
                        // a port with no owner. See udpDead in Aim for why this is the only
                        // probe whose silence or answer rewrites the conclusion.
                        payload = aim.udpDead;
                        tag = "udp-dead";
                    }
                } else {
                    payload = packet;
                    tag = "query-m";
                }
                if (probeTo != null) {
                    to = probeTo;
                }
                // Reaching here without a relayed packet and without a handshake means
                // this tick is a probe, so it sets the probe's own next due time. The
                // deadline is set after the frame is chosen rather than before it so a
                // tick that was deferred a moment ago does not push it out by another
                // interval on the way past.
                if (relayed == null && !handshake) {
                    nextProbe = now + PROBE_INTERVAL_NS;
                }
                byte[] frame = awdl.dataFrame(own, to, payload);
                // The one kept for the log lines below, so that the header dump and the
                // sequence number printed there describe a single frame rather than
                // whichever copy happened to be built last.
                byte[] first = frame;
                // One hundred bytes was the first line, and the run after it showed the
                // line was in the wrong place: the 281-byte segment carrying the POST that
                // asks the peer for its name was handed over three times, four hundred
                // milliseconds apart, and never acknowledged. See burstMaxBytes for why
                // the line now sits at the far side of the discovery records.
                int copies = 1;
                if (handshake) {
                    copies = awBurst;
                } else if (relayed != null && urgent && isRetransmission(payload, payload.length)) {
                    // TCP has already waited a timer for this segment, so this is the
                    // retry and the air is empty; see retxBurst. `urgent` keeps our own
                    // spaced relay copies out of it: those are already the spaced chance
                    // the first attempt did not get, and repeating them in one slot would
                    // put two independent chances back into a single phase.
                    copies = retxBurst > 0 ? retxBurst : awBurst;
                    retxTx++;
                } else if (relayed != null && payload.length <= burstMaxBytes) {
                    // An acknowledgement is cumulative, so repeating it four times spends
                    // the air on the one segment in the stream that the next arrival would
                    // have answered anyway; see ackBurst for the run that measured it at
                    // 73% of everything transmitted. Zero keeps the old behaviour.
                    copies = ackBurst > 0 && isPureAck(payload, payload.length)
                            ? ackBurst : awBurst;
                } else if (relayed != null && youngLeft > 0) {
                    // The head of a connection is repeated whatever its size, because
                    // the flight that opens one is four or five segments and a flight
                    // that short has no fast retransmit to fall back on. The budget is
                    // spent by the byte, so the repetition ends where the transfer
                    // begins; see youngBytes for the run that set the line.
                    copies = awBurst;
                    youngLeft -= payload.length;
                    youngTx++;
                }
                long firstUs = System.nanoTime() / 1000L;
                int wrote = 0;
                for (int k = 0; k < copies; k++) {
                    if (k > 0) {
                        // A fresh frame per copy, not the same bytes again: the four differ
                        // in the sequence number the receiver de-duplicates on, and three
                        // copies of one number are discarded above the radio -- which would
                        // show up here as three windows that were tried and did not work.
                        sleepUntilUs(firstUs
                                + (long) k * AwdlFrame.AW_PERIOD_TU * AwdlFrame.TU_US);
                        frame = awdl.dataFrame(own, to, payload);
                    }
                    // Read at the instant the frame is handed over, and not at the top of
                    // the loop. The guard waits and the edge is re-derived after every
                    // arrival, so the phase at the top of the loop is the phase we
                    // intended -- precisely the number that cannot be wrong, and the one
                    // every previous summary reported.
                    long sendUs = System.nanoTime() / 1000L;
                    int phTu = phaseTu(awdl, sendUs);
                    phCount++;
                    if (phTu < phMin) {
                        phMin = phTu;
                    }
                    if (phTu > phMax) {
                        phMax = phTu;
                    }
                    phSum += phTu;
                    phHist[Math.min(7, phTu >> 3)]++;
                    int w = AwdlInj.send(injFd, frame);
                    if (w < 0) {
                        wrote = w;
                        break;
                    }
                    wrote = w;
                    // Remember when this exact frame was handed over, so its echo can be
                    // dated. The passthru vif receives what it transmits; the difference
                    // between the two instants is the driver's queue and the radio's own
                    // scheduling, which is the one part of the send path this process
                    // cannot see and every previous conclusion quietly assumed was zero.
                    txAt.put(awdl.lastSeq, sendUs);
                    if (txAt.size() > 256) {
                        txAt.remove(txAt.keySet().iterator().next());
                    }
                }
                frame = first;
                if (wrote < 0) {
                    txErrors++;
                    if (txErrors <= 3) {
                        // The length is the diagnosis. "Message too long" says which
                        // errno and nothing about which frame, and a frame that is one
                        // byte over is as dead as one that is a kilobyte over -- the
                        // number is what says whether to blame offload or the MSS.
                        System.out.println("awdl-data: send failed: "
                                + AwdlInj.strerror(-wrote) + " (frame " + frame.length
                                + "B, packet " + payload.length + "B)");
                    }
                } else if (relayed != null) {
                    relayedTx++;
                    relayTxBytes += payload.length;
                    // The other end of the interval the inbound clock started: this is the
                    // first instant the acknowledgement could have been on the air, before
                    // the copies are spaced out. Filed against the last segment that made
                    // the kernel produce it, and only for a payload-free acknowledgement --
                    // a relayed data segment is our own upload and says nothing about the
                    // loop the peer is waiting on.
                    int[] out = tcpFields(payload, payload.length);
                    if (out != null) {
                        // The window scale this side stated in its own SYN-ACK, remembered
                        // so the window printed below is in bytes rather than in units of
                        // 64 bytes. Negative means this side never sent one, and then the
                        // raw field is printed unchanged instead of guessed at.
                        if ((out[2] & 0x12) == 0x12) {
                            int ws = synWscale(payload, payload.length);
                            if (ws >= 0 && ws <= 14) {
                                wscaleOwn = ws;
                            }
                        }
                        if (out[3] == 0 && (out[2] & 0x10) != 0 && (out[2] & 0x06) == 0) {
                            acksOut++;
                            int raw = tcpWindowRaw(payload, payload.length);
                            if (raw >= 0) {
                                if (raw < winMin) {
                                    winMin = raw;
                                }
                                winSum += raw;
                                winN++;
                            }
                            if (lastSegUs != 0) {
                                long gap = firstUs - lastSegUs;
                                if (gap >= 0 && gap <= 500_000L) {
                                    atuN++;
                                    atuSumUs += gap;
                                    if (gap > atuMaxUs) {
                                        atuMaxUs = gap;
                                    }
                                    int bin = gap < 2_000L ? 0
                                            : gap < 8_000L ? 1
                                            : gap < 20_000L ? 2
                                            : gap < 40_000L ? 3
                                            : gap < 80_000L ? 4 : 5;
                                    atuHist[bin]++;
                                }
                            }
                        }
                    }
                    // Every one of the first twenty packets, not every twentieth: the
                    // kernel's own traffic is the thing under test, and a summary count
                    // cannot say whether a missing answer was a SYN that never left or a
                    // SYN-ACK that arrived at a port nobody was listening on.
                    //
                    // The control connection is granted its own budget rather than
                    // competing for this one. relayPackets was at 5393 while this gate
                    // still read 400, so for hours the log held no line at all about what
                    // our kernel did with the phone's handshake -- and the absence was
                    // read as the packet never having been sent. A budget that the bulk
                    // stream cannot spend is the fix; the counts below survive it entirely.
                    int kind = ctlKind(payload, payload.length);
                    if (kind >= 0) {
                        ctlTx[kind]++;
                    }
                    if (kind >= 0 && ctlTxLines < ctlLogMax) {
                        ctlTxLines++;
                        System.out.println("  [" + stamp() + "] ctl out " + CTL_KINDS[kind]
                                + " #" + ctlTxLines + " (" + relayPackets + ")"
                                + " " + frame.length
                                + "B " + payload.length + "B ipv6 -> " + mac(to)
                                + " " + describeIpv6(payload, 0, payload.length)
                                + (ck.isEmpty() ? "" : " cks=" + ck) + hdr);
                    } else if (relayPackets <= 400) {
                        System.out.println("  [" + stamp() + "] relay #" + relayPackets
                                + " " + frame.length
                                + "B " + payload.length + "B ipv6 -> " + mac(to)
                                + " " + describeIpv6(payload, 0, payload.length)
                                + (ck.isEmpty() ? "" : " cks=" + ck) + hdr);
                    }
                    if (relaySynDumps < 3 && payload.length > 53 && payload[6] == 6
                            && (payload[53] & 0x02) != 0) {
                        relaySynDumps++;
                        System.out.println("  syn-relay-bytes " + hex(payload, 0, payload.length));
                    }
                } else if (handshake) {
                    synSent++;
                    if (synSent == 1) {
                        System.out.println("  syn #1 " + frame.length + "B "
                                + hex(frame, 8, 40));
                        System.out.println("  syn-aim-bytes " + hex(payload, 0, payload.length));
                    } else if (synSent == SYN_BURST) {
                        nextSynRetry = now + SYN_RETRY_NS;
                        System.out.println("  syn burst done (6 frames, ports "
                                + join(ports) + "); asking again every "
                                + (SYN_RETRY_NS / 1_000_000_000L) + "s, backing off to "
                                + (SYN_RETRY_MAX_NS / 1_000_000_000L) + "s");
                    } else if (synSent > SYN_BURST) {
                        // Interval first, then grow it: the retry that just left used the
                        // old spacing, so doubling before scheduling would make the first
                        // two retries eight seconds apart and skip the four-second one the
                        // wake-up window was sized for.
                        nextSynRetry = now + synRetryNs;
                        int retryNo = synSent - SYN_BURST;
                        boolean ceiling = synRetryNs >= SYN_RETRY_MAX_NS;
                        synRetryNs = Math.min(synRetryNs * 2, SYN_RETRY_MAX_NS);
                        // Quiet after the first few, then once per interval growth, which
                        // is at most a handful of lines for a whole run. A peer that starts
                        // answering is reported by the reply counters in the report, not by
                        // this line, so nothing diagnostic is lost by going quiet.
                        if (retryNo <= SYN_RETRY_LOUD
                                || (!ceiling && synRetryNs >= SYN_RETRY_MAX_NS)) {
                            System.out.println("  syn retry #" + retryNo
                                    + " dport=" + ports[(synSent - 1) % ports.length]
                                    + " next in " + (synRetryNs / 1_000_000_000L) + "s"
                                    + " (" + (sent + synSent) + " frames out)");
                        }
                    }
                } else {
                    sent++;
                    if (sent == 1) {
                        // A byte-order slip in this header is invisible on the air: the
                        // frame is still sent, still acked, and dropped by every peer.
                        // Printing it once makes the layout checkable against AWDL-13
                        // instead of guessable from the symptom.
                        System.out.println("  head " + hex(frame, 0, 48));
                        // radiotap + 802.11 header(24) + QoS control(2): the SNAP starts
                        // after all three, and the driver looks for it at the same offset
                        // relative to the radiotap length. The length is read from the
                        // frame rather than assumed, because it grew from eight to nine
                        // when the rate field arrived and this check kept subtracting
                        // eight -- condemning correct frames for a whole build.
                        int snap = radiotapLength(frame) + 24 + 2;
                        byte[] want = {(byte) 0xaa, (byte) 0xaa, 0x03, 0x00, 0x17,
                                (byte) 0xf2, 0x08, 0x00, 0x03, 0x04};
                        String problem = null;
                        for (int i = 0; i < want.length; i++) {
                            if (frame[snap + i] != want[i]) {
                                problem = "byte " + (snap + i) + " is "
                                        + Integer.toHexString(frame[snap + i] & 0xff)
                                        + ", AWDL-13 says " + Integer.toHexString(want[i] & 0xff);
                                break;
                            }
                        }
                        System.out.println(problem == null
                                ? "  header AWDL-13 ok (SNAP, 0x0403, seq, 0, 0x86dd)"
                                : "  header WRONG: " + problem);
                    }
                    if (sent <= 3 || sent % 20 == 0) {
                        // The AWDL sequence is the first word after the data head, so
                        // it sits at snap + 8 + 2. Same correction as the check above:
                        // the radiotap length comes from the frame.
                        int seq = radiotapLength(frame) + 24 + 2 + 8 + 2;
                        System.out.println("  tx #" + sent + " " + frame.length + "B"
                                + " " + tag
                                + " awdl_seq=" + (((frame[seq] & 0xff))
                                | ((frame[seq + 1] & 0xff) << 8))
                                // Where in the slot the frame actually left, and which
                                // slot of the sequence that was. The guard aims for
                                // three TU past a boundary; anything else printed here is
                                // the guard being overruled by a late wakeup, and a slot
                                // number that drifts instead of counting 0..15 says the
                                // AW counter was adopted with the wrong alignment.
                                + " ph=" + (AwdlFrame.SLOT_TU - awdl.nextSlotTu(nowUs))
                                + "TU slot=" + awdl.currentSlotIndex(nowUs)
                                + "@" + awdl.currentSlotChannel(nowUs));
                    }
                }
                // The rest of the group, handed over in this same turn.
                //
                // The driver seals an aggregate when collect_us expires or when it holds
                // amsdu_max frames, so a full A-MSDU needs several frames inside one
                // collect window. One frame per turn only manages that when the turns run
                // back to back, which is why the fill came out at 3.5 of 5 MSDUs and the
                // link at 2.0 MB/s while the same code has recorded 5.0 and 3.1 MB/s.
                // Writing the group here costs nothing extra on the air: the driver was
                // going to send those frames anyway, and now it sends them as one.
                //
                // Only for a plain relay. The handshake is a burst of four copies of one
                // packet on one purpose, and the probe is a single question; neither has
                // a group behind it.
                if (relayed != null && copies == 1 && wrote >= 0 && aggDepth > 1) {
                    int extra = 0;
                    while (extra < aggDepth - 1) {
                        // Re-asked before every frame rather than checked once: a group
                        // that runs past the window edge puts frames on the air when the
                        // peer is not listening, and an unfilled aggregate is much cheaper
                        // than that. Same guard the loop itself aims with.
                        long groupUs = System.nanoTime() / 1000L;
                        if (edgeWaitUs(awdl, groupUs, guard) != 0) break;
                        byte[] next;
                        synchronized (outbound) {
                            next = outbound.pollFirst();
                        }
                        if (next == null) break;
                        // The same three rewrites the first frame of the group had: an
                        // unfinished checksum is rejected above TCP while the radio still
                        // acks it, and an unclamped MSS would undo the cap on our own SYN.
                        if ("bad".equals(checksumVerdict(next, next.length))) {
                            completeTransportChecksum(next, next.length);
                        }
                        clampMss(next, next.length);
                        byte[] nextTo = receiverOf(next, next.length);
                        if (nextTo == null || Arrays.equals(nextTo, own)
                                || !Arrays.equals(nextTo, to)) {
                            // A different receiver cannot share the aggregate. Put it back
                            // at the head rather than send it to the wrong phone; the next
                            // turn will aim for whichever address it names.
                            synchronized (outbound) {
                                outbound.addFirst(next);
                            }
                            break;
                        }
                        int groupPh = phaseTu(awdl, groupUs);
                        phCount++;
                        if (groupPh < phMin) phMin = groupPh;
                        if (groupPh > phMax) phMax = groupPh;
                        phSum += groupPh;
                        phHist[Math.min(7, groupPh >> 3)]++;
                        alignRelayHeader(next, next.length);
                        if (AwdlInj.send(injFd, awdl.dataFrame(own, nextTo, next)) < 0) {
                            txErrors++;
                            break;
                        }
                        txAt.put(awdl.lastSeq, groupUs);
                        if (txAt.size() > 256) {
                            txAt.remove(txAt.keySet().iterator().next());
                        }
                        relayPackets++;
                        relayedTx++;
                        relayTxBytes += next.length;
                        extra++;
                    }
                    if (extra > 0) {
                        batchTurns++;
                        batchExtra += extra;
                    }
                }
                // A packet just taken from the kernel is a reply, and the interval after
                // it is a reply's interval. Everything else is a query and keeps the
                // probe pacing -- including the resend copies, whose spacing is set by
                // RELAY_SPACING_NS and must not be tightened by this line.
                //
                // A reply's interval is the burst gap now, not the old twenty
                // milliseconds. That number bounded how late one answer might follow the
                // previous one, which was the right question while the relay carried a
                // handshake: four packets, each worth a window. A transfer is a burst
                // inside a single window and the window is what bounds it, so the gap
                // between two frames is a property of the frame, not of the answer.
                long sentAtUs = System.nanoTime() / 1000L;
                int sentSlot = awdl.currentSlotIndex(sentAtUs);
                slotFrames[sentSlot & 15]++;
                if (sentSlot == lastSendSlot) {
                    windowSent++;
                } else {
                    // The previous window just closed: record how much it carried. This
                    // is the only place the count is final, which is why the histogram
                    // lives here and not in the report.
                    if (lastSendSlot >= 0 && windowSent > 0) {
                        burstHist[Math.min(windowSent / 4, 16)]++;
                        spanSlots++;
                        spanTurns += windowSent;
                        if (windowSent > spanMaxTurns) spanMaxTurns = windowSent;
                        // Slots the loop never stood in are invisible everywhere else: the
                        // histogram only sees windows that carried something, and sl only
                        // grows for slots that were reached. A gap of more than one index
                        // between two consecutive turns is a slot that was skipped whole,
                        // and counting them by index distance costs nothing.
                        int jumped = (sentSlot - lastSendSlot + 16) % 16;
                        if (jumped > 1) {
                            skippedSlots += jumped - 1;
                        }
                        // The phase the old window last managed to use. Clamped because a
                        // turn that crossed the boundary reports a value past the slot it
                        // is being filed under, and those are exactly the boundary cases
                        // this measurement is trying to see -- they belong in the count,
                        // but as "closed at the edge", not as a negative.
                        int closedAt = AwdlFrame.SLOT_TU - awdl.nextSlotTu(nowUs);
                        if (lastSendSlot >= 0 && lastSendSlot < 16) {
                            slotClosePh[lastSendSlot] += Math.max(0, Math.min(closedAt, AwdlFrame.SLOT_TU));
                            slotCloseN[lastSendSlot]++;
                        }
                    }
                    lastSendSlot = sentSlot;
                    windowSent = 1;
                }
                lastSendUs = sentAtUs;
                // Printed on the way out of the gap rather than at its start, so the line
                // carries the length. {@code q} is the deepest the kernel's queue got
                // while the radio was silent: zero means the kernel had nothing to hand
                // over and the pause is a TCP wait, a large number means the frames were
                // sitting here and this loop did not put them on the air. Those two want
                // opposite fixes and there is no third reading of the same number.
                //
                // {@code polls} and {@code cuts} say which branch owned the silence. A
                // quiet period that is all polls was spent in the idle branch, looking at
                // an empty queue every few milliseconds; one that is all cuts was spent
                // waiting for a slot edge and being interrupted by arrivals before it got
                // there. The first is a TCP problem wearing a radio symptom, the second is
                // this loop's own aiming.
                if (quiet) {
                    quiet = false;
                    quietReports++;
                    if (quietReports <= 160) {
                        System.out.println("  [" + stamp() + "] quiet "
                                + (sentAtUs - quietSinceUs) / 1000L + "ms, kernel queue peaked "
                                + "at " + quietMaxQueue + ", polls "
                                + (idlePolls - quietIdleFirst) + ", cuts "
                                + (edgeCut - quietEdgeFirst) + ", " + (relayTxBytes / 1024L)
                                + " KB out, window " + windowSent + "/" + windowFrames
                                + ", offch " + offChannel);
                    }
                }
                edgeDeadlineUs = 0;
                nextSend = now + (urgent ? windowGapNs : idleGapNs);
            }

            // Nothing below this line is a constant any more. Re-reading the tuning file
            // here rather than in the transmit path keeps the hot loop free of file I/O:
            // this point is reached once per iteration either way, and the reader itself
            // throttles to one stat per second.
            parseTuning();

            if (now >= nextReport) {
                long spanNs = Math.max(1L, now - prevReportAt);
                long txRate = (relayTxBytes - prevTxBytes) * 1_000_000_000L / spanNs;
                long rxRate = (relayRxBytes - prevRxBytes) * 1_000_000_000L / spanNs;
                prevReportAt = now;
                prevTxBytes = relayTxBytes;
                prevRxBytes = relayRxBytes;
                // The two numbers a tuning change is actually asking for. Slots is the
                // count this interval recorded -- one entry per window that closed with
                // something in it -- so frames per slot is exact rather than inferred, and
                // turns per slot says how much of that came in groups. Reading a rate
                // without them was how a window ceiling got mistaken for an air limit.
                int slotsInSpan = spanSlots;
                long turnsInSpan = spanTurns;
                int maxTurnsInSpan = spanMaxTurns;
                int framesInSpan = relayPackets - prevRelayPackets;
                prevRelayPackets = relayPackets;
                // Mean closing phase per slot index, sixteen small numbers. Read as: the
                // slots that stop near windowLastTu were full, the ones that stop well
                // below it were cut short with air left over.
                StringBuilder closeText = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    if (i > 0) closeText.append(',');
                    closeText.append(slotCloseN[i] == 0 ? 0 : slotClosePh[i] / slotCloseN[i]);
                }
                // What the radio made of those frames. MSDUs per air frame against the
                // driver's own ceiling is the fill, and bytes per air frame times the air
                // frame rate is the link: no other line in this report can tell "the loop
                // stopped feeding" from "the driver stopped packing".
                long[] agg = aggStats();
                String aggText = "?";
                if (agg != null) {
                    if (prevAgg != null && agg[0] > prevAgg[0]) {
                        long aggregates = agg[0] - prevAgg[0];
                        aggText = (agg[1] - prevAgg[1]) + "/" + aggregates
                                + "@" + ((agg[2] - prevAgg[2]) / aggregates) + "B";
                    } else {
                        aggText = "idle";
                    }
                }
                prevAgg = agg;
                // The acknowledgement loop, in the two forms a conclusion can be made from.
                // The mean says whether the loop is in the peer's way at all; the histogram
                // says which constant is responsible when it is, because the bins are the
                // four candidate causes and they do not overlap: under two milliseconds is
                // this loop, eight to twenty is the peer's own pacing, twenty to forty is
                // the kernel's delayed acknowledgement, and past forty is a frame that
                // missed its slot.
                String atuText = atuN == 0 ? "?"
                        : (atuSumUs / atuN / 1000L) + "ms/" + (atuMaxUs / 1000L) + "ms/"
                        + atuHist[0] + "." + atuHist[1] + "." + atuHist[2]
                        + "." + atuHist[3] + "." + atuHist[4] + "." + atuHist[5];
                String wndText;
                if (winN == 0) {
                    wndText = "?";
                } else {
                    // In bytes when the scale this side stated is known, and in the raw
                    // 64-byte units when it is not, because a window printed in the wrong
                    // unit is the exact kind of number that gets believed.
                    long unit = wscaleOwn >= 0 ? (1L << wscaleOwn) : 1L;
                    wndText = ((winMin * unit) / 1024L) + "K/"
                            + (((winSum / winN) * unit) / 1024L) + "K"
                            + (wscaleOwn >= 0 ? "@" + wscaleOwn : "@raw");
                }
                long segsSpan = segsIn - prevSegsIn;
                long acksSpan = acksOut - prevAcksOut;
                prevSegsIn = segsIn;
                prevAcksOut = acksOut;
                // The elapsed stamp is what makes this line alignable with the app's own
                // record: "when did the wire stop" and "when did the bar stop" are only
                // the same question once both clocks are on the page.
                System.out.println("  [t=" + ((now - clockStart) / 1_000_000_000L)
                        + " " + left(deadline, now) + "]"
                        + " ctrl=" + control + " syn=" + synSent + " sent=" + sent
                        + " fail=" + txErrors
                        + " data=" + dataFrames + " foreign=" + foreignData
                        + " replies=" + replies                         + " odd=" + oddData + " long=" + overlong
                        + " peerAct=" + peerActions + "/" + age(now, lastPeerAction)
                        + " fAct=" + foreignActions + "/" + age(now, lastForeignAction)
                        + "|" + lastForeignSa
                        + (tunFd >= 0 ? " relay=" + relayedTx + "/" + relayedRx
                                + " tx=" + (relayTxBytes / 1024L) + "K/" + (txRate / 1024L) + "KBs"
                                + " rx=" + (relayRxBytes / 1024L) + "K/" + (rxRate / 1024L) + "KBs"
                                // The control connection's own tally, in the CTL_KINDS
                                // order, cumulative and out/in per shape. Printed every
                                // interval because the per-packet lines above stop when
                                // their budget runs out, and the one question the budget
                                // must not be able to hide is whether our kernel answered
                                // with a SYN-ACK: ctlOut SYN-ACK>0 with ctlIn SYN>0 and
                                // ctlIn data>0 is a handshake in progress, while a SYN
                                // that keeps arriving with no SYN-ACK beside it is the
                                // kernel not answering at all -- two failures that used
                                // to look identical because neither was ever printed.
                                + " ctl=" + ctlText(ctlTx) + "/" + ctlText(ctlRx)
                                + " ctlBad=" + ctlBadCks
                                + " ctlLog=" + ctlTxLines + "+" + ctlRxLines
                                + "/" + ctlLogMax
                                // Whether the quiet window is armed and whether it was
                                // ever taken. listen=0 means the measurement is off; a
                                // listen>0 with quiet=0/0 means no handshake record
                                // arrived, which is a different failure from a window
                                // that was taken and did not help.
                                + " listen=" + (listenNs / 1_000_000L)
                                + " quiet=" + quietHits + "/" + quietPolls
                                // Who the election names and what we announce, printed
                                // together because either one alone is misleading: a node
                                // claiming mastery while still announcing five slots has
                                // won nothing, and sixteen slots announced by a follower
                                // are sixteen slots nobody aims at.
                                + " elect=" + (awdl.isMaster() ? "self" : "peer")
                                + "/h" + awdl.height()
                                + "/m" + awdl.selfMetric()
                                + " seq=" + awdl.sequenceSummary()
                                + " seqv=" + awdl.sequenceVerdict()
                                + " win4=" + buckets(burstHist)
                                // The largest window the interval closed, printed beside
                                // the histogram because the histogram's top bin is a
                                // catch-all: without this number a ceiling that is not
                                // being obeyed and one that is read identically.
                                + " wmax=" + maxTurnsInSpan
                                // Slots jumped over whole, accumulated. Divide by the
                                // sequence count to compare against the one channel-6 slot
                                // each sequence is entitled to: the excess is what the
                                // tail deficit in sl is actually made of.
                                + " skip=" + skippedSlots
                                + " pw=" + closeText
                                // Frames and turns over the slots this interval closed,
                                // as a fraction rather than a quotient: the denominator is
                                // the measurement, and rounding it to one decimal place is
                                // what let a 24-frame window read as a 16-frame one.
                                + " fslot=" + framesInSpan + "/" + slotsInSpan
                                + " tslot=" + turnsInSpan + "/" + slotsInSpan
                                + " agg=" + aggText
                                + " mss=" + mssClamp
                                // The burst depth, because it decides how many of the four
                                // windows of a slot a control frame is tried in, and every
                                // delivery number in this line is only comparable against
                                // another run taken at the same depth.
                                + " burst=" + awBurst
                                + "/" + burstMaxBytes
                                // Copies for a payload-free segment, and likewise only
                                // comparable against a run at the same value. Zero means it
                                // followed aw_burst, which is what every earlier run did.
                                + " ackb=" + ackBurst
                                // Segments repeated because they sat at the head of a
                                // connection, and the budget left. A run where the first
                                // number stays zero is a run in which young_bytes never
                                // matched a SYN-ACK, which is a different fault from a
                                // flight that was repeated and still lost.
                                + " young=" + youngTx + "/" + youngLeft
                                // Segments TCP had already given up on and this queue
                                // therefore repeated. A transfer that advances in single
                                // segments and shows zero here is a transfer whose
                                // retransmissions were still going out once; see
                                // retxBurst. The count is also the proof that the table
                                // in isRetransmission recognised the same sequence number
                                // twice, which is the whole mechanism.
                                + " retx=" + retxTx
                                + "/" + (retxBurst > 0 ? retxBurst : awBurst)
                                + " idle=" + (idleGapNs / 1_000_000L) + "ms"
                                + " poll=" + idlePolls
                                // Group fill, as turns and extra frames rather than a
                                // ratio: extra/turns is the depth the link reached, and
                                // turns against the frame count says how much of the
                                // transfer went out in groups at all.
                                + " rd=" + (readTurns == 0 ? "?"
                                        : String.valueOf(readNsSum / readTurns / 1000L)) + "us"
                                + " rt=" + readTurns
                                + " bat=" + batchTurns + "+" + batchExtra
                                // Packets the kernel produced that this queue could not
                                // hold. One of these is a hole in a TCP stream and a
                                // freeze of at least a retransmission timer, so it belongs
                                // in the interval line and not in the closing summary --
                                // a transfer that ends is the one case where it is too
                                // late to read it.
                                + " unq=" + queueDrops[0]
                                + " big=" + overMtu[0]
                                + " q=" + outboundSize(outbound) + "/" + queuePkts
                                // The acknowledgement loop. seg is inbound segments with a
                                // payload in this interval against the run's total, ack is
                                // the payload-free acknowledgements this side put on the
                                // air over the same two spans, and atu is the gap between
                                // the two events. A run where seg climbs and ack does not
                                // is a run whose kernel is batching, which is a different
                                // fault from one where both climb and the peer still sends
                                // one window per slot.
                                + " atu=" + atuText
                                + " seg=" + segsSpan + "/" + segsIn
                                + " ack=" + acksSpan + "/" + acksOut
                                + " wnd=" + wndText
                                + " drop=" + relayErrors + " rst=" + rstRefused[0]
                                + " spoof=" + spoofHits : "")
                        + " anchored=" + anchored
                        + " edgeCut=" + edgeCut
                        + " edgeOpen=" + edgeOpen
                        + " err=" + (phaseErrTu == Integer.MIN_VALUE ? "?" : phaseErrTu + "TU")
                        + " errbest=" + (bestAnchorTu == Integer.MAX_VALUE ? "?"
                                : bestAnchorTu + "TU")
                        // Announcements split by what the clock did with them. "anchored"
                        // alone cannot say whether a converging err was followed or
                        // ignored, and arej climbing while errbest stays small is the
                        // proof that whole-slot samples are being filtered rather than
                        // adopted.
                        + " arej=" + anchorRefused
                        + " slot=" + awdl.currentSlotIndex(nowUs)
                        + "@" + awdl.currentSlotChannel(nowUs)
                        + " aw=" + awdl.currentAw(nowUs)
                        + " offch=" + offChannel
                        // Frames per sequence position, cumulative. A slot the schedule
                        // spends elsewhere reads as a small number that never grows, which
                        // is the one reading that turns "the link pauses once a second"
                        // into a specific slot index.
                        + " sl=" + buckets(slotFrames)
                        + " poff=" + poffApplied
                        + " ph=" + (phCount == 0 ? "?"
                                : phMin + "-" + phMax + "/" + (phSum / phCount) + "TU")
                        + " ph8=" + buckets(phHist)
                        + " rxph=" + (rxCount == 0 ? "?" : rxMin + "-" + rxMax + "TU")
                        + " rx8=" + buckets(rxHist)
                        + " echo=" + (echoCount == 0 ? "?"
                                : echoCount + "/" + (echoMinUs / 1000L) + "-"
                                        + (echoSumUs / echoCount / 1000L) + "-"
                                        + (echoMaxUs / 1000L) + "ms"));
                // Per window, for the same reason the counters are cumulative and these
                // are not: a run whose phase is right for its first minute and wrong for
                // its second reads as one average that describes neither.
                phCount = 0;
                phMin = Integer.MAX_VALUE;
                phMax = Integer.MIN_VALUE;
                phSum = 0;
                phHist = new int[8];
                rxCount = 0;
                rxMin = Integer.MAX_VALUE;
                rxMax = Integer.MIN_VALUE;
                rxHist = new int[8];
                // Also per interval: "how full were the windows in the last five seconds"
                // is the question a tuning change is asking, and a cumulative histogram
                // answers it with the average of everything that came before it.
                burstHist = new int[17];
                spanSlots = 0;
                spanTurns = 0;
                spanMaxTurns = 0;
                slotClosePh = new int[16];
                slotCloseN = new int[16];
                // Two seconds while the link is carrying something, five while it is not.
                // A transfer of a photograph is over in less than a 5 s window, and a
                // report that catches it once describes a whole run with one average --
                // which is how a stall in the first second and a band running at rate for
                // the last ten both came out as the same modest number. Idle is not worth
                // the lines, so it keeps the old cadence.
                // One second while the link is carrying something. The opening seconds of a
                // transfer are the ones that decide how fast it is judged to be, and a five
                // second window spends most of its span on the quiet that preceded them --
                // which is how a first interval read 1.85 MB/s for a link already doing 3.2.
                nextReport = now + (framesInSpan > 0 ? 1_000_000_000L : 5_000_000_000L);
            }
        }

        System.out.println("awdl-data: ctrl=" + control + " syn=" + synSent + " sent=" + sent
                + " fail=" + txErrors + " frames=" + frames + " data=" + dataFrames
                + " foreign=" + foreignData + " replies=" + replies + " dup=" + duplicates
                + " peerDup=" + peerDupes
                + " odd=" + oddData + " long=" + overlong + " anchored=" + anchored
                + " peerAct=" + peerActions + " fAct=" + foreignActions
                + " fSa=" + lastForeignSa
                + " edgeCut=" + edgeCut
                + " edgeOpen=" + edgeOpen
                + " errbest=" + (bestAnchorTu == Integer.MAX_VALUE ? "?" : bestAnchorTu + "TU")
                + " aims=" + aims + "/" + MAX_AIMS + " aimRefused=" + aimRefused
                + " peer=" + peer);
        if (tunFd >= 0) {
            System.out.println("awdl-data: relay packets=" + relayPackets + " frames="
                    + relayedTx + " copies=" + RELAY_COPIES + " in=" + relayedRx
                    + " dropped=" + relayErrors + " unqueued=" + queueDrops[0]
                    + " rstRefused=" + rstRefused[0] + " spoofHits=" + spoofHits);
        }
        System.out.println("awdl-data: data senders=" + senders);
        censusReport(census);
        if (relayedRx > 0) {
            // The kernel's own connection was answered: a SYN-ACK reached the tun and a
            // socket was waiting for it. This is the only verdict that says a connection
            // exists rather than that a port is open.
            System.out.println("VERDICT syn-answered");
        } else if (spoofHits > 0) {
            // The peer answered, but only on the port this tool keeps for itself, and that
            // SYN-ACK was deliberately dropped because no socket owns it. Reporting this as
            // "syn-answered" made a run with in=0 print like a success, and the run after it
            // was judged on that line. What it does establish is worth stating exactly: the
            // AirDrop port was open while the frame was on the air, so the radio, the
            // address and the wake were all right and only the timing was against us.
            System.out.println("VERDICT port-open-unclaimed");
        } else if (replies > 0) {
            System.out.println("VERDICT mdns-reply");
        } else if (foreignData > 0) {
            System.out.println("VERDICT peer-data");
        } else if (sent > 0 && control > 0) {
            System.out.println("VERDICT tx-only");
        } else {
            System.out.println("VERDICT nothing");
        }
    }

    /**
     * Where in the slot an instant falls, in TU past a boundary, in {@code 0..63}.
     *
     * <p>Deliberately taken from the same clock the guard waits on, so that "aimed at
     * three TU" and "left at three TU" are the same measurement rather than two that
     * have to be reconciled. A value that comes out at the slot width means the instant
     * landed exactly on a boundary and is reported as zero, which is what it is.
     */
    private static int phaseTu(AwdlFrame awdl, long us) {
        int to = AwdlFrame.SLOT_TU - awdl.nextSlotTu(us);
        return to >= AwdlFrame.SLOT_TU || to < 0 ? 0 : to;
    }

    /**
     * An eight-bucket histogram of the slot, as eight small numbers.
     *
     * <p>Eight TU per bucket because the window being looked for is sixteen TU wide: at
     * that resolution a window shows up as two adjacent buckets and a random phase shows
     * up as eight equal ones, and the difference needs no statistics to read.
     */
    private static String buckets(int[] h) {
        StringBuilder sb = new StringBuilder();
        for (int v : h) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /**
     * Waits until {@link System#nanoTime()} reaches {@code targetUs}.
     *
     * <p>Sleeping for the whole interval and then spinning for the rest, because the two
     * halves fail in opposite directions: {@link Thread#sleep} rounds up by a millisecond
     * or two on this kernel, and a spin for sixteen milliseconds would fight the relay
     * thread for a core it is also using to receive. The spin is only the last
     * millisecond, which is where the accuracy has to be.
     */
    private static void sleepUntilUs(long targetUs) {
        long waitUs = targetUs - System.nanoTime() / 1000L;
        if (waitUs <= 0) {
            return;
        }
        if (waitUs > 1500) {
            try {
                Thread.sleep((waitUs - 1200) / 1000L);
            } catch (InterruptedException e) {
                // Nothing to restore: the caller re-reads the clock and spins the rest.
                Thread.currentThread().interrupt();
                return;
            }
        }
        while (System.nanoTime() / 1000L < targetUs) {
            // Spin, briefly, for the phase that matters.
        }
    }

    /**
     * An environment variable read as an int, or {@code def} if it is not one.
     *
     * <p>Silent on purpose. These variables exist to be swept from a shell between runs,
     * and a typo in one of them should cost a run at the default value rather than a
     * process that refuses to start on a phone whose only console is a log file.
     */
    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Hands the driver a new collection window, when the tuning file named one.
     *
     * <p>Written from here rather than from the app because the parameter is root-owned
     * and this process is root. The alternative -- a shell hop per change -- would put a
     * process launch inside the interval being measured.
     */
    private static void applyCollectUs() {
        int want = collectUsWanted;
        if (want < 0 || want == collectUsApplied) {
            return;
        }
        collectUsApplied = writeParam(AGG_COLLECT, want) ? want : -1;
        System.out.println("  [" + stamp() + "] driver collect_us=" + want);
    }

    private static boolean writeParam(String path, int value) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(path)) {
            out.write(Integer.toString(value).getBytes("UTF-8"));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The driver's cumulative aggregation counters, or {@code null} when it is not there.
     *
     * <p>Three numbers: how many air frames it sealed, how many MSDUs it packed into them,
     * and how many payload bytes crossed the radio. The ratio of the last two to the first
     * is the fill, and the fill is the throughput: a link handing the radio 1440 B frames
     * that leave as 4.6 KB air frames moves three times what the same link moves when they
     * leave one at a time. Read once per report, on a thread that is already printing.
     */
    private static long[] aggStats() {
        File f = new File(AGG_STATS);
        if (!f.canRead()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n <= 0) {
                return null;
            }
            String text = new String(b, 0, n, "UTF-8");
            long[] out = new long[3];
            out[0] = counter(text, "aggregates=");
            out[1] = counter(text, "msdus=");
            out[2] = counter(text, "bytes=");
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static long counter(String text, String key) {
        int at = text.indexOf(key);
        if (at < 0) {
            return -1L;
        }
        int i = at + key.length();
        boolean any = false;
        long value = 0L;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
            any = true;
            i++;
        }
        return any ? value : -1L;
    }

    /**
     * Re-reads {@link #TUNING_FILE} at most once a second, applying whatever it names.
     *
     * <p>The values it sets are the ones that decide throughput, and every one of them is
     * still a guess: what the peer's buffer becomes at a given MSS, how many frames fit
     * a window before the guard's promise expires, whether the availability mask means
     * what it says. A guess that can only be tried by rebuilding is a guess that gets
     * tried once a day; this one is tried by writing four lines to a file between two
     * transfers, on the same install, with the same warmed-up peer.
     *
     * <p>Both the mtime and the size are compared, because the way a shell writes this
     * file is {@code echo > file}, which can land inside the same timestamp tick as the
     * previous write. A line that does not parse is dropped, not raised: the point is to
     * keep a run alive while it is being tuned, not to validate a config.
     */
    private static void parseTuning() {
        long now = System.nanoTime();
        if (now - tuningCheckedAt < 1_000_000_000L) {
            return;
        }
        tuningCheckedAt = now;
        File f = new File(TUNING_FILE);
        long stamp = f.lastModified() * 1000L + f.length();
        if (stamp == tuningStamp) {
            // The file is unchanged, but the election still is not: a peer's announcement
            // rewrites it fifty times a second, so a claim made once at start-up would be
            // gone before the first megabyte and the run would measure a state nobody set.
            // Cheap enough to repeat -- five field assignments once a second.
            applyElection();
            return;
        }
        tuningStamp = stamp;
        // A missing file is not a reason to leave the method. The constants declared
        // further up are the tuning in that case, and they still have to reach the
        // radiotap block: returning here is how a build whose defaults were HT, MCS 7 and
        // short guard shipped a transfer that went out legacy, seven per cent slower,
        // while every status line and every echo said ht sgi. The push below applies
        // whatever the fields hold, which without a file is exactly those constants.
        List<String> lines = new ArrayList<>();
        if (f.isFile() && f.canRead()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                byte[] b = new byte[2048];
                int n = in.read(b);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    char c = (char) (b[i] & 0xff);
                    if (c == '\n') {
                        lines.add(sb.toString());
                        sb.setLength(0);
                    } else if (c != '\r') {
                        sb.append(c);
                    }
                }
                if (sb.length() > 0) {
                    lines.add(sb.toString());
                }
            } catch (IOException e) {
                // A file caught mid-write is not a reason to stop; next tick sees the size.
                return;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // Nothing to do about a close that failed on a read this small.
                    }
                }
            }
        }
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.US);
            String val = line.substring(eq + 1).trim();
            if (val.isEmpty()) {
                continue;
            }
            int num;
            try {
                num = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                continue;
            }
            switch (key) {
                case "mss":
                    if (num >= 64 && num <= MSS_CEILING) {
                        mssClamp = num;
                    }
                    break;
                case "aw_burst":
                    // Live-tunable because the thing it fixes is a delivery rate, and a
                    // rate can only be read off a run: one copy per window is what every
                    // earlier measurement was taken with, and four is what the log says
                    // the handshake needs. Anything above the four windows of a slot
                    // would spill into the next, so that is the ceiling.
                    if (num >= 1 && num <= AwdlFrame.PRESENCE_MODE) {
                        awBurst = num;
                    }
                    break;
                case "burst_max":
                    // Where a segment stops being cheap to duplicate. Zero turns the
                    // repetition off for everything relayed and leaves only the handshake,
                    // which is the shape every run before this one had.
                    if (num >= 0 && num <= 2000) {
                        burstMaxBytes = num;
                    }
                    break;
                case "young_bytes":
                    // The head of each connection, exempt from burst_max. Zero leaves
                    // every relayed segment above burst_max unrepeated, which is the
                    // shape the 4609/4668/4718/4731 ms failure run had.
                    if (num >= 0 && num <= 262144) {
                        youngBytes = num;
                        youngLeft = Math.min(youngLeft, num);
                    }
                    break;
                case "listen_ms":
                    // How long the transmitter rests after a handshake record arrives.
                    // Live-tunable because the number is a property of the peer's burst,
                    // not of this code: the run that measured it saw a 4177-byte flight
                    // lose its middle 3745 bytes in one hole, so the window has to be at
                    // least as long as five frames take to send. Zero is off and is the
                    // default; the cap keeps a typo from silencing the node for a second.
                    if (num >= 0 && num <= 200) {
                        listenNs = num * 1_000_000L;
                    }
                    break;
                case "retx_burst":
                    // Copies for a segment the kernel has already retransmitted. Zero
                    // follows aw_burst, which is what every run before this one did
                    // without knowing it -- the retransmission went out once and the
                    // 59% delivery rate decided the next timer.
                    if (num >= 0 && num <= AwdlFrame.PRESENCE_MODE) {
                        retxBurst = num;
                    }
                    break;
                case "ack_burst":
                    // Copies for a payload-free segment. Zero follows aw_burst, which is
                    // what every run before this one did: the acknowledgement stream of a
                    // transfer inherited the handshake's four copies and, at 296
                    // acknowledgements per 38 KB received, that was 73% of the frames on
                    // the air in a 1629-frame run.
                    if (num >= 0 && num <= AwdlFrame.PRESENCE_MODE) {
                        ackBurst = num;
                    }
                    break;
                case "ctl_log":
                    // How much of the control connection this run may print, each
                    // direction. Live-tunable because the useful number depends on what
                    // is being watched: a handshake needs a hundred lines and a stalled
                    // connection replaying its own retransmissions needs a few thousand.
                    // Raising it takes effect without a rebuild, which matters because a
                    // rebuild restarts the node and loses the connection under test.
                    // Zero silences the per-packet lines and leaves the status-line counts.
                    if (num >= 0 && num <= 200000) {
                        ctlLogMax = num;
                    }
                    break;
                case "fix_inbound_cksum":
                    // Flipped from the tuning file once the verdict above says it matters,
                    // so the repair can be tested against a connection that is already
                    // failing without a rebuild -- a rebuild restarts the node and takes
                    // the failing connection with it.
                    fixInboundCksum = num != 0;
                    break;
                case "window_frames":
                    if (num >= 1 && num <= 32) {
                        windowFrames = num;
                    }
                    break;
                case "window_last_tu":
                    if (num >= 4 && num <= AwdlFrame.SLOT_TU) {
                        windowLastTu = num;
                    }
                    break;
                case "window_gap_ms":
                    if (num >= 0 && num <= 50) {
                        windowGapNs = Math.max(0L, num) * 1_000_000L;
                    }
                    break;
                case "idle_ms":
                    if (num >= 1 && num <= 250) {
                        idleGapNs = num * 1_000_000L;
                    }
                    break;
                case "agg_depth":
                    // One turns the grouping off and gets exactly the old pacing back, which
                    // is what makes this measurable against a run rather than argued.
                    if (num >= 1 && num <= 16) {
                        aggDepth = num;
                    }
                    break;
                case "queue_pkts":
                    // Live-tunable because the drop it prevents is invisible from the
                    // outside: the transfer simply stops for a while and then resumes.
                    if (num >= 32 && num <= 4096) {
                        queuePkts = num;
                    }
                    break;
                case "collect_us":
                    // The driver's collection window, which is the one parameter on this
                    // path that this loop hands over already-satisfied: a group of five
                    // frames is written back to back, so a window long enough to wait for
                    // a fifth that has already arrived is a window spent waiting for
                    // nothing. Printed next to the fill so the trade is visible.
                    if (num >= 0 && num <= 20000) {
                        collectUsWanted = num;
                    }
                    break;
                case "ignore_channel":
                    ignoreChannel = num != 0;
                    break;
                case "seq_own":
                    // The zero-risk half of the duty-cycle question: change only what we
                    // announce, leave the election a follower's election. If the peer's
                    // send is aimed at our map, the rate answers in one transfer; if it is
                    // aimed at its own schedule, nothing changes and nothing is lost.
                    keepOwnSequence = num != 0;
                    break;
                case "master_take":
                    // The expensive half. Reversible from this file, which is the whole
                    // reason it is a key rather than a constant: the failure mode is a
                    // dead link, and a dead link has to be undoable with one adb command
                    // instead of a rebuild.
                    masterTake = num != 0;
                    break;
                case "self_metric":
                    // Bounded by AWDL-8's floor rather than by the default, because the
                    // default is now 1024 and the interesting experiments are below it:
                    // "does the iPhone honour a 600?" is a question this key has to be
                    // able to ask, and it could not while the floor was the default.
                    if (num >= AwdlFrame.METRIC_MIN && num <= 100_000) {
                        selfMetricWanted = num;
                    }
                    break;
                case "rate_mbps":
                    if (num >= 0 && num <= 54) {
                        AwdlFrame.setTxRateMbps(num);
                    }
                    break;
                case "mcs":
                    if (num >= -1 && num <= 9) {
                        phyMcs = num;
                        applyPhy();
                    }
                    break;
                case "bw":
                    if (num == 20 || num == 40 || num == 80) {
                        phyWidthMhz = num;
                        applyPhy();
                    }
                    break;
                case "vht":
                    phyVht = num != 0;
                    applyPhy();
                    break;
                case "sgi":
                    phySgi = num != 0;
                    applyPhy();
                    break;
                default:
                    break;
            }
        }
        // The defaults have to reach the header too, not just the fields that hold them:
        // an empty tuning file used to leave the radiotap block legacy while every status
        // line and every tuning echo said whatever the fields said.
        applyPhy();
        applyCollectUs();
        applyElection();
        System.out.println("awdl-data: tuning"
                + (lines.isEmpty() ? " (defaults, no file)" : "") + " mss=" + mssClamp
                + " frames=" + windowFrames
                + " lastTu=" + windowLastTu
                + " gap=" + (windowGapNs / 1_000_000L) + "ms"
                + " idle=" + (idleGapNs / 1_000_000L) + "ms"
                + " rate=" + AwdlFrame.txRateMbps() + "Mbps"
                + " mcs=" + (AwdlFrame.txMcs() < 0 ? "legacy" : String.valueOf(AwdlFrame.txMcs()))
                + " bw=" + AwdlFrame.txWidthMhz() + "MHz"
                + (AwdlFrame.txMcs() < 0 ? "" : AwdlFrame.txVht() ? " vht" : " ht")
                + (phySgi ? " sgi" : "")
                + " agg=" + aggDepth
                + " qpkts=" + queuePkts
                + " collect=" + (collectUsApplied < 0 ? "drv" : String.valueOf(collectUsApplied))
                + " ignoreCh=" + ignoreChannel
                + " seqOwn=" + keepOwnSequence
                + " take=" + masterTake
                + " selfMetric=" + selfMetricWanted);
    }

    /**
     * Pushes the election keys onto the header, idempotently and every tick.
     *
     * <p>Every tick rather than on change, because the two states are not a switch that
     * sits still: a follower's election is rewritten by every frame the peer sends, so a
     * claim of mastery that was applied once would be undone by the next announcement and
     * the run would then measure a state nobody chose. Re-claiming is cheap -- it is five
     * field assignments -- and it makes {@code master_take=1} mean "always", which is the
     * only meaning worth testing.
     *
     * <p>With neither key set this does nothing at all, and that is the intended default
     * now. Rank comes from {@link AwdlFrame#INIT_METRIC} and is defended per frame by
     * {@code outranks}; the map comes from {@code offerSequence}. Neither needs a tick to
     * hold it in place -- they are decisions made when the evidence arrives, not states
     * that decay between frames.
     */
    private static void applyElection() {
        AwdlFrame awdl = awdlRef;
        if (awdl == null) {
            return;
        }
        if (masterTake) {
            awdl.setSelfMetric(selfMetricWanted > 0 ? selfMetricWanted : DEF_TAKE_METRIC);
            awdl.takeMaster();
        } else if (keepOwnSequence) {
            // Kept filled rather than set once, because a copy made before the key was
            // written would otherwise be the map in force for the rest of the run: the file
            // is polled once a second and a peer's map arrives a hundred times faster.
            awdl.ownSequence();
        }
        // The rank alone is adjustable without claiming anything, and that is the rollback
        // path: self_metric=60 puts the node back at AWDL-8's floor, where it loses the
        // election to any Apple peer exactly as it did before this default changed. One
        // adb write and the next tick, with no rebuild and no lost link.
        if (!masterTake && selfMetricWanted > 0) {
            awdl.setSelfMetric(selfMetricWanted);
        }
        // Nothing else. In particular the master, the sync address and the height are left
        // alone unless mastery is claimed outright: a high rank advertised by a node that
        // still calls somebody else its master is a contradiction, and one the peer
        // resolves by putting its own election into a state this side did not intend and
        // cannot read back without a capture.
    }

    /**
     * Gives a relayed packet the two header fields the aim path always writes.
     *
     * <p>In place, on the packet the kernel produced, and only these two fields: the
     * source, the destination, the transport and its checksum are untouched, so whatever
     * the kernel meant to say is still what the frame says. Anything longer than a header
     * is left alone, because a packet this cannot parse is a packet this must not edit.
     *
     * @return a short tag for the log line, or the empty string when nothing changed
     */
    private static String alignRelayHeader(byte[] p, int len) {
        if (len < 8 || (p[0] & 0xf0) != 0x60) {
            return "";
        }
        String changed = "";
        if (RELAY_FLOW_ZERO && (p[1] != 0 || p[2] != 0 || p[3] != 0)) {
            p[1] = 0;
            p[2] = 0;
            p[3] = 0;
            changed += " fl0";
        }
        if (RELAY_HOP_LIMIT > 0 && (p[7] & 0xff) != RELAY_HOP_LIMIT) {
            p[7] = (byte) RELAY_HOP_LIMIT;
            changed += " hl" + RELAY_HOP_LIMIT;
        }
        return changed;
    }

    /**
     * The phase offset to aim with, re-read from {@link #PHASE_FILE} four times a second.
     *
     * <p>Sweeping by restarting the process measures the sweep and the peer's rediscovery
     * together, and the second of those moved the answer further than the first: two runs
     * of the same script, same phone, same closed port, returned seven hits out of twenty
     * and then one out of twelve, the difference being an address that had drifted and an
     * anchor that had to be rebuilt around it. Reading a small file costs a stat and a
     * read, and it leaves one run free to vary exactly one thing.
     *
     * <p>A missing file means no sweep is running, so the value read at startup stands.
     * That is also what makes a normal run behave exactly as before.
     */
    private static int phaseOffsetTu() {
        long now = System.nanoTime();
        if (now - phaseReadAt < 200_000_000L) {
            return phaseOffsetTu;
        }
        phaseReadAt = now;
        FileInputStream in = null;
        try {
            in = new FileInputStream(PHASE_FILE);
            byte[] b = new byte[16];
            int n = in.read(b);
            StringBuilder sb = new StringBuilder();
            // The first integer in the file, whatever else the writer put around it: an
            // offset is being asked for, not a format obeyed.
            for (int i = 0; i < n; i++) {
                char c = (char) (b[i] & 0xff);
                if (c == '-' || (c >= '0' && c <= '9')) {
                    sb.append(c);
                } else if (sb.length() > 0) {
                    break;
                }
            }
            if (sb.length() > 0) {
                phaseOffsetTu = Integer.parseInt(sb.toString());
            }
        } catch (IOException | NumberFormatException e) {
            // Left at its previous value on purpose: a half-written file should not move
            // the aim to zero mid-run.
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing to do about a close that failed on a file read this small.
                }
            }
        }
        return phaseOffsetTu;
    }

    /**
     * AWDL-16's edge guard, in microseconds to wait before transmitting.
     *
     * <p>The return value is always positive and always means the same thing: sleep this
     * long and the frame leaves at the same phase of the slot as every other frame,
     * {@code guard} past a boundary and therefore inside the availability window that
     * opens with it. The previous contract -- zero meaning "now is legal" -- was satisfied
     * almost always and therefore said nothing.
     */
    private static long edgeWaitUs(AwdlFrame awdl, long nowUs, long guard) {
        // The window is not a point, and treating it as one is what halved the link.
        //
        // Everything below aims at the boundary of the *next* slot. That is right when
        // the last frame went out and the loop came straight back -- there is nothing to
        // send and nowhere to send it. It is wrong the moment a packet arrives while the
        // window is still open, and one kind of packet arrives exactly there: the ACK.
        // The peer acknowledges a few microseconds after it has read, and this loop only
        // ever gets more to send when an ACK returns. So the common case is a packet
        // landing inside the sixteen TU the peer is still listening in, followed by a
        // wait of a whole slot (forty-eight TU, forty-nine milliseconds) before it is
        // allowed out. The transfer then does one window per slot rather than one window
        // per round trip, and a window is two kilobytes.
        //
        // Sending now is not a relaxation of the guard. The guard says a frame may not
        // precede the start of the window; it says nothing about waiting for a boundary
        // that would open a window the peer is already inside.
        //
        // The offset is applied before the test and not after it, because it is the
        // difference between this node's slot and the peer's: inside the window means
        // inside the peer's, not ours.
        int offsetTu = phaseOffsetTu();
        int posTu = AwdlFrame.SLOT_TU - awdl.nextSlotTu(nowUs) - offsetTu;
        int guardTu = (int) ((guard + TU_US - 1) / TU_US);
        if (posTu >= guardTu && posTu < windowLastTu) {
            return 0;
        }
        // Inside the guard at the head of a slot, the tempting reading is that the window
        // is one to three TU away and the frame should wait just that long. It was built,
        // measured, and removed: the same file went 9.43 s to 10.22 s, and the client's
        // stalls went 1304 ms to 2090 ms while the air silence on this side stayed at
        // roughly a second. Sending at the first TU the guard allows puts the frame on the
        // edge of the window with this pair's clock error (err reads minus one to minus
        // two TU) still unspent, so the frame lands where the peer is not yet listening,
        // is lost, and takes a whole slot to be noticed -- which is the slot the change
        // was written to save. The waste this branch was meant to remove is real but it is
        // absorbed here, in the same place every other slot-boundary frame waits, and the
        // cost of that wait is smaller than the cost of arriving early.
        //
        // What the branch was looking for -- a whole slot going by with nothing sent and
        // no interruption -- is real too, and it is not this. {@code skip=} answers it:
        // the counter rises by exactly one a second during a transfer, and the one is the
        // channel-6 slot the schedule forbids. Nothing else is being skipped.
        // Outside it, aim at the next boundary plus the guard, plus whatever offset the
        // sweep found. The offset is added here rather than folded into the guard
        // constants because the guard is a protocol number -- AWDL-16's three TU -- and
        // the offset is a property of this pair of radios, which is a different kind of
        // fact and should not be able to be mistaken for the first one.
        //
        // The earlier version returned the remainder of the guard when the boundary had
        // just passed and two other values around it, and the caller accepted only zero.
        // All of that reduces to this one expression, but the difference is not cosmetic:
        // a value the caller acts on makes the transmit phase a decision, and a value it
        // ignores makes it an accident.
        long waitUs = awdl.nextSlotUs(nowUs) + guard + (long) offsetTu * TU_US;
        // A negative offset asks to arrive before the boundary, which is the same phase
        // one slot later. Adding a slot says exactly that; without it the caller would see
        // a number it treats as "now is fine" and send at whatever phase the loop happened
        // to reach, which is the failure this whole mechanism exists to remove.
        if (waitUs <= 0) {
            waitUs += AwdlFrame.SLOT_US;
        }
        return waitUs;
    }

    /**
     * Sleeps for a duration in microseconds.
     *
     * <p>{@link Thread#sleep} takes whole milliseconds plus a nanosecond remainder, and
     * the quantity that matters here is a few TU: the guard is three TU wide and the
     * window it aims at is sixteen, so rounding this up to a millisecond would throw away
     * a fifth of the margin.
     */
    private static void sleepUs(long us) {
        if (us <= 0) {
            return;
        }
        try {
            Thread.sleep(us / 1000L, (int) (us % 1000L) * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits out an edge but stops the moment a frame arrives, and says which happened.
     *
     * <p>The alternative is {@link #sleepUs}, and the difference is not scheduling taste.
     * A sleep closes the receiver for as long as it lasts, so every frame that lands
     * inside it is read late -- and a late-read action frame announces a slot boundary
     * that has already moved, which turns into a phase error of exactly the lateness.
     * Measured before this existed, err spanned 0 to -63 TU across one run, which is one
     * whole slot of lateness and matches the 20% answer rate that a uniformly random
     * phase predicts (the peer listens for sixteen of sixty-four TU).
     *
     * <p>Returning early on a frame is safe because the caller re-derives the edge on the
     * next turn round the loop: the deadline has not moved, only the code path to it.
     *
     * @return true if a frame arrived first and the edge was not reached
     */
    private static boolean waitEdge(FileDescriptor fd, long us) {
        long until = System.nanoTime() + us * 1000L;
        while (true) {
            long left = until - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            // Whole milliseconds to the kernel, and the last one spent spinning: poll
            // rounds up, and a TU is 1.024 ms, so rounding here would move the frame out
            // of the window it is being aimed at.
            if (framePending(fd, left / 1_000_000L)) {
                return true;
            }
        }
    }

    /** Whether a frame is waiting to be read, blocking at most {@code millis}. */
    private static boolean framePending(FileDescriptor fd, long millis) {
        pollFd[0].fd = fd;
        pollFd[0].events = (short) OsConstants.POLLIN;
        pollFd[0].revents = 0;
        try {
            return Os.poll(pollFd, (int) Math.max(0L, millis)) > 0;
        } catch (ErrnoException e) {
            // Not a condition to act on: the caller will simply wait out the edge, which
            // is what it would have done before any of this existed.
            return false;
        }
    }

    private static final StructPollfd[] pollFd = {new StructPollfd()};

    /**
     * Reads an AWDL data frame's MSDU and says what is in it.
     *
     * <p>A frame that is not shaped like AWDL-13 is worth reporting verbatim rather than
     * skipping: comparing the header we produce against what the phone produces is the
     * only cheap way to catch a byte-order mistake.
     */
    private static String describeData(byte[] p, int len) {
        int rtLen = (p[2] & 0xff) | ((p[3] & 0xff) << 8);
        if (rtLen < 4 || rtLen > len) {
            return "bad radiotap";
        }
        int o = rtLen;
        if (len - o < 24) {
            return "truncated";
        }
        int fc = (p[o] & 0xff) | ((p[o + 1] & 0xff) << 8);
        int body = o + 24;
        boolean qos = ((fc >> 4) & 0xf) == 8;
        boolean amsdu = false;
        if (qos) {
            if (len - body < 2) {
                return "truncated qos";
            }
            amsdu = (((p[body] & 0xff) | ((p[body + 1] & 0xff) << 8)) & QOS_AMSDU) != 0;
            body += 2;
        }
        if (len - body < 16) {
            return "short body " + (len - body);
        }
        int snap = ((p[body] & 0xff) << 8) | (p[body + 1] & 0xff);
        int dataHead = (p[body + 8] & 0xff) | ((p[body + 9] & 0xff) << 8);
        int awdlSeq = (p[body + 10] & 0xff) | ((p[body + 11] & 0xff) << 8);
        int ethertype = ((p[body + 14] & 0xff) << 8) | (p[body + 15] & 0xff);
        String head = "snap=" + Integer.toHexString(snap) + " dataHead="
                + Integer.toHexString(dataHead) + " awdl_seq=" + awdlSeq
                + " ethertype=" + Integer.toHexString(ethertype)
                + (qos ? " qos" : "") + (amsdu ? " amsdu" : "");
        if (snap != 0xaaaa || dataHead != 0x0403) {
            return head + " (not AWDL-13)";
        }
        if (ethertype != 0x86dd) {
            return head + " (not IPv6)";
        }
        int ip = body + 16;
        return head + " | " + describeIpv6(p, ip, len);
    }

    /**
     * True when this is the local stack refusing a connection this tool invented.
     *
     * <p>A SYN built by {@link #ipv6TcpSyn} leaves with {@link #SPOOF_SPORT} as its
     * source and no socket behind it. The phone's SYN-ACK comes back through the tun,
     * the kernel finds nobody bound to that port, and emits a RST. Relaying that RST
     * tells the phone its own SYN-ACK was refused, so it tears down the half-open
     * connection -- and every later run repeats the cycle. The packet is evidence that
     * the handshake reached the phone, not something the phone should hear.
     *
     * <p>Only a RST is withheld. If a socket ever does own this port, its ACK and its
     * data are the connection working, and those must still go out.
     */
    private static boolean isKernelRefusal(byte[] p, int len) {
        int[] tcp = tcpFields(p, len);
        return tcp != null && tcp[0] == SPOOF_SPORT && (tcp[2] & 0x04) != 0;
    }

    /**
     * True when this segment carries no TCP payload and asks for nothing but an
     * acknowledgement, which is what {@link #ackBurst} is about.
     *
     * <p>Read off the IPv6 length field through {@link #tcpFields}, not off the frame:
     * an inbound frame is padded to the 802.11 minimum and the padding would make every
     * acknowledgement look like a four hundred byte segment.
     *
     * <p>A SYN is excluded even when it carries no payload, because its repetition is
     * governed by {@link #awBurst} and the handshake path and not by this trade. A RST
     * is excluded for the same reason it is never repeated: it is an answer to something
     * that will not be asked again.
     */
    private static boolean isPureAck(byte[] p, int len) {
        int[] tcp = tcpFields(p, len);
        if (tcp == null) {
            return false;
        }
        int flags = tcp[2];
        return tcp[3] == 0 && (flags & 0x10) != 0 && (flags & 0x06) == 0;
    }

    /**
     * True when this is the phone answering a connection this tool invented.
     *
     * <p>Handing that answer to the local stack cannot accomplish anything -- there is
     * no socket on {@link #SPOOF_SPORT} to complete the handshake -- and it produces a
     * RST, so the packet is counted as the answer it is and kept out of the tun.
     */
    private static boolean isSpoofedReply(byte[] p, int len) {
        int[] tcp = tcpFields(p, len);
        return tcp != null && tcp[1] == SPOOF_SPORT;
    }

    /**
     * Source port, destination port and flag byte of an IPv6 TCP packet, or null.
     *
     * <p>Extension headers are walked rather than assumed away: a packet the kernel
     * emitted can carry a fragment header, and reading the ports eight bytes early
     * turns every later decision into a guess.
     */
    private static int[] tcpFields(byte[] p, int len) {
        if (len < 40 || (p[0] & 0xf0) != 0x60) {
            return null;
        }
        int nh = p[6] & 0xff;
        int l4 = 40;
        for (int i = 0; i < 4; i++) {
            if (nh == 6) {
                break;
            }
            if (nh == 44) {
                // Fragment header: eight bytes whatever its length field says.
                if (len - l4 < 8) {
                    return null;
                }
                nh = p[l4] & 0xff;
                l4 += 8;
            } else if (nh == 0 || nh == 43 || nh == 60) {
                if (len - l4 < 8) {
                    return null;
                }
                int extLen = ((p[l4 + 1] & 0xff) + 1) * 8;
                nh = p[l4] & 0xff;
                l4 += extLen;
            } else {
                return null;
            }
        }
        if (nh != 6 || len - l4 < 20) {
            return null;
        }
        // The fourth field is the payload behind the TCP header, taken from the IPv6
        // length field rather than from the buffer: an inbound frame is padded to the
        // 802.11 minimum and counting that padding would make an empty ACK look like a
        // segment carrying data. It is what separates "the peer's flight arrived" from
        // "the peer's flight arrived intact" -- a 788 byte ServerHello and a 0 byte ACK
        // are otherwise the same three numbers.
        int ipPayload = ((p[4] & 0xff) << 8) | (p[5] & 0xff);
        int doff = (p[l4 + 12] & 0xf0) >> 2;
        int seg = Math.min(ipPayload, len - l4);
        return new int[] {
                ((p[l4] & 0xff) << 8) | (p[l4 + 1] & 0xff),
                ((p[l4 + 2] & 0xff) << 8) | (p[l4 + 3] & 0xff),
                p[l4 + 13] & 0xff,
                Math.max(0, seg - doff),
        };
    }

    /**
     * The receive window field of an IPv6 TCP packet, as stated, or -1.
     *
     * <p>As stated and not as intended: the number is in units of the scale factor this
     * side announced in its own SYN-ACK, and only the sender of that option can put it
     * back into bytes. The caller keeps the scale separately for exactly this reason.
     */
    private static int tcpWindowRaw(byte[] p, int len) {
        int l4 = tcpAt(p, len);
        if (l4 < 0 || len - l4 < 16) {
            return -1;
        }
        return ((p[l4 + 14] & 0xff) << 8) | (p[l4 + 15] & 0xff);
    }

    /**
     * The window scale factor in a SYN or SYN-ACK, or -1 when the option is absent.
     *
     * <p>Walked rather than assumed to sit at a fixed offset: the option list differs by
     * stack, and a wscale read out of the middle of an MSS option is a plausible number
     * that would silently scale every window printed afterwards.
     */
    private static int synWscale(byte[] p, int len) {
        int l4 = tcpAt(p, len);
        if (l4 < 0) {
            return -1;
        }
        int hlen = ((p[l4 + 12] & 0xf0) >> 4) * 4;
        if (hlen <= 20) {
            return -1;
        }
        int opt = l4 + 20;
        int end = Math.min(l4 + hlen, len);
        while (opt < end) {
            int kind = p[opt] & 0xff;
            if (kind == 0) {
                return -1;
            }
            if (kind == 1) {
                opt++;
                continue;
            }
            if (opt + 1 >= end) {
                return -1;
            }
            int olen = p[opt + 1] & 0xff;
            if (olen < 2 || opt + olen > end) {
                return -1;
            }
            if (kind == 3 && olen == 3) {
                return p[opt + 2] & 0xff;
            }
            opt += olen;
        }
        return -1;
    }

    /**
     * Which of the six shapes a control-connection packet has, as an index into
     * {@link #CTL_KINDS}, or -1 for anything that is not on the control port.
     *
     * <p>Ordered so that the shapes which end a connection win over the shapes that
     * carry one: a RST+ACK is a refusal and not an acknowledgement, and a FIN that also
     * carries data is a close and not a segment. Counted separately from the printed
     * lines because the print budget is finite and a count is not -- after the budget is
     * spent the per-interval status line still has to be able to answer "did the kernel
     * ever answer with a SYN-ACK", which is the question this whole change exists for.
     */
    /**
     * Whether this packet carries a TLS record that belongs to a handshake rather than to
     * a transfer.
     *
     * <p>The distinction is what makes the quiet window affordable. During a handshake the
     * peer sends a handful of frames and then waits, so a few milliseconds of silence costs
     * nothing and buys the whole flight. During a transfer every record is application data,
     * and pauses spent on those would land on the acknowledgements the transfer runs on --
     * which is a throughput regression dressed up as a fix.
     *
     * <p>A segment can hold the tail of one record and the head of the next, and the one
     * that matters is the first: a flight's opening frame is the one whose loss costs a
     * whole timer, and it is the one that arrives after our own burst.
     */
    private static boolean isHandshakeRecord(byte[] p, int len) {
        int l4 = tcpAt(p, len);
        if (l4 < 0 || len - l4 < 20) {
            return false;
        }
        int hlen = ((p[l4 + 12] & 0xf0) >> 4) * 4;
        if (hlen < 20 || len - l4 <= hlen) {
            return false;
        }
        int type = p[l4 + hlen] & 0xff;
        return type == 0x14 || type == 0x15 || type == 0x16;
    }

    private static int ctlKind(byte[] p, int len) {
        int[] tcp = tcpFields(p, len);
        if (tcp == null || (tcp[0] != TCP_PORT && tcp[1] != TCP_PORT)) {
            return -1;
        }
        int flags = tcp[2];
        if ((flags & 0x04) != 0) {
            return 2;
        }
        if ((flags & 0x02) != 0) {
            return (flags & 0x10) != 0 ? 1 : 0;
        }
        if ((flags & 0x01) != 0) {
            return 3;
        }
        return tcp[3] > 0 ? 4 : 5;
    }

    /**
     * The six counts of a control-connection direction as one compact field, e.g.
     * {@code syn3/sa1/rst0/fin0/d14/a120}.
     *
     * <p>Grep-able in both senses: the same six keys every interval, so a diff between
     * two status lines names exactly which shape moved, and a non-zero {@code sa} is the
     * kernel's SYN-ACK in one place regardless of whether that packet's own print budget
     * still had room.
     */
    private static String ctlText(int[] c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length; i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(CTL_SHORT[i]).append(c[i]);
        }
        return sb.toString();
    }

    /**
     * The transport checksum verdict for an IPv6 packet: "ok", "bad", or "" when the
     * packet carries no checksum this tool knows how to sum.
     *
     * <p>A tun device hands out packets the kernel has not finished. TCP, UDP and
     * ICMPv6 checksums are left as a partial pseudo-header sum for hardware to
     * complete, and nothing on this path is that hardware. Transmitting the bytes as
     * they were read leaves the peer's stack to reject the packet below the transport
     * layer -- after the radio has already acked it, which is why a bad checksum and
     * a peer that declines to answer look identical from the counters. Summing here
     * is the only reading that separates them.
     */
    private static String checksumVerdict(byte[] p, int len) {
        int l4 = transportAt(p, len);
        if (l4 < 0) {
            return "";
        }
        return transportSum(p, len, l4) == 0xffff ? "ok" : "bad";
    }

    /**
     * Rewrites the transport checksum of an IPv6 packet in place.
     *
     * <p>Only ever called after {@link #checksumVerdict} has said the one it arrived
     * with is wrong, or after a deliberate edit has invalidated it.
     *
     * <p>The three protocols do not agree on where the field lives. ICMPv6 puts it
     * immediately after type and code, UDP has the ports and a length in front of it,
     * and TCP has eight more bytes of sequence and acknowledgement again. Treating
     * offset two as universal -- which this did -- rewrites the source port of every
     * TCP segment it is asked to repair and leaves the checksum still wrong, so the
     * receiver drops the packet and the repair reads as a failure of the link.
     */
    private static void completeTransportChecksum(byte[] p, int len) {
        int[] discovered = new int[1];
        int l4 = transportAt(p, len, discovered);
        if (l4 < 0) {
            return;
        }
        int at = l4 + checksumOffset(discovered[0]);
        if (len - at < 2) {
            return;
        }
        p[at] = 0;
        p[at + 1] = 0;
        int cks = (~transportSum(p, len, l4)) & 0xffff;
        if (cks == 0) {
            // Zero means "no checksum" for UDP over IPv6, so the sum is carried as the
            // one value that still adds up.
            cks = 0xffff;
        }
        p[at] = (byte) ((cks >> 8) & 0xff);
        p[at + 1] = (byte) (cks & 0xff);
    }

    /** Where a transport's checksum sits, counted from the start of its header. */
    private static int checksumOffset(int nextHeader) {
        if (nextHeader == 6) {
            return 16;
        }
        if (nextHeader == 17) {
            return 6;
        }
        // ICMPv6, which is the only other thing that reaches here.
        return 2;
    }

    /**
     * Offset of the TCP header inside an IPv6 packet, or -1.
     *
     * <p>The same walk {@link #clampMss} does, kept separate because the two ask
     * different questions of the same bytes: one rewrites the option it finds and the
     * other only has to know where the header starts.
     */
    private static int tcpAt(byte[] p, int len) {
        if (len < 60 || (p[0] & 0xf0) != 0x60) {
            return -1;
        }
        int nh = p[6] & 0xff;
        int l4 = 40;
        for (int i = 0; i < 4; i++) {
            if (nh == 6) {
                break;
            }
            if (nh == 44) {
                if (len - l4 < 8) {
                    return -1;
                }
                nh = p[l4] & 0xff;
                l4 += 8;
            } else if (nh == 0 || nh == 43 || nh == 60) {
                if (len - l4 < 8) {
                    return -1;
                }
                int extLen = ((p[l4 + 1] & 0xff) + 1) * 8;
                nh = p[l4] & 0xff;
                l4 += extLen;
            } else {
                return -1;
            }
        }
        if (nh != 6 || len - l4 < 20) {
            return -1;
        }
        return l4;
    }

    /**
     * Whether this packet is the SYN-ACK the kernel sends when it accepts a connection.
     *
     * <p>That segment is the only signal this loop gets that a connection has begun.
     * The socket API is one layer up and reports nothing here, and watching for the
     * peer's SYN instead would miss the case that matters: a SYN lost on the way in
     * is answered by a kernel retransmission nobody here sees, so the first packet
     * this loop handles for that connection is our own answer. Source port rather than
     * destination, because the kernel's reply carries 8770 as its source whichever
     * ephemeral port the peer picked.
     */
    private static boolean isAcceptSynAck(byte[] p, int len) {
        int l4 = tcpAt(p, len);
        if (l4 < 0) {
            return false;
        }
        int sPort = ((p[l4] & 0xff) << 8) | (p[l4 + 1] & 0xff);
        if (sPort != TCP_PORT) {
            return false;
        }
        // SYN and ACK together: a bare SYN would be us dialling out, which is the
        // discovery sender's job and never this side's.
        return (p[l4 + 13] & 0x12) == 0x12;
    }

    /**
     * Caps the MSS named by a TCP SYN or SYN-ACK, in place, to {@link #mssClamp}.
     *
     * <p>Only handshakes carry an MSS, and only the one option matters: the sender is
     * saying how much the other side may put in one segment. Lowering it is legal at
     * any point in the handshake and needs no negotiation, which is why this is a
     * rewrite rather than something the socket API would have to grow.
     *
     * <p>Nothing else in the packet moves. The option keeps its kind and length, so
     * every later offset -- header length, sequence numbers, payload -- stays where
     * the sender put it; only the two value bytes and the transport checksum change.
     */
    private static void clampMss(byte[] p, int len) {
        if (len < 60 || (p[0] & 0xf0) != 0x60) {
            return;
        }
        int nh = p[6] & 0xff;
        int l4 = 40;
        for (int i = 0; i < 4; i++) {
            if (nh == 6) {
                break;
            }
            if (nh == 44) {
                if (len - l4 < 8) {
                    return;
                }
                nh = p[l4] & 0xff;
                l4 += 8;
            } else if (nh == 0 || nh == 43 || nh == 60) {
                if (len - l4 < 8) {
                    return;
                }
                int extLen = ((p[l4 + 1] & 0xff) + 1) * 8;
                nh = p[l4] & 0xff;
                l4 += extLen;
            } else {
                return;
            }
        }
        if (nh != 6 || len - l4 < 24) {
            return;
        }
        if ((p[l4 + 13] & 0x02) == 0) {
            // SYN is the only segment whose options define the segment size.
            return;
        }
        int hlen = ((p[l4 + 12] & 0xf0) >> 4) * 4;
        if (hlen < 24 || len - l4 < hlen) {
            return;
        }
        for (int o = l4 + 20; o < l4 + hlen; ) {
            int kind = p[o] & 0xff;
            if (kind == 0) {
                return;
            }
            if (kind == 1) {
                o++;
                continue;
            }
            if (o + 1 >= l4 + hlen) {
                return;
            }
            int optLen = p[o + 1] & 0xff;
            if (optLen < 2 || o + optLen > l4 + hlen) {
                return;
            }
            if (kind == 2 && optLen == 4) {
                int named = ((p[o + 2] & 0xff) << 8) | (p[o + 3] & 0xff);
                int clamp = mssClamp;
                if (clamp < 64) {
                    clamp = 64;
                }
                // The last word on the number, applied where it is used rather than
                // where it is set: an environment variable and a tuning file both feed
                // mssClamp, and only one of them is checked on the way in.
                if (clamp > MSS_CEILING) {
                    clamp = MSS_CEILING;
                }
                if (named <= clamp) {
                    return;
                }
                p[o + 2] = (byte) ((clamp >> 8) & 0xff);
                p[o + 3] = (byte) (clamp & 0xff);
                completeTransportChecksum(p, len);
                return;
            }
            o += optLen;
        }
    }

    /** Offset of TCP, UDP or ICMPv6 inside an IPv6 packet, or -1. */
    private static int transportAt(byte[] p, int len) {
        return transportAt(p, len, null);
    }

    /**
     * Same walk, also reporting which next-header actually named the transport.
     *
     * <p>The caller needs that value to find the checksum field, and it cannot read it
     * from {@code p[6]} once extension headers are in the way: a fragment header makes
     * {@code p[6]} say 44 while the segment behind it is still TCP.
     */
    private static int transportAt(byte[] p, int len, int[] nextHeaderOut) {
        if (len < 40 || (p[0] & 0xf0) != 0x60) {
            return -1;
        }
        int nh = p[6] & 0xff;
        int l4 = 40;
        for (int i = 0; i < 4; i++) {
            if (nh == 6 || nh == 17 || nh == 58) {
                break;
            }
            if (nh == 44) {
                if (len - l4 < 8) {
                    return -1;
                }
                nh = p[l4] & 0xff;
                l4 += 8;
            } else if (nh == 0 || nh == 43 || nh == 60) {
                if (len - l4 < 8) {
                    return -1;
                }
                nh = p[l4] & 0xff;
                l4 += ((p[l4 + 1] & 0xff) + 1) * 8;
            } else {
                return -1;
            }
        }
        if (nh != 6 && nh != 17 && nh != 58) {
            return -1;
        }
        if (len - l4 < 4) {
            return -1;
        }
        if (nextHeaderOut != null && nextHeaderOut.length > 0) {
            nextHeaderOut[0] = nh;
        }
        return l4;
    }

    /** One's-complement sum over the IPv6 pseudo-header and the transport segment. */
    private static int transportSum(byte[] p, int len, int l4) {
        int upper = len - l4;
        int sum = 0;
        for (int i = 8; i < 40; i += 2) {
            sum += ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff);
        }
        sum += (upper >> 16) & 0xffff;
        sum += upper & 0xffff;
        // Three zero bytes and the next-header value, in that order.
        sum += p[6] & 0xff;
        for (int i = l4; i + 1 < len; i += 2) {
            sum += ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff);
        }
        if ((upper & 1) != 0) {
            sum += (p[len - 1] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return sum;
    }

    /**
     * Whether this frame is another copy of one already taken inside the window.
     *
     * <p>The three vifs see one transmission once each, microseconds apart, and the only
     * thing that would tell those copies apart is metadata this code does not keep. Time
     * does: the copies are simultaneous, while two frames that say different things are
     * never. Entries older than {@link #DUP_WINDOW_US} are therefore dropped from the
     * table rather than holding a key for the rest of the run -- a key held that long is
     * a guess that the peer will not reuse it, and a pure ACK is exactly a frame that
     * gives no reason to believe it.
     *
     * <p>What the key is makes the difference between this working and this eating
     * answers; see {@link #segmentKey}.
     */
    private static boolean seenRecently(Map<String, Long> seen, String key) {
        long nowUs = System.nanoTime() / 1000L;
        Long was = seen.get(key);
        if (was != null) {
            if (nowUs - was < DUP_WINDOW_US) {
                return true;
            }
            seen.remove(key);
        }
        seen.put(key, nowUs);
        if (seen.size() > 256) {
            Iterator<Long> stamps = seen.values().iterator();
            while (stamps.hasNext()) {
                if (nowUs - stamps.next() >= DUP_WINDOW_US) {
                    stamps.remove();
                }
            }
        }
        return false;
    }

    /**
     * What makes one received frame the same packet as another.
     *
     * <p>This key was the 802.11 sequence number, on the theory that the only frames
     * sharing one are the copies of a single transmission that two vifs each heard. A run
     * showed the theory is false on this link: every data frame the phone sent in it
     * carried {@code seq=2932} -- the 93-byte status line, the 43 bytes behind it, the
     * 701-byte ServerHello, all of them -- while the AWDL sequence inside the same frames
     * advanced 18, 20, 49, 51, 54. A key that never changes is not an identity, so
     * "another copy of the same frame" degenerated into "this transmitter sent anything
     * recently", and the window became a rule that dropped whatever arrived within 50 ms
     * of anything else.
     *
     * <p>That rule ate the answers. Three consecutive {@code /Discover} runs reported
     * "0 line(s): Read timed out" while the trace for the same runs held the reply:
     * {@code peer frame not relayed #9 data=93 tls=app-data/1.2+ rec=88 inner=72} at
     * 32.279 s and {@code #10 data=43 not-tls} at 32.281 s -- the 200 OK and its body,
     * 2 ms apart, the second one discarded as a duplicate of the first. One earlier run
     * succeeded only because its head and body happened to land 1.4 s apart, outside the
     * window. What looked like a peer that answers intermittently was this.
     *
     * <p>What genuinely is identical across copies is the packet: the vifs differ in
     * which receive descriptor carried it, not in what it says. So the key is the
     * transport identity -- addresses, ports, sequence and acknowledgement numbers,
     * flags -- which two copies of one transmission share exactly and two different
     * segments of one connection do not. The kernel de-duplicates at the TCP layer
     * regardless; this only decides how much duplicate work the relay does.
     */
    private static String segmentKey(byte[] p, int len, AwdlProbe.Frame f) {
        int ip = ipv6At(p, len);
        if (ip < 0) {
            return "raw " + f.sa + '/' + f.seq + '/' + len;
        }
        int total = ipv6Total(p, ip, len);
        String ends = ipv6(p, ip + 8) + '>' + ipv6(p, ip + 24);
        int nextHeader = p[ip + 6] & 0xff;
        int l4 = ip + 40;
        if (nextHeader == 6 && len - l4 >= 20) {
            return ends + " tcp " + be16(p, l4) + "->" + be16(p, l4 + 2)
                    + " seq=" + u32(p, l4 + 4) + " ack=" + u32(p, l4 + 8)
                    + " f=" + (p[l4 + 13] & 0xff) + " len=" + total;
        }
        if (nextHeader == 17 && len - l4 >= 8) {
            return ends + " udp " + be16(p, l4) + "->" + be16(p, l4 + 2)
                    + " len=" + total;
        }
        if (nextHeader == 58 && len - l4 >= 8) {
            return ends + " icmp6 " + (p[l4] & 0xff) + '/' + (p[l4 + 1] & 0xff)
                    + " len=" + total + " id=" + be16(p, l4 + 4);
        }
        // Anything else keeps enough of the header to separate one packet from the next
        // without pretending to understand it.
        return ends + " p" + nextHeader + " len=" + total
                + " d=" + hex(p, ip, Math.min(ip + 32, len));
    }

    private static int be16(byte[] p, int at) {
        if (at + 2 > p.length) {
            return -1;
        }
        return ((p[at] & 0xff) << 8) | (p[at + 1] & 0xff);
    }

    /** Enough of IPv6 and DNS to tell a query from an answer. */
    private static String describeIpv6(byte[] p, int ip, int len) {
        if (len - ip < 40) {
            return "short ipv6";
        }
        if ((p[ip] & 0xf0) != 0x60) {
            return "not ipv6 v6";
        }
        int nextHeader = p[ip + 6] & 0xff;
        int payloadLen = ((p[ip + 4] & 0xff) << 8) | (p[ip + 5] & 0xff);
        String src = ipv6(p, ip + 8);
        String dst = ipv6(p, ip + 24);
        StringBuilder sb = new StringBuilder();
        // The hop limit is printed because it is the one field of an outgoing packet that
        // decides whether an mDNS listener will even look at it, and it is also the one
        // field of it that this process never sets: the kernel copies it out of the
        // socket option, whose default for a fresh multicast socket is 1. A peer that
        // requires 255 -- Apple's resolver does -- then drops the packet without a
        // single frame lost and without any error anywhere, which is indistinguishable
        // from a listener that was never running. Printing it here makes the difference
        // between "the peer ignored us" and "we handed the peer something it is required
        // to ignore" a reading of the log rather than an experiment.
        sb.append(src).append(" > ").append(dst)
                .append(" nh=").append(nextHeader).append(" plen=").append(payloadLen)
                .append(" hop=").append(p[ip + 7] & 0xff);
        int l4 = ip + 40;
        if (nextHeader == 58 && len - l4 >= 4) {
            int type = p[l4] & 0xff;
            int code = p[l4 + 1] & 0xff;
            sb.append(" icmp6 ").append(type == 129 ? "echo-reply"
                    : type == 128 ? "echo-request" : "type-" + type)
                    .append("/").append(code);
            // The sequence number is the whole point of printing this line rather than
            // counting it. Four echo requests leave this node: one to the peer's own
            // address and one each to ff02::1, ff02::fb and the peer's unicast address
            // disguised as a group member. A reply carries back the sequence number of
            // the request it answers, so a number here says which of the four the peer's
            // stack was willing to receive -- and those four differ only in the address
            // in the IPv6 header. Without it every reply looks identical and the only
            // available conclusion is "the peer answers pings", which is the one thing
            // already known.
            if (len - l4 >= 8) {
                int id = ((p[l4 + 4] & 0xff) << 8) | (p[l4 + 5] & 0xff);
                int seq = ((p[l4 + 6] & 0xff) << 8) | (p[l4 + 7] & 0xff);
                sb.append(" id=").append(Integer.toHexString(id)).append(" seq=").append(seq);
            }
            return sb.toString();
        }
        if (nextHeader == 6 && len - l4 >= 20) {
            // The flags separate a peer answering a SYN from one refusing it, and the two
            // sequence numbers are what a handshake is actually made of: whether the ACK
            // after the SYN-ACK carries the sequence that SYN-ACK asked for, and whether
            // the request behind it starts where that ACK left off. Every run up to now
            // printed this line without them, which left "the peer stopped answering" as
            // the only reading available -- and it is the reading that needs the least
            // proof. A number here is what turns it into a fact or a mistake.
            int sport = ((p[l4] & 0xff) << 8) | (p[l4 + 1] & 0xff);
            int dport = ((p[l4 + 2] & 0xff) << 8) | (p[l4 + 3] & 0xff);
            long seq = u32(p, l4 + 4);
            long ack = u32(p, l4 + 8);
            int doff = (p[l4 + 12] & 0xf0) >> 2;
            int flags = p[l4 + 13] & 0xff;
            int win = ((p[l4 + 14] & 0xff) << 8) | (p[l4 + 15] & 0xff);
            // The segment length comes from the IPv6 header rather than from the frame:
            // 802.11 pads short frames to its own minimum and the padding would read as
            // part of the payload.
            int seg = Math.min(payloadLen, len - l4);
            sb.append(" tcp ").append(sport).append("->").append(dport)
                    .append(" ").append(tcpFlags(flags))
                    .append(" seq=").append(seq).append(" ack=").append(ack)
                    .append(" win=").append(win).append(" hlen=").append(doff);
            if (seg > doff) {
                sb.append(" data=").append(seg - doff);
                sb.append(' ').append(describeTls(p, l4 + doff, seg - doff));
            }
            return sb.toString();
        }
        if (nextHeader != 17 || len - l4 < 8) {
            return sb.toString();
        }
        int sport = ((p[l4] & 0xff) << 8) | (p[l4 + 1] & 0xff);
        int dport = ((p[l4 + 2] & 0xff) << 8) | (p[l4 + 3] & 0xff);
        int ulen = ((p[l4 + 4] & 0xff) << 8) | (p[l4 + 5] & 0xff);
        sb.append(" udp ").append(sport).append("->").append(dport).append(" len=").append(ulen);
        if (sport != 5353 && dport != 5353) {
            return sb.toString();
        }
        int dns = l4 + 8;
        int avail = Math.min(ulen - 8, len - dns);
        return sb.append(" | ").append(describeDns(p, dns, avail)).toString();
    }

    /**
     * The TLS record at the head of a segment, when what follows the TCP header is one.
     *
     * <p>A run that ends with "the peer said nothing" is a run that could not tell a
     * farewell from a refusal, and on the wire the two differ by two bytes inside a
     * record nobody can read. TLS 1.3 makes that worse than it sounds: after the
     * handshake every record is {@code application_data}, alerts included, so the
     * visible type says nothing about the contents. Length does. One alert is five
     * bytes of header, sixteen of authentication tag and three of plaintext --
     * twenty-four bytes, which is exactly the size of the record that ended the first
     * successful session, and the reason that session looked like silence.
     */
    private static String describeTls(byte[] p, int at, int seg) {
        if (seg < 5 || at + 5 > p.length) {
            return "";
        }
        int type = p[at] & 0xff;
        // Anything that is not a TLS content type is a segment from something else on
        // port 8770, and saying so is worth more than staying quiet about it.
        if (type != 20 && type != 21 && type != 22 && type != 23) {
            return "not-tls";
        }
        int ver = ((p[at + 1] & 0xff) << 8) | (p[at + 2] & 0xff);
        int rec = ((p[at + 3] & 0xff) << 8) | (p[at + 4] & 0xff);
        StringBuilder sb = new StringBuilder("tls=");
        sb.append(type == 20 ? "change-cipher-spec" : type == 21 ? "alert"
                : type == 22 ? "handshake" : "app-data")
                .append('/').append(ver == 0x0303 ? "1.2+" : Integer.toHexString(ver))
                .append(" rec=").append(rec);
        // The length field alone cannot settle whether a short segment is the head of a
        // long record or the whole of a short one, and those two need opposite fixes:
        // the first is loss the relay has to make good, the second is a stack that is
        // sitting on a complete message waiting for a peer that never answers. The bytes
        // at the head say which, because a record header that reads back the same as
        // `seg` is a record that ended here.
        sb.append(" seg=").append(seg).append(" h=").append(hex(p, at, at + 12));
        if (type == 23) {
            // Every byte of a TLS 1.3 record is ciphertext plus a sixteen-byte tag, so
            // the plaintext length is arithmetic rather than guesswork -- and a plaintext
            // of three is a content type byte and one alert, which is the whole record.
            int inner = rec - 16;
            if (inner > 0) {
                sb.append(" inner=").append(inner);
                if (inner == 3) {
                    sb.append("=one-alert");
                }
            }
        } else if (type == 21 && rec >= 2 && at + 7 <= p.length) {
            // A plaintext alert is only possible before keys exist, so seeing one here
            // means the handshake itself was refused, not the request.
            sb.append(' ').append(alertName(p[at + 5] & 0xff, p[at + 6] & 0xff));
        }
        return sb.toString();
    }

    /** An alert's level and description, named where the name is the whole answer. */
    private static String alertName(int level, int desc) {
        String what = desc == 0 ? "close_notify"
                : desc == 40 ? "handshake_failure"
                : desc == 42 ? "bad_certificate"
                : desc == 47 ? "illegal_parameter"
                : desc == 70 ? "protocol_version"
                : desc == 80 ? "internal_error"
                : desc == 109 ? "missing_extension"
                : desc == 112 ? "unrecognized_name"
                : desc == 116 ? "certificate_required"
                : desc == 120 ? "no_application_protocol"
                : "desc-" + desc;
        return (level == 1 ? "warning " : level == 2 ? "fatal " : "level-" + level + " ")
                + what;
    }

    /** A 32-bit field as an unsigned number, because sequence numbers wrap. */
    private static long u32(byte[] p, int at) {
        if (at + 4 > p.length) {
            return -1;
        }
        return ((long) (p[at] & 0xff) << 24) | ((p[at + 1] & 0xff) << 16)
                | ((p[at + 2] & 0xff) << 8) | (p[at + 3] & 0xff);
    }

    /** The TCP flag byte, spelled out: only four of the eight bits matter here. */
    private static String tcpFlags(int flags) {
        StringBuilder sb = new StringBuilder("flags=0x").append(Integer.toHexString(flags));
        if ((flags & 0x02) != 0) {
            sb.append(" SYN");
        }
        if ((flags & 0x10) != 0) {
            sb.append(" ACK");
        }
        if ((flags & 0x01) != 0) {
            sb.append(" FIN");
        }
        if ((flags & 0x04) != 0) {
            sb.append(" RST");
        }
        if ((flags & 0x08) != 0) {
            sb.append(" PSH");
        }
        return sb.toString();
    }

    private static String describeDns(byte[] p, int at, int avail) {
        if (avail < 12) {
            return "short dns";
        }
        int flags = ((p[at + 2] & 0xff) << 8) | (p[at + 3] & 0xff);
        int qd = ((p[at + 4] & 0xff) << 8) | (p[at + 5] & 0xff);
        int an = ((p[at + 6] & 0xff) << 8) | (p[at + 7] & 0xff);
        int ns = ((p[at + 8] & 0xff) << 8) | (p[at + 9] & 0xff);
        int ar = ((p[at + 10] & 0xff) << 8) | (p[at + 11] & 0xff);
        boolean response = (flags & 0x8000) != 0;
        StringBuilder sb = new StringBuilder();
        sb.append(response ? "DNS response" : "DNS query")
                .append(" flags=0x").append(Integer.toHexString(flags))
                .append(" qd=").append(qd).append(" an=").append(an)
                .append(" ns=").append(ns).append(" ar=").append(ar);
        List<String> names = new ArrayList<>();
        int o = at + 12;
        int end = at + avail;
        for (int i = 0; i < qd + an && o < end && names.size() < 12; i++) {
            String[] read = new String[2];
            o = readName(p, o, end, read, 0);
            if (o < 0 || end - o < 4) {
                break;
            }
            int type = ((p[o] & 0xff) << 8) | (p[o + 1] & 0xff);
            o += 4;
            if (i >= qd) {
                if (end - o < 6) {
                    break;
                }
                // Past name/type/class comes ttl(4) and rdlength(2).
                int rdlen = ((p[o + 4] & 0xff) << 8) | (p[o + 5] & 0xff);
                o += 6 + rdlen;
            }
            names.add(read[0] + " type=" + type);
        }
        if (!names.isEmpty()) {
            sb.append(' ').append(names);
        }
        return sb.toString();
    }

    /** Reads a possibly compressed DNS name; returns the offset just past it. */
    private static int readName(byte[] p, int at, int end, String[] out, int depth) {
        if (depth > 8 || at >= end) {
            return -1;
        }
        StringBuilder sb = new StringBuilder();
        int o = at;
        while (o < end) {
            int len = p[o] & 0xff;
            if (len == 0) {
                o++;
                break;
            }
            if ((len & 0xc0) == 0xc0) {
                if (end - o < 2) {
                    return -1;
                }
                int ptr = ((len & 0x3f) << 8) | (p[o + 1] & 0xff);
                String[] rest = new String[1];
                if (readName(p, ptr, end, rest, depth + 1) > 0 && rest[0].length() > 0) {
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(rest[0]);
                }
                o += 2;
                out[0] = sb.toString();
                return o;
            }
            if (end - o < len + 1) {
                return -1;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(p, o + 1, len, java.nio.charset.StandardCharsets.ISO_8859_1));
            o += len + 1;
        }
        out[0] = sb.toString();
        return o;
    }

    /** A minimal DNS query: one question, no EDNS, no recursion -- what mDNS sends. */
    private static byte[] dnsQuery(String[] labels, int type, int qclass) {
        int size = 12 + 1;
        for (String label : labels) {
            size += 1 + label.length();
        }
        size += 4;
        byte[] out = new byte[size];
        int o = 0;
        // ID 0 and flags 0: mDNS queries are their own identity, and the response is
        // matched by question rather than transaction id.
        o = putBe16(out, o, 0);
        o = putBe16(out, o, 0);
        o = putBe16(out, o, 1); // qdcount
        o = putBe16(out, o, 0);
        o = putBe16(out, o, 0);
        o = putBe16(out, o, 0);
        for (String label : labels) {
            out[o++] = (byte) label.length();
            for (int i = 0; i < label.length(); i++) {
                out[o++] = (byte) label.charAt(i);
            }
        }
        out[o++] = 0;
        o = putBe16(out, o, type);
        // Class IN, plus the unicast-response bit when the question is asked of a peer
        // directly: RFC 6762 puts it in the top bit of the class field.
        putBe16(out, o, qclass);
        return out;
    }

    /**
     * Wraps a DNS message in IPv6 and UDP, with a real checksum.
     *
     * <p>Unlike IPv4, UDP over IPv6 has no "checksum absent" value: a zero there means
     * the datagram is invalid and every receiver drops it silently. mDNS is exactly the
     * protocol where that failure is invisible, because there is nothing to time out --
     * you simply never hear back. So the pseudo-header sum is computed here.
     */
    private static byte[] ipv6Udp(byte[] src, byte[] dst, int sport, int dport, byte[] dns) {
        int udpLen = 8 + dns.length;
        byte[] out = new byte[40 + udpLen];
        out[0] = 0x60; // version 6, no traffic class, no flow label
        out[4] = (byte) ((udpLen >> 8) & 0xff);
        out[5] = (byte) (udpLen & 0xff);
        out[6] = 17; // UDP
        out[7] = (byte) 255; // hop limit: mDNS is link-local, never routed
        System.arraycopy(src, 0, out, 8, 16);
        System.arraycopy(dst, 0, out, 24, 16);

        int u = 40;
        out[u] = (byte) ((sport >> 8) & 0xff);
        out[u + 1] = (byte) (sport & 0xff);
        out[u + 2] = (byte) ((dport >> 8) & 0xff);
        out[u + 3] = (byte) (dport & 0xff);
        out[u + 4] = (byte) ((udpLen >> 8) & 0xff);
        out[u + 5] = (byte) (udpLen & 0xff);
        // Checksum stays zero here; it is summed below over a zeroed field.
        System.arraycopy(dns, 0, out, u + 8, dns.length);

        long sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += ((src[i] & 0xff) << 8) | (src[i + 1] & 0xff);
            sum += ((dst[i] & 0xff) << 8) | (dst[i + 1] & 0xff);
        }
        sum += udpLen;      // upper-layer packet length, high word is zero
        sum += 17;          // next header
        // 16-bit words over header and payload; a UDP datagram may have odd length --
        // 37 bytes of DNS here -- and the last byte then counts as if a zero followed.
        int end = out.length;
        for (int i = u; i + 1 < end; i += 2) {
            sum += ((out[i] & 0xff) << 8) | (out[i + 1] & 0xff);
        }
        if (((end - u) & 1) != 0) {
            sum += (out[end - 1] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        int checksum = (int) (~sum) & 0xffff;
        // A computed zero is transmitted as all ones, the one's complement convention.
        if (checksum == 0) {
            checksum = 0xffff;
        }
        out[u + 6] = (byte) ((checksum >> 8) & 0xff);
        out[u + 7] = (byte) (checksum & 0xff);
        return out;
    }

    /**
     * A TCP SYN in IPv6, addressed to a peer's link-local address.
     *
     * <p>This frame exists for the driver, not for the peer. The passthru bridge decides
     * which address is a peer from the first unicast frame whose payload reads
     * {@code LLC | AWDL data header | IPv6 | TCP}: it checks the version nibble and
     * requires {@code next header == 6}. UDP never matches. That is why a whole round of
     * testing could send acked mDNS queries and still have no peer -- the frames reached
     * the air and were owned by nobody, so the receiver's MAC answered nothing.
     *
     * <p>The checksum is mandatory for the same reason it is on UDP, and here the TCP
     * pseudo-header is spelled out rather than reused: it carries the upper-layer length,
     * not the datagram length, and a wrong one turns a SYN into a dropped frame at a
     * layer that never reports back.
     */
    private static byte[] ipv6TcpSyn(byte[] src, byte[] dst, int port) {
        int tcpLen = 20;
        byte[] out = new byte[40 + tcpLen];
        out[0] = 0x60; // version 6
        out[4] = (byte) ((tcpLen >> 8) & 0xff);
        out[5] = (byte) (tcpLen & 0xff);
        out[6] = 6; // TCP: the value the bridge's learn hook insists on
        out[7] = (byte) 255; // link-local, never routed
        System.arraycopy(src, 0, out, 8, 16);
        System.arraycopy(dst, 0, out, 24, 16);

        int t = 40;
        out[t] = (byte) ((SPOOF_SPORT >> 8) & 0xff);
        out[t + 1] = (byte) (SPOOF_SPORT & 0xff);
        out[t + 2] = (byte) ((port >> 8) & 0xff);
        out[t + 3] = (byte) (port & 0xff);
        // Anything but zero: a receiver answers a SYN regardless of the sequence, and a
        // constant makes two runs of this tool distinguishable in a capture.
        int seq = (int) (System.nanoTime() & 0x7fffffff);
        out[t + 4] = (byte) (seq >>> 24);
        out[t + 5] = (byte) ((seq >> 16) & 0xff);
        out[t + 6] = (byte) ((seq >> 8) & 0xff);
        out[t + 7] = (byte) (seq & 0xff);
        // Acknowledgement number stays zero, as it must in a SYN without ACK.
        out[t + 12] = 0x50; // data offset 5 words, no options
        out[t + 13] = 0x02; // SYN
        out[t + 14] = (byte) 0xff; // window, unused here but never zero in practice
        out[t + 15] = (byte) 0xff;
        // Checksum and urgent pointer stay zero; the checksum is filled in below.

        long sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += ((src[i] & 0xff) << 8) | (src[i + 1] & 0xff);
            sum += ((dst[i] & 0xff) << 8) | (dst[i + 1] & 0xff);
        }
        sum += tcpLen; // upper-layer length, high word zero
        sum += 6;      // next header
        // Twenty bytes of header, so the odd-tail case of ipv6Udp cannot arise here.
        for (int i = t; i + 1 < out.length; i += 2) {
            sum += ((out[i] & 0xff) << 8) | (out[i + 1] & 0xff);
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        int checksum = (int) (~sum) & 0xffff;
        if (checksum == 0) {
            checksum = 0xffff;
        }
        out[t + 16] = (byte) ((checksum >> 8) & 0xff);
        out[t + 17] = (byte) (checksum & 0xff);
        return out;
    }

    /**
     * An ICMPv6 echo request: the cheapest question that is not a port.
     *
     * <p>A RST proves only that a kernel exists -- an unreachable port produces one
     * whether or not anything upstream of the stack trusts us. An echo reply proves the
     * peer's IPv6 stack is willing to answer this address at all, which is a different
     * fact and one that separates "no listener" from "traffic to us is filtered".
     */
    private static byte[] ipv6IcmpEcho(byte[] src, byte[] dst, int seq) {
        int icmpLen = 8 + 8; // header plus eight bytes of payload
        byte[] out = new byte[40 + icmpLen];
        out[0] = 0x60;
        out[4] = (byte) ((icmpLen >> 8) & 0xff);
        out[5] = (byte) (icmpLen & 0xff);
        out[6] = 58; // ICMPv6
        out[7] = (byte) 255;
        System.arraycopy(src, 0, out, 8, 16);
        System.arraycopy(dst, 0, out, 24, 16);

        int c = 40;
        out[c] = (byte) 128; // echo request; a reply is 129
        out[c + 1] = 0;
        out[c + 4] = 0x41; // identifier "A", so our replies are recognisable
        out[c + 5] = 0x44;
        out[c + 6] = (byte) ((seq >> 8) & 0xff);
        out[c + 7] = (byte) (seq & 0xff);
        for (int i = 0; i < 8; i++) {
            out[c + 8 + i] = (byte) ('a' + i);
        }

        long sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += ((src[i] & 0xff) << 8) | (src[i + 1] & 0xff);
            sum += ((dst[i] & 0xff) << 8) | (dst[i + 1] & 0xff);
        }
        sum += icmpLen;
        sum += 58;
        for (int i = c; i + 1 < out.length; i += 2) {
            sum += ((out[i] & 0xff) << 8) | (out[i + 1] & 0xff);
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        int checksum = (int) (~sum) & 0xffff;
        if (checksum == 0) {
            checksum = 0xffff;
        }
        out[c + 2] = (byte) ((checksum >> 8) & 0xff);
        out[c + 3] = (byte) (checksum & 0xff);
        return out;
    }

    /**
     * The link-local address a MAC maps to under RFC 4291's modified EUI-64: the OUI
     * bytes with the universal/local bit flipped, then {@code ff:fe} and the last three.
     *
     * <p>This is the same rule {@code awdl-link.sh} uses to put the address on
     * {@code wonder0}, so the source address a peer answers to is one the kernel will
     * actually accept the answer on.
     */
    private static byte[] linkLocal(byte[] mac) {
        byte[] out = new byte[16];
        out[0] = (byte) 0xfe;
        out[1] = (byte) 0x80;
        out[8] = (byte) (mac[0] ^ 0x02);
        out[9] = mac[1];
        out[10] = mac[2];
        out[11] = (byte) 0xff;
        out[12] = (byte) 0xfe;
        out[13] = mac[3];
        out[14] = mac[4];
        out[15] = mac[5];
        return out;
    }

    /**
     * The address a packet says it came from, as sixteen bytes, or null.
     *
     * <p>Read out of the fixed header at the same offsets {@link #receiverOf(byte[], int)}
     * uses, so the two cannot drift apart. Only the length and the version nibble are
     * checked: a relay that validated more would drop the packets that matter most, and
     * the caller stores this as a lookup key rather than acting on it.
     */
    private static byte[] ipv6Source(byte[] p, int len) {
        return ipv6Field(p, len, 8);
    }

    /** The address a packet says it is for, as sixteen bytes, or null. */
    private static byte[] ipv6Dest(byte[] p, int len) {
        return ipv6Field(p, len, 24);
    }

    private static byte[] ipv6Field(byte[] p, int len, int at) {
        if (len < 40 || (p[0] & 0xf0) != 0x60) {
            return null;
        }
        if (len < at + 16) {
            return null;
        }
        byte[] out = new byte[16];
        System.arraycopy(p, at, out, 0, 16);
        return out;
    }

    /**
     * The receiver a packet's own IPv6 header names, as a MAC.
     *
     * <p>The inverse of {@link #linkLocal(byte[])}. For a unicast link-local address the
     * interface identifier is a modified EUI-64, so the address the kernel chose to send
     * to determines the address the frame must be addressed to. That is the only mapping
     * worth trusting on a link where the 802.11 address fields arrive shifted -- but it
     * is also the mapping the peer applies to us in the other direction, so the two ends
     * agree without either of them reading an address off the wire.
     *
     * @return the MAC, or null when the destination carries none -- a privacy-extension
     *         address, or a multicast group outside the {@code 33:33} range
     */
    private static byte[] receiverOf(byte[] p, int len) {
        // Forty bytes is the whole requirement: the destination is the last field of the
        // fixed header. Anything more rejects exactly the packets the kernel sends most
        // often -- a router solicitation is 48 bytes and a bare RST is 60 -- and a relay
        // that silently drops those looks like a relay that works in one direction only.
        if (len < 40 || (p[0] & 0xf0) != 0x60) {
            return null;
        }
        int d = 24;
        if ((p[d] & 0xff) == 0xff && (p[d + 1] & 0xff) == 0x02) {
            // ff02::/16 maps onto 33:33 followed by the group's own low four bytes, the
            // same rule IPv4 multicast uses for 01:00:5e.
            return new byte[]{0x33, 0x33, p[d + 12], p[d + 13], p[d + 14], p[d + 15]};
        }
        if ((p[d] & 0xff) != 0xfe || (p[d + 1] & 0xff) != 0x80) {
            return null;
        }
        if (p[d + 11] != (byte) 0xff || p[d + 12] != (byte) 0xfe) {
            // Link-local but not EUI-64: the interface identifier was randomised, so the
            // MAC is simply not present in this address.
            return null;
        }
        return new byte[]{(byte) (p[d + 8] ^ 0x02), p[d + 9], p[d + 10],
                p[d + 13], p[d + 14], p[d + 15]};
    }

    /**
     * Where the IPv6 packet begins inside a received frame, or -1.
     *
     * <p>These are the offsets {@link #describeData} walks, repeated rather than inferred
     * from its report string. A relay that parsed a substring would eventually hand the
     * kernel a payload it had only partly validated, and the kernel's own error would then
     * be indistinguishable from the peer saying nothing.
     */
    private static int ipv6At(byte[] p, int len) {
        if (len < 4) {
            return -1;
        }
        int rtLen = (p[2] & 0xff) | ((p[3] & 0xff) << 8);
        if (rtLen < 4 || rtLen > len || len - rtLen < 24) {
            return -1;
        }
        int o = rtLen;
        int fc = (p[o] & 0xff) | ((p[o + 1] & 0xff) << 8);
        int body = o + 24;
        if (((fc >> 4) & 0xf) == 8) {
            if (len - body < 2) {
                return -1;
            }
            if ((((p[body] & 0xff) | ((p[body + 1] & 0xff) << 8)) & QOS_AMSDU) != 0) {
                // An A-MSDU carries several MSDUs behind one header, so the first SNAP is
                // not at this offset and the framing below does not describe it.
                return -1;
            }
            body += 2;
        }
        if (len - body < 56) {
            return -1;
        }
        if ((((p[body] & 0xff) << 8) | (p[body + 1] & 0xff)) != 0xaaaa) {
            return -1;
        }
        if (((p[body + 8] & 0xff) | ((p[body + 9] & 0xff) << 8)) != 0x0403) {
            return -1;
        }
        if ((((p[body + 14] & 0xff) << 8) | (p[body + 15] & 0xff)) != 0x86dd) {
            return -1;
        }
        int ip = body + 16;
        return (p[ip] & 0xf0) == 0x60 ? ip : -1;
    }

    /**
     * How many bytes of the packet to hand the kernel.
     *
     * <p>802.11 pads a short frame out to its own minimum, so the frame's length is not
     * the packet's length and anything read from the tail would be padding. When the
     * header's length is longer than what arrived the frame was truncated, and the part
     * that did arrive is passed on: the kernel's own checksum will reject it more
     * precisely than a length test here could.
     */
    private static int ipv6Total(byte[] p, int ip, int len) {
        if (ip < 0 || len - ip < 40) {
            return 0;
        }
        int plen = ((p[ip + 4] & 0xff) << 8) | (p[ip + 5] & 0xff);
        int total = 40 + plen;
        return total > len - ip ? len - ip : total;
    }

    /**
     * One label per transmitter and frame kind.
     *
     * <p>The census exists to separate two silences that look identical in the
     * totals: a phone that is off the channel, and a phone that is on the channel
     * but never sends data. It leaves beacons and action frames either way, so
     * seeing them under its own address is proof we were listening to the right
     * thing while it chose not to answer.
     */
    private static String censusKey(AwdlProbe.Frame f, String ownMac) {
        String kind;
        if (f.mgmtType == FC_TYPE_MGMT) {
            if (f.subtype == FC_SUBTYPE_ACTION && f.category == 0x7f && f.ouiType == 0x08) {
                kind = "awdl-action";
            } else if (f.subtype == 8) {
                kind = "beacon";
            } else {
                kind = "mgmt/" + f.subtype;
            }
        } else if (f.mgmtType == FC_TYPE_DATA) {
            kind = "data";
        } else {
            kind = "type" + f.mgmtType;
        }
        return (ownMac.equalsIgnoreCase(f.sa) ? "self  " : "other ") + f.sa + " " + kind;
    }

    /**
     * Whether a described frame is an answer rather than noise.
     *
     * <p>An echo reply, a DNS response and any TCP segment at all are here for the same
     * reason: each one means the peer's IP stack produced something in response to a
     * frame of ours. The distinction between "answered with a RST" and "answered with
     * nothing" is the whole question, so anything that comes back counts.
     */
    private static boolean isReply(String report) {
        return report.contains("echo-reply") || report.contains("DNS response")
                || report.contains(" tcp ");
    }

    private static void censusReport(Map<String, Integer> census) {
        int total = 0;
        for (int v : census.values()) {
            total += v;
        }
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(census.entrySet());
        rows.sort((a, b) -> b.getValue() - a.getValue());
        System.out.println("awdl-data: received " + total + " frames, " + rows.size()
                + " distinct source/kind pairs");
        for (int i = 0; i < rows.size() && i < 14; i++) {
            System.out.println("  rx " + rows.get(i).getValue() + "  " + rows.get(i).getKey());
        }
    }

    /**
     * ff02::1, all nodes on the link.
     *
     * <p>The one reachability question that needs no address: every IPv6 node answers
     * an echo request sent here, so a reply proves the peer's kernel saw our frame
     * while silence proves it never reached the peer's IP stack at all. That is a
     * different fact from the mDNS question, which only the AirDrop daemon answers and
     * only once it has joined the group -- and it is the difference between fixing our
     * addressing and fixing our framing.
     */
    private static byte[] allNodesGroup() {
        byte[] out = new byte[16];
        out[0] = (byte) 0xff;
        out[1] = (byte) 0x02;
        out[15] = 0x01;
        return out;
    }

    /** ff02::fb, the mDNS group every host on the link joins. */
    private static byte[] mdnsGroup() {        byte[] out = new byte[16];
        out[0] = (byte) 0xff;
        out[1] = (byte) 0x02;
        out[13] = (byte) 0x00;
        out[14] = 0x00;
        out[15] = (byte) 0xfb;
        return out;
    }

    private static int putBe16(byte[] out, int o, int value) {
        out[o++] = (byte) ((value >> 8) & 0xff);
        out[o++] = (byte) (value & 0xff);
        return o;
    }

    private static FileDescriptor openReceiver(String iface) throws ErrnoException {
        // No byte swap: Os.socket() already applies htons() to the protocol when the
        // domain is AF_PACKET, so this has to be the host-order constant.
        FileDescriptor fd = Os.socket(AF_PACKET, OsConstants.SOCK_RAW, ETH_P_ALL);
        Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1 << 20);
        // This timeout is the send loop's clock, not merely a read deadline: the loop
        // starts every turn by blocking here, so 50 ms was also the floor on one turn
        // and therefore the ceiling on one frame. A slot is 64 TU (65.5 ms) and the
        // design packs up to windowFrames=16 into it, which needs turns measured in
        // fractions of a millisecond -- at 50 ms a burst could never get past its
        // second frame. Measured: tslot=37/32 is 1.16 turns per slot, data=7.0
        // frames/s, wmax=1..2, and a 24 MB upload advancing 105 B/s. (An earlier
        // version of this note quoted ctrl=13.3 turns/s; ctrl counts control frames --
        // PSF/MIF -- not turns, so it never described the loop at all.) The cost of the
        // smaller value is a wakeup
        // per millisecond while the radio is idle, which is cheaper than the frames it
        // was costing; waitEdge() below still waits for slot boundaries with the
        // receiver open, so aiming is unaffected.
        Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(1));
        // One vif, not all of them.
        //
        // This socket was unbound for its whole life, and the header of awdl_inject.c
        // says why: an unbound AF_PACKET socket sees every virtual interface on the
        // radio, three copies of each frame, and receiving was the half that needed
        // nothing from native code. What that cost only shows up once something is
        // counting. A 179 s transfer read 1909 us at a time, on the thread that also
        // aims the frames, for 78 useful data frames a second -- and every one of the
        // duplicates arrived as an arrival during a burst, which is waitEdge() below
        // returning true, which is edgeCut, which is why a window allowed sixteen
        // frames carried three.
        //
        // Not fatal, and deliberately so: if the bind fails the socket stays unbound
        // and receives everything, which is exactly how the link ran before. The vif
        // named on the command line is the same one the transmitter is bound to, so a
        // receiver that misses frames here would mean the driver delivers a peer's
        // reply on some other vif -- worth knowing, and worth saying out loud rather
        // than dying over.
        // AWDL_RX_BIND=0 leaves the socket unbound, which is the only way to compare
        // the two paths without rebuilding: the same jar, the same run script, one
        // environment variable. Default on.
        if (envInt("AWDL_RX_BIND", 1) == 0) {
            System.out.println("awdl-data: receive socket left unbound by request");
            return fd;
        }
        int bound = AwdlInj.bind(fd, iface);
        if (bound < 0) {
            System.out.println("awdl-data: receive socket not bound to " + iface + ": "
                    + AwdlInj.strerror(-bound) + " -- every frame will be seen once per vif");
        } else {
            System.out.println("awdl-data: receive socket bound to " + iface);
        }
        return fd;
    }

    private static byte[] macOf(String iface) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(iface);
            return ni == null ? null : ni.getHardwareAddress();
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] macOfHex(String text) {
        String[] parts = text.split(":");
        if (parts.length != 6) {
            return null;
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    /**
     * The radiotap header length the frame itself declares, in bytes.
     *
     * <p>It was eight while the header carried nothing but the rate field's absence, and
     * every offset after it was written as {@code 8 + 24 + 2}. Adding the rate byte made
     * the header nine, and a check that keeps subtracting eight then reads the SNAP one
     * byte early: it printed "header WRONG: byte 34 is 0, AWDL-13 says aa" on a frame
     * whose SNAP was at thirty-five and perfectly correct. A self-check that lies is
     * worse than none, because the frames it condemns are the ones being trusted for
     * everything else. The length is in the header (version, pad, then a little-endian
     * sixteen-bit length), so read it instead of assuming it.
     */
    private static int radiotapLength(byte[] p) {
        if (p.length < 4) return 8;
        int len = (p[2] & 0xff) | ((p[3] & 0xff) << 8);
        return (len < 8 || len > 64) ? 8 : len;
    }

    /** A hex dump of one range, for comparing a built frame against a spec by eye. */
    private static String hex(byte[] p, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < p.length; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", p[i] & 0xff));
        }
        return sb.toString();
    }

    /**
     * The ports a SYN burst should try, from a comma-separated argument.
     *
     * <p>Empty means the conventional 8770 alone, so a run without the argument behaves
     * exactly as it did before the port became a question. A caller who has decoded the
     * peer's SRV passes what it read there instead.
     */
    private static int[] ports(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return new int[]{TCP_PORT};
        }
        String[] parts = spec.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String part : parts) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(text);
            } catch (NumberFormatException notAPort) {
                System.out.println("awdl-data: ignoring port \"" + text + "\"");
                continue;
            }
            if (port < 1 || port > 65535) {
                System.out.println("awdl-data: ignoring out-of-range port " + port);
                continue;
            }
            out[n++] = port;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return n == 0 ? new int[]{TCP_PORT} : trimmed;
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    /**
     * A dotted DNS name split into labels, with the trailing dot tolerated.
     *
     * <p>Kept next to {@link #join(String[], String)} so a name printed in the log is
     * spelled the same way it was parsed, which matters when the whole experiment is
     * "did the peer answer the name I asked about".
     */
    private static String[] labels(String name) {
        String trimmed = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        return trimmed.split("\\.");
    }

    private static String join(String[] values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static boolean sameMac(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != 6 || b.length != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the driver already holds a target slot for this address.
     *
     * <p>A move costs one of the driver's eight target slots and nothing ever gives one
     * back (see {@link #MAX_AIMS}), so "aim at this address" and "aim at this address
     * again" are not the same act: the second is free, and counting it as a move is how
     * a link that is working gets retired early. The table is a module parameter, so
     * each entry's MAC is readable from here without a shell, and a hit means the next
     * frame to it is mapped by a slot that already exists.
     *
     * <p>An absent parameter, an unreadable file or an unexpected format all answer
     * {@code false}: the caller's fallback is the conservative one, and a missed free
     * move costs a slot, while a guessed one costs the frame.
     */
    private static boolean driverHolds(byte[] target) {
        if (target == null || target.length != 6) {
            return false;
        }
        String wanted = mac(target);
        File[] modules = new File("/sys/module").listFiles();
        if (modules == null) {
            return false;
        }
        for (File module : modules) {
            File state = new File(new File(module, "parameters"), "opairshare_awdl_target_state");
            String text = readSmall(state);
            if (text == null) continue;
            int at = 0;
            while ((at = text.indexOf("mac=", at)) >= 0) {
                int end = at + 4;
                while (end < text.length() && "0123456789abcdefABCDEF:".indexOf(text.charAt(end)) >= 0) {
                    end++;
                }
                if (text.substring(at + 4, end).equalsIgnoreCase(wanted)) {
                    return true;
                }
                at = end;
            }
        }
        return false;
    }

    /** The whole of a small file, or {@code null} if it could not be read at all. */
    private static String readSmall(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int n = in.read(buffer);
            return n <= 0 ? "" : new String(buffer, 0, n, "UTF-8");
        } catch (IOException unreadable) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // A parameter file has nothing to lose on a failed close.
                }
            }
        }
    }

    /**
     * The unicast questions aimed at one peer, rebuilt whenever that peer is relearned.
     *
     * <p>An AWDL address is not stable. This phone answered as {@code a6:d7:35:19:c7:ba}
     * in one capture and {@code 7a:f0:34:3d:5a:e2} in the next, and a SYN aimed at the
     * old one leaves the antenna perfectly formed -- right header, right checksum, right
     * AWDL data head -- and reaches nobody, because nobody there owns the address. The
     * symptom is indistinguishable from a filter, so the address has to come from the
     * last action frame rather than from the command line.
     *
     * <p>All three probes are built together because they share the peer's link-local
     * address, and deriving it three ways in three places is how one of them ends up
     * wrong after an address change.
     */
    private static final class Aim {
        final byte[][] syns;
        final byte[] queryU;
        final byte[] echo;
        /** The same echo to ff02::1: the only probe that needs no address at all. */
        final byte[] echoAll;
        /**
         * The same echo to ff02::fb, the group the mDNS resolver lives on.
         *
         * <p>An IPv6 host accepts a packet addressed to a group it has joined and drops
         * one addressed to a group it has not, and the drop happens in the kernel before
         * any socket is consulted. So this single frame separates the two explanations
         * that the log has been unable to tell apart. If the peer answers it, the mDNS
         * group is joined on its AWDL interface, mDNS is running there, our multicast
         * addressing is fine, and silence from port 5353 is a statement about our
         * mDNS content. If the peer does not answer it while still answering the
         * identical frame sent to ff02::1, then nothing on that interface has joined
         * ff02::fb, the resolver never started there, and no amount of work on our
         * query bytes can make it reply -- the fix is upstream of the query, in whatever
         * makes iOS bring the service up on this link.
         */
        final byte[] echoMdns;
        /**
         * The same UDP shape as the mDNS question, aimed at a port nothing can own.
         *
         * <p>This exists to separate two silences that have looked identical for every run
         * so far. Port 5353 answers nothing; that could mean our datagrams never reach the
         * peer's stack, or that they arrive and no socket is bound. A datagram to an
         * unassigned port answers the question from the other side: the stack must emit
         * ICMPv6 port unreachable for it, and it will only do so if the frame was parsed,
         * the checksum verified, and the port table consulted. If this reply comes back
         * while 5353 stays quiet, the wire is proven good and the missing piece is a
         * listener on the peer -- which is a service bring-up problem, not a byte problem,
         * and no further work on our framing can move it.
         */
        final byte[] udpDead;
        /**
         * A unicast question that does not mention AirDrop at all.
         *
         * <p>Every mDNS probe so far has been asked of {@code _airdrop._tcp}, and a silence
         * to those cannot be read: it means either that the peer's resolver is not there,
         * or that it is there with no AirDrop record. This asks the one question a working
         * resolver answers no matter what it has registered -- the list of service types
         * the host itself publishes -- and it asks it over unicast with the response bit
         * set, so neither a missing group membership on the peer nor a multicast receive
         * filter on our own side can be blamed for the result.
         *
         * <p>If this comes back with records, the peer's mDNS works on this link and the
         * silence is specific to AirDrop, which puts the fault back on a byte we control.
         * If it comes back empty-shaped or not at all, then that interface carries no
         * DNS-SD at all, and every remaining question is about iOS and not about us.
         */
        final byte[] queryEnum;
        /** Kept so a retry can name the port it is asking about. */
        final int[] ports;

        Aim(byte[] src6, byte[] mac, int[] ports, String[] qname) {
            byte[] peer6 = linkLocal(mac);
            this.ports = ports.clone();
            // A SYN is what makes a peer exist in the bridge at all: it learns a peer from
            // the *receiver* address of a unicast IPv6/TCP frame, so without one every
            // later data frame is acked by nobody and dropped above the radio.
            this.syns = new byte[ports.length][];
            for (int i = 0; i < ports.length; i++) {
                syns[i] = ipv6TcpSyn(src6, peer6, ports[i]);
            }
            // The same question asked of the peer's own address with the unicast-response
            // bit set in the question class. Sending both is the test: if only this one is
            // answered, the peer hears us and ignores multicast, and the group address --
            // not the frame -- is what has been broken all along.
            this.queryU = ipv6Udp(src6, peer6, 5353, 5353,
                    dnsQuery(qname, DNS_TYPE_PTR, 0x8001));
            // A RST proves only that a kernel exists. An echo reply proves the peer's IPv6
            // stack is willing to answer this address at all, which is a different fact and
            // one that separates "no listener" from "traffic to us is filtered".
            this.echo = ipv6IcmpEcho(src6, peer6, 1);
            // Sent to the all-nodes group but addressed at L2 to the peer's unicast
            // address, the way the mDNS query above already is: multicast at IP level
            // keeps the question address-free, and a unicast receiver address keeps it
            // ackable, so "was it heard" stays answerable.
            this.echoAll = ipv6IcmpEcho(src6, allNodesGroup(), 2);
            // Sequence 3 so its replies cannot be confused with the other two; the
            // receiver address is the peer, exactly as for echoAll, so the only variable
            // between the two frames is the destination in the IPv6 header.
            this.echoMdns = ipv6IcmpEcho(src6, mdnsGroup(), 3);
            // Port 9 is the discard service: unassigned on iOS, and unlikely to be bound by
            // anything opportunistic. The payload is the same DNS question the mDNS probe
            // carries, so length and checksum arithmetic are identical between the two and
            // a difference in the answers cannot be blamed on the datagram's shape.
            this.udpDead = ipv6Udp(src6, peer6, 5353, 9,
                    dnsQuery(qname, DNS_TYPE_PTR, 0x8001));
            // Names the peer's own service-type list rather than any service in
            // particular, over unicast, so the answer depends only on whether its
            // resolver is alive on this interface.
            this.queryEnum = ipv6Udp(src6, peer6, 5353, 5353,
                    dnsQuery(new String[]{"_services", "_dns-sd", "_udp", "local"},
                            DNS_TYPE_PTR, 0x8001));
        }
    }

    static String mac(byte[] m) {
        if (m == null || m.length != 6) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", m[i] & 0xff));
        }
        return sb.toString();
    }

    private static String ipv6(byte[] p, int at) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            int group = ((p[at + i] & 0xff) << 8) | (p[at + i + 1] & 0xff);
            if (i > 0) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(group));
        }
        return sb.toString();
    }
}
