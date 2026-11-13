package com.oshare.airdrop;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a call left behind, decoded from the frame the library returns.
 *
 * <p>The frame is eight big-endian bytes of payload total, then the names as NUL-terminated
 * UTF-8 in archive order — NUL separates because a file name cannot contain one. It crosses
 * as one {@code byte[]} rather than a {@code String[]} because a share of a few hundred
 * photos would exhaust the JVM's local reference table before the array was full (see
 * {@link Native}).
 *
 * <p>This class is the one place that knows the layout. The status page, the round-trip
 * check and anything added later read the same three accessors; a second decoder would be
 * a second chance to get the byte order or the terminator wrong, and the symptom would be
 * a plausible-looking total.
 *
 * <p>Not thread-safe and not needed to be: a frame is decoded once, by whoever received it.
 */
public final class Report {
    private final long bytes;
    private final List<String> names;

    private Report(long bytes, List<String> names) {
        this.bytes = bytes;
        this.names = Collections.unmodifiableList(names);
    }

    /**
     * Decodes one frame.
     *
     * @throws IllegalArgumentException if the frame is too short to be one — a null or
     *     truncated answer means the boundary broke its own contract, and an empty report
     *     would hide that behind "nothing arrived"
     */
    public static Report of(byte[] frame) {
        if (frame == null || frame.length < 8) {
            throw new IllegalArgumentException(
                    "a report frame is at least 8 bytes, got " + (frame == null ? "null" : frame.length));
        }
        long bytes = 0;
        for (int i = 0; i < 8; i++) {
            bytes = (bytes << 8) | (frame[i] & 0xffL);
        }

        List<String> names = new ArrayList<>();
        ByteArrayOutputStream name = new ByteArrayOutputStream();
        for (int i = 8; i < frame.length; i++) {
            if (frame[i] == 0) {
                names.add(new String(name.toByteArray(), StandardCharsets.UTF_8));
                name.reset();
            } else {
                name.write(frame[i]);
            }
        }
        // A frame ends with a terminator, so a non-empty tail is a truncated frame. Reporting
        // it would show a name no file on disk has; dropping it silently would show a count
        // that is wrong in the other direction. Refusing is the only honest answer.
        if (name.size() > 0) {
            throw new IllegalArgumentException("a report frame ends with a name that is not terminated");
        }
        return new Report(bytes, names);
    }

    /** Payload bytes written. Header overhead and dropped members do not count. */
    public long bytes() {
        return bytes;
    }

    /** Names as stored, relative to the inbox, including directories. */
    public List<String> names() {
        return names;
    }

    /** True when nothing at all landed — the usual shape of a discovery probe. */
    public boolean empty() {
        return names.isEmpty() && bytes == 0;
    }
}
