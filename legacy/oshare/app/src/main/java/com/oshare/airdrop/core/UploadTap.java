package com.oshare.airdrop.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Diagnostic: keeps a copy of an {@code /Upload} body on the device.
 *
 * <p>Written to answer one question that cannot be answered from a log line -- what
 * the bytes of a failing transfer actually are. The receiver's archive reader refuses
 * the body with a framing error, and every remaining hypothesis about why (a block
 * length field that means something other than a length, a de-chunker that drops a
 * byte, a body that is not DVZip at all) is settled by looking at the bytes rather
 * than by reasoning about them again.
 *
 * <p>The copy is the body <em>after</em> de-chunking and <em>before</em> decompression,
 * which is exactly the layer the reader is failing on: transport framing removed,
 * archive wrapping still intact.
 *
 * <p>This class is scaffolding. It exists to be deleted once the format is settled; it
 * is not wired into the shipped path, only into {@link AirDropServer#upload}.
 */
public final class UploadTap extends InputStream {

    /** Where the copy lands. Same directory as the trace log, for the same reason. */
    public static final String PATH = "/data/local/tmp/oshare-upload.bin";

    /**
     * Where the copy lands when {@link #PATH} is not ours to write.
     *
     * <p>It usually is not: {@code /data/local/tmp} belongs to the shell, and the app
     * runs under its own uid, so the first attempt at this tap logged
     * {@code open failed: EACCES} and produced nothing. The fallback is the app's own
     * cache directory, which is always writable and reachable afterwards through
     * {@code run-as}.
     */
    private static final String FALLBACK = "oshare-upload.bin";

    /** Stop copying past this many bytes; a body larger than this is not the problem. */
    private static final long LIMIT = 48L * 1024 * 1024;

    private final InputStream in;
    private final FileOutputStream file;
    private long copied;
    private boolean summarised;

    private UploadTap(InputStream in, FileOutputStream file) {
        this.in = in;
        this.file = file;
    }

    /**
     * Wraps {@code body} so that everything read through it is also written to
     * {@link #PATH}, or to the cache directory when that path belongs to someone else.
     *
     * <p>Returns the body untouched when neither can be opened: a diagnostic that can
     * break a transfer is worse than no diagnostic, and the receive path is the thing
     * under test here.
     */
    static InputStream around(InputStream body) {
        File target = new File(PATH);
        FileOutputStream sink = open(target);
        if (sink == null) {
            String tmp = System.getProperty("java.io.tmpdir", ".");
            target = new File(tmp, FALLBACK);
            sink = open(target);
        }
        if (sink == null) {
            Trace.write("! upload tap unavailable: nothing writable");
            return body;
        }
        Trace.write("* upload tap -> " + target.getAbsolutePath());
        return new UploadTap(body, sink);
    }

    /** The opened file, or null with the reason traced. */
    private static FileOutputStream open(File target) {
        try {
            return new FileOutputStream(target, false);
        } catch (IOException unavailable) {
            Trace.write("! upload tap cannot write " + target.getAbsolutePath()
                + ": " + unavailable.getMessage());
            return null;
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int read = read(one, 0, 1);
        return read <= 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = in.read(buffer, offset, length);
        if (read > 0) record(buffer, offset, read);
        return read;
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } finally {
            try {
                file.close();
            } catch (IOException ignored) {
                // the copy is a diagnostic; a failed flush says nothing about the body
            }
            Trace.write("* upload tap kept " + copied + " B");
        }
    }

    private void record(byte[] buffer, int offset, int read) {
        if (copied < LIMIT) {
            try {
                file.write(buffer, offset, (int) Math.min(read, LIMIT - copied));
            } catch (IOException failed) {
                // Losing the copy must not lose the transfer.
                Trace.write("! upload tap write failed: " + failed.getMessage());
            }
        }
        copied += read;
        if (!summarised) {
            summarised = true;
            byte[] head = new byte[Math.min(read, 64)];
            System.arraycopy(buffer, offset, head, 0, head.length);
            Trace.write("* upload tap head " + Trace.dump(head));
        }
    }
}
