package com.oshare.airdrop.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * The HTTPS endpoint AirDrop senders talk to on port 8770.
 *
 * <p>A single {@link SSLServerSocket} accepts connections on behalf of {@link
 * Identity}, and each connection runs the same request loop: read a head, dispatch
 * on the path, write a response, repeat while the peer keeps the connection alive.
 * Apple's client opens one connection and walks {@code /Discover → /Ask → /Upload}
 * over it, then closes after the upload answer.
 *
 * <p>The class knows nothing about files or plists; those decisions belong to
 * {@link Handler}, which is what makes the whole path testable over loopback.
 */
public final class AirDropServer implements AutoCloseable {
    /** Room for a plist carrying a file icon, which is the largest control payload. */
    private static final int MAX_CONTROL = 4 * 1024 * 1024;

    /**
     * How long a fresh connection may spend getting to a usable TLS session.
     *
     * <p>This is a bound on the peer's patience, not ours. Measured on this link: a
     * phone sends its ClientHello and, if our ServerHello has not been acknowledged
     * by then, gives up roughly twenty seconds later -- the RST that follows is what
     * iOS renders as "declined" under the sender's avatar, so a connection that
     * overruns is not merely slow, it is reported to the user as a rejection. A
     * handshake that does work here takes 613 ms and 1133 ms, so twelve seconds is an
     * order of magnitude of slack while still tearing down well before the peer would.
     *
     * <p>Ending early is also the cheaper mistake. Until the socket closes, our own
     * kernel keeps retransmitting the unacknowledged segments with exponential
     * backoff, and on AWDL those retransmissions occupy slots that the phone's next
     * attempt -- it retries every few seconds on a fresh port -- needs for a handshake
     * that can still succeed.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 12_000;

    /**
     * Per-read bound once a session exists, covering the request head, its body and a
     * 23 MB upload's inter-chunk stalls. Long, because the download that follows a
     * successful handshake is minutes of continuous traffic and the failure it guards
     * against is a peer that vanished, not one that is slow.
     */
    private static final int READ_TIMEOUT_MS = 60_000;

    /**
     * How long a handshaken connection may sit without framing a request.
     *
     * <p>Distinct from {@link #READ_TIMEOUT_MS}, which bounds a read inside a transfer
     * that has already started. A phone that finishes the handshake and then says
     * nothing is not a slow sender, it is a probe that has already decided against
     * this device; holding its socket for a minute keeps a half-open connection in the
     * kernel and lets the retransmits of a conversation nobody is having ride the same
     * airtime the next handshake needs. Measured on this link: a stale pair
     * ({@code 8770->56774}) was still being relayed at t=32.8s, five times, and only
     * died on its own RST at t=39.5s. Real senders frame their request within a second
     * of the handshake, so eight seconds is ten times the observed need.
     */
    private static final int REQUEST_TIMEOUT_MS = 8_000;

    /**
     * How long {@code /Discover} may collect the sender's own record before answering.
     *
     * <p>This is a trade with only one right answer per link, and this link has now
     * measured both ends of it. The body carries {@code SenderComputerName} -- the only
     * name a person would recognise -- so answering without it means never learning what
     * to call the device. It used to get a quarter second, on the reasoning that the body
     * was cosmetic and the answer was what the sender was waiting for; measured, no byte
     * of it ever arrived.
     *
     * <p>Two and a half seconds was tried next, on the reasoning that a budget shorter
     * than the request head guarantees nothing. It is not the answer either, and the
     * failure is worse than losing the name: the peer sends its head, waits about one and
     * a half seconds, and then drops its half of the connection -- so the 200 written at
     * 2500 ms came back as {@code Broken pipe}, and the one exchange this endpoint exists
     * to complete failed because this side was still waiting for a body that was never
     * coming. Measured on {@code airdrop-conn-1}: head at 2138 ms after the handshake,
     * {@code broke after 4096 ms: Broken pipe}, four connecting attempts in a row.
     *
     * <p>So the budget has to fit between the two: long enough that a body already on the
     * wire can start, short enough that the answer is out before the peer's own patience
     * runs out. At 300 ms the answer did reach the peer -- the connection then lived
     * another 4.9 s without a byte of body -- which dates the peer's patience to about one
     * and a half seconds and says 700 ms is still inside it. The name is no longer the
     * reason for this window: it is kept short so the answer goes out, and the body is
     * read afterwards for as long as the peer stays.
     */
    private static final int DISCOVER_BODY_MS = 700;
    /**
     * How long the leftovers of a {@code /Discover} body may keep arriving after the
     * answer is already out.
     *
     * <p>This used to be two seconds, and two seconds was measured to be shorter than
     * the sender's own tail: the phone that posted this request stayed connected for
     * 4597 ms, so this side sent its FIN at 2.3 s -- while the sender was still pushing
     * a 3958-byte body through a link that drops about half of its long frames. Every
     * write the sender made after that FIN was answered with a reset, and a reset is
     * indistinguishable from a refusal: the phone had already read the 200, and still
     * went away without ever posting {@code /Ask}. Letting the body finish costs nothing
     * when the sender closes on its own -- the drain ends at that EOF -- and the deadline
     * only has to outlast the sender's patience, not this link's throughput.
     */
    private static final int DISCOVER_DRAIN_MS = 6_000;
    /**
     * How long the rest of a {@code /Discover} body may keep arriving once its answer is
     * already on the wire, when the body is wanted for the name it carries.
     *
     * <p>This is not a timeout on the sender. By the time it starts, the sender holds its
     * 200 and is free to hang up; what the deadline bounds is how long one connection may
     * hold one of this endpoint's threads. It has to outlast the link rather than the
     * sender: measured on this link a request head alone took 2.6 s to arrive after a
     * 525 ms handshake, so the 1142-byte body that follows it can easily need more than
     * the three seconds the sender itself stayed connected.
     */
    private static final int DISCOVER_NAME_MS = 8_000;
    /**
     * How long an {@code /Ask} body may hold up its answer.
     *
     * <p>The same shape as {@link #DISCOVER_BODY_MS}, for a failure that looked like
     * nothing happening at all. Reading the body first is what this endpoint used to do,
     * and on the direction it now serves -- an iPhone posting an offer to this phone --
     * the head arrived at 18:41:43.232 and the body never completed: this side answered
     * zero bytes for 37.1 s, until the phone gave up with {@code Connection reset} and the
     * share sheet that had said "waiting" went away with it.
     *
     * <p>The answer to {@code /Ask} is a fixed description of this device. An offer names
     * files; it does not change a byte of the reply. So the reply is built before the body
     * is touched and, when the body is late, sent without it -- 700 ms because that is the
     * window {@code /Discover} answers the same phone inside of, 883 ms measured end to
     * end.
     */
    private static final int ASK_BODY_MS = 700;
    /**
     * How long an {@code /Ask} body may keep arriving once its answer is already out.
     *
     * <p>Nobody waits on this half: the peer holds its 200 and is free to hang up. What is
     * being bought is the offer itself, which the listener wants for the name and the file
     * list, so the deadline only bounds one thread -- the same 8 s {@code /Discover} gives
     * a body it is no longer waiting for.
     */
    private static final int ASK_NAME_MS = DISCOVER_NAME_MS;

