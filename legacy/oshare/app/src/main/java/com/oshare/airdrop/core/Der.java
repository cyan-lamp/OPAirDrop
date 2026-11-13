package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The slice of DER needed to write an X.509 certificate by hand.
 *
 * <p>the core has no JNI and no third-party dependencies, and Android exposes no
 * certificate builder, so the ~200 lines below replace BouncyCastle. Every
 * primitive takes raw content bytes and wraps them with tag and length; the
 * interesting work (assembling TBSCertificate) lives in {@link Identity}.
 */
final class Der {
    static final int TAG_BOOLEAN = 0x01;
    static final int TAG_INTEGER = 0x02;
    static final int TAG_BIT_STRING = 0x03;
    static final int TAG_OCTET_STRING = 0x04;
    static final int TAG_NULL = 0x05;
    static final int TAG_OID = 0x06;
    static final int TAG_UTF8_STRING = 0x0c;
    static final int TAG_UTC_TIME = 0x17;
    static final int TAG_SEQUENCE = 0x30;
    static final int TAG_SET = 0x31;

    private Der() {
    }

    /** Wraps content in a tag-length-value triple. */
    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... parts) {
        return tlv(TAG_SEQUENCE, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(TAG_SET, concat(parts));
    }

    /** Context-specific constructed wrapper, e.g. {@code [0] EXPLICIT} for version. */
    static byte[] explicit(int tagNumber, byte[] inner) {
        return tlv(0xa0 | tagNumber, inner);
    }

    /** Context-specific primitive wrapper, e.g. {@code [2]} dNSName. */
    static byte[] contextPrimitive(int tagNumber, byte[] content) {
        return tlv(0x80 | tagNumber, content);
    }

    static byte[] bool(boolean value) {
        return tlv(TAG_BOOLEAN, new byte[] {(byte) (value ? 0xff : 0x00)});
    }

    static byte[] nullValue() {
        return tlv(TAG_NULL, new byte[0]);
    }

    /** Minimal two's complement content, as DER requires. */
    static byte[] integer(BigInteger value) {
        return tlv(TAG_INTEGER, value.toByteArray());
    }

    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    /** Prepends the unused-bit count; callers pass whole bytes. */
    static byte[] bitString(byte[] value) {
        byte[] content = new byte[value.length + 1];
        content[0] = 0;
        System.arraycopy(value, 0, content, 1, value.length);
        return tlv(TAG_BIT_STRING, content);
    }

    /** Bit string with a declared number of unused trailing bits. */
    static byte[] bitString(byte[] value, int unusedBits) {
        byte[] content = new byte[value.length + 1];
        content[0] = (byte) unusedBits;
        System.arraycopy(value, 0, content, 1, value.length);
        return tlv(TAG_BIT_STRING, content);
    }

    static byte[] octetString(byte[] value) {
        return tlv(TAG_OCTET_STRING, value);
    }

    static byte[] utf8(String text) {
        return tlv(TAG_UTF8_STRING, text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] ia5(String text) {
        return tlv(0x16, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** UTCTime; only valid for years 1950..2049, which {@link Identity} enforces. */
    static byte[] utcTime(Date date) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setTime(date);
        String text = String.format(Locale.ROOT, "%02d%02d%02d%02d%02d%02dZ",
            calendar.get(Calendar.YEAR) % 100,
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND));
        return tlv(TAG_UTC_TIME, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** Dotted-decimal object identifier, e.g. {@code 1.2.840.113549.1.1.11}. */
    static byte[] oid(String dotted) {
        String[] arcs = dotted.split("\\.");
        if (arcs.length < 2) throw new IllegalArgumentException("bad oid " + dotted);
        ByteArrayOutputStream content = new ByteArrayOutputStream(8);
        writeBase128(content, Long.parseLong(arcs[0]) * 40L + Long.parseLong(arcs[1]));
        for (int i = 2; i < arcs.length; i++) writeBase128(content, Long.parseLong(arcs[i]));
        return tlv(TAG_OID, content.toByteArray());
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int cursor = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, cursor, part.length);
            cursor += part.length;
        }
        return out;
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        int bytes = 0;
        for (int value = length; value > 0; value >>>= 8) bytes++;
        out.write(0x80 | bytes);
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            out.write((length >>> shift) & 0xff);
        }
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        int groups = 1;
        for (long probe = value; probe >= 0x80L; probe >>>= 7) groups++;
        for (int i = groups - 1; i >= 0; i--) {
            int group = (int) ((value >>> (i * 7)) & 0x7f);
            out.write(i == 0 ? group : group | 0x80);
        }
    }
}
