package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * DVZip, the block compression that wraps an AirDrop {@code /Upload} body.
 *
 * <p>The format is a bare sequence of blocks with no header, no footer and no count
 * (SPEC &sect;6.6, DVZ-1): four big-endian bytes of length, then that many bytes. The
 * top bit of the length is a flag, not part of the number — set means the block is
 * stored verbatim, clear means it is a zlib stream. That one bit is why the format
 * needs no per-block type field, and why a block that would grow under compression
 * costs four bytes instead of a decision.
 *
 * <p>Blocks are independent (DVZ-2): the compressor starts fresh every 64 KiB instead
 * of carrying a dictionary across the file. Independence is what lets a receiver
 * decode from any block boundary and lets a sender emit a block the moment it has one,
 * which is the whole reason a transfer can start before the file is done being read.
 *
 * <p>Both directions live here because they share the one constant that matters. The
 * encoder is an {@link OutputStream} so the cpio writer can stay unaware that anything
 * wraps it; the decoder is an {@link InputStream} for the same reason on the other
 * side, plugged in by {@link Cpio#decompress}.
 */
public final class DvZip {
    /** Input bytes per block when we write a body: the 64 KiB chunk of DVZ-2. */
    public static final int CHUNK = 64 * 1024;

    /**
     * Largest payload length accepted from a block header when reading.
     *
     * <p>Deliberately larger than {@link #CHUNK}, and the distinction is not academic:
     * tying the two together is what broke the first real upload. A phone sends its
     * blocks in 128 KiB slices, so its third block arrived as a deflated length of
     * 126136 -- under its own chunk size, over ours. The reader refused a body that was
     * perfectly framed, and since the refusal happens inside the archive reader the
     * phone only saw the transfer fail.
     *
     * <p>The length field <em>is</em> the framing. There is no second copy of the size to
     * cross-check against (DVZ-1), so a cap cannot verify anything; all it can do is
     * reject a valid stream from a sender that chose bigger blocks than we did, or --
     * with no cap at all -- allocate whatever four desynchronised bytes happen to say.
     * One megabyte keeps both failures away: any chunk size in real use (64, 128, 256 KiB)
     * fits with room to spare, and four random bytes still land under it only one time in
     * four thousand, after which the deflate itself fails on the garbage.
     */
    public static final int MAX_BLOCK = 1024 * 1024;

    /** Low 31 bits of a block header: the payload length. */
    private static final int LENGTH_MASK = 0x7fffffff;

    /** High bit of a block header: the payload is stored, not deflated. */
    private static final int STORED = 0x80000000;

    private DvZip() {
    }

    /**
     * True when these four bytes could be a block header.
     *
     * <p>Sniffing is how {@link Cpio#decompress} tells a DVZip body from a gzip one and
     * from a bare archive without trusting the content type, so this has to be
     * conservative. A block never exceeds {@link #MAX_BLOCK}, and no cpio magic reduces to
     * a plausible length: {@code 070707} reads as 808 million because ASCII digits are
     * large bytes. Zero is excluded too — an empty first block is legal in the format
     * but has never been worth producing, and treating an empty body as DVZip would
     * hide a truncated upload.
     */
    static boolean looksLikeBlock(byte[] header) {
        if (header == null || header.length < 4) return false;
        int raw = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16)
            | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        int length = raw & LENGTH_MASK;
        return length > 0 && length <= MAX_BLOCK;
    }

    /**
     * Writes a DVZip body: each {@link #CHUNK}-sized slice of what is written here
     * becomes one block, deflated when that is smaller and stored when it is not.
     *
     * <p>Nothing is emitted until a block is full, so a caller that writes less than
     * 64 KiB in total produces one block at {@link #finish()} — which is the common
     * case for a text file, and the reason a small transfer is four bytes of overhead
     * rather than a compressed stream that grew.
     *
     * <p>The chunk stays at 64 KiB even though the phone that receives it sends 128 KiB.
     * A block's length is its own framing, so a receiver takes whatever size arrives;
     * this side has been accepted by a real iPhone at 64 KiB, and
     * {@link #MAX_BLOCK} is what makes the opposite direction work.
     */
    public static final class Encoder extends OutputStream {
        private final OutputStream out;
        private final byte[] block = new byte[CHUNK];
        private int filled;
        private boolean finished;

        public Encoder(OutputStream out) {
            if (out == null) throw new IllegalArgumentException("out == null");
            this.out = out;
        }

        @Override
        public void write(int single) throws IOException {
            if (filled == block.length) emit();
            block[filled++] = (byte) single;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            int cursor = offset;
            int remaining = length;
            while (remaining > 0) {
                if (filled == block.length) emit();
                int take = Math.min(remaining, block.length - filled);
                System.arraycopy(data, cursor, block, filled, take);
                filled += take;
                cursor += take;
                remaining -= take;
            }
        }

        /** Emits the final, partial block. Does not close the underlying stream. */
        public void finish() throws IOException {
            if (finished) return;
            if (filled > 0) emit();
            finished = true;
            out.flush();
        }

        @Override
        public void flush() throws IOException {
            // A block cannot be flushed half-full: its length is the frame. Buffered
            // archive bytes are the caller's own problem until the block closes.
            out.flush();
        }

        private void emit() throws IOException {
            if (filled == 0) return;
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
            byte[] compressed = new byte[filled];
            int produced;
            try {
                deflater.setInput(block, 0, filled);
                deflater.finish();
                produced = deflater.deflate(compressed, 0, compressed.length);
            } finally {
                deflater.end();
            }
            if (produced > 0 && produced < filled) {
                header(produced);
                out.write(compressed, 0, produced);
            } else {
                // Storing is not a fallback, it is the better answer: the flag costs one
                // bit and saves a deflate that added bytes to already-incompressible data.
                header(STORED | filled);
                out.write(block, 0, filled);
            }
            filled = 0;
        }

        private void header(int value) throws IOException {
            out.write((value >>> 24) & 0xff);
            out.write((value >>> 16) & 0xff);
            out.write((value >>> 8) & 0xff);
            out.write(value & 0xff);
        }
    }

    /**
     * Decodes a DVZip body, presenting it as the byte stream that was compressed.
     *
     * <p>One block is held at a time, so memory stays at two blocks regardless of
     * upload size. A defect in the middle of a transfer surfaces as an {@link IOException}
     * rather than as a short file, because a short file that looks complete is the one
     * failure a receiver cannot detect afterwards.
     */
    public static InputStream decode(InputStream raw) {
        return new Decoder(raw);
    }

    private static final class Decoder extends InputStream {
        private final InputStream in;
        private byte[] block = new byte[0];
        private int position;
        private int limit;
        private boolean done;
        /** Bytes consumed from {@code in} so far, so a bad header can name its offset. */
        private long offset;
        /** Block headers described so far; the format is regular, so a dozen is plenty. */
        private int described;

        Decoder(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (position == limit) {
                if (done || !nextBlock()) return -1;
            }
            int take = Math.min(length, limit - position);
            System.arraycopy(block, position, target, offset, take);
            position += take;
            return take;
        }

        /** Loads the next block into {@code block}; false at a clean end of body. */
        private boolean nextBlock() throws IOException {
            long at = offset;
            int first = in.read();
            if (first < 0) {
                done = true;
                return false;
            }
            int raw = (first & 0xff) << 24;
            for (int shift = 16; shift >= 0; shift -= 8) {
                int next = in.read();
                if (next < 0) throw new EOFException("DVZip block header truncated");
                raw |= (next & 0xff) << shift;
            }
            offset += 4L;
            int length = raw & LENGTH_MASK;
            if (described < 16) {
                described++;
                Trace.write("* dvzip @" + at + " raw=0x" + Integer.toHexString(raw)
                    + " len=" + length + ((raw & STORED) != 0 ? " stored" : " deflated"));
            }
            if (length <= 0 || length > MAX_BLOCK) {
                throw new IOException("implausible DVZip block length " + length
                    + " (header 0x" + Integer.toHexString(raw) + " at offset " + at + ")");
            }
            byte[] payload = new byte[length];
            int got = 0;
            while (got < length) {
                int read = in.read(payload, got, length - got);
                if (read < 0) throw new EOFException("DVZip block truncated at " + got + " of " + length);
                got += read;
            }
            offset += length;
            block = (raw & STORED) != 0 ? payload : inflate(payload);
            position = 0;
            limit = block.length;
            return true;
        }

        /**
         * Inflates one block into a buffer sized by what the block actually yields.
         *
         * <p>The output cannot be sized from the header: the header carries the
         * <em>compressed</em> length, and how far deflate got on this particular slice is
         * only knowable by inflating it. A fixed output buffer is therefore wrong in the
         * quietest possible way -- it does not fail, it returns a short block, and the
         * archive reader then reports a corrupt member many kilobytes later. That is
         * exactly what a 64 KiB buffer did to the first 128 KiB block a phone sent.
         *
         * <p>The initial guess is twice the compressed size, which covers the usual case
         * (a block is a slice of a file, and slices of files compress) in one allocation
         * and grows from there for the blocks that barely compress at all.
         */
        private static byte[] inflate(byte[] payload) throws IOException {
            Inflater inflater = new Inflater(false);
            try {
                inflater.setInput(payload);
                ByteArrayOutputStream collected =
                    new ByteArrayOutputStream(Math.max(1024, Math.min(payload.length, MAX_BLOCK) * 2));
                byte[] buffer = new byte[16 * 1024];
                while (!inflater.finished()) {
                    int written = inflater.inflate(buffer);
                    if (written > 0) {
                        collected.write(buffer, 0, written);
                        continue;
                    }
                    if (inflater.needsDictionary()) {
                        throw new IOException("DVZip block needs a preset dictionary");
                    }
                    if (inflater.needsInput()) {
                        // Every block is a complete zlib stream (DVZ-2), so running out of
                        // input before the stream ends means the block lost bytes on the
                        // way here. Returning what was decoded would hide that.
                        throw new IOException("DVZip block ended mid-stream at "
                            + collected.size() + " B");
                    }
                    break;
                }
                return collected.toByteArray();
            } catch (DataFormatException corrupt) {
                throw new IOException("DVZip block is not a zlib stream: " + corrupt.getMessage(), corrupt);
            } finally {
                inflater.end();
            }
        }
    }
}
