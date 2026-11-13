package com.oshare.airdrop.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * An AirDrop probe that is actually an AirDrop probe.
 *
 * <p>The run before this one connected to 8770 and then read nothing for thirty-two
 * seconds. What it had sent was {@code GET /Discover HTTP/1.1} in the clear, and the
 * spec settles both halves of why that could not be answered: TLS-1 puts every AirDrop
 * request inside TLS 1.2 or 1.3, and HTTP-2 makes every request a {@code POST}. A server
 * waiting for a TLS ClientHello that is handed {@code 'G'} has nothing to say; silence is
 * correct behaviour for it. Reading that silence as "the peer does not answer" was the
 * error, and it is the second time in this investigation that a broken instrument was
 * mistaken for a broken link -- {@code nc} was the first.
 *
 * <p>What survives from the plaintext attempt is the measurement that mattered:
 * {@code CONNECTED in 7376ms} means TCP over the tun over AWDL completed a handshake, so
 * SYN, SYN-ACK and ACK all crossed the radio in both directions. Everything above TCP is
 * now a question of speaking the right protocol rather than of whether the link works.
 *
 * <p>No client certificate, and that is not an omission: TLS-3 has a sender present one
 * on the connection carrying /Ask and /Upload and none on the /Discover connection, and
 * requires a receiver to accept a connection without one. TLS-2 is why the trust manager
 * below accepts anything -- receivers are self-signed and host names are not checked, so
 * verifying would reject every peer while protecting nothing.
 *
 * <p>The body is an empty dictionary, which is the vector T-DISC-1 uses and the smallest
 * thing a receiver will parse. ID-4 guarantees an Everyone-mode receiver answers every
 * {@code /Discover}, so no capability list is needed to earn a reply. The answer is
 * decoded with {@link Bplist}, the same class the product will use, so a plist that this
 * probe cannot read is one the product could not have read either.
 *
 * <p>The reader that was here stopped at the blank line ending the headers, which is
 * precisely where the body begins. That was invisible on a fast link and fatal on this
 * one, where head and body arrive thirty seconds apart: it would have discarded every
 * answer it was built to collect. That is the third instrument in this investigation to
 * be broken before the thing it was measuring -- {@code nc} and the plaintext request
 * were the first two.
 *
 * <p>{@code ask} is the other half of the protocol and the reason any of this matters:
 * {@code /Discover} answers "who is there", {@code /Ask} asks "will you take this file",
 * and only its answer moves a byte of payload. Its body is not the same shape as a
 * Discover body -- it names files rather than a sender -- so a probe that only varies
 * switches on a Discover body cannot reach it. The switch set still applies, because the
 * two paths sit behind one server and a difference in how it treats them is a difference
 * in how it treats one of them.
 *
 * <p>{@code send} is {@code ask} and then the {@code /Upload} the answer permits, on the
 * same connection because HTTP-1 and UP-6 leave no alternative. It is the only mode that
 * moves payload, and the only one whose success is visible on the other device: a file
 * appears, and either it opens or it does not.
 *
 * <p>Arguments: {@code <iface> <peer-link-local> <port> <connect-ms> <read-ms> <tries>
 * [mode] [shape]}, where mode is {@code discover} (default), {@code handshake},
 * {@code get}, {@code ask}, {@code send}, {@code both}, {@code nope} or a literal
 * {@code /Path}, and
 * shape is a {@code +}-joined
 * set of switches -- {@code plain} (default), {@code close}, {@code host}, {@code real},
 * {@code chunked}, {@code plistct} -- so that a no-answer can be narrowed to one field
 * of the request rather than to the protocol carrying it.
 * Connect and read are separate numbers because they measure different things: a connect
 * is one round trip and was measured at 26-28s, while an answer to {@code /Discover} has
 * to survive a handshake and then a second round trip of the same size. One number for
 * both is how the previous version came to spend its whole budget before the peer's first
 * byte was due. {@code get} is the control that separates "the TLS and HTTP layers work
 * but the path is wrong" from "the session never got far enough to look at a path":
 * HTTP-5 has a receiver answer GET with 200.
 */
final class AwdlTcp {

    private AwdlTcp() {
    }

    /**
     * The name the session is opened to, which is not the address it is opened at.
     *
     * <p>A sender connects to {@code airdrop.local} -- not to a socket that happens to
     * point at a receiver -- and TLS carries that name in SNI. A receiver is free to decide
     * what it serves by it. Leaving it empty and then reading the peer's silence as a
     * refusal would be the fourth instrument in this investigation to be broken before
     * the thing it was measuring, and the only one that would have been our own
     * omission.
     */
    private static final String SNI_HOST = name();

    /**
     * The name as a run asked for it, or the default.
     *
     * <p>It became settable because a name is not a label here: a receiver decides what
     * to serve by it, and two runs that differed above the IP header only in this string
     * settled on different TLS versions. A control that can vary the name is the only way
     * to tell "the receiver refused the request" from "the receiver put the session on a
     * different footing because of how it was addressed".
     */
    private static String name() {
        String asked = System.getenv("TLS_SNI");
        return asked != null && !asked.isEmpty() ? asked : "airdrop.local";
    }