    /** Sends a fixed answer; a lambda so the read loop can call it once. */
    private interface Answer {
        /** @param collected how many bytes of the request body had arrived at that point */
        void send(int collected) throws IOException;
    }

    /** What the endpoint does once a request has been framed. */
    public interface Handler {
        /** Answers {@code /Discover}; the returned bytes become the plist body. */
        byte[] onDiscover(byte[] request) throws IOException;

        /**
         * The name a sender gave for itself when it asked who we are.
         *
         * <p>Not a courtesy: it is the only place an Apple device states a name a person
         * would recognise. The {@code _airdrop._tcp} instance name is a rotated hash and the
         * hostname a fresh UUID, so a share sheet that titles its rows from mDNS can only
         * ever show hex. The body of {@code /Discover} carries {@code SenderComputerName},
         * and a phone with its sheet open posts one of these every few seconds without
         * being asked -- which makes this the one name source that needs no connection of
         * our own, on a link where our own connections are what keep failing.
         *
         * <p>{@code address} is the link-local address the request arrived from, already
         * stripped of its scope; it is the key the name has to be filed under, because the
         * instance name beside it is about to change. Default does nothing so the several
         * endpoint implementations that do not care stay one-liners.
         */
        default void onPeerSeen(String address, String name) {
        }

        /** Answers {@code /Ask} after the body has been parsed. */
        byte[] onAsk(AirDropProtocol.Ask ask) throws IOException;

        /**
         * The bytes {@code /Ask} may be answered with before its body is read.
         *
         * <p>Empty means "no shortcut": the endpoint waits for the body and answers what
         * {@link #onAsk} returns, which is the only correct order for an endpoint whose
         * reply depends on the offer. A non-empty answer is what a fixed reply allows --
         * it goes out at {@code ASK_BODY_MS} whether or not the body ever completes, and
         * the offer is still parsed and still handed to {@link #onAsk} afterwards.
         */
        default byte[] askAnswer() throws IOException {
            return new byte[0];
        }

        /**
         * Consumes an {@code /Upload} body. Called only after {@code 100 Continue}
         * has been sent, so the stream is the de-chunked cpio archive (possibly
         * gzip-compressed, as {@link Cpio#extract} expects).
         */
        void onUpload(InputStream body, String contentType) throws IOException;
    }

    /** Optional diagnostics hook; the server never throws into the accept loop. */
    public interface Listener {
        void onError(String message, Throwable error);
    }

    private final Identity identity;
    private final Handler handler;
    private final Listener listener;
    private final ExecutorService workers;
    private final AtomicInteger connections = new AtomicInteger();

    /**
     * How many transfers are running right now, reported to whoever owns the
     * announcement schedule.
     *
     * <p>A receiver that keeps announcing while a phone is moving data is spending the
     * same slots the transfer needs. An announcement is worth three seconds of a share
     * sheet and nothing at all once somebody has picked this device, so the count is
     * published here and the announcer backs off while it is non-zero.
     *
     * <p>Incremented after {@code startHandshake()} returns, never at accept. See the
     * note in {@link #serveOne}: a connection that never handshakes is a retry, not a
     * transfer, and counting those turned a phone's retry loop into a device that had
     * vanished from the sheet.
     */
    private volatile java.util.function.IntConsumer activeListener;

    private final AtomicInteger active = new AtomicInteger();

    private volatile SSLServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    public AirDropServer(Identity identity, Handler handler) {
        this(identity, handler, null);
    }

