package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DNS wire codec covering the subset mDNS needs: name compression on parse,
 * uncompressed names on write, and the record types AirDrop discovery uses.
 */
public final class DnsMessage {
    public static final int TYPE_A = 1;
    public static final int TYPE_PTR = 12;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_SRV = 33;
    public static final int TYPE_NSEC = 47;
    public static final int TYPE_ANY = 255;

    public static final int CLASS_IN = 1;
    public static final int CLASS_MASK = 0x7fff;
    /** Question flag: the sender wants the answer unicast instead of multicast (QU bit). */
    public static final int CLASS_UNICAST = 0x8000;

    public static final int FLAG_RESPONSE = 0x8000;
    public static final int FLAG_AUTHORITATIVE = 0x0400;

    /** Malformed packet. */
    public static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    public static final class Question {
        public final String name;
        public final int type;
        public final int cls;
        /** Set when the querier asked for a direct answer rather than a multicast one. */
        public final boolean unicast;

        Question(String name, int type, int cls, boolean unicast) {
            this.name = name;
            this.type = type;
            this.cls = cls;
            this.unicast = unicast;
        }

        public boolean matches(String other, int otherType) {
            if (otherType != TYPE_ANY && type != TYPE_ANY && type != otherType) return false;
            return sameName(name, other);
        }

        @Override
        public String toString() {
            return name + " type=" + type + " class=" + cls;
        }
    }

    /** One resource record. {@code rdata} stays in the message buffer so that
     *  compressed names inside SRV/NSEC can be resolved. */
    public static final class Record {
        public final String name;
        public final int type;
        public final int cls;
        public final long ttl;
        private final byte[] message;
        private final int offset;
        private final int length;

        Record(String name, int type, int cls, long ttl, byte[] message, int offset, int length) {
            this.name = name;
            this.type = type;
            this.cls = cls;
            this.ttl = ttl;
            this.message = message;
            this.offset = offset;
            this.length = length;
        }

        public byte[] rdata() {
            byte[] copy = new byte[length];
            System.arraycopy(message, offset, copy, 0, length);
            return copy;
        }

        public String target() throws FormatException {
            requireLength(2);
            int[] cursor = {offset + 6};
            return readName(message, cursor);
        }

        public int port() throws FormatException {
            requireLength(6);
            return ((message[offset + 4] & 0xff) << 8) | (message[offset + 5] & 0xff);
        }

        public int priority() throws FormatException {
            requireLength(2);
            return ((message[offset] & 0xff) << 8) | (message[offset + 1] & 0xff);
        }

        public String addressV6() throws FormatException {
            requireLength(16);
            int[] groups = new int[8];
            for (int i = 0; i < 8; i++) {
                groups[i] = ((message[offset + i * 2] & 0xff) << 8) | (message[offset + i * 2 + 1] & 0xff);
            }
            int bestStart = -1;
            int bestLength = 0;
            for (int i = 0; i < 8; ) {
                if (groups[i] != 0) {
                    i++;
                    continue;
                }
                int start = i;
                while (i < 8 && groups[i] == 0) i++;
                if (i - start > bestLength) {
                    bestStart = start;
                    bestLength = i - start;
                }
            }
            if (bestLength < 2) bestStart = -1;
            StringBuilder out = new StringBuilder(39);
            for (int i = 0; i < 8; i++) {
                if (bestStart >= 0 && i == bestStart) {
                    out.append("::");
                    i += bestLength - 1;
                    continue;
                }
                if (out.length() > 0 && out.charAt(out.length() - 1) != ':') out.append(':');
                out.append(Integer.toHexString(groups[i]));
            }
            return out.toString();
        }

        public byte[] addressV4() throws FormatException {
            requireLength(4);
            byte[] out = new byte[4];
            System.arraycopy(message, offset, out, 0, 4);
            return out;
        }

        /** TXT strings, each holding one DNS-SD key/value pair. */
        public Txt txt() throws FormatException {
            List<String> entries = new ArrayList<>();
            int end = offset + length;
            int cursor = offset;
            while (cursor < end) {
                int len = message[cursor] & 0xff;
                cursor++;
                if (cursor + len > end) throw new FormatException("TXT string overruns record");
                entries.add(new String(message, cursor, len, StandardCharsets.UTF_8));
                cursor += len;
            }
            return Txt.parse(entries);
        }

