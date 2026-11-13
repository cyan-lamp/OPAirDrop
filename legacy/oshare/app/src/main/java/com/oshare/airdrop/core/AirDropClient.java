package com.oshare.airdrop.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;

/**
 * Sends files to a discovered peer: {@code /Discover} to see who answers, {@code /Ask}
 * to say what is coming, then {@code /Upload} with a cpio archive.
 *
 * <p>This is the mirror of {@link AirDropLink}. That object answers those three
 * requests; this one makes them, and both run over the transport {@link HttpCall}
 * provides. Keeping the sender free of Android means a transfer can be rehearsed on
 * the JVM or through {@code app_process} before any device is involved, which is how
 * the receive path was debugged and is the only way to test sending without an Apple
 * peer in the room.
 *
 * <p>The connection is pinned to the fingerprint the peer advertised in its TXT
 * record. That is what makes a self-signed certificate safe to trust: the fingerprint
 * arrived over mDNS from the same machine that will present the certificate, so
 * trusting it is exactly as strong as the discovery channel.
 */
public final class AirDropClient {
    /**
     * How often to report progress, in bytes of archive written.
     *
     * <p>A quarter of a megabyte, and the number was chosen for the bar rather than for
     * the log: on a twenty-four megabyte send at two megabytes a second that is one report
     * every hundred and sixteen milliseconds, which is coarse enough that a stall shows up
     * as the bar jumping by a fifth of a megabyte and fine enough not to cost anything.
     * Rendered, it is not fine enough. What the user watches is a percentage, and the
     * first report is not zero but a quarter of a megabyte -- the bar sits at nothing and
     * then leaps, which reads as "nothing is happening" at exactly the moment a receiver
     * is finishing its own bookkeeping. Sixty-four kilobytes is four times the resolution
     * over the same transfer, still only a few hundred callbacks, and the first byte is
     * reported on its own so the bar moves as soon as the archive does.
     */
    private static final long PROGRESS_STEP = 64 * 1024;

    private static final int COPY_BUFFER = 16 * 1024;

    /**
     * How long {@code /Ask} may stay unanswered before the session is treated as dead.
     *
     * <p>The receiver answers it from its own UI — a prompt appears and the user taps —
     * and a measured iPhone took between 1.0 and 1.6 seconds over three sends. Fifteen
     * seconds is an order of magnitude of headroom for a slow tap and still short enough
     * that a session the peer quietly abandoned is recognised while the user is looking
     * at the progress line, rather than after the upload's ninety-second budget. Only the
     * ask is bounded this way; {@link #PATH_UPLOAD} keeps the long one.
     */
    private static final int ASK_READ_TIMEOUT_MS = 15_000;

    /**
     * How long {@code /Discover} may stay unanswered before it is written off.
     *
     * <p>Not the session's ninety seconds. {@code /Discover} exists to supply a display
     * name; {@code /Ask} is the request that puts a prompt in front of the other person,
     * and the whole point of asking this one first is that it is meant to be cheap. A
     * receiver that says nothing measured four and a half seconds of handshake followed by
     * thirteen and a half seconds of silence and then a reset, and the ninety-second
     * budget turned that into "connection interrupted" with no prompt ever offered.
     *
     * <p>Four seconds turned out to be too tight, and the failure it caused was the exact
     * one this call exists to prevent: a measured iPhone answered {@code /Discover} in
     * 402 ms once and took 4273 ms the next time, that 273 ms overshoot became
     * {@code Read timed out}, and the offer went out as "offering without a name" — a
     * named row degraded to a hex instance name over a quarter of a second. The variance
     * is in the receiver, which is busy bringing up the AWDL link and populating its
     * capabilities plist, so the bound has to clear the slow case and not the fast one.
     *
     * <p>Eight seconds was still short, and the run that proved it also says what the
     * bound has to cover. On a link carrying a twenty-three megabyte upload in each
     * direction at once, the TLS handshake to the same phone measured 2467 ms and then
     * 6593 ms, and one {@code /Discover} died at 9562 ms — 8000 ms of read plus the
     * second and a half it had already spent connecting — while the very next connection
     * to that phone succeeded. Nothing was wrong with the receiver; the number was
     * smaller than a handshake this link can take. So it is now the same budget as
     * {@link #ASK_READ_TIMEOUT_MS}: both calls wait on machinery that is not a person,
     * and there is no reason for the one that asks for a name to give up before the one
     * that asks for a decision. The cost of being wrong is asymmetric too — too short
     * silently drops the name, too long only delays the offer by a few seconds.
     */
    private static final int DISCOVER_READ_TIMEOUT_MS = 15_000;