    /**
     * The version a run insists on, or null to let the peer choose.
     *
     * <p>Offering both and reading back the result answers "which version does the peer
     * prefer", which is a fact about the peer. A pinned run answers "does the version
     * alone change what the receiver does with the request", which is the fact this
     * investigation needs, and the two are not the same question.
     */
    private static String pinnedVersion() {
        String asked = System.getenv("TLS_VERSION");
        if (asked == null || asked.isEmpty()) return null;
        return asked.startsWith("TLS") ? asked : "TLSv" + asked;
    }

    /**
     * The request shapes worth telling apart by name, joined with {@code +}.
     *
     * <p>{@link #SHAPE_PLAIN} is the minimal request: a {@code keep-alive} connection
     * and no {@code Host}. {@link #SHAPE_HOST} adds the one header the product's own
     * client sends and the spec says a receiver must not require. {@link #SHAPE_CLOSE}
     * is the shape that produced a bare TLS farewell and no HTTP at all, kept so the
     * next run can re-measure it rather than remember it.
     *
     * <p>The last three exist because the first three were all refused in the same way:
     * a server that reads a request and answers with a single encrypted alert has told us
     * it disliked something in the request and nothing about which line it was. So the
     * request became a set of independent switches rather than a list of spellings --
     * {@code real+host} says "a body with the fields a real sender fills in, plus Host"
     * without a new constant per combination. Each switch is one hypothesis about what
     * "an empty dict is a valid request" stopped meaning on this receiver.
     */
    private static final String SHAPE_CLOSE = "close";
    private static final String SHAPE_HOST = "host";
    private static final String SHAPE_PLAIN = "plain";

    /**
     * A body that names the sender, which is the only thing a receiver can show a person.
     *
     * <p>§6.3 marks all three of these optional and says an empty dict is a valid
     * request. Both statements can be true of the format and false of the implementation:
     * a Discover with no computer name has nothing to put in the consent prompt, and a
     * receiver that gives up on it before deciding whether to answer would close the
     * connection exactly as this one does. This switch exists to find out.
     */
    private static final String SHAPE_REAL = "real";

    /**
     * {@code Transfer-Encoding: chunked} instead of a length, the framing HTTP-2 uses for
     * the {@code /Ask} example and HTTP-2a requires a receiver to accept for both paths.
     *
     * <p>Worth a switch of its own because chunked and Content-Length are parsed by
     * different code in any server: if only one of the two is refused here, the fault is
     * in that parser and not in the request's meaning.
     */
    private static final String SHAPE_CHUNKED = "chunked";

    /**
     * {@code Content-Type: application/x-apple-binary-plist} rather than
     * {@code application/octet-stream}.
     *
     * <p>HTTP-3 says the bodies <em>are</em> binary plists and HTTP-2's sample says the
     * type is octet-stream, so the minimal shape is right on the letter. But a receiver
     * that routes on the media type would read one and not the other, and the cost of
     * asking is one connection.
     */
    private static final String SHAPE_PLIST_CT = "plistct";

    /**
     * The sender's service ID, fixed so that two runs differ only in what they change.
     *
     * <p>MDNS-3 makes it a UUID and nothing in §6.3 validates its shape, so a constant is
     * as real as a random one and comparable between runs.
     */
    private static final String SENDER_ID = "3B1F5C2A-9D4E-4F1B-8A77-2C6D5E9A1B34";

    /**
     * {@code bplist00} of an empty dictionary, 42 bytes.
     *
     * <p>Written out rather than encoded because the whole point of this probe is to be
     * sure of what it sends: object 0 is {@code 0xd0}, the empty-dict marker; the offset
     * table is the single byte {@code 0x08}; the trailer names one object, the top object
     * 0, and the table at offset 8. A plist library would produce these same bytes and
     * hide the fact that they are the only thing standing between us and a 400.
     */
    private static final byte[] BPLIST_EMPTY_DICT = {
            0x62, 0x70, 0x6c, 0x69, 0x73, 0x74, 0x30, 0x30,   // "bplist00"
            (byte) 0xd0,                                      // object 0: {}
            0x08,                                             // offset table: {8}
            0, 0, 0, 0, 0, 0,                                 // trailer: six spare
            0x01,                                             //   offset int size
            0x01,                                             //   object ref size
            0, 0, 0, 0, 0, 0, 0, 0x01,                        //   numObjects 1
            0, 0, 0, 0, 0, 0, 0, 0x00,                        //   topObject 0
            0, 0, 0, 0, 0, 0, 0, 0x08,                        //   table offset 8
    };

