package com.oshare.airdrop.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The decisions {@link AirDropServer} does not make: what to answer, where files go,
 * and who to tell about it.
 *
 * <p>Answers are the two plists Apple's client needs — {@code /Discover} returns the
 * media capabilities plus our names, {@code /Ask} returns the name it shows while the
 * transfer runs — and the archive is handed to {@link Inbox}, which is the only code
 * allowed to touch the disk.
 *
 * <p>Uploads are serialised: connections run on their own threads, but one inbox means
 * one writer, and the byte budget in {@link Inbox} is not safe to share. A second
 * transfer waits instead of racing, which is also the order a person expects.
 */
public final class ReceiveHandler implements AirDropServer.Handler {
    /** Where the transfer is in its life cycle, as far as the UI cares. */
    public interface Listener {
        /** An {@code /Ask} arrived: the sender is offering these items right now. */
        void onAsk(AirDropProtocol.Ask ask);

        /** An {@code /Upload} finished; {@code files} are the members just written. */
        void onReceived(List<File> files);
    }

    private final String computerName;
    private final String modelName;
    private final Inbox inbox;
    private final Object uploadLock = new Object();

    private volatile Listener listener;
    private volatile AirDropProtocol.Ask lastAsk;

    public ReceiveHandler(String computerName, String modelName, Inbox inbox) {
        if (computerName == null || computerName.isEmpty()) throw new IllegalArgumentException("empty name");
        if (inbox == null) throw new IllegalArgumentException("inbox == null");
        this.computerName = computerName;
        this.modelName = modelName == null || modelName.isEmpty() ? "Android" : modelName;
        this.inbox = inbox;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Inbox inbox() {
        return inbox;
    }

    /** The most recent offer, whether or not it was accepted; handy for a dialog. */
    public AirDropProtocol.Ask lastAsk() {
        return lastAsk;
    }

    @Override
    public byte[] onDiscover(byte[] request) {
        // The request may carry SenderRecordData; nothing in it changes the answer,
        // because our capabilities are fixed by what this module can actually do.
        return AirDropProtocol.discoverAnswer(computerName, modelName);
    }

    /**
     * Files the name the caller gave for itself, so a list of devices can show it.
     *
     * <p>Logged only when the answer changes. A phone with a share sheet open posts
     * {@code /Discover} every few seconds for as long as the sheet is up, and forty
     * identical lines would bury the four that said something happened.
     */
    @Override
    public void onPeerSeen(String address, String name) {
        if (name == null || name.isEmpty()) return;
        String had = PeerNames.of(address);
        if (name.equals(had)) return;
        PeerNames.note(address, name);
        // What actually got filed: a name that the store rejected as a rotated hash is
        // the difference between "the phone told us nothing" and "the phone told us its
        // hash", and only the trace can tell those apart later.
        String kept = PeerNames.of(address);
        if (kept == null) {
            // The store threw the value away, and until now it did so without a word,
            // which made "the phone sent a hash instead of a name" indistinguishable
            // from "the phone sent no body". Named here for exactly that reason.
            Trace.write("! " + address + " offered \"" + name
                + "\" and the store rejected it as a rotated hash");
            return;
        }
        if (kept.equals(had)) return;
        Trace.write("* " + address + " calls itself \"" + kept + "\" in its /Discover");
    }

    @Override
    public byte[] askAnswer() {
        // Everything the answer says -- this device's name, its model, and that contact
        // exchange is not supported -- is known before the offer arrives, so the answer
        // does not have to wait for a body that this link has been measured to stall.
        return AirDropProtocol.askAnswer(computerName, modelName);
    }

    @Override
    public byte[] onAsk(AirDropProtocol.Ask ask) {
        lastAsk = ask;
        // What the sender actually offered, not just that something arrived. The file
        // names and their declared sizes are the one place a mismatch between "what the
        // other user picked" and "what the archive turned out to hold" becomes visible
        // before the upload has even started, and the icon flag is worth a word because a
        // sender that attaches one sends a materially larger /Ask that a strict receiver
        // may refuse.
        StringBuilder offered = new StringBuilder();
        for (AirDropProtocol.Item item : ask.files) {
            if (offered.length() > 0) offered.append(", ");
            offered.append(item.bomPath.isEmpty() ? item.name : item.bomPath);
            offered.append(" (").append(item.type);
            if (item.size >= 0L) offered.append(' ').append(item.size).append(" B");
            offered.append(')');
        }
        Trace.write("* ask from \"" + ask.senderName + "\" model=\"" + ask.senderModel
            + "\" id=" + ask.senderId + " bundle=" + ask.bundleId
            + " files=" + ask.files.size() + " links=" + ask.links.size()
            + " icon=" + ask.hasIcon + " files=[" + offered + "]");
        Listener current = listener;
        if (current != null) {
            try {
                current.onAsk(ask);
            } catch (RuntimeException ignored) {
                // A listener that throws must not turn an offer into an error answer:
                // the sender is waiting, and the transfer is still valid.
            }
        }
        // Unconditional acceptance, and the trace says so out loud because it is a policy
        // rather than a mechanism. Nothing on this side asks the user; whatever the peer
        // offered, it gets a 200 and the upload follows. A sender's own UI reports the
        // transfer as accepted the moment this answer lands, so a condition that refused
        // here would have to be decided in microseconds and would look to the other user
        // like a device that was never there.
        Trace.write("* ask accepted unconditionally (no prompt on this side)");
        return AirDropProtocol.askAnswer(computerName, modelName);
    }

    @Override
    public void onUpload(InputStream body, String contentType) throws IOException {
        List<File> written;
        long started = System.nanoTime();
        long bytes;
        synchronized (uploadLock) {
            int before = inbox.files().size();
            inbox.extract(body);
            List<File> all = inbox.files();
            written = Collections.unmodifiableList(new ArrayList<>(all.subList(before, all.size())));
            bytes = inbox.bytes();
        }
        // The archive's own length against the sum of what landed on disk. They differ
        // whenever the sender's cpio included something this receiver drops -- a symlink,
        // a name that escapes the directory, a duplicate -- and a size that is short by
        // exactly one member reads as a truncated file unless both numbers are printed.
        long onDisk = 0L;
        for (File file : written) onDisk += file.length();
        long micros = (System.nanoTime() - started) / 1000L;
        Trace.write("* inbox wrote " + written.size() + " entr" + (written.size() == 1 ? "y" : "ies")
            + ", " + onDisk + " B on disk, " + bytes + " B through the budget, in "
            + (micros < 1000L ? micros + " us" : (micros / 1000L) + " ms")
            + " as " + contentType + " -> " + inbox.directory());
        Listener current = listener;
        if (current != null && !written.isEmpty()) {
            try {
                current.onReceived(written);
            } catch (RuntimeException ignored) {
                // Files are already on disk; a broken UI callback cannot undo that.
            }
        }
    }
}