        private void requireLength(int min) throws FormatException {
            if (length < min) throw new FormatException("short rdata for type " + type);
        }

        @Override
        public String toString() {
            return name + " type=" + type + " ttl=" + ttl + " len=" + length;
        }
    }

    private final byte[] data;
    private final int id;
    private final int flags;
    private final List<Question> questions;
    private final List<Record> records;

    private DnsMessage(byte[] data, int id, int flags, List<Question> questions, List<Record> records) {
        this.data = data;
        this.id = id;
        this.flags = flags;
        this.questions = Collections.unmodifiableList(questions);
        this.records = Collections.unmodifiableList(records);
    }

    public int id() {
        return id;
    }

    public boolean isResponse() {
        return (flags & FLAG_RESPONSE) != 0;
    }

    public List<Question> questions() {
        return questions;
    }

    public List<Record> records() {
        return records;
    }

    public List<Record> records(int type) {
        List<Record> out = new ArrayList<>();
        for (Record record : records) {
            if (record.type == type) out.add(record);
        }
        return out;
    }

    public static DnsMessage parse(byte[] packet) throws FormatException {
        if (packet.length < 12) throw new FormatException("short header");
        int id = unsigned16(packet, 0);
        int flags = unsigned16(packet, 2);
        int questions = unsigned16(packet, 4);
        int answers = unsigned16(packet, 6);
        int authorities = unsigned16(packet, 8);
        int additionals = unsigned16(packet, 10);

        int[] cursor = {12};
        List<Question> parsedQuestions = new ArrayList<>(questions);
        for (int i = 0; i < questions; i++) {
            String name = readName(packet, cursor);
            need(packet, cursor[0], 4);
            int type = unsigned16(packet, cursor[0]);
            int cls = unsigned16(packet, cursor[0] + 2);
            cursor[0] += 4;
            parsedQuestions.add(new Question(name, type, cls & CLASS_MASK, (cls & CLASS_UNICAST) != 0));
        }

        List<Record> parsedRecords = new ArrayList<>(answers + authorities + additionals);
        int total = answers + authorities + additionals;
        for (int i = 0; i < total; i++) {
            String name = readName(packet, cursor);
            need(packet, cursor[0], 10);
            int type = unsigned16(packet, cursor[0]);
            int cls = unsigned16(packet, cursor[0] + 2);
            long ttl = unsigned32(packet, cursor[0] + 4);
            int length = unsigned16(packet, cursor[0] + 8);
            cursor[0] += 10;
            need(packet, cursor[0], length);
            parsedRecords.add(new Record(name, type, cls & CLASS_MASK, ttl, packet, cursor[0], length));
            cursor[0] += length;
        }
        return new DnsMessage(packet, id, flags, parsedQuestions, parsedRecords);
    }

    /** Builds a single-question query, uncompressed, mDNS style. */
    public static byte[] query(String name, int type) {
        return new Builder(0, 0).question(name, type, CLASS_IN).build();
    }

    public static final class Builder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(256);
        private final int id;
        private final int flags;
        private int questionCount;
        private int recordCount;
        private boolean frozen;

        public Builder(int id, int flags) {
            this.id = id;
            this.flags = flags;
        }

        public Builder question(String name, int type, int cls) {
            writeName(body, name);
            write16(body, type);
            write16(body, cls);
            questionCount++;
            return this;
        }

        public Builder answer(String name, int type, long ttl, byte[] rdata) {
            writeName(body, name);
            write16(body, type);
            write16(body, CLASS_IN);
            body.write((int) (ttl >>> 24) & 0xff);
            body.write((int) (ttl >>> 16) & 0xff);
            body.write((int) (ttl >>> 8) & 0xff);
            body.write((int) ttl & 0xff);
            write16(body, rdata.length);
            body.write(rdata, 0, rdata.length);
            recordCount++;
            return this;
        }

