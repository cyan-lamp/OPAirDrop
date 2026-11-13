package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple binary property list ("bplist00") codec.
 *
 * <p>AirDrop does not use OPACK on the wire paths we serve: the {@code /Ask} and
 * {@code /Discover} bodies are binary plists, and the media capabilities blob
 * nested inside a {@code /Discover} answer is JSON bytes carried as plist data.
 * The older OPACK container only shows up as a {@code bplist} literal in the
 * Mosey dex, so a plist codec is the whole requirement here.
 *
 * <p>Values decode to plain Java: {@code Map<String,Object>}, {@code List<Object>},
 * {@code String}, {@code byte[]}, {@code Long}, {@code Float}/{@code Double},
 * {@code Boolean} and {@code java.util.Date}.
 */
public final class Bplist {
    /** Malformed payload. */
    public static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    private static final byte[] MAGIC = "bplist00".getBytes(StandardCharsets.US_ASCII);
    private static final int TRAILER_SIZE = 32;
    private static final int MAX_DEPTH = 32;
    /** Seconds between the Unix epoch and Apple's 2001-01-01 reference date. */
    private static final double APPLE_EPOCH = 978307200d;

    private final byte[] data;
    private final int[] offsets;
    private final int refSize;
    private final int topObject;
    private final Object[] cache;
    private final boolean[] done;
    private final boolean[] visiting;

    private Bplist(byte[] payload) throws FormatException {
        if (payload == null || payload.length < MAGIC.length + TRAILER_SIZE) {
            throw new FormatException("plist shorter than header plus trailer");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (payload[i] != MAGIC[i]) throw new FormatException("not a bplist00 payload");
        }
        this.data = payload;
        int trailer = payload.length - TRAILER_SIZE;
        int offsetWidth = payload[trailer + 6] & 0xff;
        int referenceWidth = payload[trailer + 7] & 0xff;
        if (!validWidth(offsetWidth)) throw new FormatException("bad offset width " + offsetWidth);
        if (!validWidth(referenceWidth)) throw new FormatException("bad reference width " + referenceWidth);

        long count = readUnsigned(trailer + 8, 8);
        long top = readUnsigned(trailer + 16, 8);
        long table = readUnsigned(trailer + 24, 8);
        if (count <= 0L || count > (1 << 20)) throw new FormatException("implausible object count " + count);
        if (top < 0L || top >= count) throw new FormatException("top object " + top + " out of range");
        if (table < MAGIC.length || table + count * offsetWidth > trailer) {
            throw new FormatException("offset table outside payload");
        }

        this.refSize = referenceWidth;
        this.topObject = (int) top;
        this.offsets = new int[(int) count];
        for (int i = 0; i < offsets.length; i++) {
            long value = readUnsigned((int) table + i * offsetWidth, offsetWidth);
            if (value < MAGIC.length || value >= trailer) {
                throw new FormatException("object " + i + " offset outside payload");
            }
            offsets[i] = (int) value;
        }
        this.cache = new Object[offsets.length];
        this.done = new boolean[offsets.length];
        this.visiting = new boolean[offsets.length];
    }

    /** Decodes the root object of a bplist payload. */
    public static Object parse(byte[] payload) throws FormatException {
        Bplist plist = new Bplist(payload);
        return plist.object(plist.topObject, 0);
    }

    /** Decodes a payload whose root must be a dictionary. */
    public static Map<String, Object> parseDictionary(byte[] payload) throws FormatException {
        Object root = parse(payload);
        if (!(root instanceof Map)) throw new FormatException("root object is not a dictionary");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;
        return map;
    }

    private Object object(int index, int depth) throws FormatException {
        if (depth > MAX_DEPTH) throw new FormatException("nesting deeper than " + MAX_DEPTH);
        if (index < 0 || index >= offsets.length) {
            throw new FormatException("object reference " + index + " out of range");
        }
        if (done[index]) return cache[index];
        if (visiting[index]) throw new FormatException("cyclic object reference at " + index);
        visiting[index] = true;

        int offset = offsets[index];
        int marker = data[offset] & 0xff;
        int type = marker >>> 4;
        int info = marker & 0x0f;
        Object value;
        switch (type) {
            case 0x0:
                value = simple(marker);
                break;
            case 0x1:
                value = integer(offset, info);
                break;
            case 0x2:
                value = real(offset, info);
                break;
            case 0x3:
                if (info != 0x3) throw new FormatException("unexpected date width " + info);
                require(offset + 1, 8);
                value = new Date(Math.round((readDouble(offset + 1) + APPLE_EPOCH) * 1000d));
                break;
            case 0x4: {
                int[] slice = length(offset, info);
                require(slice[0], slice[1]);
                byte[] blob = new byte[slice[1]];
                System.arraycopy(data, slice[0], blob, 0, slice[1]);
                value = blob;
                break;
            }
            case 0x5: {
                int[] slice = length(offset, info);
                require(slice[0], slice[1]);
                value = new String(data, slice[0], slice[1], StandardCharsets.ISO_8859_1);
                break;
            }
            case 0x6: {
                int[] slice = length(offset, info);
                require(slice[0], slice[1] * 2);
                value = new String(data, slice[0], slice[1] * 2, StandardCharsets.UTF_16BE);
                break;
            }
            case 0x7: {
                int[] slice = length(offset, info);
                require(slice[0], slice[1]);
                value = new String(data, slice[0], slice[1], StandardCharsets.UTF_8);
                break;
            }
            case 0x8: {
                int width = info + 1;
                require(offset + 1, width);
                value = readUnsigned(offset + 1, width);
                break;
            }
            case 0xa:
            case 0xc:
                value = array(offset, info, depth);
                break;
            case 0xd:
                value = dictionary(offset, info, depth);
                break;
            default:
                throw new FormatException("unsupported marker 0x" + Integer.toHexString(marker));
        }

        visiting[index] = false;
        done[index] = true;
        cache[index] = value;
        return value;
    }

