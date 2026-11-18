package com.oshare.airdrop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The device's view of the Rust library.
 *
 * <p>This file is the contract, not a convenience wrapper: every method here is a symbol the
 * Rust side spells out by hand ({@code Java_com_oshare_airdrop_Native_<name>}), so the package
 * must stay {@code com.oshare.airdrop} and the class must stay {@code Native}. Renaming either
 * moves the symbol — the package name is part of it — and the JVM will report
 * {@code UnsatisfiedLinkError} on the first call. The package was changed once, from
 * {@code com.opairdrop}, and this file and {@code crates/ffi/src/jni.rs} had to change together.
 *
 * <p>Nothing structured crosses this boundary. Names of received files come back packed
 * into one {@code byte[]} — an eight-byte big-endian total, then the names in archive order,
 * NUL-terminated, UTF-8 — rather than as a {@code String[]}, because a share of a few
 * hundred photos would exhaust the JVM's 512-entry local reference table before the array
 * was full, and the error would name nothing.
 *
 * <p>Every failure arrives as an exception carrying the Rust side's own message. A
 * {@code java.io.IOException} for anything that happened while reading the stream or
 * writing the files, {@code IllegalArgumentException} for a path or a setting that is not
 * allowed, {@code NullPointerException} for a missing argument.
 */
public final class Native {
    private Native() {}

    static {
        System.loadLibrary("opairdrop_ffi");
    }

    /**
     * The ABI version this Java source was written against.
     *
     * <p>Lives here, beside {@link #abiVersion()}, rather than once per caller: a number
     * that has to be edited in several places is a number that will be edited in all but
     * one of them. That happened — the library moved to 3 while two callers still said 2,
     * and the visible symptom was a receiver refusing to start with a message about a stale
     * library that was in fact current.
     *
     * <p>Every change to a {@code native} signature above bumps this.
     */
    public static final int ABI_VERSION = 5;

    /**
     * The ABI version this library was built against.
     *
     * <p>Call this once before anything else: a library whose answer differs from
     * {@link #ABI_VERSION} is a stale {@code .so}, and every later call would be reading a
     * struct the two sides disagree about.
     */
    public static native int abiVersion();

    /**
     * Receives one archive into {@code directory}, reading it from {@code archive}.
     *
     * <p>The stream is read to its end; the caller keeps ownership of it and of closing it.
     * Whatever {@link InputStream#read(byte[], int, int)} throws becomes this method's
     * exception, so a socket that times out fails here with the socket's own diagnosis
     * rather than with a generic one.
     *
     * <p>The stream must end where the archive ends. This layer cannot tell a complete
     * archive from one whose last member is still in flight — a cpio trailer carries no
     * data, so a connection dropped between members looks exactly like success. Framing is
     * the HTTP layer's job, and it is the only layer that knows how many bytes were
     * promised.
     *
     * @param settings the stored settings text, or null for the table's defaults
     * @return the report frame described in the class comment
     * @throws IOException if the stream fails or a file cannot be written
     * @throws IllegalArgumentException if the settings cannot be read
     */
    public static native byte[] receiveArchive(String directory, String settings, InputStream archive)
            throws IOException;

    /**
     * Serves one connection: requests are read from {@code input}, answers written to
     * {@code output}.
     *
     * <p>This is the call that makes the device a receiver rather than only a parser. It
     * answers {@code /Discover} and {@code /Ask} with the name in the settings — falling back
     * to {@code model} when that name is blank — and writes what arrives at {@code /Upload}
     * into {@code directory} under the same rules as {@link #receiveArchive}.
     *
     * <p>It returns when the connection ends. After an upload the endpoint ends it on
     * purpose, because an archive is the last thing a sender has to say; a caller that wants
     * a second conversation accepts the next connection. The caller owns the socket — it
     * listened, it accepted, it closes — and this method closes neither stream. That is also
     * what lets a caller wrap the connection in TLS before handing it over: this layer only
     * sees an {@code InputStream} and an {@code OutputStream}.
     *
     * <p>The report describes <em>this connection</em>, so two uploads on one connection add
     * up. A refusal ends the connection with a {@code 500} and arrives here as an exception
     * whose message says why; the members that landed before it are on disk either way, each
     * whole or not at all.
     *
     * @param settings the stored settings text, or null for the table's defaults
     * @param model what the operating system calls this hardware, or null
     * @return the report frame described in the class comment
     * @throws IOException if either stream fails or a member cannot be written
     * @throws IllegalArgumentException if the settings cannot be read, or the directory is
     *     empty or not a string
     */
    public static native byte[] serve(
            String directory, String settings, String model, InputStream input, OutputStream output)
            throws IOException;