    /** Coarse phase, so a caller can show something before any bytes move. */
    public enum Stage {
        DISCOVERING,
        ASKING,
        UPLOADING,
        DONE
    }

    public interface Progress {
        void onStage(Stage stage);

        /** {@code sent} can exceed {@code total} by a few header bytes; clamp for display. */
        void onProgress(long sent, long total);

        /**
         * The name the receiver gave for itself, as soon as it is known.
         *
         * <p>Reported mid-transfer rather than carried out in {@link Result} because the
         * list behind the progress line is what needs it: the row was titled with the
         * rotated instance name the tap was aimed at, and the name a person recognises
         * arrives about a second later, while the offer is still in flight. A caller that
         * only reads {@code Result} still gets it there.
         */
        default void onPeerNamed(String name) {
        }

        Progress SILENT = new Progress() {
            @Override
            public void onStage(Stage stage) {
            }

            @Override
            public void onProgress(long sent, long total) {
            }
        };
    }

    public static final class Result {
        /** Name the peer gave for itself; may be {@code null} if it skipped /Discover. */
        public final String receiverName;
        public final String receiverModel;
        public final int files;
        public final long bytes;
        public final long millis;

        Result(String receiverName, String receiverModel, int files, long bytes, long millis) {
            this.receiverName = receiverName;
            this.receiverModel = receiverModel;
            this.files = files;
            this.bytes = bytes;
            this.millis = millis;
        }

        @Override
        public String toString() {
            return files + " file(s), " + bytes + " B to "
                + (receiverName == null ? "peer" : receiverName) + " in " + millis + " ms";
        }
    }

    private final Identity identity;
    private final String interfaceName;
    private final String modelName;
    private final String senderId;

    /**
     * @param interfaceName the link the transfer rides, e.g. {@code wonder0}; a
     *                      link-local peer address is unusable without it
     * @param modelName     reported as {@code SenderModelName}; Android has no model
     *                      identifier an Apple device would recognise, so the hardware
     *                      model string is the honest answer
     */
    public AirDropClient(Identity identity, String interfaceName, String modelName) {
        this(identity, interfaceName, modelName, null);
    }

    public AirDropClient(Identity identity, String interfaceName, String modelName, String senderId) {
        if (identity == null) throw new IllegalArgumentException("identity == null");
        this.identity = identity;
        this.interfaceName = interfaceName;
        this.modelName = modelName == null || modelName.trim().isEmpty() ? "Android" : modelName.trim();
        this.senderId = senderId == null ? derivedSenderId(identity) : senderId;
    }

    /**
     * A stable per-identity UUID. Apple's senders present a {@code SenderID} that stays
     * the same across transfers, and receivers use it to group a sender's activity, so
     * deriving it from the certificate keeps it stable without another thing to store.
     */
    private static String derivedSenderId(Identity identity) {
        String seed = "oshare-airdrop:" + identity.certHash();
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString().toUpperCase(Locale.ROOT);
    }

    public Identity identity() {
        return identity;
    }

    /**
     * Total archive size for these files; the denominator of any progress display.
     *
     * <p>Counts the {@code .} directory entry, every member and the trailer, because
     * UP-2a's layout has all three. Leaving any of them out makes a transfer that
     * finished perfectly stop a few dozen bytes short of its own total, which reads as
     * a stall at 99% and is the sort of bug nobody reproduces on purpose.
     */
    public static long archiveLength(List<Outgoing> files) {
        long total = Cpio.odcMemberLength(".", 0) + Cpio.odcTrailerLength();
        for (Outgoing file : files) total += Cpio.odcMemberLength(file.bomPath, file.length);
        return total;
    }

