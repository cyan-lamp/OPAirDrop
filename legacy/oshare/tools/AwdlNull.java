package com.oshare.airdrop.core;

/**
 * Sends the smallest frame that still asks for an acknowledgement: an empty QoS Data
 * frame addressed to one peer.
 *
 * <p>Why it exists. {@code tools/awdl-ack.sh} finds that our Master Indication Frames
 * are never acked, which leaves two very different readings: the peer does not hear
 * this phone at all, or it hears it and only the management path is wrong. The second
 * is not far-fetched -- the bridge hands an action frame to the firmware's management
 * queue at 6 Mbps and no explicit PHY parameters, while a QoS data frame takes the RAW
 * path with a chosen preamble, MCS and retry limit.
 *
 * <p>Twenty-six bytes of header with nothing behind it was the first attempt, and it
 * answered nothing: {@code opairshare_pas_mgmt_xmit()} rejects a data frame shorter
 * than 42 bytes or one that does not carry the AWDL LLC/SNAP
 * {@code aa aa 03 00 17 f2 08 00} at offset 26, so the frame never left the phone and
 * no counter moved. The frame built here carries that LLC plus eight bytes, which is
 * the smallest thing the bridge will forward and still needs no interpretation by the
 * receiver.
 *
 * <p>The driver already keeps the score where it can be read:
 * {@code /sys/module/qca_cld3_peach_v2/parameters/opairshare_tx_metrics} reports data
 * frame completions as {@code status0} (acked) and {@code status3} (no ack), and
 * ignores management frames altogether. Read that file before and after a run and the
 * difference is the answer; dmesg's rate-limited lines are only a second opinion.
 *
 * <p>Usage: {@code AwdlNull [iface] [peer] [count] [period-ms]}
 */
public final class AwdlNull {

    /** Frame control of a QoS Data frame: version 0, type 2, subtype 8. */
    private static final int FC_QOS_DATA = 0x0088;
    /**
     * The LLC/SNAP header the bridge insists on: {@code aa aa 03 00 17 f2 08 00},
     * which is Apple's OUI followed by the AWDL type. A data frame without it is
     * rejected before anything is queued, so a bare 26-byte frame proves nothing --
     * it never leaves the phone and never moves a counter.
     */
    private static final byte[] LLC = {(byte) 0xaa, (byte) 0xaa, 0x03, 0x00, 0x17,
            (byte) 0xf2, 0x08, 0x00};
    /** 24-byte header, two bytes of QoS control, the LLC, and eight bytes behind it. */
    private static final int HEADER_LEN = 26;
    private static final int FRAME_LEN = HEADER_LEN + 8 + 8;

    private AwdlNull() {
    }

    public static void main(String[] args) {
        String iface = args.length > 0 ? args[0] : "wondertap0";
        byte[] peer = args.length > 1 ? parseMac(args[1]) : null;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int periodMs = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        if (peer == null) {
            System.out.println("awdl-null: no peer address given");
            System.out.println("VERDICT no-peer");
            return;
        }
        byte[] self = AwdlTx.readAddress(iface);
        if (self == null) {
            System.out.println("awdl-null: cannot read /sys/class/net/" + iface + "/address");
            System.out.println("VERDICT no-address");
            return;
        }
        System.out.println("awdl-null: iface=" + iface + " self=" + AwdlTx.mac(self)
                + " peer=" + AwdlTx.mac(peer) + " count=" + count + " period=" + periodMs + "ms");

        int fd = AwdlInj.open(iface);
        if (fd < 0) {
            System.out.println("awdl-null: bind failed: " + describe(fd));
            System.out.println("VERDICT no-socket");
            return;
        }

        byte[] frame = qosNull(self, peer);
        System.out.println("awdl-null: frame " + frame.length + " bytes: " + hex(frame));

        int sent = 0;
        int failed = 0;
        try {
            for (int i = 0; i < count; i++) {
                int n = AwdlInj.send(fd, frame);
                if (n < 0) {
                    failed++;
                    System.out.println("awdl-null: send failed: " + describe(n));
                    if (failed >= 3) {
                        break;
                    }
                } else {
                    sent++;
                }
                if (periodMs > 0 && i + 1 < count) {
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            AwdlInj.close(fd);
        }

        System.out.println("awdl-null: sent=" + sent + " failed=" + failed);
        System.out.println(sent > 0 ? "VERDICT sent" : "VERDICT nothing-sent");
    }

    /**
     * A QoS Data frame carrying nothing but the required LLC/SNAP header and eight
     * bytes of zeroes: 24-byte header, two bytes of QoS control, then 16 bytes.
     *
     * <p>Radiotap in front, address 3 carrying the AWDL BSSID as every frame on this
     * link does, sequence and QoS control zero -- the driver assigns what it needs.
     * Nothing here is meant to be parsed by the peer: the only thing asked of it is a
     * MAC-level ACK.
     */
    private static byte[] qosNull(byte[] self, byte[] peer) {
        byte[] out = new byte[8 + FRAME_LEN];
        System.arraycopy(AwdlFrame.RADIOTAP, 0, out, 0, 8);
        int o = 8;
        out[o] = (byte) FC_QOS_DATA;
        out[o + 1] = (byte) (FC_QOS_DATA >> 8);
        System.arraycopy(peer, 0, out, o + 4, 6);
        System.arraycopy(self, 0, out, o + 10, 6);
        System.arraycopy(AwdlFrame.BSSID, 0, out, o + 16, 6);
        System.arraycopy(LLC, 0, out, o + HEADER_LEN, LLC.length);
        return out;
    }

    /** Parses {@code aa:bb:cc:dd:ee:ff}, or returns null if it isn't one. */
    private static byte[] parseMac(String text) {
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

    private static String hex(byte[] p) {
        StringBuilder sb = new StringBuilder(p.length * 3);
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", p[i] & 0xff));
        }
        return sb.toString();
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
}
