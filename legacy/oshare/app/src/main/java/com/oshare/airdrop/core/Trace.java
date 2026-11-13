package com.oshare.airdrop.core;

import java.nio.charset.StandardCharsets;

/**
 * A pluggable line sink for the protocol code, so both conversations can be followed
 * byte by byte from {@code logcat} without dragging Android into a package that is
 * otherwise pure Java.
 *
 * <p>{@link HttpCall} and {@link AirDropClient} have no other way to report what they
 * sent and what came back. When a peer accepts the TLS session, acknowledges every
 * segment, and still never shows its user a prompt, the only question worth asking is
 * "what exactly did it answer to {@code /Discover}?" — and the answer is invisible from
 * the outside, because the answer is an encrypted plist.
 *
 * <p>The sinks are a list rather than a single field because one process now runs two
 * conversations that never see each other. A send screen installs a sink while it is
 * open, and the receiver service installs one when the app is merely sitting there. With
 * one slot the second install silences the first, and the loser is whichever of the two
 * the user happened to use last — which is precisely the case worth reading when a
 * transfer fails in the middle of the other one.
 *
 * <p>With no sink installed every call here returns immediately, which is what keeps this
 * class from costing anything in the receive path or in an offline rehearsal.
 */
public final class Trace {
    /** Where lines go. Implementations must not throw and must not block. */
    public interface Sink {
        void line(String text);
    }

    /** How much of a body is worth putting in a log line before it stops being readable. */
    private static final int PREVIEW = 96;

    /** Copy-on-write: writes happen on connection threads, installs on the UI thread. */
    private static final java.util.List<Sink> sinks = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Trace() {
    }

    /** Adds a destination. Adding the same sink twice is a no-op, not a doubled line. */
    public static void addSink(Sink destination) {
        if (destination == null) return;
        if (sinks.contains(destination)) return;
        sinks.add(destination);
    }

    /** Removes a destination; a sink that was never added is ignored. */
    public static void removeSink(Sink destination) {
        if (destination == null) return;
        sinks.remove(destination);
    }

    /** Drops every destination. Only a rehearsal on the JVM has a use for this. */
    public static void clearSinks() {
        sinks.clear();
    }

    public static boolean active() {
        return !sinks.isEmpty();
    }

    public static void write(String text) {
        if (sinks.isEmpty()) return;
        for (Sink destination : sinks) {
            try {
                destination.line(text);
            } catch (RuntimeException ignored) {
                // A broken sink must not break a transfer, and one broken sink must not
                // cost the other one its line either -- hence the try inside the loop.
            }
        }
    }

    /**
     * A body as hex with an ASCII gutter: plists start {@code bplist00}, and a stray
     * TLS alert or an HTTP error page is unrecognisable in hex alone but obvious as
     * text, so both are printed.
     */
    public static String dump(byte[] body) {
        if (body == null) return "null";
        if (body.length == 0) return "0 B";
        int shown = Math.min(body.length, PREVIEW);
        StringBuilder hex = new StringBuilder(shown * 3);
        StringBuilder ascii = new StringBuilder(shown);
        for (int i = 0; i < shown; i++) {
            int value = body[i] & 0xff;
            if (i > 0) hex.append(' ');
            hex.append(Character.forDigit(value >> 4, 16)).append(Character.forDigit(value & 0xf, 16));
            ascii.append(value >= 0x20 && value < 0x7f ? (char) value : '.');
        }
        if (body.length > shown) hex.append(" …");
        return body.length + " B [" + ascii + "] " + hex;
    }

    /** The printable part of a head, newlines folded so one request stays one log line. */
    public static String flatten(byte[] head) {
        if (head == null) return "";
        String text = new String(head, StandardCharsets.ISO_8859_1);
        return text.replace("\r\n", " | ").trim();
    }
}