    /**
     * Runs the whole conversation. Blocking: call it on a worker thread.
     *
     * <p>{@code /Discover} failing is not fatal on its own — a peer that answers
     * {@code /Ask} is willing to receive, and the discover exchange only supplies a
     * display name — so the transfer continues without one.
     *
     * <p>Every socket opened here is opened at the moment the user taps, and every socket
     * is used for exactly one request. Both halves of that sentence were paid for.
     *
     * <p>Opening at the tap, rather than reusing a session built while the list was on
     * screen, is the reversal of what this method used to do. An iPhone rebuilds its
     * AirDrop service while the list is open and closes nothing while doing it -- its
     * machine id changed from 01f04635-... to 7e091bc2-... inside one session -- and a
     * socket that predates the rebuild is then answered by nothing at all: no status, no
     * reset, not one byte in fifteen seconds, while the identical offer on a socket opened
     * at the tap came back 200 in 1.0 to 1.4 s. A warm socket that is still young enough to
     * be answered does save 150 to 400 ms of handshake, but handing the tap to one is a bet
     * with the entire tap-to-prompt budget on it, and the tap is not the place for a bet.
     *
     * <p>One request per socket, also measured. The receiver reads one request per
     * connection and then stops reading: a second request on the same socket is answered
     * by silence that lasts longer than any timeout worth setting.
     *
     * <p>The warm-up has not disappeared, it has withdrawn to the part of the work that
     * cannot fail: {@link #prime} installs the route with its initial congestion window
     * before the tap, and that survives the tap because it belongs to the route rather than
     * to a socket.
     */
    public Result send(AirDropPeer peer, List<Outgoing> files, Progress progress) throws IOException {
        if (peer == null) throw new IllegalArgumentException("peer == null");
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("nothing to send");
        Progress reporter = progress == null ? Progress.SILENT : progress;

        long total = archiveLength(files);
        long started = System.nanoTime();
        InetAddress target = addressOf(peer, interfaceName);

        SSLContext context;
        try {
            context = Identity.clientContext(peer.certHash);
        } catch (GeneralSecurityException failed) {
            throw new IOException("cannot build a pinned context for "
                + peer.displayName + ": " + failed.getMessage(), failed);
        }

        String receiverName = null;
        String receiverModel = null;

        Trace.write("* offer " + files.size() + " file(s), " + total + " B to "
            + peer.displayName + " [" + peer.host + " " + target.getHostAddress()
            + ":" + peer.port + "] pinned " + peer.certHash
            + ", sender " + senderId + " \"" + identity.computerName() + "\"");
        // What the peer says it speaks is worth a line: iOS declares its protocol
        // version through rp/rpH and no longer publishes a certificate fingerprint,
        // so these three fields are all the warning a sender gets before choosing
        // which request shape to use.
        Trace.write("* peer flags=" + peer.flags + " rp=" + peer.receiverProtocol
            + " rpH=" + peer.receiverProtocolHash + " addresses=" + peer.addresses);

        HttpCall session = new HttpCall(target, peer.port, context, peer.host);
        try (HttpCall call = session) {
            reporter.onStage(Stage.DISCOVERING);
            SelfDescription said = discoverSafely(call);
            receiverName = said.name;
            receiverModel = said.model;
            if (receiverName != null && !receiverName.isEmpty()) {
                // Filed by whoever is listening before the offer goes out: the name is worth
                // more than the transfer it arrived with, and the row it belongs to stays on
                // screen afterwards.
                reporter.onPeerNamed(receiverName);
            }

            reporter.onStage(Stage.ASKING);
            // One id for the whole transfer: it is offered here and echoed in the
            // upload's TransferID header, and UP-9 makes a mismatch a rejection.
            String transferId = AirDropProtocol.newTransferId();
            byte[] ask = AirDropProtocol.askRequest(identity.computerName(), modelName, senderId,
                transferId, items(files), new ArrayList<String>());
            HttpCall.Reply asked = call.readWithin(ASK_READ_TIMEOUT_MS)
                .post(AirDropProtocol.PATH_ASK, AirDropProtocol.OCTET_STREAM, ask);
            // The upload is bounded by bytes, not by a person, so it gets the session's
            // ordinary budget back before anything is written on this connection.
            call.readWithin(HttpCall.READ_TIMEOUT_MS);
            if (!asked.ok()) {
                // The receiver refused before any content moved. Retrying the same
                // files against the same peer will fail the same way.
                Trace.write("* ask refused: " + asked.status + " "
                    + HttpResponse.reason(asked.status));
                throw new IOException("peer refused the offer: " + asked.status + " "
                    + HttpResponse.reason(asked.status));
            }
            Trace.write("* ask accepted, transfer " + transferId);
            // A 200 here is consent: an answer means the receiver's user was asked and
            // said yes (ASK-2), and the answer carries a session id because a session
            // now exists. Anything else -- a refusal, a close, a timeout -- must not be
            // followed by an upload (ASK-1), which is why both checks are on the path.
            Map<String, Object> accepted = asked.plist();
            if (accepted != null) {
                String name = Bplist.string(accepted, "ReceiverComputerName");
                if (name != null && !name.isEmpty()) {
                    receiverName = name;
                    reporter.onPeerNamed(name);
                }
                String model = Bplist.string(accepted, "ReceiverModelName");
                if (model != null && !model.isEmpty()) receiverModel = model;
            }

            reporter.onStage(Stage.UPLOADING);
            Meter meter = new Meter(reporter, total);
            long offered = 0L;
            for (Outgoing file : files) offered += file.length;
            Map<String, String> uploadHeaders = new LinkedHashMap<>();
            uploadHeaders.put("TransferID", transferId);
            // UP-10: the sum of the sizes offered in /Ask, or zero when there are none.
            uploadHeaders.put("TotalBytes", Long.toString(offered));
            // Chunked on purpose. The body is DVZip, whose length is not knowable before
            // compressing it, and the alternative -- compress the whole archive into
            // memory or a temp file first -- would undo the reason for streaming at all.
            HttpCall.Reply uploaded = call.postStreaming(AirDropProtocol.PATH_UPLOAD,
                AirDropProtocol.UPLOAD_CONTENT_TYPE, -1, uploadHeaders, out -> {
                    DvZip.Encoder archive = new DvZip.Encoder(out);
                    writeArchive(meter.wrap(archive), files);
                    archive.finish();
                });
            if (!uploaded.ok()) {
                throw new IOException("upload rejected: " + uploaded.status + " "
                    + HttpResponse.reason(uploaded.status));
            }
            // UP-5 spells out what success looks like and it is not just the status: a
            // 200 with a body is an error report, and a 200 with neither Content-Length
            // nor a closed connection leaves the sender unable to tell a stored file
            // from a silently dropped one.
            if (uploaded.body != null && uploaded.body.length > 0) {
                throw new IOException("upload answered 200 with " + uploaded.body.length
                    + " bytes of body, which is not the empty success UP-5 describes");
            }
            reporter.onStage(Stage.DONE);
            Trace.write("* upload accepted, " + meter.written() + " B archived");

            long millis = (System.nanoTime() - started) / 1_000_000L;
            // Bplist.string() answers "" for an absent key; callers want "unknown".
            if (receiverName != null && receiverName.isEmpty()) receiverName = null;
            if (receiverModel != null && receiverModel.isEmpty()) receiverModel = null;
            return new Result(receiverName, receiverModel, files.size(), meter.written(), millis);
        }
    }

