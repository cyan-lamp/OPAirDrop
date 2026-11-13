package com.oshare.airdrop.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * The hand-built certificate and the TLS contexts around it.
 *
 * <p>The decisive check is {@link X509Certificate#verify}: it recomputes the
 * SHA256withRSA signature over our DER-encoded TBS, so it fails if any field,
 * length, or ordering in {@link Der}/{@link Identity} is wrong.
 */
public final class IdentityTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Identity identity = Identity.generate("Pixel 9");
        certificateShape(identity);
        signatureVerifies(identity);
        fingerprintMatchesSha256(identity);
        reloadRoundTrip(identity);
        serverHandshakeSucceeds(identity);
        pinnedClientAcceptsMatchingFingerprint(identity);
        pinnedClientRejectsOtherFingerprint(identity);
        System.out.println("IdentityTest OK (" + checks + " checks)");
    }

    private static void certificateShape(Identity identity) throws Exception {
        X509Certificate certificate = identity.x509();
        eq("CN=Pixel 9", certificate.getSubjectX500Principal().getName(), "subject");
        eq(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal(), "self-signed");
        eq("SHA256withRSA", certificate.getSigAlgName(), "signature algorithm");
        eq(3, certificate.getVersion(), "x509 version");
        eq(-1, certificate.getBasicConstraints(), "not a CA");
        check(certificate.getSerialNumber().signum() > 0, "positive serial");
        check(certificate.getNotAfter().getTime() - certificate.getNotBefore().getTime()
            > 300L * 24 * 3600 * 1000, "long validity");

        boolean[] usage = certificate.getKeyUsage();
        check(usage != null, "key usage present");
        check(usage[0], "digitalSignature");
        check(usage[2], "keyEncipherment");

        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        check(names != null && !names.isEmpty(), "subject alt names present");
        String dns = null;
        for (List<?> entry : names) {
            if (((Integer) entry.get(0)) == 2) dns = (String) entry.get(1);
        }
        eq("Pixel-9.local", dns, "dns name");

        check(certificate.getPublicKey().getAlgorithm().equals("RSA"), "rsa public key");
    }

    private static void signatureVerifies(Identity identity) throws Exception {
        identity.x509().verify(identity.publicKey());
        checks++;
        // A tampered byte must invalidate the signature, proving verify() is real.
        byte[] der = identity.certificate();
        byte[] tampered = der.clone();
        tampered[tampered.length / 2] ^= 0x01;
        boolean rejected;
        try {
            Identity.parse(tampered).verify(identity.publicKey());
            rejected = false;
        } catch (Exception expected) {
            rejected = true;
        }
        check(rejected, "tampered certificate rejected");
    }

    private static void fingerprintMatchesSha256(Identity identity) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.certificate());
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02X", value));
        eq(hex.toString(), identity.certHash(), "fingerprint equals sha256 of der");
        eq(64, identity.certHash().length(), "hex length");
        check(identity.certHash().equals(identity.certHash().toUpperCase(java.util.Locale.ROOT)),
            "uppercase hex like apple's h=");
    }

    private static void reloadRoundTrip(Identity identity) throws Exception {
        Identity reloaded = Identity.load(identity.privateKey(), identity.certificate(), "Pixel 9");
        eq(identity.certHash(), reloaded.certHash(), "reload keeps the fingerprint");
        eq(identity.x509(), reloaded.x509(), "reload keeps the certificate");
    }

    private static void serverHandshakeSucceeds(Identity identity) throws Exception {
        try (SSLServerSocket server = listen(identity)) {
            Thread worker = new Thread(() -> acceptAndEcho(server));
            worker.setDaemon(true);
            worker.start();

            try (SSLSocket client = connect(Identity.clientContext(null), server.getLocalPort())) {
                OutputStream out = client.getOutputStream();
                out.write('p');
                out.flush();
                eq((int) 'p', client.getInputStream().read(), "echo over tls");
            }
        }
    }

    private static void pinnedClientAcceptsMatchingFingerprint(Identity identity) throws Exception {
        try (SSLServerSocket server = listen(identity)) {
            Thread worker = new Thread(() -> acceptAndEcho(server));
            worker.setDaemon(true);
            worker.start();

            SSLContext pinned = Identity.clientContext(identity.certHash());
            try (SSLSocket client = connect(pinned, server.getLocalPort())) {
                client.getOutputStream().write('k');
                client.getOutputStream().flush();
                eq((int) 'k', client.getInputStream().read(), "pinned handshake");
            }
        }
    }

    private static void pinnedClientRejectsOtherFingerprint(Identity identity) throws Exception {
        try (SSLServerSocket server = listen(identity)) {
            Thread worker = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.startHandshake();
                } catch (Exception expected) {
                    // the client is the side under test
                }
            });
            worker.setDaemon(true);
            worker.start();

            String wrong = new String(new char[64]).replace('\0', 'A');
            SSLContext pinned = Identity.clientContext(wrong);
            boolean rejected;
            try (SSLSocket client = connect(pinned, server.getLocalPort())) {
                client.getOutputStream().write('x');
                client.getOutputStream().flush();
                client.getInputStream().read();
                rejected = false;
            } catch (SSLException expected) {
                rejected = true;
                check(chainContains(expected, "fingerprint"), "reason mentions the fingerprint");
            }
            check(rejected, "mismatched fingerprint rejected");
        }
    }

    private static SSLServerSocket listen(Identity identity) throws Exception {
        SSLServerSocket server = (SSLServerSocket) identity.serverContext()
            .getServerSocketFactory().createServerSocket(0);
        server.setNeedClientAuth(false);
        return server;
    }

    private static SSLSocket connect(SSLContext context, int port) throws Exception {
        SSLSocket socket = (SSLSocket) context.getSocketFactory()
            .createSocket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(10_000);
        socket.startHandshake();
        return socket;
    }

    private static void acceptAndEcho(SSLServerSocket server) {
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
            // the assertions live on the client side
        }
    }

    private static boolean chainContains(Throwable error, String needle) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String message = cursor.getMessage();
            if (message != null && message.contains(needle)) return true;
        }
        return false;
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("failed: " + what);
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private IdentityTest() {
    }
}
