package com.oshare.airdrop.core;

/**
 * The AirDrop advertisement a sender transmits while it browses (SPEC §2, BLE-1..BLE-5).
 *
 * <p>This is layer 1 of the stack, and it is a trigger rather than a transport: nothing
 * in it carries data. A receiver scans for these, and only after hearing one does it
 * bring AWDL and its AirDrop server up. That is the whole reason an iPhone can be
 * visible at the AWDL layer -- its MIFs arrive, our data frames reach it, our TCP SYN
 * draws a RST -- while nothing on port 8770 is listening: the radio is on, the server
 * is not, and no amount of correct framing at layers 2 and 3 changes that. The wake-up
 * has to come from Bluetooth.
 *
 * <p>The layout is 20 bytes of manufacturer data under Apple's company ID:
 *
 * <pre>
 *   05 12                     AirDrop TLV type 5, payload length 0x12
 *   40                        reserved, always 0x40 in version 3
 *   tt tt tt                  tag, rotates with the advertiser's BLE address
 *   00 00 00 00               reserved
 *   03                        version 3
 *   hh hh  x4                 four 2-byte contact hashes
 *   00                        reserved
 * </pre>
 *
 * <p>The hashes are the first two bytes of SHA-256 of one of the sender's identifiers,
 * which needs an Apple ID to exist at all. A sender without one sends zero hashes
 * (BLE-3): receivers in Everyone mode wake on the advertisement itself and never look
 * at them, so the whole eighteen-byte payload still works, and Contacts Only receivers
 * stay asleep -- which is the honest outcome, since we have no identity to match.
 *
 * <p>{@link #manufacturerData()} returns the payload without the company ID, because
 * Android's {@code addManufacturerData} writes that part itself from the ID argument.
 */
public final class BleAdvert {

    /** Apple's Bluetooth SIG company ID; the key the payload hangs under. */
    public static final int APPLE_COMPANY_ID = 0x004c;

    /** The AirDrop TLV type inside Apple's manufacturer data. */
    private static final int TLV_TYPE = 0x05;

    /** Payload length the TLV declares, and the number of bytes that follow it. */
    private static final int PAYLOAD_LEN = 0x12;

    /** Reserved first byte of a version 3 payload. */
    private static final int RESERVED_HEAD = 0x40;

    /** Version 3 is what current devices answer to (BLE-2, BLE-5). */
    private static final int VERSION = 0x03;

    /** Advertising interval an Apple receiver actually hears (BLE-5). */
    public static final int INTERVAL_MS = 100;

    /** The 3-byte tag; BLE-5 asks for a random one per run. */
    private final byte[] tag;

    /** Four 2-byte contact hashes, zero when we have no Apple identity. */
    private final byte[][] hashes;

    private BleAdvert(byte[] tag, byte[][] hashes) {
        this.tag = tag.clone();
        this.hashes = new byte[hashes.length][];
        for (int i = 0; i < hashes.length; i++) {
            this.hashes[i] = hashes[i].clone();
        }
    }

    /**
     * A version 3 advertisement with no contact hashes (BLE-3, BLE-5).
     *
     * <p>Everyone mode ignores the hashes, so this reaches every receiver that is
     * willing to be reached. Contacts Only receivers do not wake, and cannot be made
     * to without an Apple ID to hash.
     */
    public static BleAdvert anonymous(byte[] tag) {
        if (tag == null || tag.length != 3) {
            throw new IllegalArgumentException("tag must be 3 bytes, got "
                    + (tag == null ? "null" : tag.length));
        }
        return new BleAdvert(tag, new byte[4][2]);
    }

    /** A random tag, as BLE-5 asks: it rotates so an observer cannot track the sender. */
    public static byte[] randomTag() {
        byte[] tag = new byte[3];
        new java.util.Random().nextBytes(tag);
        return tag;
    }

    /** The tag this advertisement carries. */
    public byte[] tag() {
        return tag.clone();
    }

    /**
     * The manufacturer data without the company ID: TLV type, length, and the 18-byte
     * payload. 20 bytes, so the whole advertisement -- flag byte, length byte, company
     * ID, this -- still fits the 31-byte legacy advertising packet.
     */
    public byte[] manufacturerData() {
        byte[] out = new byte[2 + PAYLOAD_LEN];
        out[0] = (byte) TLV_TYPE;
        out[1] = (byte) PAYLOAD_LEN;
        out[2] = (byte) RESERVED_HEAD;
        out[3] = tag[0];
        out[4] = tag[1];
        out[5] = tag[2];
        // out[6..9] stay zero: reserved.
        out[10] = (byte) VERSION;
        for (int i = 0; i < 4; i++) {
            out[11 + i * 2] = hashes[i][0];
            out[12 + i * 2] = hashes[i][1];
        }
        // out[19] stays zero: the trailing reserved byte.
        return out;
    }

    /** The same bytes as hex, for logs. */
    public String hex() {
        return hex(manufacturerData());
    }

    static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.forDigit((b >> 4) & 0xf, 16))
              .append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
