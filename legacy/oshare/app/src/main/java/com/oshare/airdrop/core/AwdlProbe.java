package com.oshare.airdrop.core;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw 802.11 receive probe on the AWDL monitor interface.
 *
 * <p>The rest of the stack speaks IP over {@code wonder0}. That works for local tests
 * but not for an Apple peer: an AWDL peer exchanges no data at all until it has seen us
 * inside its own sync and availability schedule, and its mDNS records never appear on the
 * air before that. Mosey solves this by running a full AWDL MAC in user space over this
 * same interface, so the interface is not an IBSS at all -- it is a monitor/radiotap
 * device and every frame, ours and the phone's, is visible here.
 *
 * <p>This probe is the receive half of that path, and it decodes the frames field by
 * field against the AWDL spec's §3.2 layout, which was checked against real iOS captures.
 * That matters because the layout is not guessable: the schedule lives in a 34-byte
 * SyncParams TLV at odd offsets, and the election state is split across a legacy V1 TLV
 * and a V2 TLV that must be preferred when both are present.
 *
 * <p>The socket cannot be bound: {@code bind(2)} on {@code AF_PACKET} needs a
 * {@code sockaddr_ll}, and Android exposes neither that structure nor
 * {@code setsockoptBytes} (so {@code SO_BINDTODEVICE} is out of reach too). It is a
 * happy accident that binding is not needed -- {@code tcpdump -i any} shows that an
 * unbound {@code ETH_P_ALL} socket still sees every frame. The cost is that the same
 * frame arrives once per vif that carries it: this device feeds {@code wonder0},
 * {@code wondertap0} and {@code opairsharerx0} from one receive path, so every AWDL
 * frame shows up three times. The loop de-duplicates on {@code SA + sequence control},
 * which are identical across the copies.
 *
 * <p>One bionic trap is worth spelling out, because it cost a long detour.
 * {@code Os.socket()} calls {@code htons()} on the protocol argument itself when the
 * domain is {@code AF_PACKET}. Pre-swapping {@code ETH_P_ALL} the way a C caller would
 * therefore cancels out and registers a {@code prot_hook.type} of {@code 0x0300}
 * instead of the {@code htons(ETH_P_ALL)} the kernel compares against, so the socket
 * lands in a {@code ptype_specific} hash bucket that no device ever matches. It opens
 * without error and then simply never receives a frame.
 */
public final class AwdlProbe {

    private static final int AF_PACKET = 17;
    private static final int ETH_P_ALL = 0x0003;

    private static final int FC_TYPE_MGMT = 0;
    private static final int FC_SUBTYPE_ACTION = 13;

    private static final int ACTION_VENDOR = 0x7f;
    private static final int OUI_TYPE_AWDL = 0x08;

    /** Every AWDL frame carries this BSSID; it is what makes AWDL separable at L2. */
    private static final String AWDL_BSSID = "00:25:00:ff:94:73";

    /** AWDL action subtypes (SPEC §3.2: {@code subtype} after the OUI/version pair). */
    private static final Map<Integer, String> AWDL_SUBTYPES = new LinkedHashMap<>();

    static {
        AWDL_SUBTYPES.put(0x00, "PSF");
        AWDL_SUBTYPES.put(0x03, "MIF");
    }

    private static final Map<Integer, String> TLV_NAMES = new LinkedHashMap<>();

    static {
        TLV_NAMES.put(2, "ServiceResponse");
        TLV_NAMES.put(4, "SyncParams");
        TLV_NAMES.put(5, "ElectionV1");
        TLV_NAMES.put(6, "ServiceParams");
        TLV_NAMES.put(7, "HtCapabilities");
        TLV_NAMES.put(12, "DataPathState");
        TLV_NAMES.put(16, "Arpa");
        TLV_NAMES.put(17, "Ieee80211Container");
        TLV_NAMES.put(18, "ChannelSequence");
        TLV_NAMES.put(20, "SyncTree");
        TLV_NAMES.put(21, "Version");
        TLV_NAMES.put(22, "BloomFilter");
        TLV_NAMES.put(24, "ElectionV2");
    }

    private AwdlProbe() {
    }

    public static void main(String[] args) throws Exception {
        String iface = args.length > 0 ? args[0] : "wonder0";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;

        System.out.println("awdl-probe: iface=" + iface + " seconds=" + seconds);

        FileDescriptor fd = open(iface);
        try {
            run(fd, seconds);
        } finally {
            try {
                Os.close(fd);
            } catch (ErrnoException ignored) {
                // Nothing useful to do while shutting down.
            }
        }
    }

