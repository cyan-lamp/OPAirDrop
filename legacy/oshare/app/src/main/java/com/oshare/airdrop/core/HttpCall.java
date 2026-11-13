package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * The sending half of the AirDrop conversation: an HTTP/1.1 client that speaks
 * only as much as {@code /Ask} and {@code /Upload} need.
 *
 * <p>{@link HttpRequest} deliberately stays a parser, because the server reads its
 * body a byte at a time to avoid swallowing upload bytes. This class is the mirror
 * image — it writes the head, then either a known-length body or a chunked one — and
 * exists separately so neither side grows a mode the other cannot reason about.
 *
 * <p>Two behaviours here are not obvious and both are load-bearing:
 *
 * <ul>
 *   <li><b>No {@code Expect: 100-continue}</b> — an earlier version announced the body
 *       and waited for the interim answer before writing it, on the theory that a
 *       receiver refusing the content type would say {@code 406} without reading. The
 *       iPhone never sends {@code 100}: it simply waits for bytes. So every upload sat
 *       in that wait for its full budget and only then started sending, which for a
 *       3 KB file read as a hung transfer rather than a deadline. The body now goes
 *       out immediately, which is also what the working probe did.</li>
 *   <li><b>Reuse</b> — {@code /Ask} and {@code /Upload} share one TLS session when the
 *       peer keeps it alive, which is what a Mac does. That saves a second RSA
 *       handshake per transfer, but the connection must be reopened if the previous
 *       answer carried {@code Connection: close}.</li>
 * </ul>
 *
 * <p>Certificate pinning lives in {@link Identity#clientContext}, not here: this class
 * is handed a context that only trusts one fingerprint, so it performs no hostname
 * verification on purpose. A peer's certificate is self-signed with a name the
 * discovery records may not even agree with; the fingerprint is the identity.
 */
public final class HttpCall implements Closeable {
    /*
     * These three numbers are measurements, not guesses. They used to describe a LAN,
     * and a LAN is not the link any more: with the peer reached through the AWDL
     * tunnel the probe measured a TCP connect at 26.6s and 27.5s on consecutive
     * attempts, and a TLS 1.3 handshake at 28.2s after that. The old values sat below
     * the latency of the link they were timing, so the sender gave up on connections
     * that were still being built and reported a dead peer while the peer was in fact
     * answering. Anything here that is smaller than thirty seconds is a bug.
     */

    /**
     * Socket read timeout once the exchange is under way. Also covers the handshake,
     * because it is installed before {@link SSLSocket#startHandshake()}.
     */
    public static final int READ_TIMEOUT_MS = 90_000;

    /**
     * Time allowed for TCP connect plus the TLS handshake layered on top of it.
     *
     * <p>Fifteen seconds, down from forty-five, and neither number is what actually
     * decides: a peer that never answers is given up on by the kernel first. Its SYN
     * backoff is one, two, four, eight and sixteen seconds, so the socket reports
     * {@code ETIMEDOUT} at thirty-three seconds no matter what this constant says -- the
     * measured failure spent 33.7 s there while this budget had sixteen seconds left.
     * Forty-five was therefore not a timeout at all on a dead peer, it was a promise the
     * caller would wait out somebody else's timer. Fifteen still covers every SYN the
     * backoff sends before it starts doubling, and it covers the slowest connect this link
     * has ever produced (26.6 s was measured once, on a tunnel that had not yet learned
     * the peer) only in the sense that such a connect is over the budget: a call that
     * needs half a minute to build is one the user has already abandoned.
     */
    public static final int CONNECT_TIMEOUT_MS = 15_000;

    /**
     * Send buffer the upload runs on.
     *
     * <p>Left to the platform this grows past a megabyte, and the opening write of a
     * transfer lands in it whole. Measured on a 24 MB video: the archive reported 1.06 MB
     * written inside 50 ms — sixteen blocks that went no further than this buffer — and
     * then nothing at all for 7.24 s while the kernel drained those blocks at slow-start
     * cwnd. The progress line read {@code 1 MB / 24 MB} for the whole stall, which is the
     * one moment a user judges the transfer by, and the relay log says the link was busy
     * the entire time. The buffer was not buying throughput; it was buying a queue.
     *
     * <p>At this size the application cannot be more than a round trip ahead of the wire,
     * so its own write rate becomes the link's rate and the number on screen is the number
     * on the wire. It is still several times the bandwidth-delay product of the tunnel
     * (3.3 MB/s over a few milliseconds), so it bounds how far ahead we may run, not how
     * fast.
     */
    private static final int SEND_BUFFER = 64 * 1024;

    private static final int MAX_HEAD = 64 * 1024;

    /** Answers are plists; anything past this is a peer we do not understand. */
    private static final int MAX_BODY = 1 << 20;

    private static final int CHUNK = 16 * 1024;

    /** Writes a request body; used for uploads so they never land in memory whole. */
    public interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    /** A status line, its headers, and whatever body came with it. */
    public static final class Reply {
        public final int status;
        public final Map<String, String> headers;
        public final byte[] body;

        Reply(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** Parses a plist answer, or {@code null} when this reply has no body. */
        public Map<String, Object> plist() {
            if (body == null || body.length == 0) return null;
            try {
                return Bplist.parseDictionary(body);
            } catch (Bplist.FormatException notAPlist) {
                return null;
            }
        }

        @Override
        public String toString() {
            return status + " (" + (body == null ? 0 : body.length) + " B)";
        }
    }

    private final InetAddress address;
    private final int port;
    private final SSLContext context;
    private final String hostHeader;

    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;

    /**
     * How long one read may wait, installed when the socket is built and applied to an
     * open one on request.
     *
     * <p>Not a constant because the two requests on one session need different bounds.
     * {@code /Ask} is answered by the receiver's user, so a peer that has said nothing for
     * fifteen seconds is not about to; {@code /Upload} is bounded by how long the bytes
     * take. Giving both the upload's ninety seconds turned a dead warmed session into a
     * twenty-eight second wait before the sender learned what a fresh connect would have
     * told it in forty milliseconds.
     */
    private int readTimeoutMs = READ_TIMEOUT_MS;

    /**
     * @param address   peer address, already scoped (for example
     *                  {@code fe80::1%wonder0}) — link-local addresses are unusable
     *                  without the scope and {@link InetAddress} has no setter for it
     * @param hostHeader value of the {@code Host} header, normally the peer's mDNS
     *                  host name such as {@code phone.local}
     */
    public HttpCall(InetAddress address, int port, SSLContext context, String hostHeader) {
        if (address == null) throw new IllegalArgumentException("address == null");
        if (context == null) throw new IllegalArgumentException("context == null");
        this.address = address;
        this.port = port;
        this.context = context;
        this.hostHeader = hostHeader == null || hostHeader.isEmpty()
            ? address.getHostAddress() : hostHeader;
    }

    /**
     * Sets the read budget for the requests that follow, including on an open session.
     *
     * <p>The field alone would only reach a socket that has not been built yet, and the
     * session a caller wants to bound is exactly the one the warm-up already opened: it
     * carries whatever timeout it was created with. Applying it here is what lets
     * {@code /Ask} be impatient without making {@code /Upload} impatient too.
     *
     * @param millis read timeout in milliseconds; zero or less restores
     *               {@link #READ_TIMEOUT_MS}
     */
    public HttpCall readWithin(int millis) {
        readTimeoutMs = millis > 0 ? millis : READ_TIMEOUT_MS;
        SSLSocket open = socket;
        if (open != null) {
            try {
                open.setSoTimeout(readTimeoutMs);
            } catch (SocketException gone) {
                // The socket is already unusable; the request about to run on it will
                // report that in the peer's own terms, which is the better message.
                Trace.write("* could not shorten the read timeout: " + gone.getMessage());
            }
        }
        return this;
    }

    /** {@code POST} with a body whose length is known up front. */
    public Reply post(String path, String contentType, byte[] body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        ensureOpen();
        try {
            byte[] head = head(path, contentType, "Content-Length",
                Integer.toString(payload.length), null);
            Trace.write("> " + Trace.flatten(head) + " body " + Trace.dump(payload));
            out.write(head);
            if (payload.length > 0) out.write(payload);
            out.flush();
            long sent = System.nanoTime();
            Reply reply = remember(readReply(in));
            Trace.write("< " + path + " " + reply.status + " in "
                + (System.nanoTime() - sent) / 1_000_000L + " ms, headers " + reply.headers
                + ", body " + Trace.dump(reply.body));
            return reply;
        } catch (IOException failed) {
            Trace.write("! " + path + " failed: " + failed);
            drop();
            throw failed;
        }
    }

    /**
     * {@code POST} with a streamed body. A negative {@code declaredLength} selects
     * {@code Transfer-Encoding: chunked}, which is what AirDrop's {@code /Upload}
     * expects; a known length is sent as {@code Content-Length} instead.
     */
    public Reply postStreaming(String path, String contentType, long declaredLength, Body body)
        throws IOException {
        return postStreaming(path, contentType, declaredLength, null, body);
    }

    /**
     * The same, with protocol headers the endpoint requires.
     *
     * <p>{@code /Upload} carries {@code TransferID} and {@code TotalBytes}, and neither
     * is optional: UP-9 refuses an upload whose id does not match the offer, and UP-10
     * defines {@code TotalBytes} as the sum of the offered sizes. They arrive here as a
     * map because what belongs on an {@code /Ask} and what belongs on an {@code /Upload}
     * differ, and a header the endpoint did not ask for is noise on the wire that a
     * strict receiver is free to reject.
     */
    public Reply postStreaming(String path, String contentType, long declaredLength,
                               Map<String, String> extraHeaders, Body body) throws IOException {
        ensureOpen();
        boolean chunked = declaredLength < 0;
        try {
            byte[] head = head(path, contentType,
                chunked ? "Transfer-Encoding" : "Content-Length",
                chunked ? "chunked" : Long.toString(declaredLength), extraHeaders);
            Trace.write("> " + Trace.flatten(head));
            out.write(head);
            out.flush();

            ChunkedOutputStream encoder = chunked ? new ChunkedOutputStream(out, CHUNK) : null;
            OutputStream sink = chunked ? encoder : out;
            body.writeTo(sink);
            if (chunked) encoder.finish();
            out.flush();
            long sent = System.nanoTime();
            Reply reply = remember(readReply(in));
            Trace.write("< " + path + " " + reply.status + " in "
                + (System.nanoTime() - sent) / 1_000_000L + " ms, headers " + reply.headers
                + ", body " + Trace.dump(reply.body));
            return reply;
        } catch (IOException failed) {
            Trace.write("! " + path + " failed: " + failed);
            drop();
            throw failed;
        }
    }

    private byte[] head(String path, String contentType, String framingName, String framingValue,
                        Map<String, String> extraHeaders) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(256);
        ascii(head, "POST " + path + " HTTP/1.1\r\n");
        ascii(head, "Host: " + hostHeader + "\r\n");
        ascii(head, "User-Agent: " + AirDropProtocol.USER_AGENT + "\r\n");
        if (contentType != null) ascii(head, "Content-Type: " + contentType + "\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                if (header.getKey() == null || header.getValue() == null) continue;
                ascii(head, header.getKey() + ": " + header.getValue() + "\r\n");
            }
        }
        ascii(head, framingName + ": " + framingValue + "\r\n");
        ascii(head, "Connection: keep-alive\r\n");
        ascii(head, "\r\n");
        return head.toByteArray();
    }

    private Reply readReply(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) throw new EOFException("peer closed instead of answering");
        int status = statusOf(line);
        // A peer that does send the interim answer puts it in front of the final one, so
        // the first status line is not always the answer. Skipping 1xx costs nothing
        // when nobody sends one -- this reads a line that has already arrived rather
        // than waiting for one that may never come, which is the difference between
        // ignoring the interim answer and blocking on it.
        while (status >= 100 && status < 200) {
            while (true) {
                String header = readLine(in);
                if (header == null || header.isEmpty()) break;
            }
            line = readLine(in);
            if (line == null) throw new EOFException("peer closed after an interim answer");
            status = statusOf(line);
        }
        return readReply(in, status);
    }

    private Reply readReply(InputStream in, int status) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String header = readLine(in);
            if (header == null) throw new EOFException("headers truncated");
            if (header.isEmpty()) break;
            if (++count > 64) throw new IOException("more than 64 headers");
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                header.substring(colon + 1).trim());
        }
        return new Reply(status, headers, readBody(in, headers, status));
    }

    private byte[] readBody(InputStream in, Map<String, String> headers, int status) throws IOException {
        if (status == 204 || status == 304) return new byte[0];
        String transfer = headers.get("transfer-encoding");
        if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in);
        }
        String length = headers.get("content-length");
        if (length != null) {
            int size;
            try {
                size = Integer.parseInt(length.trim());
            } catch (NumberFormatException bad) {
                throw new IOException("bad Content-Length: " + length);
            }
            if (size < 0 || size > MAX_BODY) throw new IOException("refusing " + size + " byte answer");
            byte[] body = new byte[size];
            readFully(in, body, size);
            return body;
        }
        // No framing at all: the answer ends when the connection does.
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            int read = in.read(buffer);
            if (read < 0) break;
            collected.write(buffer, 0, read);
            if (collected.size() > MAX_BODY) throw new IOException("answer larger than " + MAX_BODY);
        }
        return collected.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) throw new EOFException("chunked body truncated");
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException bad) {
                throw new IOException("bad chunk size: " + sizeLine);
            }
            if (size == 0) {
                // Trailer section, empty in practice.
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return collected.toByteArray();
            }
            if (collected.size() + size > MAX_BODY) throw new IOException("answer larger than " + MAX_BODY);
            byte[] chunk = new byte[size];
            readFully(in, chunk, size);
            collected.write(chunk, 0, size);
            readLine(in); // CRLF after the chunk
        }
    }

    private static void readFully(InputStream in, byte[] target, int size) throws IOException {
        int offset = 0;
        while (offset < size) {
            int read = in.read(target, offset, size - offset);
            if (read < 0) throw new EOFException("body truncated at " + offset + " of " + size);
            offset += read;
        }
    }

    /** Reads a CRLF-terminated line as ISO-8859-1, the encoding HTTP heads use. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(64);
        while (true) {
            int raw = in.read();
            if (raw < 0) return line.length() == 0 ? null : line.toString();
            if (raw == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') line.setLength(end - 1);
                return line.toString();
            }
            if (line.length() >= MAX_HEAD) throw new IOException("header line too long");
            line.append((char) raw);
        }
    }

    private static int statusOf(String line) throws IOException {
        // "HTTP/1.1 200 OK"
        int space = line.indexOf(' ');
        if (space < 0) throw new IOException("bad status line: " + line);
        int end = line.indexOf(' ', space + 1);
        String digits = end < 0 ? line.substring(space + 1) : line.substring(space + 1, end);
        try {
            return Integer.parseInt(digits.trim());
        } catch (NumberFormatException bad) {
            throw new IOException("bad status line: " + line);
        }
    }

    private Reply remember(Reply reply) {
        String connection = reply.header("connection");
        if (connection != null && connection.toLowerCase(Locale.ROOT).contains("close")) drop();
        return reply;
    }

    private void ensureOpen() throws IOException {
        if (socket != null) return;
        // createSocket(address, port) connects while it builds the socket and leaves the
        // timeout to the platform, which is why CONNECT_TIMEOUT_MS spent its life as a
        // constant with no callers: there was no line to apply it on. Connecting
        // explicitly is the only way to put a bound on a handshake that this link
        // measures in tens of seconds. The socket factory still supplies the layer, and
        // InetSocketAddress keeps the scope id that a link-local peer address needs.
        //
        // Every phase is timed separately because "connect took 429 ms on a link whose
        // round trip is 9 ms" is not a statement about the link. Without the split there
        // is no way to tell a slow factory from a slow connect from a tunnel that has not
        // woken up yet, and all three have been blamed for the same number in turn.
        long began = System.nanoTime();
        SSLSocket fresh = (SSLSocket) context.getSocketFactory().createSocket();
        long built = System.nanoTime();
        boolean opened = false;
        long started = built;
        try {
            fresh.setSoTimeout(readTimeoutMs);
            // Nagle off. The offer is a header followed by a body, and with it on the body
            // waits a round trip for the header's acknowledgement: measured on the relay,
            // 204 B and then 625 B left 64 ms apart. Nothing on the upload side gets
            // smaller from this -- a flush writes one TLS record whatever it holds.
            try {
                fresh.setTcpNoDelay(true);
            } catch (SocketException refused) {
                Trace.write("! tcp nodelay refused: " + refused);
            }
            // Before connect: the platform sizes the buffer when the socket is built, and
            // a late call only relabels a queue that is already the wrong size. Failure is
            // not fatal -- a socket that would not take the setting still transfers, it
            // just does so with the buffer this class exists to avoid.
            try {
                fresh.setSendBufferSize(SEND_BUFFER);
            } catch (SocketException refused) {
                Trace.write("! send buffer " + SEND_BUFFER + " refused: " + refused);
            }
            long tuned = System.nanoTime();
            fresh.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            long connected = System.nanoTime();
            fresh.setSoLinger(false, 0);
            fresh.startHandshake();
            this.in = fresh.getInputStream();
            this.out = fresh.getOutputStream();
            this.socket = fresh;
            opened = true;
            Trace.write("* connected to " + address.getHostAddress() + ":" + port + " as "
                + hostHeader + " in " + (connected - started) / 1_000_000L + " ms, tls "
                + fresh.getSession().getProtocol() + " "
                + fresh.getSession().getCipherSuite() + " handshake "
                + (System.nanoTime() - connected) / 1_000_000L + " ms, sndbuf "
                + fresh.getSendBufferSize() + ", phases socket "
                + (built - began) / 1_000_000L + " ms, options "
                + (tuned - built) / 1_000_000L + " ms, connect "
                + (connected - tuned) / 1_000_000L + " ms");
        } catch (IOException failed) {
            Trace.write("* connect to " + address.getHostAddress() + ":" + port
                + " failed after " + (System.nanoTime() - started) / 1_000_000L + " ms: " + failed);
            throw failed;
        } finally {
            if (!opened) closeQuietly(fresh);
        }
    }

    /** Closes the connection; the next request opens a new one. */
    public void drop() {
        SSLSocket stale = socket;
        socket = null;
        in = null;
        out = null;
        closeQuietly(stale);
    }

    /** True while a connection is open and the peer has not asked to close it. */
    public boolean isConnected() {
        return socket != null;
    }

    /**
     * Builds the connection now without writing a request on it.
     *
     * <p>Everything a tap pays for before the receiver can show a prompt happens here —
     * the TCP handshake through the AWDL tunnel and the TLS handshake over it — and none
     * of it depends on which files are being sent. Doing it while the peer list is on
     * screen is what removes it from the interval the user is timing.
     *
     * <p>Nothing is written on purpose. A receiver reads one request per connection, so
     * a connection that already carried {@code /Discover} can never carry the
     * {@code /Ask} that makes it prompt; a warmed connection is useful only while it is
     * still empty.
     */
    public void open() throws IOException {
        ensureOpen();
    }

    @Override
    public void close() {
        drop();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do while tearing down.
        }
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        out.write(raw, 0, raw.length);
    }

    /**
     * Buffers writes into fixed chunks so each one carries a length prefix. Flushing
     * mid-member is fine — cpio is a byte stream and the receiver reassembles it — and
     * it keeps peak memory at one chunk regardless of file size.
     */
    static final class ChunkedOutputStream extends OutputStream {
        private final OutputStream out;
        private final byte[] buffer;
        private int filled;

        ChunkedOutputStream(OutputStream out, int chunkSize) {
            this.out = out;
            this.buffer = new byte[chunkSize];
        }

        @Override
        public void write(int single) throws IOException {
            if (filled == buffer.length) flush();
            buffer[filled++] = (byte) single;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            int remaining = length;
            int cursor = offset;
            while (remaining > 0) {
                if (filled == buffer.length) flush();
                int take = Math.min(remaining, buffer.length - filled);
                System.arraycopy(data, cursor, buffer, filled, take);
                filled += take;
                cursor += take;
                remaining -= take;
            }
        }

        @Override
        public void flush() throws IOException {
            if (filled == 0) return;
            byte[] prefix = (Integer.toHexString(filled) + "\r\n").getBytes(StandardCharsets.US_ASCII);
            out.write(prefix);
            out.write(buffer, 0, filled);
            out.write('\r');
            out.write('\n');
            filled = 0;
            out.flush();
        }

        /** Writes the terminating zero-length chunk. Does not close the socket. */
        void finish() throws IOException {
            flush();
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }
}