    /**
     * Prepares everything a tap will need except a socket.
     *
     * <p>What a tap benefits from is not a connection, it is what making one leaves
     * behind: the neighbour entry through the AWDL tunnel, the route with its initial
     * congestion window, and the pinned {@link SSLContext} in the process-wide cache.
     * All three outlive the socket that produced them, and none of them can be answered
     * by nobody — which is the property that matters here. A socket opened before the tap
     * can be silently forgotten by the peer; a route cannot.
     *
     * <p>This is what is left of the warm-up after the socket was taken away from it. It
     * still has to run while the list is on screen, and it still has to run early, because
     * {@code initcwnd} is read once, when a connection is set up.
     */
    public static void prime(AirDropPeer peer, String interfaceName) {
        if (peer == null) return;
        // Root, a shell and one route; no network I/O, so a failure here is a warning
        // rather than something the caller can act on.
        try {
            PeerRoute.install(addressOf(peer, interfaceName).getHostAddress(), interfaceName);
        } catch (RuntimeException | IOException failed) {
            Trace.write("! prime: no route for " + peer.displayName + ": "
                + failed.getMessage());
        }
        // The pinned context is cached by certificate; building it now means the tap does
        // not pay for it, and the cost when it is not cached is the few milliseconds the
        // trace calls "client context built in 2 ms".
        try {
            Identity.clientContext(peer.certHash);
        } catch (GeneralSecurityException failed) {
            Trace.write("! prime: no pinned context for " + peer.displayName + ": "
                + failed.getMessage());
        }
    }