    /**
     * How far a transfer has got.
     *
     * <p>Called on the sending thread. {@link #READY}, {@link #WAITING} and {@link #DONE}
     * arrive once each; {@link #SENDING} arrives at most once per half-megabyte read.
     *
     * <p>It must not block: the call happens inside the file read, so a slow listener makes
     * the transfer itself slow. Marshal to the UI thread and return.
     */
    public interface Progress {
        /** Connected, and the peer has said it takes files. */
        int READY = 0;
        /** `/Ask` is out; the other phone is showing its accept prompt. */
        int WAITING = 1;
        /** Accepted, bytes are moving. */
        int SENDING = 2;
        /** The archive is in and the peer said so. */
        int DONE = 3;

        void report(int phase, long sent, long total);

        /**
         * The peer's own name, once, as soon as `/Discover` answers.
         *
         * <p>Default so that sinks written before this callback existed keep compiling and
         * working — they simply never learn the name. Called on the sending thread, before the
         * first {@link #READY} report; marshal to the UI thread before touching a view.
         */
        default void name(String peerName) {}
    }

    /**
     * Opens the second connection a transfer needs.
     *
     * <p>An iPhone reads <em>one request per connection</em>. After answering
     * {@code /Discover} it stops reading, and it leaves the socket open while it does —
     * so a sender that writes {@code /Ask} onto that socket sees not a refusal but
     * silence, indefinitely. The same {@code /Ask} on a connection of its own is answered
     * {@code 200} with a session id, and the far phone shows its prompt. Renewing is
     * therefore part of the protocol, not housekeeping.
     *
     * <p>{@code send}/{@code sendWithProgress} call this exactly once, between
     * {@code /Discover} and {@code /Ask}. {@link Progress#READY} has already been reported
     * when it happens, so a caller that needs to change anything visible can. The
     * {@code /Upload} that follows stays on the connection this returned, because the
     * offer's answer is what the archive belongs to.
     *
     * <p>{@link #connect} is called on the sending thread. It should close the previous
     * connection first — the receiver has finished with it — and it must return a socket
     * whose streams are already wrapped however the caller wraps them (TLS included), as a
     * plain {@code Socket} is the only way this layer can reach them. Throwing is reported
     * as an {@code IOException} from the send call.
     */
    public interface Link {
        java.net.Socket connect() throws IOException;
    }

    /**
     * Sends, reporting progress as the files are read.
     *
     * <p>The numbers count bytes taken off disk, not bytes on the wire: DVZip compresses, so
     * the two disagree by however much the data happens to compress to, and a ring driven by
     * the wire would jump. What the person chose is the honest denominator.
     *
     * @param progress a sink that is called while the transfer runs; never null
     * @param link how to obtain the connection {@code /Ask} and {@code /Upload} use; never
     *     null, because a transfer that cannot open a second connection cannot finish
     * @param settings the stored settings text, or null for every default; see {@link #send}
     * @param senderId who this device is, as the peer records it; see {@link #send}
     * @return {@code peer<TAB>name\nbytes<TAB>count\n} as UTF-8
     * @throws RefusedException if the peer answered that it is not taking files, which is
     *     the one failure here that is not a fault — see the class comment. It arrives before
     *     {@link Progress#READY}, because the peer's answer to {@code /Discover} is what says
     *     so, and no offer can be made to a peer that has said it will not take one.
     * @throws IOException if the connection fails
     */
    public static native byte[] sendWithProgress(
            String host,
            String settings,
            String sender,
            String model,
            String senderId,
            String transferId,
            String[] paths,
            InputStream input,
            OutputStream output,
            Link link,
            Progress progress)
            throws IOException;

    /**
     * Asks one peer for the name a person gave it, without starting a transfer.
     *
     * <p>The device list shows an mDNS hash until this is called: on current iPhones the
     * instance name is a rotating identifier with no name in it, and the only place the real
     * name appears is the {@code /Discover} answer. This sends just that one request on a
     * connection the caller has already made (TLS and certificate pinning included). No
     * {@code /Ask} follows, so nothing appears on the peer's screen.
     *
     * @param host the peer's host or address, as for {@link #sendWithProgress}
     * @param input  the already-connected stream to read the answer from
     * @param output the already-connected stream to write the request to
     * @return the peer's own name as UTF-8 bytes (empty when it did not supply one)
     * @throws IOException if the connection or the request fails
     */
    public static native byte[] discoverName(
            String host,
            String settings,
            String sender,
            String model,
            String senderId,
            InputStream input,
            OutputStream output)
            throws IOException;