    public AirDropServer(Identity identity, Handler handler, Listener listener) {
        this.identity = identity;
        this.handler = handler;
        this.listener = listener;
        this.workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "airdrop-conn-" + connections.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Binds the AirDrop port on all addresses and starts accepting. */
    public void start() throws IOException {
        start(AirDropPeer.DEFAULT_PORT);
    }

    /**
     * Binds an explicit port; {@code 0} picks a free one, which tests rely on.
     * The wildcard address covers the link-local IPv6 address on {@code wonder0}
     * as well as the v4-mapped forms some peers still try.
     */
    public void start(int port) throws IOException {
        if (running) throw new IllegalStateException("already started");
        SSLContext context;
        try {
            context = identity.serverContext();
        } catch (java.security.GeneralSecurityException broken) {
            throw new IOException("cannot build TLS context", broken);
        }

        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        socket.setReuseAddress(true);
        // The receive buffer is set rather than discovered, and the measurement says it
        // was not the gate -- kept because it costs nothing and closes the question.
        //
        // The lead was a bandwidth-delay product small enough that tcp_moderate_rcvbuf
        // would park the advertised window near the delivered rate and hold it there: a
        // slow link keeping the window small and a small window keeping the link slow.
        // Setting it directly removes that possibility instead of arguing about it. Two
        // 5.1 MB photos from the same iPhone, one either side of the change: 118.0 KB/s
        // with the buffer left to autotuning, 119.9 KB/s with it at 1 MB. One and a half
        // per cent, which is noise. So the window is not what paces this link and the
        // loop it was meant to break does not exist.
        //
        // The number that replaced it, from the arrival stream of that same transfer: the
        // peer's data frames land about twelve milliseconds apart in bursts of one to
        // three, uniformly across the sixty-four millisecond slot (no phase preference,
        // so not a schedule), and essentially none of them are retransmissions. A sender
        // that is neither scheduled nor losing is being fed slowly -- the pacing is on the
        // other end. Nothing set here can raise it; see the note in AwdlData on
        // RELAY_COPIES for the three runs that bracket it.
        //
        // Set before bind anyway, because that part is true regardless: the window scale
        // factor is fixed by the SYN-ACK, and an accepted socket inherits the listener's
        // buffer, so a value set after accept would be capped by a scale chosen for the
        // old size. AWDL_RCVBUF moves it without a rebuild; zero restores autotuning.
        int rcvbuf = envInt("AWDL_RCVBUF", 1 << 20);
        if (rcvbuf > 0) socket.setReceiveBufferSize(rcvbuf);
        socket.bind(new InetSocketAddress(port), 16);
        // Said out loud because the effective value is the kernel's, not ours: it doubles
        // what is asked for, and caps it at rmem_max. A line reading 1 MB is the fix in
        // place; a line reading 212992 is the request having been refused somewhere.
        System.out.println("airdrop-server: listening on " + socket.getLocalPort()
                + " receive buffer " + socket.getReceiveBufferSize() + " B"
                + " (asked " + rcvbuf + ")");
        // Not asked for. This was briefly turned on -- setWantClientAuth(true) -- in the
        // hope of reading a name out of the certificate the peer presents, and the
        // experiment answered the opposite question: a phone that is asked for a client
        // certificate during TLS 1.3 gives up on the connection instead. With it on, the
        // log over seventy-five lines carried no "handshake" line at all, only
        // "broke after 2034 ms: Connection reset" and its kind; with it off, every
        // connection completed "TLSv1.3 TLS_AES_128_GCM_SHA256 handshake 1018 ms" and was
        // reset afterwards. So the request did not merely fail to yield a name, it moved
        // the reset earlier, into the handshake. Never ask again: the name has to come
        // from what the peer says in its own traffic, not from what we make it prove.
        socket.setNeedClientAuth(false);
        serverSocket = socket;
        running = true;

        Thread thread = new Thread(this::acceptLoop, "airdrop-accept");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    /** Port actually bound, which differs from the request when {@code 0} was used. */
    public int port() {
        ServerSocket socket = serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    /** Notified with the number of open conversations, on every change. */
    public void setActiveListener(java.util.function.IntConsumer listener) {
        this.activeListener = listener;
        reportActive();
    }

    private void reportActive() {
        java.util.function.IntConsumer target = activeListener;
        if (target != null) target.accept(active.get());
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException stopped) {
                if (running) report("accept failed", stopped);
                break;
            }
            try {
                workers.execute(() -> serve(socket));
            } catch (RuntimeException rejected) {
                closeQuietly(socket);
            }
        }
    }

    private void serve(Socket raw) {
        serveOne(raw);
    }

    private void serveOne(Socket raw) {
        // The worker thread carries the connection number in its name, so every line
        // below can be read as one conversation without threading an id through the
        // dispatcher. Two transfers never share a thread, which is the whole reason the
        // pool names its threads instead of leaving them anonymous.
        String label = Thread.currentThread().getName();
        String who = raw.getInetAddress() == null
            ? "?" : raw.getInetAddress().getHostAddress();
        long openedAt = System.nanoTime();
        Trace.write("* recv " + label + " from " + who);
        // Counted from the end of the handshake, not from accept. Counting from accept
        // looked harmless until a phone that had already picked this device went into its
        // retry loop: measured over one session, thirty-three connections arrived and only
        // one finished a handshake, each abandoning read after twelve seconds, so the
        // announcement was held down almost continuously -- and the interval it is held
        // down to is fifteen seconds, which is longer than a person waits before deciding
        // the device is not there. A connection that never handshakes is a failure, not a
        // transfer, and a failure has no claim on the slots an announcement needs.
        boolean counted = false;
        try (SSLSocket socket = (SSLSocket) raw) {
            socket.setUseClientMode(false);
            // Nagle was on for every connection this server has ever accepted, and the
            // flight that opens TLS is written as four small records -- 788, 445, 273 and
            // 100 bytes, all of them under the 800-byte MSS. Nagle holds each one until the
            // one before it is acknowledged, so that flight cost four round trips rather
            // than one: measured at 6757 ms on a link whose retransmit timer is 1520 ms,
            // which is 4.4 of them. A transfer does not pay this because it fills an MSS
            // on every write, which is also why the same socket went from 6.7 s on the
            // handshake to 570 ms on the upload. Turning it off lets the whole flight leave
            // back to back while keeping the base the segment size.
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.startHandshake();
            active.incrementAndGet();
            counted = true;
            reportActive();
            // Handshake done, so the request bound takes over. See REQUEST_TIMEOUT_MS:
            // this is the window in which a real sender frames its first request, and a
            // socket that stays silent through it is a probe, not a transfer.
            socket.setSoTimeout(REQUEST_TIMEOUT_MS);
            // Only readable once a session exists. It is worth a line because a peer that
            // negotiated something unexpected -- TLS 1.2 with a TLS 1.3-only expectation,
            // or a suite whose key size the radio chokes on -- looks exactly like a healthy
            // connection until the first request dies, and by then the handshake is long
            // gone from anywhere it could be inspected.
            javax.net.ssl.SSLSession session = socket.getSession();
            Trace.write("* recv " + label + " " + who + " " + session.getProtocol() + " "
                + session.getCipherSuite() + " handshake " + elapsed(openedAt));
            // Who the peer says it is, before it has said a word about a file. This was
            // tried as an answer to the missing name and it is not one: the certificate is
            // only ever presented when the server asks for it, and asking for it breaks
            // the handshake (see setNeedClientAuth above). The line stays, but it is now
            // silent unless a chain actually arrives, because a line reading "none
            // offered" on every two-second reconnect would be noise pretending to be
            // evidence.
            String peer = describePeer(socket);
            if (peer != null) Trace.write("* recv " + label + " peer certificate: " + peer);
            // One buffered stream for head and body: the parser reads byte by byte,
            // and every body reader below shares this same view of the connection.
            //
            // The fast-acknowledgement wrapper sits below the buffer, not above it, so
            // it counts reads of the socket rather than reads of the parser: a
            // sixteen-kilobyte pull from the connection is one chance to acknowledge
            // what has landed. It is a pass-through unless AWDL_QUICKACK=1 -- arming the
            // option measured 41.7% duplicate acknowledgements and 13.6% retransmitted
            // frames on this path, and the class comment in QuickAck says how.
            InputStream in = new BufferedInputStream(
                QuickAck.wrap(socket, socket.getInputStream()), 16 * 1024);
            OutputStream out = socket.getOutputStream();
            while (running) {
                HttpRequest request = HttpRequest.read(in);
                if (request == null) break;
                // A request has been framed, so this is a conversation rather than a
                // probe. From here a read that returns nothing means the upload stalled,
                // and killing it early would throw away a transfer that is merely slow.
                socket.setSoTimeout(READ_TIMEOUT_MS);
                // The bare framing is followed by every header the peer sent, because a
                // stall after the head is only explicable from the head: "chunked close"
                // does not say whether a length was promised, whether the peer expected a
                // 100 before releasing the body, or whether it asked for a keep-alive it
                // then did not use.
                Trace.write("< recv " + label + " " + request.method + " " + request.path
                    + " " + describe(request) + " [" + request.headText() + "]");
                if (!dispatch(request, in, out, socket, label)) break;
            }
            Trace.write("* recv " + label + " closed by the peer after " + elapsed(openedAt));
        } catch (IOException broken) {
            // Peers drop the connection mid-handshake all the time; not worth a report
            // unless it happens outside a normal close.
            if (broken.getMessage() != null && !broken.getMessage().contains("Connection reset")) {
                report("connection ended: " + broken.getMessage(), broken);
            }
            Trace.write("! recv " + label + " broke after " + elapsed(openedAt) + ": "
                + broken.getMessage());
        } catch (RuntimeException bug) {
            report("unexpected failure", bug);
            Trace.write("! recv " + label + " failed after " + elapsed(openedAt) + ": " + bug);
        } finally {
            // Paired with the increment above and only reached when that one ran, so a
            // failed handshake neither publishes a count nor takes one back.
            if (counted) {
                active.decrementAndGet();
                reportActive();
            }
        }
    }

    /**
     * Handles one request; returns whether the connection may carry another.
     *
     * <p>Every branch writes one answer line with the byte count and the time it took.
     * That is the only place either number exists: the sender sees the status, the
     * receiver's user sees nothing at all, and "the phone said yes in 3 ms" against
     * "the phone said yes in 400 ms" is the difference between a receiver that answered
     * from memory and one that had to go and ask somebody.
     */
    /**
     * Reads a {@code /Discover} body, sending the answer first if the body is slow.
     *
     * <p>Returns the body when it arrived to its last byte, and {@code null} when it did
     * not. The caller answers either way -- the answer is a fixed description of this
     * device and no byte of the request can change it -- but the return value still
     * matters, because only a body consumed to its end leaves the stream positioned at
     * the next request, so only that case may keep the connection.
     *
     * <p>The two budgets exist because the two halves of this exchange have different
     * owners. {@code beforeAnswerMs} belongs to the sender: it is how long the sender will
     * wait for its 200 while this side collects a body it does not need to answer with, so
     * it must stay short. {@code afterAnswerMs} belongs to this endpoint: once
     * {@code answer} has run, every remaining byte is being read for the name inside it,
     * which nobody is waiting on, and the only cost of waiting is one worker thread.
     *
     * <p>That distinction is what the previous shape of this code got wrong, and both
     * halves of it have now been measured. Reading the body for 300 ms and then answering
     * without it is correct for the answer -- the peer read that 200 and kept the
     * connection open another 4.9 s -- but it never yields a name: on every {@code
     * /Discover} this phone posted, not one byte of the record that carries its name
     * arrived. Waiting 2500 ms instead did not yield a name either, and cost the answer
     * itself: the peer had already dropped its half by then, and the write failed with
     * {@code Broken pipe}. The body of a {@code /Discover} on this link is therefore not
     * late, it is absent, and the window is sized to get the answer out rather than to
     * wait for something that does not come.
     *
     * <p>Every exit writes what it saw. "No body" was the single most misleading line in
     * this file: it read the same whether the peer had sent nothing, had sent part of
     * something and then reset, or had been cut off by this side's own deadline, and each
     * of those three suggests a different fix.
     */
    private static byte[] readBodyAroundAnswer(HttpRequest request, InputStream in,
                                               SSLSocket socket, int beforeAnswerMs,
                                               int afterAnswerMs, Answer answer, String label)
        throws IOException {
        InputStream body = request.body(in);
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream(2048);
        byte[] buffer = new byte[4096];
        int saved = socket.getSoTimeout();
        boolean answered = false;
        long deadline = System.nanoTime() + beforeAnswerMs * 1_000_000L;
        try {
            while (true) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0L) {
                    // Out of patience on a body nobody is waiting for. Send the answer,
                    // then keep reading on the second budget -- the bytes are still
                    // worth having, they are just no longer worth delaying for.
                    if (answered) {
                        Trace.write("* recv " + label + " still no body " + afterAnswerMs
                            + " ms after its answer (" + collected.size() + " B), giving up");
                        return null;
                    }
                    answered = true;
                    answer.send(collected.size());
                    deadline = System.nanoTime() + afterAnswerMs * 1_000_000L;
                    continue;
                }
                socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
                int read;
                try {
                    read = body.read(buffer, 0, buffer.length);
                } catch (java.net.SocketTimeoutException stall) {
                    // The read timeout was set to the remaining budget, so a timeout
                    // means a whole budget with no byte at all -- a stall, not slowness.
                    // Retrying would let one peer hold this thread indefinitely.
                    if (answered) {
                        Trace.write("* recv " + label + " still no body " + afterAnswerMs
                            + " ms after its answer (" + collected.size() + " B), giving up");
                        return null;
                    }
                    answered = true;
                    answer.send(collected.size());
                    deadline = System.nanoTime() + afterAnswerMs * 1_000_000L;
                    continue;
                }
                if (read < 0) {
                    // Two verdicts, and the trace has to tell them apart: a body that
                    // arrived whole, and a peer that closed having sent none at all. The
                    // second one is the whole question about /Discover on this link, and
                    // reporting it as "0 B" alongside a real body of zero length would
                    // leave it open for another session.
                    if (collected.size() == 0) {
                        Trace.write("* recv " + label + " sent no body at all before it closed");
                    }
                    return collected.toByteArray();
                }
                collected.write(buffer, 0, read);
                if (collected.size() > MAX_CONTROL) return null;
            }
        } catch (IOException broken) {
            Trace.write("* recv " + label + " body stopped after " + collected.size()
                + " B: " + broken.getMessage());
            return null;
        } finally {
            try {
                socket.setSoTimeout(saved);
            } catch (IOException ignored) {
                // The connection is about to close anyway.
            }
        }
    }

    /** Swallows what is left of a request body so that closing is a FIN, not a reset. */
    private static void drainBriefly(InputStream in, SSLSocket socket) {
        int saved;
        try {
            saved = socket.getSoTimeout();
            socket.setSoTimeout(DISCOVER_DRAIN_MS);
        } catch (IOException gone) {
            return;
        }
        byte[] scratch = new byte[4096];
        try {
            while (in.read(scratch) >= 0) {
                // Discarded on purpose: these are bytes of a plist this side already
                // decided it does not need.
            }
        } catch (IOException done) {
            // A timeout here is the deadline doing its job, and a reset is the sender
            // having hung up after reading the answer. Neither is worth a line.
        } finally {
            try {
                socket.setSoTimeout(saved);
            } catch (IOException ignored) {
                // The connection is about to close anyway.
            }
        }
    }

    /**
     * Passes on the name a sender used for itself in its own {@code /Discover} body.
     *
     * <p>Never throws: this runs on the answering path of the one request that has to be
     * fast, and a name is a label on a row, so a body that will not parse must not cost
     * the sender its answer.
     *
     * <p>It used to be silent on every failure, and that silence is what made the name
     * question unanswerable: the log showed a full {@code /Discover} body arriving and
     * then showed nothing, so "the body was empty", "the body was not a plist" and "the
     * plist had no such key" all read as the same line -- no line. Every branch now says
     * which one it was, with the head of the body and the readable strings inside it,
     * which is enough to tell a plist from a chunk framing from a different schema
     * without another round trip to the device.
     */
    private void rememberSender(byte[] body, SSLSocket socket, String label) {
        if (body == null || socket == null) {
            Trace.write("? recv " + label + " had no body to read a name from");
            return;
        }
        if (body.length == 0) {
            Trace.write("? recv " + label + " carried an empty body, so it named nobody");
            return;
        }
        Map<String, Object> fields;
        try {
            fields = Bplist.parseDictionary(body);
        } catch (Bplist.FormatException | RuntimeException unreadable) {
            Trace.write("! recv " + label + " body of " + body.length
                + " B did not parse as a dictionary: " + unreadable
                + " head=" + Trace.dump(body)
                + " strings=" + readable(body));
            return;
        }
        String name = Bplist.string(fields, "SenderComputerName");
        Trace.write("* recv " + label + " body of " + body.length + " B parsed, keys="
            + fields.keySet()
            + ", SenderComputerName=" + (name == null ? "(absent)" : "\"" + name + "\""));
        rememberSender(name, socket);
    }

    /**
     * The printable runs inside a body, longest first, as one short phrase.
     *
     * <p>Exists because {@link Trace#dump} stops at 96 bytes and the interesting part of a
     * plist body is the key strings, which sit in the object area behind a header and an
     * offset table. A run has to be at least four characters to count, which drops the
     * single-byte markers and keeps the field names that answer the question this was
     * written for: whether the name is there, and under what spelling.
     */
    private static String readable(byte[] body) {
        StringBuilder out = new StringBuilder(200);
        int run = 0;
        for (int i = 0; i <= body.length; i++) {
            int c = i < body.length ? (body[i] & 0xff) : 0;
            if (c >= 0x20 && c < 0x7f) {
                run++;
                continue;
            }
            if (run >= 4) {
                if (out.length() > 0) out.append(" | ");
                out.append(new String(body, i - run, run, java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            run = 0;
            if (out.length() > 400) {
                out.append(" | …");
                break;
            }
        }
        return out.length() == 0 ? "(none)" : out.toString();
    }

    /**
     * Files a name the peer gave for itself against the address it connected from.
     *
     * <p>The address is taken from the socket rather than from anything in the plist: the
     * plist's own idea of where it lives is a claim, and the connection is a fact. The
     * two do not always even spell the same link-local address -- see
     * {@link PeerNames#of(String)} for why the store keys on the device instead.
     */
    private void rememberSender(String name, SSLSocket socket) {
        if (name == null || name.isEmpty() || socket == null) return;
        java.net.InetAddress from = socket.getInetAddress();
        if (from == null) return;
        handler.onPeerSeen(from.getHostAddress(), name);
    }

    /**
     * The subject of the certificate the peer presented, as one trace line.
     *
     * <p>Written while hunting for a readable device name, and kept as the record of a
     * ruled-out lead. The premise was wrong: the certificate is not volunteered. It is
     * presented only in answer to a CertificateRequest, and a phone that receives one
     * during TLS 1.3 abandons the connection -- the handshake line disappears from the
     * log entirely, which is what a whole build's worth of evidence showed. Asking is
     * therefore off (see {@code setNeedClientAuth} in {@link #start}), and with nothing
     * asking, there is nothing to describe.
     *
     * <p>Returns {@code null} rather than a phrase when no chain arrived, so the caller
     * can stay quiet. A peer that says nothing is the normal case here, not an event.
     * Never throws: this runs on the path that has to answer a sender, and an unreadable
     * certificate is a row without a title, not a lost transfer.
     */
    private static String describePeer(SSLSocket socket) {
        java.security.cert.Certificate[] chain;
        try {
            chain = socket.getSession().getPeerCertificates();
        } catch (javax.net.ssl.SSLPeerUnverifiedException anonymous) {
            return null;
        } catch (RuntimeException odd) {
            return "unreadable (" + odd + ")";
        }
        if (chain == null || chain.length == 0) return null;
        if (!(chain[0] instanceof java.security.cert.X509Certificate)) {
            return chain.length + " certificate(s), none X.509";
        }
        java.security.cert.X509Certificate leaf = (java.security.cert.X509Certificate) chain[0];
        StringBuilder out = new StringBuilder(160);
        out.append(leaf.getSubjectX500Principal().getName());
        try {
            out.append(" | issuer ").append(leaf.getIssuerX500Principal().getName());
        } catch (RuntimeException ignored) {
            // Some providers cannot produce a principal for a malformed name.
        }
        try {
            java.util.Collection<java.util.List<?>> names = leaf.getSubjectAlternativeNames();
            if (names != null) {
                for (java.util.List<?> entry : names) {
                    if (entry.size() < 2) continue;
                    out.append(" | san ").append(entry.get(0)).append('=').append(entry.get(1));
                }
            }
        } catch (java.security.cert.CertificateParsingException | RuntimeException ignored) {
            // No alternate names, or none this provider can parse.
        }
        try {
            out.append(" | sha256 ").append(Identity.fingerprint(leaf.getEncoded()));
        } catch (java.security.cert.CertificateEncodingException | RuntimeException ignored) {
            // The digest is a convenience here; the subject is the point.
        }
        return out.toString();
    }

    private boolean dispatch(HttpRequest request, InputStream in, OutputStream out,
                             SSLSocket socket, String label) throws IOException {
        String path = request.path;
        if (!"POST".equals(request.method)) {
            HttpResponse.sendEmpty(out, 405, false);
            Trace.write("> recv " + label + " 405 " + path + " (not a POST)");
            return false;
        }

        if (AirDropProtocol.PATH_DISCOVER.equals(path)) {
            long started = System.nanoTime();
            // The answer is a fixed description of this device. Nothing the sender puts
            // in its body -- including the name this side is about to read out of it --
            // can change a byte of the reply, so the reply is built before the body is
            // touched and the body is read only for what it says about the sender.
            byte[] answer = handler.onDiscover(new byte[0]);
            boolean keepWanted = request.keepAlive();
            // /Upload used to be the only path that honoured Expect: 100-continue, and a
            // peer is entitled to post /Discover with it too: it would send the head and
            // then wait for this one line before releasing a body it has already sized.
            // That hypothesis has since been tested and does not explain what this link
            // does -- the head this phone sends carries no such header, and no body byte
            // arrives whether or not one is sent. It stays because it costs one line and
            // because the alternative is deciding, on the evidence of one phone, that no
            // peer will ever ask.
            if (request.expectsContinue()) HttpResponse.sendContinue(out);
            boolean[] answered = {false};
            Answer early = (collected) -> {
                answered[0] = true;
                // Answered before the body was consumed, so this connection must not
                // carry another request: unread bytes would be framed as the head of the
                // next one. HTTP requires the close in exactly this situation, and the
                // sender is already prepared for it.
                HttpResponse.send(out, answer, false);
                // The byte count is the experiment: it says whether the peer had started
                // its body at all when the deadline expired, which is the difference
                // between a budget that is too short and a peer that never sends one.
                Trace.write("* recv " + label + " answered at " + elapsed(started) + " with "
                    + collected + " B of its body in hand; reading on for its name");
            };
            byte[] asked = readBodyAroundAnswer(request, in, socket, DISCOVER_BODY_MS,
                DISCOVER_NAME_MS, early, label);
            // A body abandoned mid-flight leaves unread bytes in the stream, so a
            // connection that answered early is spent even though its peer asked to keep
            // it. Only a body read to its very last byte allows another request.
            boolean keep = asked != null && !answered[0] && keepWanted;
            if (!answered[0]) HttpResponse.send(out, answer, keep);
            // A half-read plist is not a plist, and parsing one would file whatever a
            // truncated dictionary happened to yield under a device's address, where it
            // would outlive the packet that carried it.
            if (asked != null) rememberSender(asked, socket, label + " /Discover");
            Trace.write("> recv " + label + " /Discover 200 " + answer.length + " B in "
                + elapsed(started) + " (" + (asked == null ? "body cut short" : asked.length + " B in")
                + ", " + (keep ? "kept alive" : "closing") + ")");
            if (!keep) {
                // Closing with the sender's bytes still unread would turn the close into a
                // reset, and a reset can discard the answer that is the whole point of the
                // exchange. Swallowing the rest of the body first makes it an ordinary
                // end-of-stream, which is what the sender is already prepared for.
                drainBriefly(in, socket);
            }
            return keep;
        }

        if (AirDropProtocol.PATH_ASK.equals(path)) {
            long started = System.nanoTime();
            // The reply is fixed, so it is available before the body is: see ASK_BODY_MS
            // for the 37.1 s of silence that the opposite order cost on this link. A
            // handler that has nothing to say before the body returns empty and keeps the
            // old order below, which is what the tests do.
            byte[] fixed = handler.askAnswer();
            if (fixed != null && fixed.length > 0) {
                boolean keepWanted = request.keepAlive();
                // Answered before its body is consumed, so a connection that answered
                // early is spent even though its peer asked to keep it: unread bytes
                // would be framed as the head of the next request. An offer is followed
                // by /Upload on a connection of its own anyway.
                if (request.expectsContinue()) HttpResponse.sendContinue(out);
                boolean[] answered = {false};
                Answer early = (collected) -> {
                    answered[0] = true;
                    HttpResponse.send(out, fixed, false);
                    Trace.write("* recv " + label + " answered /Ask at " + elapsed(started)
                        + " with " + collected + " B of its body in hand");
                };
                byte[] body = readBodyAroundAnswer(request, in, socket, ASK_BODY_MS,
                    ASK_NAME_MS, early, label);
                boolean keep = body != null && !answered[0] && keepWanted;
                AirDropProtocol.Ask ask = null;
                Bplist.FormatException malformed = null;
                if (body != null && body.length > 0) {
                    try {
                        ask = AirDropProtocol.ask(body);
                    } catch (Bplist.FormatException bad) {
                        malformed = bad;
                    }
                }
                if (!answered[0]) {
                    // The peer is still waiting, so the parser's verdict can still reach
                    // it. A 400 here is our own parser refusing a body the peer considers
                    // valid, and the offending bytes are the only way to find out which
                    // field it was.
                    if (malformed != null) {
                        HttpResponse.sendEmpty(out, 400, false);
                        Trace.write("! recv " + label + " /Ask unreadable (" + body.length
                            + " B): " + malformed.getMessage() + " " + Trace.dump(body));
                        return false;
                    }
                    HttpResponse.send(out, fixed, keep);
                } else if (malformed != null) {
                    Trace.write("! recv " + label + " /Ask unreadable (" + body.length
                        + " B, already answered): " + malformed.getMessage() + " "
                        + Trace.dump(body));
                }
                if (ask != null) {
                    // Only for what this side knows: the peer already holds its 200, so
                    // the answer this returns is written nowhere. The offer still reaches
                    // the listener, which is what names the sender on screen.
                    handler.onAsk(ask);
                    rememberSender(ask.senderName, socket);
                }
                Trace.write("> recv " + label + " /Ask 200 " + fixed.length + " B in "
                    + elapsed(started) + " (" + (body == null ? "body cut short"
                    : body.length + " B in") + ", " + (keep ? "kept alive" : "closing") + ")");
                if (!keep) {
                    // Closing with the offer's bytes still unread would turn the close
                    // into a reset, and a reset can discard the answer the peer has not
                    // read yet.
                    drainBriefly(in, socket);
                }
                return keep;
            }
            byte[] requestBody = request.readBody(in, MAX_CONTROL);
            AirDropProtocol.Ask ask;
            try {
                ask = AirDropProtocol.ask(requestBody);
            } catch (Bplist.FormatException malformed) {
                HttpResponse.sendEmpty(out, 400, false);
                // A 400 here is our own parser refusing a body the peer considers valid,
                // so the offending bytes are the only way to find out which field it was.
                Trace.write("! recv " + label + " /Ask unreadable (" + requestBody.length
                    + " B): " + malformed.getMessage() + " " + Trace.dump(requestBody));
                return false;
            }
            byte[] answer = handler.onAsk(ask);
            // The offer names its sender in the same plist field the probe does, and this
            // body is the one inbound body on this link that reliably arrives: it is read
            // straight through with the transfer's own budget, with nobody waiting on a
            // deadline, and a sender that has got this far is committed. So a device that
            // has ever sent this one a file has told it what it is called -- and that is
            // worth filing even when the /Discover probe never carried the same field.
            rememberSender(ask.senderName, socket);
            HttpResponse.send(out, answer, request.keepAlive());
            Trace.write("> recv " + label + " /Ask 200 " + answer.length + " B in "
                + elapsed(started) + (request.keepAlive() ? ", kept alive" : ", closing"));
            return request.keepAlive();
        }

        if (AirDropProtocol.PATH_UPLOAD.equals(path)) {
            return upload(request, in, out, label);
        }

        HttpResponse.sendEmpty(out, 404, false);
        Trace.write("> recv " + label + " 404 " + path);
        return false;
    }

    /**
     * {@code /Upload} is the one endpoint with an ordering requirement: the peer
     * waits for {@code 100 Continue} before it sends a single archive byte.
     *
     * <p>Two things here look like over-acceptance and are not. The content type is
     * matched against three values because UP-4 only prescribes {@code 406} for a body
     * that is not an archive, not for one whose archive flavour is spelled differently;
     * and both framings are read because UP-1a says a receiver has to take
     * {@code Content-Length} and chunked alike, while the sender decides which it can
     * even offer. {@link Cpio#decompress} sorts out whether the archive arrives bare,
     * gzipped or in DVZip blocks, so nothing here has to guess.
     */
    private boolean upload(HttpRequest request, InputStream in, OutputStream out, String label)
        throws IOException {
        String contentType = request.header("content-type");
        if (!AirDropProtocol.isArchiveType(contentType)) {
            HttpResponse.sendEmpty(out, 406, false);
            Trace.write("! recv " + label + " /Upload 406, content type " + contentType);
            return false;
        }
        if (request.expectsContinue()) HttpResponse.sendContinue(out);
        long started = System.nanoTime();
        // The upload's own byte count is not known here -- the body is streamed straight
        // into the archive reader -- so it is the reader that reports how much it wrote.
        // What this line adds is the framing and the wall clock around the whole body.
        handler.onUpload(UploadTap.around(request.body(in)), contentType);
        HttpResponse.sendEmpty(out, 200, false);
        Trace.write("> recv " + label + " /Upload 200 in " + elapsed(started) + " ("
            + contentType + ", " + describe(request)
            + (request.expectsContinue() ? ", 100-continue sent" : "")
            // Whether the fast-acknowledgement option was in force for this body, and how
            // many times it was re-armed. "off" is the default build: the option is a
            // pass-through unless AWDL_QUICKACK=1, because arming it measured worse.
            + ", quickack " + QuickAck.summary() + ")");
        return false;
    }

    /** What the head says about the body, in the few words a log line has room for. */
    /**
     * The shape of a request as one log field: body framing plus what the sender wants
     * done with the connection afterwards.
     *
     * <p>The connection half is recent and it is the half that decided a failure. Two
     * senders posting the same {@code /Discover} differ only in whether they intend to
     * reuse the session for {@code /Ask}, and that intent was invisible here -- the
     * response said {@code close} either way, so the log could not separate a sender that
     * was told to leave from one that wanted to stay.
     */
    private static String describe(HttpRequest request) {
        long length = request.contentLength();
        String framing = request.isChunked() ? "chunked"
            : length >= 0L ? length + " B" : "no body";
        return framing + (request.expectsContinue() ? " 100-continue" : "")
            + (request.keepAlive() ? " keep-alive" : " close");
    }

    /** Millis since {@code startedAt}, as a log friendly number. */
    private static String elapsed(long startedAt) {
        long micros = (System.nanoTime() - startedAt) / 1000L;
        if (micros < 1000L) return micros + " us";
        return (micros / 1000L) + " ms";
    }

    /** Integer environment override, or {@code fallback} when unset or unparseable. */
    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private void report(String message, Throwable error) {
        if (listener != null) listener.onError(message, error);
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        Thread thread = acceptThread;
        if (thread != null) thread.interrupt();
        acceptThread = null;
        workers.shutdownNow();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
    }
}