    /**
     * Asks {@code /Discover} on {@code call} and closes it, returning what the receiver
     * said about itself.
     *
     * <p>The connection is spent by the question and this receiver enforces that rather
     * than asking for it. HTTP-7 gives a receiver one {@code /Discover} per connection;
     * an iPhone reading a second request on the connection that already carried one
     * answers nothing at all — not a status, not a reset, no byte for seventy-five
     * seconds — while the identical {@code /Ask} on a connection of its own is answered
     * 200 with a session id and a prompt on the other device. That silence is
     * indistinguishable from a receiver that never saw the offer, and it is why dropping
     * here is a protocol step rather than a closing courtesy.
     */
    private SelfDescription discover(HttpCall call) throws IOException {
        String name = null;
        String model = null;
        // Its own budget, and a short one. A receiver that is going to answer this does so
        // in a fraction of a second -- 402 ms measured -- and one that is not will sit on
        // the connection until somebody else's timeout fires. The connection is spent
        // either way: /Ask gets one of its own.
        HttpCall.Reply discovered = call.readWithin(DISCOVER_READ_TIMEOUT_MS)
            .post(AirDropProtocol.PATH_DISCOVER,
                AirDropProtocol.OCTET_STREAM,
                AirDropProtocol.discoverRequest(identity.computerName(), modelName, senderId));
        if (discovered.ok()) {
            Map<String, Object> caps = discovered.plist();
            if (caps != null) {
                name = Bplist.string(caps, "ReceiverComputerName");
                model = Bplist.string(caps, "ReceiverModelName");
                Trace.write("* discover plist keys " + caps.keySet());
                // What the answer actually says, not just which keys it used. The body
                // is 1437 bytes and only its first 96 are ever previewed, so the values
                // land in the one part of the answer nobody reads -- and IsAirDropable
                // is the receiver's only statement of whether it is willing to be asked
                // at all. A false here is answered by the next /Ask with a full
                // acknowledgement of every byte and then silence until the connection is
                // reset, which from this side is indistinguishable from a prompt that was
                // shown and ignored.
                Trace.write("* discover says airDropable="
                    + Bplist.flag(caps, "IsAirDropable", true)
                    + " name=\"" + name + "\" model=\"" + model + "\""
                    + " media=" + Bplist.string(caps, "ReceiverMediaCapabilities"));
            } else {
                Trace.write("* discover answered " + discovered.status
                    + " with a body that is not a plist");
            }
        } else {
            // Not fatal, but a receiver that refuses /Discover and then refuses /Ask is
            // better diagnosed from the first status than the second.
            Trace.write("* discover refused: " + discovered.status + " "
                + HttpResponse.reason(discovered.status));
        }
        Trace.write("* closing the discovery connection; /Ask goes on its own");
        call.drop();
        return new SelfDescription(name, model);
    }

    /**
     * {@link #discover} with its failure removed from the path.
     *
     * <p>The class comment has said all along that a {@code /Discover} that fails is not
     * fatal, and the code did the opposite: every timeout and every reset in it went
     * straight up into {@code send()} and ended the transfer. What that cost, measured, is
     * the whole tap. A receiver whose service had just rebuilt completed the handshake,
     * read nothing for thirteen and a half seconds, reset the connection, and the run
     * stopped there without ever posting the one request that makes a phone show a prompt.
     * The user saw "connection interrupted" and the other person saw nothing at all.
     *
     * <p>Nothing downstream needs this answer. {@code /Ask} carries the same identity
     * fields and is answered by a person rather than by a capability query, so a name is
     * what is lost and a name is all that is lost -- and the name is normally learned from
     * the other direction anyway, off the {@code /Discover} the receiver posts to us.
     *
     * <p>The connection is dropped here rather than left to the caller: after a timeout or
     * a reset its framing is unknowable, and /Ask opens its own.
     */
    private SelfDescription discoverSafely(HttpCall call) {
        try {
            return discover(call);
        } catch (IOException | RuntimeException noAnswer) {
            Trace.write("! /Discover gave nothing (" + noAnswer + "); offering without a name");
            try {
                call.drop();
            } catch (RuntimeException ignored) {
                // Already gone, which is the state this method exists to tolerate.
            }
            return new SelfDescription(null, null);
        }
    }

