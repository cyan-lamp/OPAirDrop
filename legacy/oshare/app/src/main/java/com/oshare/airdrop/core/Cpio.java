package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * cpio archive codec for the AirDrop {@code /Upload} payload.
 *
 * <p>An upload is a {@code Content-Type: application/x-cpio} body, usually
 * gzip-compressed, holding one cpio member per shared file plus an AppleDouble
 * {@code ._name} sidecar. Both POSIX variants appear in the wild and are handled
 * here: {@code 070701}/{@code 070702} ("newc", 4-byte aligned fields) and
 * {@code 070707} ("odc", octal fields, pathname padded to an even length).
 *
 * <p>Reading is streaming so multi-gigabyte uploads never land in memory; the
 * caller decides what to keep through {@link Sink}.
 *
 * <p>Writing has two forms because the spec does. {@link #write} makes "newc", which
 * Apple's libarchive reads but UP-2 only requires a receiver to accept; {@link #writeOdc}
 * makes "odc", which is what UP-2 requires a <em>sender</em> to send. Sending uses odc.
 */
public final class Cpio {
    /**
     * Member modes, permission bits included.
     *
     * <p>UP-2a spells the wire values out — {@code 040755} for the root directory and
     * {@code 0100644} for a file — and the permission half is not decoration. A header
     * carrying only the type bits ({@code 040000}, {@code 010000}) declares a member
     * nobody may read; an extractor that honours it creates the entry and then cannot
     * fill it, and a receiver that pre-checks the mode can refuse the archive
     * outright while still counting every byte. That is a transfer that runs to 100%,
     * answers {@code 200}, and leaves nothing behind.
     */
    public static final int MODE_DIRECTORY = 0040755;
    public static final int MODE_REGULAR = 0100644;
    public static final int MODE_SYMLINK = 0120777;

    /**
     * The file-type half of a mode. Comparing a received mode against {@link #MODE_*}
     * directly stopped working the moment those constants grew their permission bits,
     * so anything that classifies an incoming entry goes through here.
     */
    public static final int TYPE_DIRECTORY = 0040000;
    public static final int TYPE_REGULAR = 0100000;
    public static final int TYPE_SYMLINK = 0120000;

    private static final int TYPE = 0170000;

    /** The type bits of {@code mode}, with permissions stripped. */
    public static int typeOf(int mode) {
        return mode & TYPE;
    }

    private static final String TRAILER = "TRAILER!!!";
    private static final int NEWC_HEADER = 110;
    private static final int ODC_HEADER = 76;
    private static final int MAX_NAME = 4096;

    /** Receives the members of an archive as they stream past. */
    public interface Sink {
        /**
         * Called once per member, before its bytes are copied. Return a stream to
         * keep the content, or {@code null} to discard it. The stream is closed for
         * you once the member has been copied.
         */
        OutputStream open(String name, long size, int mode) throws IOException;
    }

    /** One archive member held in memory. */
    public static final class Entry {
        public final String name;
        public final int mode;
        public final byte[] data;

        public Entry(String name, int mode, byte[] data) {
            this.name = name;
            this.mode = mode;
            this.data = data == null ? new byte[0] : data;
        }

        public boolean isDirectory() {
            return (mode & TYPE) == TYPE_DIRECTORY;
        }

        @Override
        public String toString() {
            return name + " (" + data.length + " bytes, mode 0" + Integer.toOctalString(mode) + ")";
        }
    }

    /**
     * Reads every member of an archive into memory. Only meant for small payloads
     * such as tests; use {@link #extract} for real uploads.
     */
    public static List<Entry> read(byte[] archive) throws IOException {
        final List<Entry> entries = new ArrayList<>();
        extract(new ByteArrayInputStream(archive), new Sink() {
            @Override
            public OutputStream open(String name, long size, int mode) {
                return new MemberCollector(entries, name, mode);
            }
        });
        return entries;
    }

    /** Collects one member into memory; {@link #close()} publishes it. */
    private static final class MemberCollector extends ByteArrayOutputStream {
        private final List<Entry> entries;
        private final String name;
        private final int mode;

        MemberCollector(List<Entry> entries, String name, int mode) {
            super(64);
            this.entries = entries;
            this.name = name;
            this.mode = mode;
        }

        @Override
        public void close() {
            entries.add(new Entry(name, mode, toByteArray()));
        }
    }

    /**
     * Streams an archive into {@code sink} and returns how many non-directory
     * members were stored. Entries whose name would escape the target directory
     * are reported to the sink as {@code null} names and skipped.
     */
    public static int extract(InputStream archive, Sink sink) throws IOException {
        PushbackInputStream in = new PushbackInputStream(decompress(archive), 32);
        int stored = 0;
        while (seekHeader(in)) {
            byte[] magic = new byte[6];
            readFully(in, magic, 0, 6);
            String tag = ascii(magic);
            boolean newc;
            final int headerSize;
            if (tag.equals("070701") || tag.equals("070702")) {
                newc = true;
                headerSize = NEWC_HEADER;
            } else if (tag.equals("070707")) {
                newc = false;
                headerSize = ODC_HEADER;
            } else {
                throw new IOException("unknown cpio magic '" + tag + "'");
            }

            byte[] header = new byte[headerSize];
            System.arraycopy(magic, 0, header, 0, 6);
            readFully(in, header, 6, headerSize - 6);

            long size = newc ? number(header, 54, 8, 16) : number(header, 65, 11, 8);
            int nameSize = (int) (newc ? number(header, 94, 8, 16) : number(header, 59, 6, 8));
            int mode = (int) (newc ? number(header, 14, 8, 16) : number(header, 18, 6, 8));
            if (size < 0L) throw new IOException("negative cpio member size");
            if (nameSize <= 0 || nameSize > MAX_NAME) {
                throw new IOException("implausible cpio name size " + nameSize);
            }

            byte[] rawName = new byte[nameSize];
            readFully(in, rawName, 0, nameSize);
            String name = cstring(rawName);
            if (TRAILER.equals(name)) return stored;

            // newc aligns header+name to four bytes. odc aligns nothing: the data starts
            // at the byte right after the name's NUL, however odd that offset is.
            int namePad = newc
                ? (4 - ((NEWC_HEADER + nameSize) % 4)) % 4
                : 0;
            skipFully(in, namePad);

            String safe = safeName(name);
            OutputStream target = safe == null ? null : sink.open(safe, size, mode);
            copy(in, target, size);
            if (target != null) {
                target.close();
                if ((mode & TYPE) != TYPE_DIRECTORY) stored++;
            }
        }
        return stored;
    }

    /** Builds a "newc" archive, the variant Apple and libarchive both accept. */
    public static byte[] write(List<Entry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int inode = 1;
        for (Entry entry : entries) {
            writeMember(out, entry.name, entry.mode, entry.data, inode++);
        }
        writeTrailer(out);
        return out.toByteArray();
    }

    /** Appends a single "newc" member to {@code out}. */
    public static void writeMember(OutputStream out, String name, int mode, byte[] data, int inode)
        throws IOException {
        int size = data == null ? 0 : data.length;
        writeHead(out, name, mode, size, inode);
        if (size > 0) {
            out.write(data, 0, size);
            pad(out, size);
        }
    }

    /**
     * Appends a member whose contents come from {@code data}, exactly {@code size}
     * bytes of it.
     *
     * <p>Sending needs this shape: {@link #write} materializes the whole archive,
     * which means a shared video has to fit in the heap twice — once as bytes and
     * once as the archive. cpio is a plain byte stream and the receiver concatenates
     * it before extracting, so nothing requires a member to be contiguous in memory.
     */
    public static void writeMember(OutputStream out, String name, int mode, long size,
                                   InputStream data, int inode) throws IOException {
        if (size < 0) throw new IllegalArgumentException("negative size: " + size);
        writeHead(out, name, mode, size, inode);
        if (size > 0) {
            copyExact(out, data, size, name);
            pad(out, size);
        }
    }

    /** Writes the {@code TRAILER!!!} member that ends a "newc" archive. */
    public static void writeTrailer(OutputStream out) throws IOException {
        writeMember(out, TRAILER, 0, new byte[0], 0);
    }

    /**
     * Exact byte count a member occupies in a "newc" archive, header and padding
     * included.
     *
     * <p>A sender needs this before writing anything: {@code /Upload} is chunked, so
     * the body length is never on the wire, but a progress bar still has to know what
     * "done" means. Deriving it here keeps the layout constants in one file, and
     * {@code SendTest} checks it against what the writer actually produces.
     */
    public static long memberLength(String name, long size) {
        long nameSize = name.getBytes(StandardCharsets.UTF_8).length + 1L;
        long head = NEWC_HEADER + nameSize;
        return head + padFor(head) + size + padFor(size);
    }

    /** Bytes the terminating member occupies. */
    public static long trailerLength() {
        return memberLength(TRAILER, 0);
    }

    /**
     * Builds an "odc" archive, which is the form UP-2 requires a sender to send.
     *
     * <p>{@link #write} produces "newc", and UP-2 says a sender MUST send odc while a
     * receiver only SHOULD accept newc -- so every upload this product has sent was in a
     * format the other end was free to refuse. odc is also the easier of the two: 76
     * bytes of ASCII octal with no four-byte alignment anywhere, and file data laid down
     * exactly as it is.
     *
     * <p>No alignment anywhere. Neither the pathname nor the data is padded, so header,
     * name and data follow each other back to back -- see {@link #writeOdcHead} for why
     * an odd name must not be rounded up.
     */
    public static byte[] writeOdc(List<Entry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int inode = 1;
        for (Entry entry : entries) {
            writeOdcMember(out, entry.name, entry.mode, entry.data, inode++);
        }
        writeOdcTrailer(out);
        return out.toByteArray();
    }

    /** Appends a single "odc" member to {@code out}. */
    public static void writeOdcMember(OutputStream out, String name, int mode, byte[] data,
                                      int inode) throws IOException {
        int size = data == null ? 0 : data.length;
        writeOdcHead(out, name, mode, size, inode);
        if (size > 0) out.write(data, 0, size);
    }

    /**
     * Appends an "odc" member whose contents come from {@code data}, exactly
     * {@code size} bytes of it.
     *
     * <p>The counterpart of the streaming {@code newc} writer above, and the one the
     * sender actually uses: odc needs no trailing four-byte alignment, so a shared
     * video streams from its file descriptor straight onto the socket with peak
     * memory at one 16 KiB buffer.
     */
    public static void writeOdcMember(OutputStream out, String name, int mode, long size,
                                      InputStream data, int inode) throws IOException {
        if (size < 0) throw new IllegalArgumentException("negative size: " + size);
        writeOdcHead(out, name, mode, size, inode);
        if (size > 0) copyExact(out, data, size, name);
    }

    /** Copies exactly {@code size} bytes or fails; a short member has no honest fix. */
    private static void copyExact(OutputStream out, InputStream data, long size, String name)
        throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = data.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("member " + name + " ended " + (size - remaining)
                    + " bytes into a declared " + size);
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /** Writes the {@code TRAILER!!!} member that ends an "odc" archive. */
    public static void writeOdcTrailer(OutputStream out) throws IOException {
        writeOdcMember(out, TRAILER, 0, new byte[0], 0);
    }

    /**
     * Exact byte count a member occupies in an "odc" archive.
     *
     * <p>Needed for {@code TotalBytes} and for the upload's progress accounting, and
     * derived here so the layout constants stay in one file. odc aligns nothing, so the
     * count is the header, the name with its NUL, and the data -- with no rounding
     * anywhere. A term here that rounded to an even boundary is what made the progress
     * bar agree with a writer that was inserting a byte the receiver would read as data.
     */
    public static long odcMemberLength(String name, long size) {
        long nameSize = name.getBytes(StandardCharsets.UTF_8).length + 1L;
        return ODC_HEADER + nameSize + size;
    }

    /** Bytes the terminating member occupies in an "odc" archive. */
    public static long odcTrailerLength() {
        return odcMemberLength(TRAILER, 0);
    }

    /**
     * The 76-byte octal header, then the name and its NUL, then the even-length pad.
     *
     * <p>Field order is POSIX's, and the two widths that differ from everything else --
     * {@code mtime} and {@code filesize} -- are 11 digits for a reason: octal seconds
     * stopped fitting in six in 1971 and octal sizes stopped fitting well before files
     * did. Truncating instead of overflowing keeps a header well-formed when a value is
     * out of range, which is what cpio itself does.
     *
     * <p><b>Nothing is padded here.</b> odc is the one cpio variant with no alignment at
     * all: the name and the data follow the 76-byte header back to back, and the next
     * header starts immediately after the last data byte however odd that offset is.
     * FreeBSD's cpio(5) spells it out -- odc "has no additional padding after the
     * pathname or file contents", in contrast with the old binary format where an odd
     * namesize does get a NUL. This method used to pad an odd name to an even boundary,
     * which for every file whose name plus {@code ./} plus NUL came out odd inserted one
     * stray zero byte between the name and the data. The receiver counted the declared
     * filesize from there, so the file it wrote began with that zero byte and ended one
     * byte short: a photo that will not open, a video Photos refuses to index, and a
     * transfer that still reaches 100% and answers {@code 200} because the archive is
     * exactly as long as it says. The reader had the matching off-by-one, so every
     * round-trip test agreed with the writer and nothing caught it.
     */
    private static void writeOdcHead(OutputStream out, String name, int mode, long size,
                                     int inode) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int nameSize = nameBytes.length + 1;
        StringBuilder header = new StringBuilder(ODC_HEADER);
        header.append("070707");
        header.append(oct(0, 6));                            // dev
        header.append(oct(inode, 6));                        // ino
        header.append(oct(mode, 6));                         // mode
        header.append(oct(0, 6));                            // uid
        header.append(oct(0, 6));                            // gid
        header.append(oct((mode & TYPE) == TYPE_DIRECTORY ? 2 : 1, 6)); // nlink
        header.append(oct(0, 6));                            // rdev
        header.append(oct(System.currentTimeMillis() / 1000L, 11));        // mtime
        header.append(oct(nameSize, 6));                     // namesize
        header.append(oct(size, 11));                        // filesize
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(nameBytes);
        out.write(0);
    }

    /** A zero-padded octal field of exactly {@code width} digits. */
    private static String oct(long value, int width) {
        String digits = Long.toOctalString(Math.max(0L, value));
        if (digits.length() > width) digits = digits.substring(digits.length() - width);
        StringBuilder field = new StringBuilder(width);
        for (int i = digits.length(); i < width; i++) field.append('0');
        return field.append(digits).toString();
    }

    private static void writeHead(OutputStream out, String name, int mode, long size, int inode)
        throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int nameSize = nameBytes.length + 1;
        StringBuilder header = new StringBuilder(NEWC_HEADER);
        header.append("070701");
        header.append(hex8(inode));
        header.append(hex8(mode));
        header.append(hex8(0)); // uid
        header.append(hex8(0)); // gid
        header.append(hex8(1)); // nlink
        header.append(hex8(System.currentTimeMillis() / 1000L));
        header.append(hex8(size));
        header.append(hex8(0)); // devmajor
        header.append(hex8(0)); // devminor
        header.append(hex8(0)); // rdevmajor
        header.append(hex8(0)); // rdevminor
        header.append(hex8(nameSize));
        header.append(hex8(0)); // check
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(nameBytes);
        out.write(0);
        pad(out, NEWC_HEADER + nameSize);
    }

    private static void pad(OutputStream out, long length) throws IOException {
        long remainder = padFor(length);
        for (long i = 0; i < remainder; i++) out.write(0);
    }

    private static long padFor(long length) {
        long remainder = length % 4;
        return remainder == 0 ? 0 : 4 - remainder;
    }

    /**
     * Normalizes an archive member name into a relative path, or {@code null} when
     * the name is absolute, empty, or tries to walk out of the target directory.
     */
    public static String safeName(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        if (name.isEmpty() || name.equals(".")) return null;
        StringBuilder cleaned = new StringBuilder(name.length());
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (part.indexOf('\0') >= 0) return null;
            if (cleaned.length() > 0) cleaned.append('/');
            cleaned.append(part);
        }
        return cleaned.length() == 0 ? null : cleaned.toString();
    }

    /**
     * Unwraps the transport compression, if there is any.
     *
     * <p>Three layers can sit between an {@code /Upload} body and a cpio header, and
     * the receiver cannot be told which one it got — only the content type says, and
     * peers disagree about it. So the first bytes decide: gzip announces itself, a
     * DVZip block announces a length that could not be a cpio magic, and anything
     * else is a bare archive. Reading four bytes and pushing them back costs one
     * buffer and removes the need to trust the header.
     *
     * <p>DVZip is the case that matters in practice: UP-1b says a sender that does not
     * know the receiver's capabilities must assume DVZip, so an Apple peer's upload is
     * a block sequence rather than a zlib stream, and before this it reached
     * {@link #extract} as garbage.
     */
    public static InputStream decompress(InputStream raw) throws IOException {
        PushbackInputStream probe = new PushbackInputStream(raw, 4);
        byte[] magic = new byte[4];
        int got = readAtMost(probe, magic, 0, 4);
        if (got > 0) probe.unread(magic, 0, got);
        if (got >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(probe);
        }
        if (got == 4 && DvZip.looksLikeBlock(magic)) return DvZip.decode(probe);
        return probe;
    }

    /**
     * Leaves {@code in} positioned at a header magic, absorbing at most three
     * padding bytes. Returns false at a clean end of archive.
     */
    private static boolean seekHeader(PushbackInputStream in) throws IOException {
        byte[] probe = new byte[10];
        int got = readAtMost(in, probe, 0, probe.length);
        if (got < 6) return false; // only trailing padding remains
        for (int skip = 0; skip <= 3 && skip + 6 <= got; skip++) {
            if (probe[skip] == '0' && probe[skip + 1] == '7' && probe[skip + 2] == '0'
                && probe[skip + 3] == '7' && probe[skip + 4] == '0') {
                in.unread(probe, skip, got - skip);
                return true;
            }
        }
        throw new IOException("lost cpio framing: no header within four bytes");
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0L) {
            int want = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, want);
            if (read < 0) throw new IOException("truncated cpio member: " + remaining + " bytes missing");
            if (out != null) out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipFully(InputStream in, int count) throws IOException {
        byte[] scratch = new byte[count];
        readFully(in, scratch, 0, count);
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read < 0) throw new IOException("truncated cpio header or name");
            total += read;
        }
    }

    private static int readAtMost(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    /** Parses a fixed-width ASCII number field. */
    private static long number(byte[] header, int offset, int width, int radix) throws IOException {
        long value = 0L;
        for (int i = 0; i < width; i++) {
            int digit = Character.digit(header[offset + i] & 0xff, radix);
            if (digit < 0) throw new IOException("bad cpio numeric field at " + (offset + i));
            value = value * radix + digit;
        }
        return value;
    }

    private static String cstring(byte[] raw) {
        int end = 0;
        while (end < raw.length && raw[end] != 0) end++;
        return new String(raw, 0, end, StandardCharsets.UTF_8);
    }

    private static String ascii(byte[] raw) {
        return new String(raw, StandardCharsets.US_ASCII);
    }

    private static String hex8(long value) {
        String text = Long.toHexString(value & 0xffffffffL);
        StringBuilder padded = new StringBuilder(8);
        for (int i = text.length(); i < 8; i++) padded.append('0');
        return padded.append(text).toString();
    }

    private Cpio() {
    }
}