    /**
     * Who decides whether a transfer is taken.
     *
     * <p>Called on the thread serving the connection, after the offer has been read and
     * before `/Ask` is answered. It may block — that is what it is for — and the connection
     * waits: the peer is in the middle of asking, and the answer it gets is the answer this
     * returns.
     *
     * <p>{@code summary} is {@code sender<TAB>files<TAB>bytes<TAB>links}, UTF-8. Four fields
     * rather than a sentence because the wording belongs to whoever shows it — and the fourth
     * is not decoration: a share from Safari is a links-only offer, so a three-field summary
     * describes it as "0 files", which reads as "there is nothing to take" on the one prompt
     * where somebody is deciding.
     *
     * <p>Returning {@code false}, throwing, or throwing {@link InterruptedException} all
     * mean the same thing downstream: the transfer is refused.
     */
    public interface Decision {
        boolean accept(String summary);
    }

    /**
     * Told how far a receive has got.
     *
     * <p>Called on the thread serving the connection, from inside the inbox's write loop:
     * once per landed chunk, so as often as bytes arrive. It must not block and must not do
     * real work — the transfer is waiting on it. Marshal to the UI thread and return.
     *
     * <p>{@code done} and {@code total} are payload bytes, which is the unit an offer's sizes
     * are in, so {@code done} can be held against what the sender announced. {@code total} is
     * what it declared and it is not a promise: a peer may send more or less than it said,
     * and a {@code total} of zero means it declared nothing at all — show motion rather than
     * a fraction in that case.
     *
     * <p>Throwing stops the reports. It does not stop the transfer.
     */
    public interface Landing {
        void landed(long done, long total);
    }

    /**
     * Serves one connection, asking {@code decision} before every transfer is taken.
     *
     * <p>The older {@link #serve} answers `/Ask` before it has read the offer, which is what
     * makes a stalled sender harmless. That shortcut is impossible here by construction: a
     * prompt that has not read the offer cannot describe it. The cost is the documented one
     * — the peer waits for its answer while it uploads the offer — and it is paid only on
     * connections where somebody is actually going to be asked.
     *
     * @param decision asked once per offer; never null
     * @return the report frame described in the class comment
     * @throws IOException if a stream fails or a member cannot be written
     * @throws IllegalArgumentException if the settings cannot be read, or the directory is
     *     empty or not a string
     */
    public static native byte[] serveDecided(
            String directory,
            String settings,
            String model,
            InputStream input,
            OutputStream output,
            Decision decision)
            throws IOException;

    /**
     * Serves one connection, asking {@code decision} before every transfer and reporting how
     * far the bytes have got to {@code landing}.
     *
     * <p>{@link #serveDecided} with a watcher. A separate method rather than two more
     * parameters on that one, for the same reason that one is separate from {@link #serve}: a
     * caller built against an older signature keeps working.
     *
     * @param decision asked once per offer; never null
     * @param landing told how many bytes have landed, and how many the offer declared; never
     *     null
     * @param model what the system calls this hardware — shown to a peer as the model
     * @param systemName what the system calls *this phone*: the device name in Settings, which
     *     is also what Bluetooth advertises. Blank falls back to {@code model}, which is what a
     *     peer used to be shown for an untouched installation.
     * @return the report frame described in the class comment
     * @throws IOException if a stream fails or a member cannot be written
     * @throws IllegalArgumentException if the settings cannot be read, or the directory is
     *     empty or not a string
     */
    public static native byte[] serveWatched(
            String directory,
            String settings,
            String model,
            String systemName,
            InputStream input,
            OutputStream output,
            Decision decision,
            Landing landing)
            throws IOException;

    /**
     * The PTR query a sender multicasts to find AirDrop peers.
     *
     * <p>Send it to {@code 224.0.0.251:5353} on the interface the peer is reachable over,
     * and hand every datagram that comes back to {@link #mdnsRecords(byte[])}. Nothing here
     * opens a socket: which interface a peer is on is the one thing this library cannot
     * know.
     *
     * @return the DNS message as bytes
     */
    public static native byte[] mdnsQuery();

