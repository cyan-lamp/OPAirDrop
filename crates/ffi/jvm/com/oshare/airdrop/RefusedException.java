package com.oshare.airdrop;

import java.io.IOException;

/**
 * The peer answered, and the answer was that it is not taking files.
 *
 * <p>This is the one failure on the sending path that is not a fault. Everything else that
 * comes back from {@link Native#sendWithProgress} means something broke between here and the
 * other phone — a socket, a stream, a file, a body that did not parse — and the honest
 * sentence for it is "the transfer failed". Here nothing broke: the connection held, the
 * TLS handshake completed, {@code /Discover} was answered with a {@code 200}, and the
 * receiver even said what its name is. The only missing thing is one setting on a device
 * the caller cannot reach — AirDrop set to "receiving off" or "contacts only", a locked
 * screen, a sleeping phone.
 *
 * <p>So the message that arrives here is deliberately <em>not</em> shown as-is. It is the
 * fact ("{@code 卷卷薯 is not accepting files}"), and it belongs in the log; what a person
 * needs on screen is the setting to change. Keeping the two apart is the whole reason this
 * class exists rather than a plain {@link IOException} with a telling message: a caller that
 * had to recognise a refusal by reading English would break silently the first time the
 * wording changed.
 *
 * <p>Extends {@link IOException} so that every existing {@code catch} on the sending path
 * still catches it. A refusal that skipped those handlers would leave the send page stuck in
 * "connecting" — the state it was in when the peer said no.
 */
public final class RefusedException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Public because the constructor is called from the native side, by name and signature
     * ({@code <init>(Ljava/lang/String;)V}), with no call site in Java to widen the access
     * for. The class is not meant to be thrown from Java, but a package-private constructor
     * would make that a promise the runtime has to keep rather than a fact of the code.
     */
    public RefusedException(String message) {
        super(message);
    }
}
