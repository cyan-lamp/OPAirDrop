package com.oshare.airdrop;

import java.io.IOException;
import java.security.cert.CertificateException;

/**
 * The peer presented a certificate that is not the one it advertised.
 *
 * <p># Why this is a type and not a message
 *
 * <p>{@link Identity#clientContext(String)} pins the peer's certificate: the sender reads
 * the fingerprint out of the {@code h} key of the TXT record of the device it found, and the
 * handshake is only
 * allowed to finish if the certificate on the wire hashes to that same value. When it does
 * not, the attempt has to stop — and the person in front of the phone needs a sentence that
 * says what happened, not {@code javax.net.ssl.SSLHandshakeException: java.security.cert
 * .CertificateException: fingerprint mismatch: advertised AAAA…, presented BBBB…}.
 *
 * <p>The mismatch is raised inside the handshake as {@link Pin}, which is a
 * {@link CertificateException} because that is the only thing a {@code TrustManager} may
 * throw. From there the JSSE wraps it — twice, in a {@code ValidatorException} and then in an
 * {@link javax.net.ssl.SSLHandshakeException} — and each JDK and each Android release wraps
 * it a slightly different number of times. So the exception cannot be recognised by type at
 * the catch site; {@link #of(Throwable)} walks the cause chain instead, and the caller gets
 * back the same type on every platform.
 *
 * <p>Extends {@link IOException} for the same reason {@link RefusedException} does: the
 * sending path declares {@code throws IOException} and every existing handler on it has to
 * keep catching this. What the type buys is the one decision those handlers make differently
 * for it — a refused or mismatched peer is not a fault in the transfer, it is a reason not to
 * start one.
 *
 * <p># What this check does and does not prove
 *
 * <p>It binds the device that answered the mDNS query to the device that answered the
 * handshake: the same responder that published {@code h} must also hold the private key
 * for that certificate. An attacker who can rewrite the TXT record can publish their own hash
 * and defeat it — mDNS is unauthenticated, and nothing on this path can fix that. What it does
 * stop is the cheaper attack: answering the query with a hash of the real phone and then
 * completing the TLS handshake with a certificate of your own.
 */
final class PeerMismatch extends IOException {
    private static final long serialVersionUID = 1L;

    /** How much of each hash goes into the sentence. Enough to tell two apart, short enough
     * to read on a phone; the full pair stays in the cause and lands in the log. */
    private static final int SHOWN = 12;

    private PeerMismatch(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns the mismatch behind {@code failure}, or {@code null} if there is none.
     *
     * <p>Walks the chain rather than checking the top: the {@link Pin} is always at least two
     * levels down by the time a caller sees it.
     */
    static PeerMismatch of(Throwable failure) {
        Throwable at = failure;
        // A hand-written cap rather than a "seen" set: cause chains are short, and a chain that
        // loops is a bug in someone else's exception, not something to grow a HashSet for.
        for (int depth = 0; at != null && depth < 16; depth++) {
            if (at instanceof Pin) {
                Pin pin = (Pin) at;
                return new PeerMismatch(sentence(pin.expected, pin.presented), failure);
            }
            if (at.getCause() == at) {
                break;
            }
            at = at.getCause();
        }
        return null;
    }

    private static String sentence(String advertised, String presented) {
        return "对方出示的证书与它广播的指纹不符（广播 " + abbreviated(advertised)
                + "…，实际 " + abbreviated(presented) + "…）";
    }

    private static String abbreviated(String hash) {
        if (hash == null) {
            return "?";
        }
        return hash.length() <= SHOWN ? hash : hash.substring(0, SHOWN);
    }

    /**
     * Raised by {@link Identity}'s trust manager when the hashes disagree.
     *
     * <p>A {@link CertificateException} because that is the contract of
     * {@code checkServerTrusted}; the two hashes are kept as fields so the sentence above can
     * be built once, at the place that knows how to phrase it, rather than parsed back out of
     * a message.
     */
    static final class Pin extends CertificateException {
        private static final long serialVersionUID = 1L;
        final String expected;
        final String presented;

        Pin(String expected, String presented) {
            // The word "fingerprint" is load-bearing: it is what the desktop check looks for
            // when it asserts that a rejected handshake says why.
            super("fingerprint mismatch: advertised " + expected + ", presented " + presented);
            this.expected = expected;
            this.presented = presented;
        }
    }
}