    /**
     * Reads one mDNS response into record lines.
     *
     * <p>Tab-separated, one record per line: {@code srv instance host port},
     * {@code addr host address}, {@code txt instance key value}. Records are returned rather
     * than joined into peers because a peer's facts arrive across two or three packets —
     * joining them needs the socket's memory of what has been seen so far, which is the
     * caller's.
     *
     * <p>A datagram that is not DNS throws {@link IOException}; a DNS message with nothing
     * relevant answers an empty array, which is the common case.
     *
     * @return the record lines as UTF-8
     * @throws IOException if the packet is not a DNS message
     */
    public static native byte[] mdnsRecords(byte[] packet) throws IOException;

    /**
     * Builds one announcement: the records that make this device visible to a peer browsing.
     *
     * <p>{@code instance} is the bare label — the name the other phone shows — and is escaped
     * here, so a name containing a dot stays one name. {@code host} is the full
     * {@code something.local} the SRV record targets and the AAAA records are owned by.
     * {@code txt} is {@code key=value} per entry, in the order the record should carry them;
     * {@code addresses} is one textual IPv6 address per entry, and any entry that does not
     * parse is left out rather than failing the announcement.
     *
     * <p>{@code id} is zero for an announcement and the query's id for a unicast reply.
     * {@code types} is a mask of {@link Responder}'s {@code RECORD_*} bits, so a caller can
     * announce the address alone or withdraw everything. {@code ttl} is
     * {@link Responder#TTL_AS_PUBLISHED} for each record's own lifetime, and {@code 0} to
     * withdraw: a record sent with a zero lifetime is how RFC 6762 §10.1 says "delete this",
     * and a responder that only stops announcing is remembered for seventy-five minutes.
     *
     * @return the DNS message as bytes
     * @throws IOException if an address or a name cannot be put on the wire
     */
    public static native byte[] mdnsAnnounce(
            String instance,
            String host,
            int port,
            String[] txt,
            String[] addresses,
            int id,
            int types,
            int ttl);

    /**
     * Answers one query packet, or answers nothing when the packet asks nothing of ours.
     *
     * <p>The result carries a one-byte header before the message: {@code 0} means multicast
     * it to {@code ff02::fb:5353}, {@code 1} means send it back to the address the query came
     * from — which RFC 6762 §6.7 requires when the querier asked from a port other than 5353.
     * An empty array means there is nothing to say, which is the ordinary answer to another
     * device's own announcement and not an error.
     *
     * <p>The mask of records is decided here rather than by the caller: a {@code PTR} question
     * for the service gets the whole instance, an {@code SRV} question brings the TXT and the
     * address with it because an SRV without a certificate hash is a peer nobody can connect
     * to, and a {@code TXT} or {@code AAAA} question gets exactly what it asked for.
     *
     * @return one flag byte and the DNS message, or an empty array
     * @throws IOException if the packet is not a DNS message at all
     */
    public static native byte[] mdnsReply(
            byte[] packet,
            String instance,
            String host,
            int port,
            String[] txt,
            String[] addresses)
            throws IOException;

    /**
     * Sends {@code paths} to the peer on the far end of {@code input}/{@code output}.
     *
     * <p>The connection has to be established already — address, port and, for AirDrop, the
     * TLS session — because this layer knows nothing about certificates beyond the bytes
     * they carry. {@code host} is the {@code Host} header, which is the peer's mDNS instance
     * name when it was found that way and its address otherwise.
     *
     * <p>{@code transferId} must be 32 hexadecimal digits, dashes allowed; draw it from
     * {@link java.util.UUID#randomUUID()} rather than inventing one, because the receiver
     * echoes it back and a malformed identifier fails on that phone rather than here.
     *
     * <p>Every file is streamed into a cpio archive inside a DVZip block sequence, so a
     * multi-gigabyte share costs memory in blocks, not in file sizes.
     *
     * @param host the {@code Host} header value
     * @param settings the stored settings text, or null for every default. Only
     *     {@code http.response_limit} is read on this side, and it is the whole text rather
     *     than the one number so that a second row can be obeyed without a second parameter.
     *     Text that does not parse is refused instead of ignored: a person who edited a row
     *     should not have the send quietly send with defaults.
     * @param sender the name the receiving phone shows, or null
     * @param model what this hardware is called, or null
     * @param senderId who this device is, as the peer records it. Not the transfer id: this
     *     one has to be the **same string on every transfer**, because a receiver groups a
     *     sender's activity by it — a value drawn per transfer arrives as a stranger each
     *     time. {@link Identity#senderId()} derives one that survives restarts; null is
     *     accepted and falls back to the transfer id, which is a body a receiver can still
     *     read but nothing more
     * @param transferId a UUID in any casing
     * @param paths absolute paths of regular files, in the order they should arrive
     * @param link how to obtain the connection {@code /Ask} and {@code /Upload} use; never
     *     null
     * @return {@code peer<TAB>name\nbytes<TAB>count\n} as UTF-8
     * @throws RefusedException if the peer answered that it is not taking files
     * @throws IOException if the connection fails
     * @throws IllegalArgumentException if a path cannot be sent or the id is malformed
     */
    public static native byte[] send(
            String host,
            String settings,
            String sender,
            String model,
            String senderId,
            String transferId,
            String[] paths,
            InputStream input,
            OutputStream output,
            Link link)
            throws IOException;

