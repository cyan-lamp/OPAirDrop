package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough HTTP/1.1 to serve AirDrop: read a request head, then stream the body
 * either by {@code Content-Length} or {@code Transfer-Encoding: chunked}.
 *
 * <p>Header parsing reads a byte at a time on purpose. The body follows the blank
 * line immediately, so any read-ahead buffer here would swallow upload bytes that
 * the cpio reader needs.
 */
public final class HttpRequest {
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 64;

    public final String method;
    public final String path;
    public final String version;
    private final Map<String, String> headers;

    private HttpRequest(String method, String path, String version, Map<String, String> headers) {
        this.method = method;
        this.path = path;
        this.version = version;
        this.headers = headers;
    }

    /** Reads a request head, or returns {@code null} when the peer closed cleanly. */
    public static HttpRequest read(InputStream in) throws IOException {
        String line = readLine(in);
        // Tolerate stray CRLF between pipelined requests.
        while (line != null && line.isEmpty()) line = readLine(in);
        if (line == null) return null;

        int firstSpace = line.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : line.indexOf(' ', firstSpace + 1);
        if (firstSpace <= 0) throw new IOException("bad request line: " + line);
        String method = line.substring(0, firstSpace);
        String path = secondSpace < 0 ? line.substring(firstSpace + 1) : line.substring(firstSpace + 1, secondSpace);
        String version = secondSpace < 0 ? "HTTP/1.1" : line.substring(secondSpace + 1).trim();

        Map<String, String> headers = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String header = readLine(in);
            if (header == null) throw new IOException("headers truncated");
            if (header.isEmpty()) break;
            if (++count > MAX_HEADERS) throw new IOException("more than " + MAX_HEADERS + " headers");
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            String previous = headers.get(name);
            headers.put(name, previous == null ? value : previous + ", " + value);
        }
        return new HttpRequest(method, path, version, headers);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The whole head as one line, for a trace.
     *
     * <p>Written because the framing summary was not enough to explain a stall. A phone
     * posted {@code /Discover} -- described as "chunked close" -- and then sent no body
     * byte for the rest of the connection's life, and there was no way to tell from the
     * summary whether it had promised a body at all, or promised one of a size it then
     * did not deliver. The headers are what answers that, and they exist exactly once, on
     * the one request in this protocol that has ever stalled.
     *
     * <p>Capped: a hostile or merely chatty peer must not be able to write a megabyte of
     * header into a trace file. The cap is per line, and the summary fields are printed
     * before it rather than after, so truncation never costs the framing.
     */
    public String headText() {
        StringBuilder out = new StringBuilder(160);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(header.getKey()).append(": ").append(header.getValue());
            if (out.length() > 300) {
                out.append(" ...");
                break;
            }
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    /** Declared body length, or {@code -1} when the request carries no length. */
    public long contentLength() {
        String raw = header("content-length");
        if (raw == null) return -1L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException bad) {
            return -1L;
        }
    }

    public boolean isChunked() {
        String encoding = header("transfer-encoding");
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    /** AirDrop sends {@code Expect: 100-continue} before every upload body. */
    public boolean expectsContinue() {
        String expect = header("expect");
        return expect != null && expect.toLowerCase(Locale.ROOT).contains("100-continue");
    }

    public boolean keepAlive() {
        String connection = header("connection");
        if (connection != null) {
            String value = connection.toLowerCase(Locale.ROOT);
            if (value.contains("close")) return false;
            // HTTP/1.0 can opt in explicitly, which older AirDrop peers still do.
            if (value.contains("keep-alive")) return true;
        }
        return !version.contains("1.0");
    }

    /** Opens the body, de-chunking when needed. Closes over the same stream. */
    public InputStream body(InputStream in) {
        if (isChunked()) return new Chunked(in);
        long length = contentLength();
        return new Bounded(in, length <= 0L ? 0L : length);
    }

    /** Reads the whole body, refusing anything above {@code limit} bytes. */
    public byte[] readBody(InputStream in, int limit) throws IOException {
        InputStream body = body(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = body.read(buffer, 0, buffer.length)) >= 0) {
            out.write(buffer, 0, read);
            if (out.size() > limit) throw new IOException("body exceeds " + limit + " bytes");
        }
        return out.toByteArray();
    }

    /** Reads a CRLF terminated line; {@code null} only at a clean end of stream. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return buffer.size() == 0 ? null : new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (b == '\n') break;
            if (buffer.size() >= MAX_LINE) throw new IOException("line longer than " + MAX_LINE + " bytes");
            buffer.write(b);
        }
        String line = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') end--;
        return line.substring(0, end);
    }

    /** Body delimited by {@code Content-Length}. */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long remaining;

        Bounded(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0L) return -1;
            int want = (int) Math.min(length, remaining);
            int read = in.read(buffer, offset, want);
            if (read < 0) throw new IOException("body truncated, " + remaining + " bytes missing");
            remaining -= read;
            return read;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }
    }

    /** Body framed as a sequence of hex-sized chunks. */
    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean finished;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (finished) return -1;
            if (remaining == 0L && !nextChunk()) return -1;
            int want = (int) Math.min(length, remaining);
            int read = in.read(buffer, offset, want);
            if (read < 0) throw new IOException("chunk data truncated");
            remaining -= read;
            if (remaining == 0L) readLine(in); // the CRLF that closes this chunk
            return read;
        }

        private boolean nextChunk() throws IOException {
            String line = readLine(in);
            if (line == null) throw new IOException("chunk size missing");
            int extension = line.indexOf(';');
            if (extension >= 0) line = line.substring(0, extension);
            long size;
            try {
                size = Long.parseLong(line.trim(), 16);
            } catch (NumberFormatException bad) {
                throw new IOException("bad chunk size '" + line + "'");
            }
            if (size < 0L) throw new IOException("negative chunk size");
            if (size == 0L) {
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                finished = true;
                return false;
            }
            remaining = size;
            return true;
        }
    }
}
