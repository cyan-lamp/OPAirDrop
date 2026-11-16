package com.oshare.airdrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * The hand-built certificate, the persistence around it, and the TLS contexts that use it.
 *
 * <p>The decisive check is {@link X509Certificate#verify}: it recomputes the SHA256withRSA
 * signature over our DER-encoded TBS, so it fails if any field, length or ordering in
 * {@link Der}/{@link Identity} is wrong. Nothing else in the JVM would notice a malformed
 * certificate — a hand-built X.509 that is subtly wrong still parses.
 *
 * <p>The rest is the part the phone would find out the hard way: that the fingerprint on disk
 * survives a restart, that a truncated file becomes a new identity instead of a receiver that
 * refuses to start, and that a pinned client really rejects a certificate it was not told to
 * accept.
 *
 * <p>Run: {@code java com.oshare.airdrop.IdentityCheck <scratch directory>}
 */
public final class IdentityCheck {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: IdentityCheck <scratch directory>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("cannot use " + dir);
            System.exit(2);
        }

        // One identity for the certificate checks, because generating it costs a second and
        // every assertion below is about the same certificate.
        Identity identity = Identity.generate("Pixel 9");
        certificateShape(identity);
        signatureVerifies(identity);
        fingerprintMatchesSha256(identity);
        senderIdIsDerivedFromTheCertificate(identity);
        reloadKeepsTheCertificate(identity);
        coherentRejectsAForeignKey(identity);
        serverHandshakeSucceeds(identity);
        pinnedClientAcceptsMatchingFingerprint(identity);
        pinnedClientRejectsOtherFingerprint(identity);
        theRefusalArrivesAsOneType(identity);

        storeGeneratesAndKeepsItsIdentity(dir);
        storeReplacesATruncatedCertificate(dir);
        storeRejectsAKeyFromAnotherIdentity(dir);
        storeKeepsTheKeyToItsOwner(dir);

        System.out.println();
        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " of " + checks);
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println("ok, " + checks + " checks");
    }

    // ------------------------------------------------------------- the certificate

    private static void certificateShape(Identity identity) throws Exception {
        X509Certificate certificate = identity.x509();
        check("subject is CN=<device name>",
            "CN=Pixel 9".equals(certificate.getSubjectX500Principal().getName()),
            certificate.getSubjectX500Principal().getName());
        check("issuer equals subject (self-signed)",
            certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal()),
            certificate.getIssuerX500Principal().getName());
        check("signature algorithm", "SHA256withRSA".equals(certificate.getSigAlgName()),
            certificate.getSigAlgName());
        check("x509 version 3", certificate.getVersion() == 3, "" + certificate.getVersion());
        check("not a CA", certificate.getBasicConstraints() == -1,
            "" + certificate.getBasicConstraints());
        check("positive serial", certificate.getSerialNumber().signum() > 0,
            certificate.getSerialNumber().toString());
        check("validity is years, not days",
            certificate.getNotAfter().getTime() - certificate.getNotBefore().getTime()
                > 300L * 24 * 3600 * 1000,
            "" + (certificate.getNotAfter().getTime() - certificate.getNotBefore().getTime()));

        boolean[] usage = certificate.getKeyUsage();
        check("key usage present", usage != null, "none");
        check("digitalSignature", usage != null && usage[0], "unset");
        check("keyEncipherment", usage != null && usage[2], "unset");

        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        check("subject alt names present", names != null && !names.isEmpty(), "none");
        String dns = null;
        if (names != null) {
            for (List<?> entry : names) {
                if (((Integer) entry.get(0)) == 2) {
                    dns = (String) entry.get(1);
                }
            }
        }
        check("dns name is the device name with dots made safe",
            "Pixel-9.local".equals(dns), "" + dns);
        check("rsa public key", "RSA".equals(certificate.getPublicKey().getAlgorithm()),
            certificate.getPublicKey().getAlgorithm());

        // A name that cannot be encoded as IA5 still has to leave a well-formed extension: a
        // Chinese device name is the common case on this phone, not an edge case.
        Identity chinese = Identity.generate("一加 13");
        Collection<List<?>> alt = chinese.x509().getSubjectAlternativeNames();
        String label = null;
        if (alt != null) {
            for (List<?> entry : alt) {
                if (((Integer) entry.get(0)) == 2) {
                    label = (String) entry.get(1);
                }
            }
        }
        check("a non-ASCII device name still encodes a dNSName",
            label != null && label.endsWith(".local"), "" + label);
    }

    private static void signatureVerifies(Identity identity) throws Exception {
        boolean verified;
        try {
            identity.x509().verify(identity.publicKey());
            verified = true;
        } catch (Exception refused) {
            verified = false;
        }
        check("signature verifies against our own public key", verified, "verify threw");

        // A tampered byte must invalidate the signature, proving verify() was doing work and
        // not being skipped by a lenient provider.
        byte[] tampered = identity.certificate();
        tampered[tampered.length / 2] ^= 0x01;
        boolean rejected;
        try {
            Identity.parse(tampered).verify(identity.publicKey());
            rejected = false;
        } catch (Exception expected) {
            rejected = true;
        }
        check("a tampered certificate is rejected", rejected, "verify accepted a flipped bit");
    }

    private static void fingerprintMatchesSha256(Identity identity) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.certificate());
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02X", value));
        }
        check("fingerprint is the sha256 of the der", hex.toString().equals(identity.certHash()),
            identity.certHash());
        check("hex length", identity.certHash().length() == 64, "" + identity.certHash().length());
        check("uppercase hex, like apple's h=",
            identity.certHash().equals(identity.certHash().toUpperCase(Locale.ROOT)),
            identity.certHash());
    }

    // ---------------------------------------------------------------- the sender id

    /**
     * The one string a receiver files this device under.
     *
     * <p>It is neither random nor per-transfer, and both halves of that matter: two offers from
     * the same phone must arrive as one device, and a phone that restarted must arrive as the
     * device it was before. So the assertions are about the value staying put while everything
     * around it moves — a second {@link Identity} over the same certificate, another device
     * name, a fresh process — and about its exact form, because which string the peer groups by
     * is not something a later refactor may quietly change.
     */
    private static void senderIdIsDerivedFromTheCertificate(Identity identity) throws Exception {
        String senderId = identity.senderId();
        check("the sender id is a type 3 uuid",
            senderId.matches(
                "[0-9A-F]{8}-[0-9A-F]{4}-3[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}"),
            senderId);

        // The derivation spelled out again instead of called: this line is the agreement with
        // the reference implementation, and a check that reused the production expression would
        // agree with whatever prefix it was handed.
        String expected = java.util.UUID.nameUUIDFromBytes(
            ("oshare-airdrop:" + identity.certHash())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString().toUpperCase(Locale.ROOT);
        check("the sender id is the name-based uuid of the prefix and the fingerprint",
            expected.equals(senderId), expected);
        check("the sender id is not the fingerprint", !senderId.equals(identity.certHash()),
            senderId);

        Identity reloaded = Identity.load(identity.privateKey(), identity.certificate(), "Pixel 9");
        check("the same certificate gives the same sender id",
            senderId.equals(reloaded.senderId()), reloaded.senderId());

        // A rename must not read as a new device: the peer keys its grouping on this string, and
        // what the person typed is a label, not an identity.
        Identity renamed = Identity.load(identity.privateKey(), identity.certificate(), "另一个名字");
        check("a renamed device keeps its sender id",
            senderId.equals(renamed.senderId()), renamed.senderId());

        Identity other = Identity.generate("Pixel 9");
        check("a different device is a different sender id",
            !senderId.equals(other.senderId()), other.senderId());
    }

    private static void reloadKeepsTheCertificate(Identity identity) throws Exception {
        Identity reloaded = Identity.load(identity.privateKey(), identity.certificate(), "Pixel 9");
        check("reload keeps the fingerprint",
            identity.certHash().equals(reloaded.certHash()), reloaded.certHash());
        check("reload keeps the certificate",
            identity.x509().equals(reloaded.x509()), "different x509");
        check("reload keeps the name", "Pixel 9".equals(reloaded.computerName()),
            reloaded.computerName());
        check("a reloaded pair is coherent", reloaded.coherent(), "signature did not verify");
    }

    private static void coherentRejectsAForeignKey(Identity identity) throws Exception {
        Identity other = Identity.generate("Pixel 9", System.currentTimeMillis() - 1000,
            System.currentTimeMillis() + 1000);
        // The certificate of one identity with the key of another: both parse, the pair builds,
        // and only a signature tells them apart.
        Identity mismatched = Identity.load(other.privateKey(), identity.certificate(), "Pixel 9");
        check("a key from another identity is not coherent", !mismatched.coherent(),
            "the pair looked valid");
        check("the original is still coherent", identity.coherent(), "the original failed");
    }

    // ------------------------------------------------------------------ the TLS

    private static void serverHandshakeSucceeds(Identity identity) throws Exception {
        try (SSLServerSocket server = listen(identity)) {
            startEcho(server);
            try (SSLSocket client = connect(Identity.clientContext(null), server.getLocalPort())) {
                OutputStream out = client.getOutputStream();
                out.write('p');
                out.flush();
                check("echo over tls", client.getInputStream().read() == 'p', "nothing came back");
            }
        }
    }

    private static void pinnedClientAcceptsMatchingFingerprint(Identity identity) throws Exception {
        try (SSLServerSocket server = listen(identity)) {
            startEcho(server);
            SSLContext pinned = Identity.clientContext(identity.certHash());
            try (SSLSocket client = connect(pinned, server.getLocalPort())) {
                client.getOutputStream().write('k');
                client.getOutputStream().flush();
                check("a pinned client accepts the matching fingerprint",
                    client.getInputStream().read() == 'k', "nothing came back");
            }
        }
    }

    private static void pinnedClientRejectsOtherFingerprint(Identity identity) throws Exception {
        try (SSLServerSocket server = listen(identity)) {
            startEcho(server);
            // All A's: a well-formed hash of nothing in particular, which is what a stale TXT
            // record looks like to this code.
            String wrong = new String(new char[64]).replace('\0', 'A');
            SSLContext pinned = Identity.clientContext(wrong);
            boolean rejected;
            try (SSLSocket client = connect(pinned, server.getLocalPort())) {
                client.getOutputStream().write('x');
                client.getOutputStream().flush();
                int value = client.getInputStream().read();
                rejected = value < 0;
                if (!rejected) {
                    // A byte came back: the pin did not hold, and the check below says so.
                    System.out.println("    (the pinned client read " + value + ")");
                }
            } catch (SSLException expected) {
                rejected = true;
                check("the rejection names the fingerprint", chainContains(expected, "fingerprint"),
                    String.valueOf(expected.getMessage()));
            }
            check("a mismatched fingerprint is rejected", rejected, "the handshake was accepted");
        }
    }

    /**
     * The sending path decides what to say by type, and the type has to survive the JSSE.
     *
     * <p>This is the half of pinning that {@link #pinnedClientRejectsOtherFingerprint} does not
     * cover: it is not enough for the handshake to fail, the caller has to be able to tell a
     * swapped certificate from a closed socket. The {@code TrustManager} raises a
     * {@link PeerMismatch.Pin}, and by the time the send page sees it the JSSE has wrapped it —
     * how many times differs between this JDK and Android's Conscrypt, which is why
     * {@link PeerMismatch#of} walks the chain instead of checking the top. Running the real
     * handshake here is what makes that claim a fact rather than a reading of the source.
     */
    private static void theRefusalArrivesAsOneType(Identity identity) throws Exception {
        try (SSLServerSocket server = listen(identity)) {
            startEcho(server);
            String wrong = new String(new char[64]).replace('\0', 'A');
            Exception refusal = null;
            try (SSLSocket client = connect(Identity.clientContext(wrong), server.getLocalPort())) {
                // Unreachable when the pin holds, which is the point: the handshake in `connect`
                // is where the refusal has to arrive.
                client.getOutputStream().write('x');
            } catch (Exception expected) {
                refusal = expected;
            }
            check("a pinned mismatch does refuse the handshake", refusal != null,
                "the handshake completed with the wrong fingerprint pinned");
            PeerMismatch mismatch = PeerMismatch.of(refusal);
            check("the refusal is recognised as a pin mismatch", mismatch != null,
                "nothing in the chain said so: " + refusal);
            if (mismatch == null) {
                return;
            }
            String sentence = mismatch.getMessage();
            check("the sentence says what the peer advertised",
                sentence.contains(wrong.substring(0, 12)), sentence);
            check("the sentence says what the peer presented",
                sentence.contains(identity.certHash().substring(0, 12)), sentence);
            check("the sentence fits a tile and a notification line", sentence.length() <= 96,
                sentence.length() + " characters: " + sentence);
            check("a pin mismatch is an IOException like every other send failure",
                mismatch instanceof IOException, mismatch.getClass().getName());
            // The walk has to answer "no" for an ordinary failure, or every broken socket would
            // be reported as a swapped certificate.
            check("an unrelated failure is not read as a pin mismatch",
                PeerMismatch.of(new IOException("socket closed")) == null,
                "an IOException was taken for a mismatch");
        }
    }

    // ------------------------------------------------------------ the stored pair

    private static void storeGeneratesAndKeepsItsIdentity(File dir) throws Exception {
        IdentityStore store = new IdentityStore(new File(dir, "keep"));
        check("nothing stored yet", !store.exists(), "a file was already there");
        check("loading what is not there answers null", store.load("Pixel 9") == null,
            "something loaded");

        Identity first = store.loadOrCreate("Pixel 9");
        check("creating writes both files", store.exists(), "one file is missing");

        Identity second = store.loadOrCreate("Pixel 9");
        check("a second load reuses the stored identity",
            first.certHash().equals(second.certHash()), second.certHash());
        check("the reloaded identity is coherent", second.coherent(), "signature did not verify");
        check("the fingerprint survives a restart", first.certHash().equals(second.certHash()),
            first.certHash() + " vs " + second.certHash());
    }

    private static void storeReplacesATruncatedCertificate(File dir) throws Exception {
        File where = new File(dir, "truncated");
        IdentityStore store = new IdentityStore(where);
        Identity first = store.loadOrCreate("Pixel 9");

        File certificate = new File(where, "identity.der");
        byte[] whole = Files.readAllBytes(certificate.toPath());
        byte[] half = new byte[whole.length / 2];
        System.arraycopy(whole, 0, half, 0, half.length);
        try (FileOutputStream out = new FileOutputStream(certificate)) {
            out.write(half);
        }

        check("a truncated certificate does not load", store.load("Pixel 9") == null,
            "half a certificate parsed");
        Identity replaced = store.loadOrCreate("Pixel 9");
        check("a truncated certificate is replaced, not fatal",
            !first.certHash().equals(replaced.certHash()), "the same identity came back");
        check("the replacement is coherent", replaced.coherent(), "signature did not verify");
        check("the replacement is on disk", store.exists(), "one file is missing");
    }

    private static void storeRejectsAKeyFromAnotherIdentity(File dir) throws Exception {
        File where = new File(dir, "mismatched");
        IdentityStore store = new IdentityStore(where);
        Identity ours = store.loadOrCreate("Pixel 9");
        Identity foreign = Identity.generate("Pixel 9");

        File key = new File(where, "identity.key");
        try (FileOutputStream out = new FileOutputStream(key)) {
            out.write(foreign.privateKey().getEncoded());
        }

        check("a key that does not match the certificate is treated as absent",
            store.load("Pixel 9") == null, "the mismatched pair loaded");
        Identity fresh = store.loadOrCreate("Pixel 9");
        check("and is replaced", fresh.coherent() && !fresh.certHash().equals(ours.certHash()),
            fresh.certHash());
    }

    /** 0700 on the directory and 0600 on the key, where the filesystem honours the bits. */
    private static void storeKeepsTheKeyToItsOwner(File dir) throws Exception {
        File where = new File(dir, "permissions");
        IdentityStore store = new IdentityStore(where);
        store.loadOrCreate("Pixel 9");
        File key = new File(where, "identity.key");

        // Windows cannot express these bits at all, so the assertion is only made where they
        // mean something. Saying "ok" on Windows would be the kind of green that teaches
        // nothing; the phone is a Linux kernel and does honour them.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            System.out.println("  skip 0600 on the key (windows cannot express it)");
            return;
        }
        check("the directory is 0700", (Files.getPosixFilePermissions(where.toPath()).size()) <= 3,
            Files.getPosixFilePermissions(where.toPath()).toString());
        String bits = Files.getPosixFilePermissions(key.toPath()).toString();
        check("the key is 0600", !bits.contains("GROUP") && !bits.contains("OTHERS"), bits);
    }

    // ----------------------------------------------------------------- plumbing

    private static SSLServerSocket listen(Identity identity) throws Exception {
        SSLServerSocket server = (SSLServerSocket) identity.serverContext()
            .getServerSocketFactory().createServerSocket(0);
        server.setNeedClientAuth(false);
        return server;
    }

    private static void startEcho(SSLServerSocket server) {
        Thread worker = new Thread(() -> {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                socket.startHandshake();
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                int value = in.read();
                if (value >= 0) {
                    out.write(value);
                    out.flush();
                }
            } catch (Exception ignored) {
                // The assertions live on the client side; the server ends either way.
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private static SSLSocket connect(SSLContext context, int port) throws Exception {
        SSLSocket socket = (SSLSocket) context.getSocketFactory()
            .createSocket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(10_000);
        socket.startHandshake();
        return socket;
    }

    private static boolean chainContains(Throwable error, String needle) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String message = cursor.getMessage();
            if (message != null && message.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : "  <- " + detail));
        if (!ok) {
            failures.add(what + "  <- " + detail);
        }
    }

    private IdentityCheck() {
    }
}