        public byte[] build() {
            if (frozen) throw new IllegalStateException("builder already used");
            frozen = true;
            write16(out, id);
            write16(out, flags);
            write16(out, questionCount);
            write16(out, recordCount);
            write16(out, 0);
            write16(out, 0);
            byte[] payload = body.toByteArray();
            out.write(payload, 0, payload.length);
            return out.toByteArray();
        }
    }

    static String readName(byte[] packet, int[] cursor) throws FormatException {
        StringBuilder name = new StringBuilder();
        int at = cursor[0];
        int guard = 0;
        boolean jumped = false;
        while (true) {
            if (at >= packet.length) throw new FormatException("name overruns packet");
            int len = packet[at] & 0xff;
            if ((len & 0xc0) == 0xc0) {
                if (at + 1 >= packet.length) throw new FormatException("truncated pointer");
                int pointer = ((len & 0x3f) << 8) | (packet[at + 1] & 0xff);
                if (!jumped) cursor[0] = at + 2;
                jumped = true;
                at = pointer;
                if (++guard > 64) throw new FormatException("compression loop");
                continue;
            }
            if (len == 0) {
                if (!jumped) cursor[0] = at + 1;
                break;
            }
            if ((len & 0xc0) != 0) throw new FormatException("unsupported label type");
            if (at + 1 + len > packet.length) throw new FormatException("label overruns packet");
            if (name.length() > 0) name.append('.');
            name.append(new String(packet, at + 1, len, StandardCharsets.UTF_8));
            at += 1 + len;
        }
        return name.toString();
    }

    /**
     * Writes a name as length-prefixed labels. Labels are separated by unescaped
     * dots only: a {@code \.} stays inside its label as the two bytes {@code \} and
     * {@code .}, which is how DNS-SD spells an instance name that contains a dot.
     * {@link AirDropPeer#unescape(String)} decodes it on the read side.
     */
    static void writeName(ByteArrayOutputStream out, String name) {
        String trimmed = name.endsWith(".") && !name.endsWith("\\.") ? name.substring(0, name.length() - 1) : name;
        if (trimmed.isEmpty()) {
            out.write(0);
            return;
        }
        int start = 0;
        int i = 0;
        while (i < trimmed.length()) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length()) {
                i += 2;
                continue;
            }
            if (c == '.') {
                writeLabel(out, trimmed.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        writeLabel(out, trimmed.substring(start));
        out.write(0);
    }

    private static void writeLabel(ByteArrayOutputStream out, String label) {
        if (label.isEmpty()) return;
        byte[] raw = label.getBytes(StandardCharsets.UTF_8);
        if (raw.length > 63) throw new IllegalArgumentException("label too long: " + label);
        out.write(raw.length);
        out.write(raw, 0, raw.length);
    }

    static void write16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void need(byte[] packet, int at, int length) throws FormatException {
        if (at + length > packet.length) throw new FormatException("record overruns packet");
    }

    private static int unsigned16(byte[] packet, int at) {
        return ((packet[at] & 0xff) << 8) | (packet[at + 1] & 0xff);
    }

    private static long unsigned32(byte[] packet, int at) {
        return ((long) (packet[at] & 0xff) << 24)
            | ((packet[at + 1] & 0xff) << 16)
            | ((packet[at + 2] & 0xff) << 8)
            | (packet[at + 3] & 0xff);
    }

    /** Case-insensitive DNS name comparison, trailing dot ignored. */
    public static boolean sameName(String left, String right) {
        return trimDot(left).equalsIgnoreCase(trimDot(right));
    }

    static String trimDot(String name) {
        return name.endsWith(".") && name.length() > 1 ? name.substring(0, name.length() - 1) : name;
    }

    /** DNS-SD TXT payload: key/value pairs, bare keys allowed. */
    public static final class Txt {
        private final java.util.Map<String, String> values;

        private Txt(java.util.Map<String, String> values) {
            this.values = Collections.unmodifiableMap(values);
        }

        static Txt parse(List<String> entries) {
            java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
            for (String entry : entries) {
                if (entry == null || entry.isEmpty()) continue;
                int split = entry.indexOf('=');
                if (split <= 0) {
                    map.put(entry, "");
                } else {
                    map.put(entry.substring(0, split), entry.substring(split + 1));
                }
            }
            return new Txt(map);
        }

        static Txt parse(String single) {
            List<String> entries = new ArrayList<>();
            if (single != null && !single.isEmpty()) entries.add(single);
            return parse(entries);
        }

        public String get(String key) {
            String value = values.get(key);
            return value == null ? "" : value;
        }

        public boolean has(String key) {
            return values.containsKey(key);
        }

        public java.util.Map<String, String> values() {
            return values;
        }

        @Override
        public String toString() {
            return values.toString();
        }
    }
}
