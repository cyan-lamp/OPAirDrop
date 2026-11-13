package com.oshare.airdrop.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The receive side of an {@code /Upload}: a directory plus the rules for turning
 * an untrusted cpio stream into files on disk.
 *
 * <p>The archive comes from another device, so every member is treated as hostile
 * until proven otherwise. Names are re-sanitized here (belt and braces over
 * {@link Cpio#safeName}), paths are checked to stay inside the target directory,
 * symlinks are dropped rather than written, collisions get a " (n)" suffix, and a
 * running byte budget stops a sender from filling the volume.
 */
public final class Inbox implements Cpio.Sink {
    /** Upper bound on a single transfer; larger uploads fail instead of filling the disk. */
    public static final long DEFAULT_LIMIT = 8L * 1024L * 1024L * 1024L;

    private static final OutputStream DISCARD = new OutputStream() {
        @Override
        public void write(int value) {
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
        }
    };

    private final File directory;
    private final long byteLimit;
    private final List<File> created = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Set<String> used = new HashSet<>();
    private long total;

    public Inbox(File directory) {
        this(directory, DEFAULT_LIMIT);
    }

    public Inbox(File directory, long byteLimit) {
        if (directory == null) throw new IllegalArgumentException("directory == null");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalArgumentException("cannot create " + directory);
        }
        this.directory = directory;
        this.byteLimit = byteLimit <= 0L ? DEFAULT_LIMIT : byteLimit;
    }

    public File directory() {
        return directory;
    }

    /** Everything written so far, in archive order. */
    public List<File> files() {
        return Collections.unmodifiableList(created);
    }

    /** Names as stored, relative to {@link #directory()} — what the UI should show. */
    public List<String> names() {
        return Collections.unmodifiableList(names);
    }

    public long bytes() {
        return total;
    }

    /** Extracts a whole archive into this inbox and returns the member count. */
    public int extract(InputStream archive) throws IOException {
        try {
            return Cpio.extract(archive, this);
        } catch (IOException failed) {
            // A member that failed mid-write has already been unlinked; drop it from
            // the record so callers never see a name without a file behind it.
            prune();
            throw failed;
        }
    }

    private void prune() {
        for (int i = created.size() - 1; i >= 0; i--) {
            if (!created.get(i).exists()) {
                created.remove(i);
                names.remove(i);
            }
        }
    }

    @Override
    public OutputStream open(String name, long size, int mode) throws IOException {
        String safe = Cpio.safeName(name);
        if (safe == null) return null; // traversal, absolute, or empty: drop silently

        int type = Cpio.typeOf(mode);
        if (type == Cpio.TYPE_SYMLINK) return null; // never create links from a peer

        if (type == Cpio.TYPE_DIRECTORY) {
            File target = inside(safe);
            if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                throw new IOException("cannot create directory " + safe);
            }
            used.add(safe);
            created.add(target);
            names.add(safe);
            return DISCARD;
        }

        String chosen = unique(safe);
        File target = inside(chosen);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        created.add(target);
        names.add(chosen);
        return new Budgeted(target);
    }

    /** Resolves a relative name and refuses anything that escapes the directory. */
    private File inside(String relative) throws IOException {
        File target = new File(directory, relative);
        String root = directory.getCanonicalPath();
        String path = target.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) {
            throw new IOException("refusing path outside inbox: " + relative);
        }
        return target;
    }

    /** First free variant of a name, honouring both the disk and this transfer. */
    private String unique(String relative) throws IOException {
        if (!taken(relative)) {
            used.add(relative);
            return relative;
        }
        int slash = relative.lastIndexOf('/');
        int dot = relative.lastIndexOf('.');
        String base = relative;
        String extension = "";
        if (dot > slash + 1) {
            base = relative.substring(0, dot);
            extension = relative.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            String candidate = base + " (" + i + ")" + extension;
            if (!taken(candidate)) {
                used.add(candidate);
                return candidate;
            }
        }
        throw new IOException("no free name for " + relative);
    }

    private boolean taken(String relative) {
        return used.contains(relative) || new File(directory, relative).exists();
    }

    /** Writes one member, counting bytes against the transfer budget. */
    private final class Budgeted extends OutputStream {
        private final File target;
        private final FileOutputStream out;
        private boolean closed;

        Budgeted(File target) throws IOException {
            this.target = target;
            this.out = new FileOutputStream(target);
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (length <= 0) return;
            if (total + length > byteLimit) {
                closeQuietly();
                target.delete();
                throw new IOException("transfer exceeds " + byteLimit + " bytes");
            }
            out.write(buffer, offset, length);
            total += length;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            out.close();
        }

        private void closeQuietly() {
            try {
                out.close();
            } catch (IOException ignored) {
                // the budget error is the one worth reporting
            }
        }
    }
}