    private static FileDescriptor open(String iface) throws ErrnoException {
        // No byte swap: Os.socket() already applies htons() to the protocol when the
        // domain is AF_PACKET, so this has to be the host-order constant.
        FileDescriptor fd = Os.socket(AF_PACKET, OsConstants.SOCK_RAW, ETH_P_ALL);
        Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1 << 20);
        // A blocking read with a timeout keeps the loop responsive to the deadline
        // without a second thread or a non-blocking spin.
        Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(250));
        System.out.println("awdl-probe: packet socket open, unbound (sees every vif),"
                + " wanted=" + iface + ", filter BSSID=" + AWDL_BSSID);
        return fd;
    }

    private static void run(FileDescriptor fd, int seconds) throws ErrnoException {
        byte[] buf = new byte[8192];
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long nextReport = System.currentTimeMillis() + 5000L;

        int reads = 0;
        int timeouts = 0;
        int frames = 0;
        int awdl = 0;
        int psf = 0;
        int other = 0;
        int duplicates = 0;
        Set<String> recent = new LinkedHashSet<>();
        Map<String, Integer> senders = new LinkedHashMap<>();
        Map<String, Integer> subtypes = new LinkedHashMap<>();
        Frame last = null;

        while (System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = Os.read(fd, buf, 0, buf.length);
            } catch (InterruptedIOException timeout) {
                // SO_RCVTIMEO fired: no frame inside the window, so re-check the deadline.
                timeouts++;
                continue;
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EAGAIN) {
                    timeouts++;
                    continue;
                }
                throw e;
            }
            if (n <= 0) {
                continue;
            }
            reads++;
            frames++;

            Frame f = Frame.parse(buf, n);
            if (f == null) {
                other++;
                continue;
            }
            if (f.mgmtType != FC_TYPE_MGMT || f.subtype != FC_SUBTYPE_ACTION) {
                other++;
                continue;
            }
            if (f.category != ACTION_VENDOR || f.ouiType != OUI_TYPE_AWDL) {
                other++;
                continue;
            }
            // One receive path feeds three vifs, so each frame shows up three times.
            // The 802.11 sequence control is identical across the copies.
            if (!recent.add(f.sa + '/' + f.seq)) {
                duplicates++;
                continue;
            }
            if (recent.size() > 128) {
                recent.remove(recent.iterator().next());
            }
            awdl++;
            String name = AWDL_SUBTYPES.getOrDefault(f.awdlSubtype,
                    String.format("subtype-0x%02x", f.awdlSubtype));
            subtypes.merge(name, 1, Integer::sum);
            senders.merge(f.sa, 1, Integer::sum);
            last = f;
            if (f.awdlSubtype == 0x00) {
                psf++;
            }
            if (f.awdlSubtype == 0x00 && psf <= 4) {
                System.out.println("  " + f);
            }

            long now = System.currentTimeMillis();
            if (now >= nextReport) {
                nextReport = now + 5000L;
                System.out.println("  [" + ((deadline - now) / 1000) + "s left] frames="
                        + frames + " awdl=" + awdl + " psf=" + psf + " other=" + other
                        + " timeouts=" + timeouts);
            }
        }

        System.out.println("awdl-probe: reads=" + reads + " timeouts=" + timeouts
                + " frames=" + frames + " awdl=" + awdl + " psf=" + psf + " other=" + other
                + " dup=" + duplicates);
        System.out.println("awdl-probe: senders=" + senders);
        System.out.println("awdl-probe: subtypes=" + subtypes);
        if (last != null) {
            System.out.println("awdl-probe: last " + last);
        }
        System.out.println(psf > 0 ? "VERDICT awdl-rx" : "VERDICT no-psf");
    }

    /** A TLV inside an action body: {@code type(u8) length(u16 LE) value}. */
    private static final class Tlv {
        final int type;
        final int off;
        final int len;

        Tlv(int type, int off, int len) {
            this.type = type;
            this.off = off;
            this.len = len;
        }
    }

    /**
     * The parts of an AWDL action frame the v2 stack has to agree with a peer on.
     *
     * <p>Every field here comes from a real capture or from SPEC §3.2; nothing is
     * inferred from "the most common word in the body", which is how the first version
     * of this probe reported a channel sequence that did not exist.
     *
     * <p>Package visible rather than private: {@link AwdlTx} runs the receive side of the
     * same stack and has no business parsing the frame a second time, in a way that would
     * drift from this one.
     */
    static final class Frame {
        int mgmtType;
        int subtype;
        String da = "?";
        String sa = "?";
        String bssid = "?";
        int seq = -1;
        int signal;
        int bodyLength;

        int category = -1;
        int ouiType = -1;
        int awdlVersion = -1;
        int awdlSubtype = -1;
        long phyTx;
        long targetTx;

        /** TLV 4. */
        int nextChannel = -1;
        int nextTu = -1;
        int masterChannel = -1;
        int awPeriod = -1;
        int afPeriod = -1;
        int presenceMode = -1;
        int awCounter = -1;
        String master = "?";

        /** TLV 5 / 24: V2 wins when both are present, as SPEC requires. */
        int height = -1;
        int masterMetric = -1;
        int selfMetric = -1;
        int masterCounter = -1;
        /** Whether this frame carried Election Parameters version 2. */
        boolean v2;
        /**
         * TLV 24 only: the node this peer takes its clock from. Equal to {@link #sa} for
         * a master. Election needs it to refuse peers that follow us, which is the loop
         * break in {@code election.rs}.
         */
        String sync = "?";

        /** TLV 18. */
        String sequence = "?";
        /** TLV 18, expanded: one channel per slot, 0 for a slot that is not ours. */
        int[] seqSlots = new int[0];

        /** TLV 21. */
        int version = -1;
        int deviceClass = -1;

        /**
         * Every TLV in the frame, as {@code Name(len)} plus the payload of the ones a
         * reader has to see to believe. SPEC MDNS-2 says a node may carry its DNS-SD
         * records in the Service Response TLV instead of over mDNS, so an action frame
         * that never gets looked inside is a discovery record thrown away.
         */
        String tlvs = "";

        boolean mif;

        static Frame parse(byte[] p, int len) {
            if (len < 5) {
                return null;
            }
            int rtLen = (p[2] & 0xff) | ((p[3] & 0xff) << 8);
            // Eight is the floor, not four. A radiotap header is version(1) pad(1)
            // length(2) present(4) before the first field, so a length of four leaves the
            // present word inside the 802.11 frame and a length of five -- which the
            // passthru path really does emit on some frames -- leaves the caller reading
            // addresses six bytes past where they start. The symptom was an "odd data"
            // line whose da/sa/bssid were 80:00:00:00:00:00 / 00:e8:38:68:ff:fe /
            // 9d:ad:30:fe:80:00: the same frame shifted, not a corrupt one.
            if (rtLen < 8 || rtLen > len) {
                return null;
            }
            Frame f = new Frame();
            f.signal = radiotapSignal(p, rtLen);

            int o = rtLen;
            if (len - o < 24) {
                return null;
            }
            int fc = (p[o] & 0xff) | ((p[o + 1] & 0xff) << 8);
            f.mgmtType = (fc >> 2) & 0x3;
            f.subtype = (fc >> 4) & 0xf;
            f.da = mac(p, o + 4);
            f.sa = mac(p, o + 10);
            f.bssid = mac(p, o + 16);
            f.seq = ((p[o + 22] & 0xff) | ((p[o + 23] & 0xff) << 8)) >> 4;

            int body = o + 24;
            f.bodyLength = len - body;
            if (f.mgmtType != FC_TYPE_MGMT || f.subtype != FC_SUBTYPE_ACTION
                    || f.bodyLength < 16) {
                return f;
            }
            // category(1) OUI(3) type(1) version(1) subtype(1) reserved(1)
            // phy_tx(4) target_tx(4), then the TLVs.
            f.category = p[body] & 0xff;
            f.ouiType = p[body + 4] & 0xff;
            f.awdlVersion = p[body + 5] & 0xff;
            f.awdlSubtype = p[body + 6] & 0xff;
            f.phyTx = le32(p, body + 8);
            f.targetTx = le32(p, body + 12);

            StringBuilder kinds = new StringBuilder();
            for (Tlv t : tlvs(p, body + 16, len)) {
                switch (t.type) {
                    case 4:
                        f.readSync(p, t);
                        break;
                    case 5:
                        if (f.masterMetric < 0) {
                            f.readElectionV1(p, t);
                        }
                        break;
                    case 18:
                        f.sequence = readSequence(p, t);
                        f.seqSlots = readSlots(p, t);
                        break;
                    case 21:
                        if (t.len >= 2) {
                            f.version = p[t.off] & 0xff;
                            f.deviceClass = p[t.off + 1] & 0xff;
                        }
                        break;
                    case 24:
                        f.readElectionV2(p, t);
                        break;
                    default:
                        break;
                }
                if (kinds.length() > 0) {
                    kinds.append(' ');
                }
                kinds.append(TLV_NAMES.getOrDefault(t.type, "kind-" + t.type))
                        .append('(').append(t.len).append(')');
                // The service response is the whole point of this walk: it can carry a
                // peer's host name and service records without a single mDNS packet.
                // ARPA is the same information under a different label, and the sync
                // tree names the node above us. Sync/election/sequence are already
                // decoded into fields, so their bytes would only be noise.
                if (t.type == 2 || t.type == 16 || t.type == 20) {
                    // Text is easy to read but lossy, and the service response is a run
                    // of DNS records whose compression pointers point at offsets this
                    // frame does not contain -- so the bytes matter as much as the words.
                    String text = t.type == 16 ? arpaName(p, t.off, t.len)
                            : t.type == 2 ? dnsName(p, t.off, t.len) : "";
                    kinds.append('[').append(hexAt(p, t.off, t.len, t.type == 2 ? 220 : 40));
                    if (!text.isEmpty()) {
                        kinds.append(' ').append(printable(text));
                    }
                    kinds.append(']');
                }
                // Service Params and Data Path State are dumped for one reason: this
                // side sends Service Params as nine zero bytes, on the theory that a
                // changed service user identifier makes peers flush their mDNS caches.
                // The peer's own bytes are the only way to learn whether "all zero"
                // reads as "no services here" to it -- a node that advertises no
                // service is a node whose mDNS is worth ignoring, which would explain
                // a phone that answers our echo requests and nothing else. Data Path
                // State is dumped alongside because the two are what a peer consults
                // before it brings the IP path up, and reading one without the other
                // is what made earlier runs guess.
                if (t.type == 6 || t.type == 12) {
                    kinds.append('[').append(hexAt(p, t.off, t.len, 64)).append(']');
                }
                // The HT Capabilities TLV is the negotiation, not a description: a peer
                // that reads "twenty megahertz only" here will never send a wide frame,
                // however capable both radios are. Printing it is the only way to see
                // which side put the ceiling in.
                if (t.type == 7 && t.len >= 6) {
                    kinds.append('[').append(htCapabilities(p, t.off, t.len)).append(']');
                }
            }
            f.tlvs = kinds.toString();
            return f;
        }

        private void readSync(byte[] p, Tlv t) {
            if (t.len < 31) {
                return;
            }
            int o = t.off;
            nextChannel = p[o] & 0xff;
            nextTu = le16(p, o + 1);
            masterChannel = p[o + 3] & 0xff;
            awPeriod = le16(p, o + 5);
            afPeriod = le16(p, o + 7);
            presenceMode = p[o + 27] & 0xff;
            awCounter = le16(p, o + 29);
            master = mac(p, o + 21);
        }

        private void readElectionV1(byte[] p, Tlv t) {
            if (t.len < 19) {
                return;
            }
            height = p[t.off + 3] & 0xff;
            master = mac(p, t.off + 5);
            masterMetric = (int) le32(p, t.off + 11);
            selfMetric = (int) le32(p, t.off + 15);
        }

        /**
         * Election Parameters, version 2: {@code master, sync, master counter, height,
         * master metric, self metric, ...}. The layout is the one {@code put_election_v2}
         * writes; {@code sync} is the one field V1 has no analogue for, and it is what
         * lets election refuse a peer that follows us.
         */
        private void readElectionV2(byte[] p, Tlv t) {
            if (t.len < 40) {
                return;
            }
            master = mac(p, t.off);
            sync = mac(p, t.off + 6);
            masterCounter = (int) le32(p, t.off + 12);
            height = (int) le32(p, t.off + 16);
            masterMetric = (int) le32(p, t.off + 20);
            selfMetric = (int) le32(p, t.off + 24);
            // Last, so a truncated TLV leaves the frame flagged as V1 rather than as a
            // V2 with half its fields still at their defaults.
            v2 = true;
        }

        /**
         * SPEC: {@code count-1, encoding, duplicate, step, fill(u16), then slots}.
         * Legacy words are {@code [flags, channel]}, opclass words are
         * {@code [channel, opclass]}; a zero word means the slot is not ours.
         */
        private static String readSequence(byte[] p, Tlv t) {
            if (t.len < 6) {
                return "?";
            }
            int count = (p[t.off] & 0xff) + 1;
            int encoding = p[t.off + 1] & 0xff;
            int step = (p[t.off + 3] & 0xff) + 1;
            int at = t.off + 6;
            int end = t.off + t.len;
            StringBuilder sb = new StringBuilder();
            sb.append(encoding == 0 ? "simple" : encoding == 1 ? "legacy" : "opclass");
            sb.append("/step=").append(step).append(" [");
            for (int i = 0; i < count; i++) {
                if (encoding == 0) {
                    if (at >= end) {
                        break;
                    }
                    int channel = p[at] & 0xff;
                    sb.append(channel == 0 ? "." : String.valueOf(channel));
                    at += 1;
                } else {
                    if (at + 1 >= end) {
                        break;
                    }
                    int lo = p[at] & 0xff;
                    int hi = p[at + 1] & 0xff;
                    int channel = encoding == 1 ? hi : lo;
                    sb.append(channel == 0 ? "." : String.valueOf(channel));
                    at += 2;
                }
                if (i + 1 < count) {
                    sb.append(',');
                }
            }
            return sb.append(']').toString();
        }

        /**
         * The sequence expanded to one channel per slot, 0 where a slot is not the
         * sender's.
         *
         * <p>The decode rule is {@code channel.rs}'s and is not a guess: each slot keeps
         * the two bytes it arrived with, {@code [channel, opclass]} in the opclass
         * encoding and {@code [flags, channel]} in the legacy one, so the same byte is
         * either the channel or its operating class depending on the encoding. Simple
         * encoding has no second byte at all.
         *
         * <p>Short TLVs are tolerated by stopping early, which leaves the remaining slots
         * at 0 — "not ours" — rather than filling them with whatever followed in the
         * frame.
         */
        private static int[] readSlots(byte[] p, Tlv t) {
            int[] out = new int[AwdlFrame.SLOTS];
            if (t.len < 6) {
                return out;
            }
            int count = (p[t.off] & 0xff) + 1;
            int encoding = p[t.off + 1] & 0xff;
            int size = encoding == 0 ? 1 : 2;
            int at = t.off + 6;
            int end = t.off + t.len;
            for (int i = 0; i < count && i < out.length; i++) {
                if (at + size > end) {
                    break;
                }
                out[i] = encoding == 0 || encoding == 3
                        ? p[at] & 0xff
                        : p[at + 1] & 0xff;
                at += size;
            }
            return out;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(AWDL_SUBTYPES.getOrDefault(awdlSubtype, "subtype-"
                    + Integer.toHexString(awdlSubtype)));
            sb.append(" sa=").append(sa).append(" bssid=").append(bssid)
                    .append(" rssi=").append(signal).append("dBm")
                    .append(" len=").append(bodyLength);
            if (nextChannel >= 0) {
                sb.append(" next_ch=").append(nextChannel)
                        .append(" next_tu=").append(nextTu)
                        .append(" master=").append(master)
                        .append(" counter=").append(awCounter)
                        .append(" aw=").append(awPeriod)
                        .append(" af=").append(afPeriod)
                        .append(" presence=").append(presenceMode);
            }
            if (masterMetric >= 0) {
                sb.append(" metric=").append(masterMetric).append('/').append(selfMetric)
                        .append(" height=").append(height)
                        .append(" mcounter=").append(masterCounter);
            }
            if (!"?".equals(sequence)) {
                sb.append(" seq=").append(sequence);
            }
            if (version >= 0) {
                sb.append(" ver=0x").append(Integer.toHexString(version))
                        .append(" class=").append(deviceClass);
            }
            if (!tlvs.isEmpty()) {
                sb.append(" tlvs=").append(tlvs);
            }
            return sb.toString();
        }

        /**
         * Walks the radiotap present bitmap and returns the antenna signal in dBm.
         *
         * <p>Only the fields that shift later offsets are decoded: TSFT (bit 0) is
         * 8-byte aligned but follows the header directly, and DBM_ANTSIGNAL (bit 5) is
         * the value {@code tcpdump} prints as {@code -27dBm}.
         */
        private static int radiotapSignal(byte[] p, int rtLen) {
            if (rtLen < 8) {
                return 0;
            }
            long present = le32(p, 4);
            int off = 8;
            if ((present & 0x1) != 0) {
                off += 8;
            }
            if ((present & 0x2) != 0) {
                off += 1;
            }
            if ((present & 0x4) != 0) {
                off += 1;
            }
            if ((present & 0x8) != 0) {
                off += 4;
            }
            if ((present & 0x10) != 0) {
                off += 2;
            }
            if ((present & 0x20) != 0 && off < rtLen) {
                return p[off];
            }
            return 0;
        }

        private static String mac(byte[] p, int off) {
            StringBuilder sb = new StringBuilder(17);
            for (int i = 0; i < 6; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02x", p[off + i] & 0xff));
            }
            return sb.toString();
        }
    }

    /**
     * Walks the TLVs after the action header.
     *
     * <p>A trailing run of fewer than three zero bytes is legal padding (some nodes add
     * it to MIFs). Anything else that does not parse is a truncated TLV, so the walk
     * stops rather than inventing fields from the remainder.
     */
    private static List<Tlv> tlvs(byte[] p, int off, int end) {
        List<Tlv> out = new ArrayList<>();
        while (off + 3 <= end) {
            int type = p[off] & 0xff;
            int len = le16(p, off + 1);
            if (off + 3 + len > end) {
                break;
            }
            out.add(new Tlv(type, off + 3, len));
            off += 3 + len;
        }
        return out;
    }

    private static int le16(byte[] p, int off) {
        return (p[off] & 0xff) | ((p[off + 1] & 0xff) << 8);
    }

    /**
     * The HT Capabilities TLV as the handful of bits that decide the link's width.
     *
     * <p>Two bytes of info, then the A-MPDU parameters, then a four-byte receive MCS mask
     * and a four-byte transmit one, both little endian with a bit per index. Bit 1 of the
     * info word is the forty-megahertz channel, bits 5 and 6 are short guard intervals at
     * twenty and at forty, bit 0 is LDPC. Everything else in the thirty-two bits describes
     * aggregation or beamforming and is not what limits a file copy.
     */
    private static String htCapabilities(byte[] p, int off, int len) {
        int info = le16(p, off);
        StringBuilder sb = new StringBuilder((info & 0x0002) != 0 ? "HT40" : "HT20-only");
        if ((info & 0x0020) != 0) {
            sb.append(" sgi20");
        }
        if ((info & 0x0040) != 0) {
            sb.append(" sgi40");
        }
        if ((info & 0x0001) != 0) {
            sb.append(" ldpc");
        }
        if (len >= 10) {
            sb.append(" rx=").append(String.format("0x%04x", le16(p, off + 4)));
            sb.append(" tx=").append(String.format("0x%04x", le16(p, off + 8)));
        }
        return sb.toString();
    }

    /**
     * A run of DNS labels as text, e.g. {@code _airdrop._tcp.local}.
     *
     * <p>Compression pointers are not followed: the pointer would point into the
     * mDNS message this TLV does not contain, so one is rendered as {@code .} and
     * the walk ends. What matters for discovery is the literal labels, which is
     * where a service instance name and its type live.
     */
    private static String dnsName(byte[] p, int off, int len) {
        StringBuilder sb = new StringBuilder();
        int at = off;
        int end = off + len;
        while (at < end) {
            int n = p[at] & 0xff;
            if (n == 0) {
                break;
            }
            if ((n & 0xc0) != 0) {
                sb.append('.');
                break;
            }
            if (at + 1 + n > end) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(p, at + 1, n, java.nio.charset.StandardCharsets.ISO_8859_1));
            at += 1 + n;
        }
        return sb.toString();
    }

    /**
     * The host name in an ARPA TLV: a flags byte, a length, the label, then a
     * pointer to {@code local}. The pointer is fixed and says nothing new, so the
     * name is reported as {@code <label>.local}.
     */
    private static String arpaName(byte[] p, int off, int len) {
        if (len < 2) {
            return "";
        }
        int n = p[off + 1] & 0xff;
        if (2 + n > len) {
            return "";
        }
        String name = new String(p, off + 2, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        return 2 + n + 2 <= len ? name + ".local" : name;
    }

    /**
     * Text with anything outside printable ASCII replaced by a dot, so a DNS label
     * that happens to hold a UTF-8 device name cannot split the line it is printed on
     * and cannot turn the log into something grep calls binary.
     */
    private static String printable(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '.');
        }
        return sb.toString();
    }

    /** The value bytes as hex, cut so a long TLV cannot flood the log. */
    private static String hexAt(byte[] p, int off, int len, int cap) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(len, cap);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", p[off + i] & 0xff));
        }
        if (len > n) {
            sb.append("..").append(len);
        }
        return sb.toString();
    }

    private static long le32(byte[] p, int off) {
        long v = 0;
        for (int i = 3; i >= 0; i--) {
            v = (v << 8) | (p[off + i] & 0xffL);
        }
        return v;
    }
}