    public static void main(String[] args) {
        if (args.length < 6) {
            System.out.println("awdl-tcp: <iface> <peer-ll> <port> <connect-ms> <read-ms> <tries>"
                    + " [discover|get|ask|send|nope|handshake|/Path]"
                    + " [plain|host|close|real|chunked|plistct, joined with +]");
            System.exit(2);
        }
        String iface = args[0];
        String peer = args[1];
        int port = Integer.parseInt(args[2]);
        int connectMs = Integer.parseInt(args[3]);
        int readMs = Integer.parseInt(args[4]);
        int tries = Integer.parseInt(args[5]);
        String mode = args.length > 6 ? args[6] : "discover";
        String shape = args.length > 7 ? args[7] : SHAPE_PLAIN;

        InetAddress addr;
        try {
            NetworkInterface nif = NetworkInterface.getByName(iface);
            if (nif == null) {
                System.out.println("awdl-tcp: no interface " + iface);
                return;
            }
            // The scope is part of the address, not a decoration: a link-local without
            // one is ambiguous, and the stack answers that ambiguity with EINVAL rather
            // than with a route lookup.
            addr = InetAddress.getByName(peer + "%" + iface);
            System.out.println("awdl-tcp: " + addr.getHostAddress() + " index "
                    + nif.getIndex() + " mtu " + nif.getMTU() + " mode " + mode);
        } catch (Exception e) {
            System.out.println("awdl-tcp: resolve failed: " + e);
            return;
        }

        SSLContext ctx;
        try {
            ctx = clientContext();
        } catch (Exception e) {
            // Worth saying loudly: without a context there is no TLS, and without TLS
            // there is no conversation at all. If this is the line that fails, the run
            // measured the JVM's crypto providers rather than the link.
            System.out.println("awdl-tcp: no TLS context: " + e);
            return;
        }

        byte[] request = request(mode, shape);
        System.out.println("awdl-tcp: " + mode + "/" + shape + " request " + request.length
                + "B sni=" + SNI_HOST + " head=" + headOf(request));
        // The bytes on disk, so a request that the peer answers and one it ignores can be
        // compared rather than described. Every earlier comparison of the two was made
        // from printed heads, and a head says nothing about the body that followed it.
        dump(request);
        for (int i = 1; i <= tries; i++) {
            long t0 = System.currentTimeMillis();
            SSLSocket s = null;
            Socket raw = null;
            try {
                // A plain socket first, then the session wrapped around it. The address is
                // a scoped link-local and cannot be a host name, while the session still
                // has to name the service, and the only factory method that takes both of
                // those separately is this one. The alternative -- createSocket() with no
                // arguments, then connect() -- ends up with no host at all, so no SNI,
                // and the peer is left to guess what was asked of it.
                raw = new Socket();
                raw.connect(new InetSocketAddress(addr, port), connectMs);
                raw.setTcpNoDelay(true);
                System.out.println("awdl-tcp: try " + i + " CONNECTED in "
                        + (System.currentTimeMillis() - t0) + "ms local="
                        + raw.getLocalAddress().getHostAddress() + ":" + raw.getLocalPort());
                s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, SNI_HOST, port, true);

                // Read budget, not connect budget: startHandshake below is a series of
                // round trips, so timing it with a connect timeout measures the timeout.
                s.setSoTimeout(readMs);
                tune(s);
                long th = System.currentTimeMillis();
                s.startHandshake();
                System.out.println("awdl-tcp: try " + i + " TLS "
                        + s.getSession().getProtocol() + " " + s.getSession().getCipherSuite()
                        + " handshake in " + (System.currentTimeMillis() - th) + "ms");
                // The peer's certificate says which device answered, and that is the one
                // fact the radio cannot fake: a name here is proof the TLS session ended
                // at the phone rather than at a helper on the way.
                System.out.println("awdl-tcp: try " + i + " peer cert "
                        + subject(s.getSession().getPeerCertificates()));

                if ("handshake".equals(mode)) {
                    // Nothing above TLS is in question here. If this line is reached, the
                    // kernel's TCP crossed the radio in both directions and carried a
                    // multi-round-trip TLS session with it, which is the entire claim the
                    // tun experiment exists to test. The HTTP exchange is the product's
                    // job and the product already does it (HttpCall, AirDropClient).
                    System.out.println("awdl-tcp: try " + i + " HANDSHAKE COMPLETE in "
                            + (System.currentTimeMillis() - t0) + "ms");
                    return;
                }

                OutputStream out = s.getOutputStream();
                out.write(request);
                out.flush();
                long t1 = System.currentTimeMillis();
                // One buffered reader for the whole session, not one per answer: buffered
                // bytes belong to the connection, and a second wrap would sit on top of a
                // buffer that may already hold the start of the next answer. An /Upload
                // follows an /Ask on this same stream, so "the next answer" is not
                // hypothetical.
                InputStream in = new BufferedInputStream(s.getInputStream(), 8 * 1024);
                String status = read(i, s, in, t1, readMs, mode);
                if ("send".equals(mode)) {
                    upload(i, s, in, out, status, readMs);
                }
                // "both" exists to test a rule about the receiver rather than a field in
                // the request: HTTP-7 lets a receiver accept /Discover once per
                // connection, so the question is whether a second request on that same
                // connection is answered at all. The product sends exactly this pair on
                // one socket, which makes this the only run that can say whether the
                // silence that follows is the second request or the bytes inside it.
                if ("both".equals(mode)) {
                    byte[] second = request("ask", shape);
                    System.out.println("awdl-tcp: try " + i + " second request on the same"
                            + " connection " + second.length + "B head=" + headOf(second));
                    out.write(second);
                    out.flush();
                    long t2 = System.currentTimeMillis();
                    read(i, s, in, t2, readMs, "ask");
                }
                return;
            } catch (Exception e) {
                System.out.println("awdl-tcp: try " + i + " failed after "
                        + (System.currentTimeMillis() - t0) + "ms: " + e);
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                        // Nothing to do: the connection is already being abandoned.
                    }
                } else if (raw != null) {
                    // Only reachable when the wrap itself failed, in which case the socket
                    // underneath it is still open and is nobody else's to close.
                    try {
                        raw.close();
                    } catch (Exception ignored) {
                        // As above.
                    }
                }
            }
        }
        System.out.println("awdl-tcp: gave up after " + tries + " tries");
    }

    /**
     * Reads one HTTP answer: the status line, the headers, then the body they promised.
     *
     * <p>The test this replaces stopped at the blank line that ends the headers. On a
     * link whose round trip is thirty seconds the head and the body never arrive
     * together, so that rule threw away every answer worth having. Where the peer states
     * a {@code Content-Length} that is the only honest stopping rule; where it does not,
     * the end of the stream is, because HTTP-2a's alternative is a body that ends when
     * the connection does.
     *
     * <p>Neither of those rules covers what this receiver actually does. It answers
     * {@code 200 OK} with {@code Transfer-Encoding: chunked} and then leaves the
     * connection open, because HTTP-1 wants it open: measured over the radio, the head
     * arrives at 12.7 s and 1450 bytes of body at 14.1 s, and the connection is still
     * idle and usable afterwards. A reader that waits for the end of the stream therefore
     * times out with a complete answer already in its buffer, which is what this one did
     * for ten seconds over a body that had arrived. Chunked framing is not an
     * alternative spelling of Content-Length here; it is the only framing this peer uses,
     * so it has to be read rather than waited out.
     */
    private static String read(int attempt, SSLSocket s, InputStream in, long t1, int readMs,
                               String mode) {
        String status = null;
        int length = -1;
        boolean chunked = false;
        try {
            s.setSoTimeout(readMs);
            status = line(in);
            if (status == null) {
                // The TLS session above was established, so this is not a lost answer. A
                // timeout would have thrown and been reported below; reaching this line
                // means the stream ended, and on a session whose keys are up that is the
                // peer closing -- JSSE turns a close_notify into exactly this silent end
                // of stream and raises only for a fatal alert. So: the request was read,
                // nothing was answered, and the farewell was polite.
                System.out.println("awdl-tcp: try " + attempt + " NO REPLY after "
                        + (System.currentTimeMillis() - t1) + "ms (TLS ok, peer closed stream)");
                return null;
            }
            System.out.println("awdl-tcp: try " + attempt + " status: " + status);
            String header;
            while ((header = line(in)) != null && !header.isEmpty()) {
                System.out.println("awdl-tcp: try " + attempt + " header: " + header);
                int colon = header.indexOf(':');
                if (colon <= 0) continue;
                String name = header.substring(0, colon).trim();
                String value = header.substring(colon + 1).trim();
                if ("content-length".equalsIgnoreCase(name)) {
                    try {
                        length = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        length = -1;
                    }
                } else if ("transfer-encoding".equalsIgnoreCase(name)
                        && value.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
        } catch (Exception e) {
            System.out.println("awdl-tcp: try " + attempt + " stopped after "
                    + (System.currentTimeMillis() - t1) + "ms with " + (status == null ? 0 : 1)
                    + " line(s): " + e);
            return status;
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            if (chunked) {
                readChunked(in, body);
            } else {
                readBody(in, length, body);
            }
        } catch (Exception e) {
            // A body that stopped early is still printed: the status line and headers are
            // already known to have arrived, and how much of the body made it is the
            // difference between a framing mistake and a lost packet.
            System.out.println("awdl-tcp: try " + attempt + " body stopped after "
                    + (System.currentTimeMillis() - t1) + "ms with " + body.size() + "B: " + e);
            if (body.size() == 0) return status;
        }
        byte[] payload = body.toByteArray();
        System.out.println("awdl-tcp: try " + attempt + " body " + payload.length + "B in "
                + (System.currentTimeMillis() - t1) + "ms hex " + hex(payload, payload.length, 160));

        decode(attempt, payload, mode);
        return status;
    }

    /**
     * Sends the {@code /Upload} that has to follow an accepted {@code /Ask}.
     *
     * <p>Two rules fix the shape of this. HTTP-1 puts {@code /Ask} and {@code /Upload} on
     * one connection, which is why this writes to the socket the answer above arrived on
     * instead of opening another: UP-6 has a receiver reject an upload that does not
     * follow an accepted ask on the same connection, and from outside that rejection
     * looks like every other failure. UP-9 requires the {@code TransferID} header to equal
     * the id the ask carried; both come from one constant, so they cannot drift.
     *
     * <p>ASK-1 is why a non-200 stops it here: a sender must not send the body after a
     * refusal, and a probe that sent it anyway would be measuring the receiver's tolerance
     * for a broken sender rather than its own behaviour.
     */
    private static void upload(int attempt, SSLSocket s, InputStream in, OutputStream out,
                               String askStatus, int readMs) {
        if (askStatus == null || !askStatus.contains(" 200")) {
            System.out.println("awdl-tcp: try " + attempt + " no /Upload: /Ask answered "
                    + (askStatus == null ? "nothing" : askStatus) + " (ASK-1)");
            return;
        }
        try {
            byte[] body = uploadBody();
            byte[] head = uploadHead(body.length);
            // Head and body as one write. The first version of this sent only the head,
            // and the receiver sat waiting for the 276 bytes the head had promised until
            // its timeout -- which reads exactly like a receiver that took the file and
            // said nothing, and is the opposite conclusion.
            byte[] request = new byte[head.length + body.length];
            int at = copy(head, request, 0);
            copy(body, request, at);
            System.out.println("awdl-tcp: try " + attempt + " UPLOAD " + request.length
                    + "B head=" + headOf(request));
            out.write(request);
            out.flush();
            read(attempt, s, in, System.currentTimeMillis(), readMs, "upload");
        } catch (Exception e) {
            System.out.println("awdl-tcp: try " + attempt + " upload failed: " + e);
        }
    }

    /**
     * The DVZip stream carrying the one file.
     *
     * <p>The archive follows UP-2a's layout and UP-2's variant: an odc archive holding the
     * {@code .} root entry, the file at exactly the bom path the ask named, and
     * {@code TRAILER!!!}. The path matters -- UP-7 has a receiver reject entries that the
     * accepted ask never listed, and {@code ./probe.txt} is the only name this transfer is
     * allowed to contain.
     *
     * <p>One DVZip chunk, marked stored by DVZ-1's top bit. DVZ-2's 64 KiB split describes
     * what a multi-megabyte archive needs; a twenty-byte file is one piece however it is
     * divided, and compressing it would make the body longer than the archive. That is
     * precisely the case the stored form exists for.
     */
    private static byte[] uploadBody() throws IOException {
        List<Cpio.Entry> entries = new ArrayList<>();
        entries.add(new Cpio.Entry(".", 0040755, null));
        entries.add(new Cpio.Entry("./" + ASK_FILE_NAME, 0100644, ASK_FILE_TEXT));
        byte[] archive = Cpio.writeOdc(entries);
        System.out.println("awdl-tcp: archive odc " + archive.length + "B hex "
                + hex(archive, archive.length, 200));
        byte[] body = new byte[4 + archive.length];
        int stored = 0x80000000 | archive.length;
        body[0] = (byte) (stored >>> 24);
        body[1] = (byte) (stored >>> 16);
        body[2] = (byte) (stored >>> 8);
        body[3] = (byte) stored;
        System.arraycopy(archive, 0, body, 4, archive.length);
        return body;
    }

    /**
     * UP-1's headers for the upload, with {@code application/x-dvzip}.
     *
     * <p>DVZip rather than gzip because UP-1b tells a sender that does not know the
     * receiver's TXT flags to send it, and nothing in the {@code /Discover} answer carries
     * those flags. {@code TotalBytes} is UP-10's sum of the file sizes: advisory, since a
     * receiver must not rely on it, but the number the progress indicator counts against.
     */
    private static byte[] uploadHead(int bodyLength) throws IOException {
        return ("POST /Upload HTTP/1.1\r\n"
                + "User-Agent: AirDrop/1.0\r\n"
                + "TransferID: " + ASK_TRANSFER_ID + "\r\n"
                + "TotalBytes: " + ASK_FILE_TEXT.length + "\r\n"
                + "Content-Type: application/x-dvzip\r\n"
                + "Connection: keep-alive\r\n"
                + "Content-Length: " + bodyLength + "\r\n\r\n").getBytes("UTF-8");
    }

    /**
     * Prints what the answer says, using the product's own plist reader.
     *
     * <p>{@code ReceiverComputerName} is the name the phone shows in its own AirDrop
     * panel, which makes the answer checkable by eye rather than by faith.
     * {@code IsAirDropable} is the receiver's verdict on being sent to at all, and
     * {@code ReceiverMediaCapabilities} is the JSON that decides which formats a later
     * {@code /Ask} may offer.
     *
     * <p>{@code StatusCode} is absent. SPEC §6.3 lists it as a response field with the
     * values 100, 200 and 401, and every answer an iOS 27 phone gave was a three-key
     * dictionary without it -- so the key is reported when present and the HTTP status
     * line is what actually decided the outcome. Treating a missing key as an error
     * would have called a 200 OK a rejection, which is the same mistake in the other
     * direction as reading the empty request body as valid.
     *
     * <p>An {@code /Ask} answer says less than a {@code /Discover} one and means more: two
     * keys naming the receiver, and a {@code 200} that is the only evidence a person
     * actually agreed. ASK-1 makes the status line the whole verdict, which is why a
     * non-200 here is reported as a refusal rather than as a failed parse.
     */
    private static void decode(int attempt, byte[] body, String mode) {
        if (body.length == 0) {
            System.out.println("awdl-tcp: try " + attempt + " EMPTY BODY");
            return;
        }
        try {
            Map<String, Object> plist = Bplist.parseDictionary(body);
            Object status = plist.get("StatusCode");
            // An /Ask answer is two keys long -- ReceiverComputerName and
            // ReceiverModelName -- and nothing else. Printing the Discover line for it
            // would fill three columns with "(absent)" and hide that the receiver had in
            // fact answered.
            String label;
            boolean askAnswer = "ask".equals(mode) || "send".equals(mode);
            if (askAnswer) {
                label = "ASK";
            } else if ("upload".equals(mode)) {
                label = "UPLOAD";
            } else {
                label = "DISCOVER";
            }
            if (askAnswer) {
                System.out.println("awdl-tcp: try " + attempt + " " + label
                        + " ReceiverComputerName=" + Bplist.string(plist, "ReceiverComputerName")
                        + " ReceiverModelName=" + Bplist.string(plist, "ReceiverModelName")
                        + " keys=" + plist.keySet());
            } else {
                System.out.println("awdl-tcp: try " + attempt + " " + label
                        + " StatusCode=" + (status == null ? "(absent, HTTP status decides)" : status)
                        + " ReceiverComputerName=" + Bplist.string(plist, "ReceiverComputerName")
                        + " ReceiverModelName=" + Bplist.string(plist, "ReceiverModelName")
                        + " IsAirDropable=" + plist.get("IsAirDropable")
                        + " keys=" + plist.keySet());
            }
            // Every field, with the type it arrived as. §6.3 calls
            // ReceiverMediaCapabilities "UTF-8 JSON" and the reader returned null for it,
            // which means the receiver encodes it as something other than a plist string
            // and the format note describes its contents rather than its type. Printing
            // the type costs nothing and stops the next reader from guessing.
            for (Map.Entry<String, Object> field : plist.entrySet()) {
                Object value = field.getValue();
                String shown;
                if (value instanceof byte[]) {
                    shown = new String((byte[]) value, "UTF-8");
                } else {
                    shown = String.valueOf(value);
                }
                if (shown.length() > 700) {
                    shown = shown.substring(0, 700) + "... (" + shown.length() + " chars)";
                }
                System.out.println("awdl-tcp: try " + attempt + " field " + field.getKey()
                        + " <" + (value == null ? "null" : value.getClass().getSimpleName())
                        + "> = " + shown);
            }
        } catch (Bplist.FormatException e) {
            System.out.println("awdl-tcp: try " + attempt + " body is not a bplist: " + e);
        } catch (Exception e) {
            System.out.println("awdl-tcp: try " + attempt + " body decode failed: " + e);
        }
    }

    /** One CRLF-terminated line, or {@code null} at end of stream before any byte. */
    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int end = sb.length();
                if (end > 0 && sb.charAt(end - 1) == '\r') {
                    sb.setLength(end - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8 * 1024) {
                throw new IOException("header line too long");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * The body, sized by the peer where it said so and by the end of stream where it did
     * not. A short body is written as what arrived rather than as an error: half an
     * answer still carries the status line, and knowing it was half is the finding.
     */
    private static void readBody(InputStream in, int length, ByteArrayOutputStream acc)
            throws IOException {
        if (length >= 0) {
            byte[] body = new byte[length];
            int off = 0;
            while (off < length) {
                int n = in.read(body, off, length - off);
                if (n <= 0) {
                    System.out.println("awdl-tcp: short body " + off + " of " + length + "B");
                    break;
                }
                off += n;
            }
            acc.write(body, 0, off);
            return;
        }
        byte[] chunk = new byte[4 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            acc.write(chunk, 0, n);
            if (acc.size() > 1 << 20) break;
        }
    }

    /**
     * One chunked body: a hex size, that many bytes, CRLF, repeated, then a zero size.
     *
     * <p>Read rather than waited out, because the peer does not close after the body --
     * HTTP-1 has it hold the connection for a later {@code /Ask}, so "the stream ended"
     * never happens and a reader waiting for it waits for its own timeout. The size line
     * may carry {@code ;ext} extensions (RFC 9112 §7.1.1) and the zero chunk is followed
     * by trailer fields and a blank line; neither is used here, and both are tolerated so
     * that a peer which does use them is still read rather than reported as broken.
     */
    private static void readChunked(InputStream in, ByteArrayOutputStream acc) throws IOException {
        byte[] chunk = new byte[4 * 1024];
        while (true) {
            String sizeLine = line(in);
            if (sizeLine == null) {
                throw new IOException("chunked body ended without a last chunk");
            }
            int semi = sizeLine.indexOf(';');
            String hexSize = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hexSize, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size '" + sizeLine + "'");
            }
            if (size == 0) {
                // Trailers, then the blank line that ends them.
                String trailer;
                while ((trailer = line(in)) != null && !trailer.isEmpty()) {
                    // Nothing here reads trailers; draining them keeps the next request
                    // on this connection from starting inside one.
                }
                return;
            }
            int left = size;
            while (left > 0) {
                int n = in.read(chunk, 0, Math.min(left, chunk.length));
                if (n <= 0) {
                    throw new IOException("chunk of " + size + "B stopped with " + left + "B left");
                }
                acc.write(chunk, 0, n);
                left -= n;
                if (acc.size() > 1 << 20) {
                    throw new IOException("chunked body over 1 MiB");
                }
            }
            // The CRLF that closes the chunk's data.
            line(in);
        }
    }

    private static byte[] request(String mode, String shape) {
        try {
            String path = pathFor(mode);
            Set<String> flags = flags(shape);
            if ("get".equals(mode)) {
                // HTTP-5: a receiver answers GET with 200. No body, no length, nothing to
                // get wrong -- so a 200 here and a close on /Discover isolates the failure
                // to the request's body rather than to the session.
                return ("GET " + path + " HTTP/1.1\r\nUser-Agent: AirDrop/1.0\r\nConnection: "
                        + connection(flags) + "\r\n\r\n").getBytes("UTF-8");
            }
            // HTTP-2's header shape. The minimal one is the reference: User-Agent,
            // Content-Type, Connection, Content-Length, no Host and no Accept*. The empty
            // dictionary is a valid request under §6.3, so nothing here depends on a
            // capability list -- unless the receiver disagrees, which is what `real` is for.
            byte[] body;
            if ("ask".equals(mode) || "send".equals(mode)) {
                // A Discover body names a sender; an Ask body names files. Neither can be
                // turned into the other by a switch, so the mode decides. `send` is `/Ask`
                // plus the `/Upload` that has to follow it, so it starts from the same body.
                body = askBody();
            } else {
                body = flags.contains(SHAPE_REAL) ? realBody() : BPLIST_EMPTY_DICT;
            }
            StringBuilder head = new StringBuilder("POST ").append(path).append(" HTTP/1.1\r\n");
            head.append("User-Agent: AirDrop/1.0\r\n");
            if (flags.contains(SHAPE_HOST)) {
                // Sent because "a receiver MUST NOT require Host" is a rule about the
                // receiver, not evidence about this one, and the product's own client has
                // always sent it. If this is the line that changes the answer, the rule
                // was being observed by a receiver that never read it.
                head.append("Host: ").append(SNI_HOST).append("\r\n");
            }
            head.append("Content-Type: ")
                    .append(flags.contains(SHAPE_PLIST_CT)
                            ? "application/x-apple-binary-plist"
                            : "application/octet-stream")
                    .append("\r\n");
            head.append("Connection: ").append(connection(flags)).append("\r\n");
            if (flags.contains(SHAPE_CHUNKED)) {
                // One chunk, then the zero-length terminator. The body is small enough that
                // splitting it would only add a place to be wrong.
                head.append("Transfer-Encoding: chunked\r\n\r\n");
                byte[] h = head.toString().getBytes("UTF-8");
                byte[] tail = ("0\r\n\r\n").getBytes("UTF-8");
                byte[] size = (Integer.toHexString(body.length) + "\r\n").getBytes("UTF-8");
                byte[] crlf = "\r\n".getBytes("UTF-8");
                byte[] req = new byte[h.length + size.length + body.length + crlf.length + tail.length];
                int at = 0;
                at = copy(h, req, at);
                at = copy(size, req, at);
                at = copy(body, req, at);
                at = copy(crlf, req, at);
                copy(tail, req, at);
                return req;
            }
            head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            byte[] h = head.toString().getBytes("UTF-8");
            byte[] req = new byte[h.length + body.length];
            int at = copy(h, req, 0);
            copy(body, req, at);
            return req;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The switch names in a shape such as {@code real+host}. */
    private static Set<String> flags(String shape) {
        return new HashSet<>(Arrays.asList(shape.split("\\+")));
    }

    private static int copy(byte[] from, byte[] to, int at) {
        System.arraycopy(from, 0, to, at, from.length);
        return at + from.length;
    }

    /**
     * A {@code /Discover} body with the three fields a real sender fills in, encoded by
     * the same {@link Bplist} the product will use.
     *
     * <p>Encoding rather than writing the bytes out by hand is the opposite choice from
     * {@link #BPLIST_EMPTY_DICT}, and deliberately so: the empty dict is written by hand
     * because its bytes are the thing under test, while this body exists to be a normal
     * request, and a normal request is whatever the encoder produces.
     */
    private static byte[] realBody() {
        return Bplist.encode(Bplist.dictionary(
                "SenderComputerName", "Android",
                "SenderModelName", "PJZ110",
                "SenderID", SENDER_ID));
    }

    /**
     * The {@code /Ask} body, in the current form rather than the one the product sends.
     *
     * <p>§6.4 has two forms. The current one carries {@code TransferID} and
     * {@code TransferType}; the legacy one carries neither, and ASK-7 says a receiver that
     * gets a legacy {@code /Ask} may answer {@code 200} without prompting and then drop
     * the {@code /Upload} it was asked about. That is a 200 that means nothing, and a
     * probe that sends the legacy form would collect exactly it and learn nothing. The
     * product's own {@link AirDropProtocol#askRequest} is the legacy form, so it is not
     * reusable here even though it is the right shape in every other respect: no
     * {@code TransferID}, no {@code TransferType}, no {@code FileSize}, no
     * {@code ShouldConvertMediaFormats}.
     *
     * <p>One small text file, because the question here is whether the receiver prompts
     * at all, and a prompt shows a name and a size. The size has to be the size the
     * {@code /Upload} will actually carry or the receiver's own accounting will disagree
     * with it later.
     *
     * <p>{@code ./} on the {@code FileBomPath} is not decoration: ASK-5 has a receiver
     * refuse an {@code /Ask} whose bom path is not {@code ./<FileName>}, so a path without
     * it would measure the receiver's validation rather than its willingness to ask.
     *
     * <p>{@code SenderIdentityAuthTag} is left out. §6.4 marks it optional and says it is
     * not needed in Everyone mode, which is the mode this phone is in; sending a made-up
     * one would be a lie in a field whose derivation the spec does not define, and a
     * receiver that checked it would be right to refuse.
     */
    private static byte[] askBody() {
        List<Object> files = new ArrayList<>();
        files.add(Bplist.dictionary(
                "FileName", ASK_FILE_NAME,
                "FileType", "public.plain-text",
                "FileSize", (long) ASK_FILE_TEXT.length,
                "FileBomPath", "./" + ASK_FILE_NAME,
                "FileIsDirectory", Boolean.FALSE,
                "ShouldConvertMediaFormats", Boolean.FALSE));
        Map<String, Object> body = Bplist.dictionary(
                "SenderComputerName", "Android",
                "SenderModelName", "PJZ110",
                "SenderID", SENDER_ID,
                "BundleID", "com.apple.finder",
                "ConvertMediaFormats", Boolean.FALSE,
                "TransferID", Bplist.dictionary("id", ASK_TRANSFER_ID),
                "TransferType", Bplist.dictionary("files", Bplist.dictionary()));
        body.put("Files", files);
        // Present and empty on a file share: §6.4's sample sends it, and a receiver that
        // distinguishes "no links" from "did not say" would read its absence as the latter.
        body.put("Items", new ArrayList<Object>());
        return Bplist.encode(body);
    }

    /**
     * The name and contents of the one file the {@code /Ask} offers.
     *
     * <p>Fixed rather than generated so that two runs differ only in what the run is
     * testing, and in a format no receiver can refuse to display.
     */
    private static final String ASK_FILE_NAME = "probe.txt";

    private static final byte[] ASK_FILE_TEXT = "oshare awdl probe\n".getBytes();

    /**
     * The transfer this {@code /Ask} is about. §6.4 wants a fresh one per transfer, and a
     * fixed one is as honest here as a random one would be: no {@code /Upload} follows yet,
     * and UP-9 only requires the upload's header to match whatever this said.
     */
    private static final String ASK_TRANSFER_ID = "A3F34E01-1234-5678-ABCD-EF0123456789";

    /**
     * The path this mode asks for. A mode may name one directly, which is how the run
     * tells "the receiver will not answer this path" apart from "the receiver will not
     * answer a POST": HTTP-5 has a receiver answer any unknown path with 400, so a 400
     * on {@code /Nope} and a silence on {@code /Discover} would be two different facts
     * about one receiver rather than one fact about the session.
     */
    private static String pathFor(String mode) {
        if (mode.startsWith("/")) {
            return mode;
        }
        if ("nope".equals(mode)) {
            return "/Nope";
        }
        if ("get".equals(mode)) {
            return "/";
        }
        if ("ask".equals(mode) || "send".equals(mode)) {
            return "/Ask";
        }
        return "/Discover";
    }

    /** HTTP-1 wants the Discover connection left open; only the control closes it. */
    private static String connection(Set<String> flags) {
        return flags.contains(SHAPE_CLOSE) ? "close" : "keep-alive";
    }

    /**
     * The request as lines, so the log says what was sent instead of how many bytes.
     *
     * <p>A size alone cannot be compared with anything recorded earlier, and every
     * question left in this investigation is a comparison against an earlier capture.
     */
    private static String headOf(byte[] req) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < req.length; i++) {
            if (req[i] == '\r') {
                continue;
            }
            if (req[i] == '\n') {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                continue;
            }
            if (req[i] < 0x20 || (req[i] & 0xff) > 0x7e) {
                break;
            }
            sb.append((char) (req[i] & 0xff));
        }
        return sb.toString();
    }

    /**
     * Writes the outgoing request where a run asked for it, with a matching {@code .txt}
     * so the bytes are readable without a hex viewer. Silent unless {@code DUMP_REQUEST}
     * names a path: a probe that writes files by default leaves evidence a later run has
     * to explain.
     */
    private static void dump(byte[] request) {
        String path = System.getenv("DUMP_REQUEST");
        if (path == null || path.isEmpty()) return;
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(path);
            try {
                out.write(request);
            } finally {
                out.close();
            }
            out = new java.io.FileOutputStream(path + ".txt");
            try {
                out.write(headOf(request).getBytes("UTF-8"));
                out.write('\n');
                out.write(String.valueOf(request.length).getBytes("UTF-8"));
                out.write('\n');
            } finally {
                out.close();
            }
            System.out.println("awdl-tcp: request dumped to " + path);
        } catch (Exception failed) {
            System.out.println("awdl-tcp: cannot dump the request to " + path + ": " + failed);
        }
    }

    /**
     * A context that trusts every certificate, because TLS-2 says there is nothing to
     * verify: receivers are self-signed and their names are not checked, and identity
     * travels in the record rather than in the certificate chain.
     */
    private static SSLContext clientContext() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        // A null key manager means no client certificate, which is TLS-3's rule for the
        // Discover connection rather than a shortcut.
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx;
    }

    private static void tune(SSLSocket s) {
        String pinned = pinnedVersion();
        if (pinned != null) {
            try {
                s.setEnabledProtocols(new String[]{pinned});
                System.out.println("awdl-tcp: TLS pinned to " + pinned);
                return;
            } catch (IllegalArgumentException unsupported) {
                // Saying so is the point: a pin that silently degrades to the default set
                // turns a controlled run into an uncontrolled one wearing its name.
                System.out.println("awdl-tcp: this stack has no " + pinned
                        + "; falling back to the default set");
            }
        }
        try {
            s.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        } catch (IllegalArgumentException e) {
            // TLS-1 asks for one of the two, not both; an older stack knows only 1.2.
            s.setEnabledProtocols(new String[]{"TLSv1.2"});
        }
    }

    private static String subject(java.security.cert.Certificate[] chain) {
        if (chain == null || chain.length == 0) {
            return "(none)";
        }
        try {
            java.security.cert.X509Certificate c = (java.security.cert.X509Certificate) chain[0];
            return c.getSubjectX500Principal().getName() + " by "
                    + c.getIssuerX500Principal().getName();
        } catch (Exception e) {
            return "(unreadable: " + e + ")";
        }
    }

    private static String hex(byte[] b, int n, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < max; i++) {
            sb.append(String.format("%02x", b[i] & 0xff));
            if ((i & 15) == 15) {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