    private static Object simple(int marker) throws FormatException {
        switch (marker) {
            case 0x00:
                return null;
            case 0x08:
                return Boolean.FALSE;
            case 0x09:
                return Boolean.TRUE;
            case 0x0f: // fill byte, treated as absent
                return null;
            default:
                throw new FormatException("unsupported simple marker 0x" + Integer.toHexString(marker));
        }
    }

    private Object integer(int offset, int info) throws FormatException {
        int width = 1 << info;
        require(offset + 1, width);
        if (width > 8) return readUnsigned(offset + 1 + width - 8, 8);
        return readUnsigned(offset + 1, width);
    }

    private Object real(int offset, int info) throws FormatException {
        if (info == 0x2) {
            require(offset + 1, 4);
            return Float.intBitsToFloat((int) readUnsigned(offset + 1, 4));
        }
        if (info == 0x3) {
            require(offset + 1, 8);
            return readDouble(offset + 1);
        }
        throw new FormatException("unsupported real width 2^" + info);
    }

    private List<Object> array(int offset, int info, int depth) throws FormatException {
        int[] slice = length(offset, info);
        int count = slice[1];
        if (count > data.length / refSize) throw new FormatException("array longer than payload");
        require(slice[0], count * refSize);
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(object((int) readUnsigned(slice[0] + i * refSize, refSize), depth + 1));
        }
        return list;
    }

    private Map<String, Object> dictionary(int offset, int info, int depth) throws FormatException {
        int[] slice = length(offset, info);
        int count = slice[1];
        if (count > data.length / refSize) throw new FormatException("dictionary longer than payload");
        require(slice[0], count * refSize * 2);
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Object key = object((int) readUnsigned(slice[0] + i * refSize, refSize), depth + 1);
            Object value = object((int) readUnsigned(slice[0] + (count + i) * refSize, refSize), depth + 1);
            map.put(key instanceof String ? (String) key : String.valueOf(key), value);
        }
        return map;
    }

    /**
     * Resolves the payload offset and element count of a variable length object.
     * Returns {@code [dataOffset, count]}; strings count characters, data counts bytes.
     */
    private int[] length(int offset, int info) throws FormatException {
        if (info != 0x0f) return new int[] {offset + 1, info};
        require(offset + 1, 2);
        int marker = data[offset + 1] & 0xff;
        if ((marker >>> 4) != 0x1) throw new FormatException("extended length is not an integer");
        int width = 1 << (marker & 0x0f);
        if (width > 8) throw new FormatException("extended length wider than 8 bytes");
        require(offset + 2, width);
        long value = readUnsigned(offset + 2, width);
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new FormatException("extended length out of range: " + value);
        }
        return new int[] {offset + 2 + width, (int) value};
    }

    private long readUnsigned(int offset, int width) {
        long value = 0L;
        for (int i = 0; i < width; i++) value = (value << 8) | (data[offset + i] & 0xffL);
        return value;
    }

    private double readDouble(int offset) {
        return Double.longBitsToDouble(readUnsigned(offset, 8));
    }

    private void require(int offset, int length) throws FormatException {
        if (length < 0 || offset < 0 || offset > data.length - length) {
            throw new FormatException("object runs past payload end");
        }
    }

    private static boolean validWidth(int width) {
        return width == 1 || width == 2 || width == 4 || width == 8;
    }

    // ---------------------------------------------------------------- writing

    /** Encodes a value as a bplist payload. */
    public static byte[] encode(Object value) {
        return new Encoder().encode(value);
    }

    /** Builds an insertion-ordered dictionary from alternating keys and values. */
    public static Map<String, Object> dictionary(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) throw new IllegalArgumentException("odd key/value count");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    /** Reads a string field, returning {@code null} when absent or mistyped. */
    public static String string(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** Reads a data field, returning {@code null} when absent or mistyped. */
    public static byte[] bytes(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof byte[] ? (byte[]) value : null;
    }

    /** Reads a list field, returning an empty list when absent or mistyped. */
    public static List<?> list(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
    }

    /** Reads a boolean field with the given fallback. */
    public static boolean flag(Map<?, ?> map, String key, boolean fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).longValue() != 0L;
        return fallback;
    }

    /** Reads an integer field, returning {@code fallback} when absent or mistyped. */
    public static long number(Map<?, ?> map, String key, long fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static final class Encoder {
        private final List<Object> objects = new ArrayList<>();
        private final List<int[]> references = new ArrayList<>();

        byte[] encode(Object value) {
            add(value);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC, 0, MAGIC.length);
            int referenceWidth = widthFor(objects.size() - 1);
            int[] positions = new int[objects.size()];
            for (int i = 0; i < objects.size(); i++) {
                positions[i] = out.size();
                writeObject(out, i, referenceWidth);
            }
            int tableOffset = out.size();
            int offsetWidth = widthFor(tableOffset);
            for (int position : positions) writeUnsigned(out, position, offsetWidth);
            for (int i = 0; i < 6; i++) out.write(0);
            out.write(offsetWidth);
            out.write(referenceWidth);
            writeUnsigned(out, objects.size(), 8);
            writeUnsigned(out, 0, 8);
            writeUnsigned(out, tableOffset, 8);
            return out.toByteArray();
        }

        /** Appends a value to the object table and returns its index. */
        private int add(Object value) {
            int index = objects.size();
            objects.add(value);
            references.add(null);
            if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                int[] refs = new int[map.size() * 2];
                int cursor = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    refs[cursor++] = add(entry.getKey() == null ? null : String.valueOf(entry.getKey()));
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    refs[cursor++] = add(entry.getValue());
                }
                references.set(index, refs);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                int[] refs = new int[list.size()];
                for (int i = 0; i < refs.length; i++) refs[i] = add(list.get(i));
                references.set(index, refs);
            } else if (value != null
                && !(value instanceof String)
                && !(value instanceof byte[])
                && !(value instanceof Number)
                && !(value instanceof Boolean)
                && !(value instanceof Date)) {
                throw new IllegalArgumentException("unsupported plist value " + value.getClass());
            }
            return index;
        }

        private void writeObject(ByteArrayOutputStream out, int index, int referenceWidth) {
            Object value = objects.get(index);
            if (value == null) {
                out.write(0x00);
            } else if (value instanceof Boolean) {
                out.write((Boolean) value ? 0x09 : 0x08);
            } else if (value instanceof Date) {
                out.write(0x33);
                writeUnsigned(out, Double.doubleToLongBits(((Date) value).getTime() / 1000d - APPLE_EPOCH), 8);
            } else if (value instanceof Float) {
                out.write(0x22);
                writeUnsigned(out, Float.floatToIntBits((Float) value) & 0xffffffffL, 4);
            } else if (value instanceof Double) {
                out.write(0x23);
                writeUnsigned(out, Double.doubleToLongBits((Double) value), 8);
            } else if (value instanceof Number) {
                writeInteger(out, ((Number) value).longValue());
            } else if (value instanceof byte[]) {
                byte[] blob = (byte[]) value;
                writeHeader(out, 0x4, blob.length);
                out.write(blob, 0, blob.length);
            } else if (value instanceof String) {
                String text = (String) value;
                if (ascii(text)) {
                    writeHeader(out, 0x5, text.length());
                    byte[] raw = text.getBytes(StandardCharsets.ISO_8859_1);
                    out.write(raw, 0, raw.length);
                } else {
                    writeHeader(out, 0x6, text.length());
                    byte[] raw = text.getBytes(StandardCharsets.UTF_16BE);
                    out.write(raw, 0, raw.length);
                }
            } else {
                int[] refs = references.get(index);
                writeHeader(out, value instanceof Map ? 0xd : 0xa, refs.length / (value instanceof Map ? 2 : 1));
                for (int ref : refs) writeUnsigned(out, ref, referenceWidth);
            }
        }

        private static void writeInteger(ByteArrayOutputStream out, long value) {
            int width;
            if (value >= 0L && value <= 0xffL) {
                width = 1;
            } else if (value >= 0L && value <= 0xffffL) {
                width = 2;
            } else if (value >= 0L && value <= 0xffffffffL) {
                width = 4;
            } else {
                width = 8;
            }
            out.write(0x10 | Integer.numberOfTrailingZeros(width));
            writeUnsigned(out, value, width);
        }

        private static void writeHeader(ByteArrayOutputStream out, int type, int count) {
            if (count < 0x0f) {
                out.write((type << 4) | count);
                return;
            }
            out.write((type << 4) | 0x0f);
            writeInteger(out, count);
        }

        private static boolean ascii(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) >= 0x80) return false;
            }
            return true;
        }

        private static int widthFor(long value) {
            if (value <= 0xffL) return 1;
            if (value <= 0xffffL) return 2;
            if (value <= 0xffffffffL) return 4;
            return 8;
        }

        private static void writeUnsigned(ByteArrayOutputStream out, long value, int width) {
            for (int shift = (width - 1) * 8; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xff);
            }
        }
    }
}
