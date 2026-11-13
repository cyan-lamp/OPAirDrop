package com.oshare.airdrop.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a peer calls itself, keyed by the device rather than by its address.
 *
 * <p>An AirDrop answer carries no device name. The instance name in {@code _airdrop._tcp} is
 * a rotated hash -- one iPhone published {@code a1e9ac0eb2e6}, then {@code 4c8e0b0f3870},
 * then {@code f8247b7ae195} from the same link-local address inside one afternoon -- and
 * its hostname is a fresh UUID on every announcement, so neither can be shown to a person.
 * The name a person recognises travels in exactly two places, both plists:
 * {@code ReceiverComputerName} in the answer to our {@code /Discover}, and
 * {@code SenderComputerName} in the {@code /Discover} the peer sends us.
 *
 * <p>Both of those connections are transient and neither belongs to the screen that lists
 * devices. The inbound one is answered by the receiver endpoint, which lives in the runtime
 * service; the outbound one is read by the client on a worker thread; the list is drawn by
 * an activity that may be created after either. So the name is parked here and the list
 * asks. What it is parked under has to survive two kinds of rotation: the instance name,
 * which changes every few minutes, and the address, which changes with the interface.
 *
 * <p>The second one is why this class does not key on the address string. One iPhone in
 * this project was published over mDNS as {@code fe80::c45b:f91f:fea2:c4b6} and opened its
 * {@code /Discover} connections from {@code fe80::c45b:f9ff:fea2:c4b6} -- same device, same
 * MAC, two addresses, because the middle of an interface identifier is either the EUI-64
 * marker {@code ff:fe} or a randomised pair depending on which interface spoke. Keying on
 * the string meant the name was filed under one of them and looked up under the other,
 * which is the same as not having the name at all. Keying on {@link #key(String)} -- the
 * MAC wrapped around that middle -- matches both.
 *
 * <p>Process-wide on purpose, and it is the only state in this package that is. It is not a
 * cache of one screen's work, it is what the device has learned about its neighbours, and
 * the send screen and the receive endpoint are two halves of one process.
 *
 * <p>And it outlives the process, because the way to learn a name on this link is to be
 * told it, and being told it is exactly what does not happen on demand. A peer names
 * itself in two places, both transient, both at the peer's choosing; a session that
 * catches one of them is luck, and throwing the result away when the activity is
 * recreated means the next session starts from "some device" again. What is stored is
 * keyed by {@link #key(String)}, which is the MAC and therefore survives both the address
 * and the instance name changing -- so one {@code /Ask} from a phone, ever, is enough for
 * every later session to show that phone by name.
 */
public final class PeerNames {
    /** Where the learned names are kept, under {@code name:<device key>}. */
    private static final String STORE = "peer-names";
    private static final Map<String, String> BY_DEVICE = new ConcurrentHashMap<>();
    /** {@code null} until {@link #attach(Context)}; the map works without it. */
    private static volatile SharedPreferences store;

    private PeerNames() {
    }

    /**
     * Opens the on-disk store and loads what earlier sessions learned.
     *
     * <p>Idempotent, and safe to call from any number of entry points: an activity and the
     * receiver service are started independently and either can be the first thing that
     * needs a name. Loading is last-write-wins against whatever the live map already
     * holds, because a process that ran long enough to learn a name has a fresher one than
     * the disk does.
     */
    public static void attach(Context context) {
        if (context == null || store != null) return;
        SharedPreferences opened;
        try {
            opened = context.getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE);
        } catch (RuntimeException unavailable) {
            // A name is a label on a row; a store that will not open costs the row its
            // title in the next session and nothing at all in this one.
            return;
        }
        try {
            for (Map.Entry<String, ?> entry : opened.getAll().entrySet()) {
                if (entry.getValue() instanceof String && !((String) entry.getValue()).isEmpty()) {
                    BY_DEVICE.putIfAbsent(entry.getKey(), (String) entry.getValue());
                }
            }
        } catch (RuntimeException unreadable) {
            // Corrupt store, or one written by an older shape. Start empty.
        }
        store = opened;
    }

    /**
     * Records that the device at {@code address} says it is called {@code name}.
     *
     * <p>Blank names and names that are still the rotated hash are dropped: showing
     * {@code f8247b7ae195} as if a person had typed it is worse than showing nothing,
     * because it looks like a device that has no name rather than one we have not asked.
     */
    public static void note(String address, String name) {
        if (address == null || name == null) return;
        String clean = name.trim();
        if (clean.isEmpty() || isHash(clean)) return;
        String device = key(address);
        BY_DEVICE.put(device, clean);
        SharedPreferences into = store;
        if (into == null) return;
        try {
            // Applied, not committed: this runs on the thread holding a transfer or
            // answering a probe, and losing the write to a kill is a name that has to be
            // learned again, not a transfer that fails.
            into.edit().putString(device, clean).apply();
        } catch (RuntimeException ignored) {
            // Same reasoning as above.
        }
    }

    /** The name learned for the device that answers at {@code address}, or {@code null}. */
    public static String of(String address) {
        if (address == null) return null;
        return BY_DEVICE.get(key(address));
    }

    /** Every name learned so far: device key to name. Read-only, for a trace or a screen. */
    public static Map<String, String> all() {
        return Collections.unmodifiableMap(BY_DEVICE);
    }

    /**
     * The device behind an address, as opposed to the address itself.
     *
     * <p>A link-local address is {@code fe80::} followed by an interface identifier, and
     * only the middle pair of that identifier is unstable: {@code ff:fe} on an address
     * derived from the MAC, something else on one that was randomised. The first three and
     * last three bytes are the MAC and do not change, so those six bytes are the identity.
     *
     * <p>Anything this cannot parse -- an IPv4 literal, a hostname, an empty string -- falls
     * back to the normalised text, which keeps the old exact-match behaviour for callers
     * that were never talking about link-local devices.
     */
    private static String key(String address) {
        String bare = bare(address);
        byte[] raw = ipv6(bare);
        if (raw == null) return bare;
        return hex(raw, 8, 3) + ':' + hex(raw, 13, 3);
    }

    /** The address without its {@code %zone} suffix, trimmed and lower-cased. */
    private static String bare(String address) {
        // mDNS answers, socket addresses and TXT-derived values all spell the same
        // link-local address, and only the case is inconsistent between them.
        int cut = address.indexOf('%');
        String withoutZone = cut < 0 ? address : address.substring(0, cut);
        return withoutZone.trim().toLowerCase(Locale.ROOT);
    }

    private static String hex(byte[] raw, int from, int count) {
        StringBuilder text = new StringBuilder(count * 2);
        for (int i = from; i < from + count; i++) {
            int value = raw[i] & 0xff;
            if (value < 0x10) text.append('0');
            text.append(Integer.toHexString(value));
        }
        return text.toString();
    }

    /**
     * Parses an IPv6 literal into its sixteen bytes, or {@code null} if it is not one.
     *
     * <p>Hand-rolled rather than {@code InetAddress.getByName}: that call is documented to
     * resolve names, and a name lookup from a row being drawn would be a network round trip
     * on the UI thread's path. This only ever accepts a literal, and it never throws.
     */
    private static byte[] ipv6(String literal) {
        if (literal.isEmpty()) return null;
        int gap = literal.indexOf("::");
        if (gap >= 0 && literal.indexOf("::", gap + 1) >= 0) return null;
        String[] left = words(gap < 0 ? literal : literal.substring(0, gap));
        String[] right = gap < 0 ? new String[0] : words(literal.substring(gap + 2));
        if (left == null || right == null) return null;
        int[] parsed = new int[8];
        int at = 0;
        for (String part : left) {
            if (at >= 8) return null;
            parsed[at++] = Integer.parseInt(part, 16);
        }
        int end = 7;
        for (int i = right.length - 1; i >= 0; i--) {
            if (end < at) return null;
            parsed[end--] = Integer.parseInt(right[i], 16);
        }
        // Without "::" the address has to be exactly eight groups; with it, the gap
        // absorbs the rest as zeros and must have at least one group to absorb.
        if (gap < 0 ? at != 8 : at > 7 || right.length > 7) return null;
        byte[] raw = new byte[16];
        for (int i = 0; i < 8; i++) {
            raw[i * 2] = (byte) (parsed[i] >> 8);
            raw[i * 2 + 1] = (byte) parsed[i];
        }
        return raw;
    }

    /** Splits on ':' and validates each group; {@code null} if any of them is not hex. */
    private static String[] words(String part) {
        if (part.isEmpty()) return new String[0];
        String[] pieces = part.split(":", -1);
        for (String piece : pieces) {
            if (piece.isEmpty() || piece.length() > 4) return null;
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!hex) return null;
            }
        }
        return pieces;
    }

    /** Whether a string is one of the rotated hex instance names rather than a name. */
    private static boolean isHash(String value) {
        if (value.length() < 8 || value.length() > 16) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