    /** What a receiver said about itself in {@code /Discover}; either half may be null. */
    private static final class SelfDescription {
        final String name;
        final String model;

        SelfDescription(String name, String model) {
            this.name = name;
            this.model = model;
        }
    }

    private static List<AirDropProtocol.Item> items(List<Outgoing> files) {
        List<AirDropProtocol.Item> out = new ArrayList<>(files.size());
        for (Outgoing file : files) {
            // The declared size is not decoration: §6.4 makes FileSize required in the
            // current form, and a receiver that trusts it draws its progress bar from it.
            out.add(new AirDropProtocol.Item(file.name, file.type, file.bomPath, false,
                file.length));
        }
        return out;
    }

    /**
     * Writes the archive UP-2a describes, in the format UP-2 requires of a sender.
     *
     * <p>Three parts, in order: a {@code .} directory entry that marks the destination
     * folder, one regular-file member per offer, then the trailer. The names are the
     * {@code FileBomPath} values from the offer -- ASK-5 has the receiver check that
     * each one is {@code ./<FileName>}, and UP-7 lets it drop members that were never
     * offered at all, so a member name that differs from the offer is a file that
     * arrives as nothing.
     */
    private static void writeArchive(OutputStream out, List<Outgoing> files) throws IOException {
        Cpio.writeOdcMember(out, ".", Cpio.MODE_DIRECTORY, new byte[0], 0);
        int inode = 1;
        for (Outgoing file : files) {
            try (InputStream data = file.open()) {
                Cpio.writeOdcMember(out, file.bomPath, Cpio.MODE_REGULAR, file.length, data, inode++);
            }
        }
        Cpio.writeOdcTrailer(out);
    }

    /**
     * Picks an address to dial. Link-local is what an AWDL link hands out, so it wins
     * over anything else the peer listed, and it is only reachable with the interface
     * named — {@code InetAddress} has no setter for the zone, so the suffix goes into
     * the literal.
     */
    private static InetAddress addressOf(AirDropPeer peer, String interfaceName) throws IOException {
        if (peer.addresses == null || peer.addresses.isEmpty()) {
            throw new IOException("peer " + peer.displayName + " advertised no address");
        }
        String chosen = null;
        for (String candidate : peer.addresses) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith("fe80")) {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null) chosen = peer.addresses.get(0);

        String scoped = interfaceName == null ? chosen : chosen + "%" + interfaceName;
        InetAddress resolved;
        try {
            resolved = InetAddress.getByName(scoped);
        } catch (IOException unresolved) {
            // Some resolvers reject the zone suffix; the bare literal still works when
            // the route table already points at the link.
            try {
                resolved = InetAddress.getByName(chosen);
            } catch (IOException failed) {
                throw new IOException("cannot resolve " + scoped, failed);
            }
        }
        // Before the caller builds a socket. The congestion window is read once, when the
        // connection is set up, so a route installed after connect() buys nothing -- and
        // both the warmed path and the cold one resolve their address through here.
        PeerRoute.install(chosen, interfaceName);
        return resolved;
    }

    /** Counts archive bytes on their way into the socket and reports in coarse steps. */
    private static final class Meter {
        private final Progress progress;
        private final long total;
        private long count;
        private long reported;

        Meter(Progress progress, long total) {
            this.progress = progress;
            this.total = total;
        }

        OutputStream wrap(OutputStream out) {
            return new OutputStream() {
                @Override
                public void write(int single) throws IOException {
                    out.write(single);
                    advance(1);
                }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    out.write(data, offset, length);
                    advance(length);
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }
            };
        }

        private void advance(int bytes) {
            count += bytes;
            // The first write is its own milestone: it is the moment the receiver's
            // prompt has been answered and the archive exists as bytes rather than as a
            // plan, and everything the user sees before it is a still frame.
            if (reported == 0 || count - reported >= PROGRESS_STEP) {
                reported = count;
                progress.onProgress(count, total);
            }
        }

        long written() {
            return count;
        }
    }
}