    /**
     * Starts the BLE advertisement that wakes a sleeping receiver, and answers where it got
     * to.
     *
     * <p>This is layer 1 of discovery, and it is the only layer that is a knock rather than a
     * transport: nothing in it carries data. An iPhone listens for this advertisement and
     * only then brings AWDL's AirDrop service up, which is why a query can be perfectly
     * formed, go out on the wireless every two seconds, and be answered by nobody — the
     * receiver was present at every other layer with nothing listening on 8770.
     *
     * <p>The twenty bytes and the four advertising settings are chosen on the Rust side,
     * because they are Apple's format and not a caller's choice. The radio is
     * {@code BleRadio}'s, which hands them over and reports the stack's verdict back through
     * {@link #bleVerdict(int)}.
     *
     * <p>Calling this again while something is registered answers the current state and does
     * not register twice.
     *
     * @param context an {@code android.content.Context}, normally the calling activity
     * @return one state line as UTF-8: {@code idle}, {@code starting}, {@code advertising},
     *     or {@code failed: } and the reason. A failure to wake is not an error the caller
     *     should raise: a receiver that is already awake still answers.
     */
    public static native byte[] bleAdvertise(android.content.Context context);

    /**
     * Records the Bluetooth stack's answer about the advertisement.
     *
     * <p>Called from {@code BleRadio}, on whichever thread had a looper when the
     * registration was made. A positive code is the stack's own {@code ADVERTISE_FAILED_*},
     * zero is success, and a negative one means the radio never got that far — the numbers
     * are {@code BleRadio}'s {@code NO_PERMISSION} and siblings, and are a contract with
     * {@code crates/ffi/src/ble.rs}.
     *
     * @return the new state line as UTF-8, ready to be logged
     */
    public static native byte[] bleVerdict(int code);

    /**
     * Takes the advertisement down, and answers the state line.
     *
     * <p>Stopping something that was never started is not a failure. A page stops on its way
     * out whether or not the advertisement began.
     *
     * @return one state line as UTF-8, {@code idle} unless something went wrong
     */
    public static native byte[] bleStop();

    /**
     * The settings catalog: one line per setting, tab-separated.
     *
     * <p>{@code key}, the kind spec, the default, the title, the summary — with a version
     * head line before them. The settings screen draws what this returns, so a setting the
     * app does not know about cannot exist, and one in the table cannot be missing from the
     * screen. There is no second list of keys in Java for the same reason.
     *
     * @return the catalog as UTF-8 text
     */
    public static native byte[] settingsCatalog();
    /**
     * Reads the settings stored at {@code path}, or every default when there is no file.
     *
     * <p>A file that exists but does not parse is refused rather than replaced: the message
     * names the line, and the person can fix it. Answering with defaults would silently
     * discard everything they had set.
     *
     * @return the stored form as UTF-8 text, {@code key=value} per line
     * @throws IllegalArgumentException if the file cannot be parsed
     * @throws IOException if the file exists and cannot be read
     */
    public static native byte[] loadSettings(String path) throws IOException;

    /**
     * Validates {@code text} and, if it holds, stores it at {@code path}.
     *
     * <p>Validation happens before the write, so a file that would not load again is never
     * the one on disk. A save replaces the file wholesale, in table order: a comment added
     * by hand is read and is not carried through.
     *
     * @return the stored form as written
     * @throws IllegalArgumentException if a value is outside its range or names a key the
     *     table does not have
     * @throws IOException if the file cannot be written
     */
    public static native byte[] saveSettings(String path, String text) throws IOException;
}
