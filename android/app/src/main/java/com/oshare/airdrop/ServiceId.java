package com.oshare.airdrop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The DNS-SD instance name this device publishes, kept across restarts.
 *
 * <p>The instance name is not the computer name. It is the receiver's <em>service ID</em>:
 * twelve lowercase hex digits, six random bytes, the same value a sender is handed as
 * {@code SenderID} in {@code /Discover} and {@code /Ask}. The peer we are trying to appear in
 * does exactly this — its own record reads {@code 87eb19167bcd._airdrop._tcp.local} — so an
 * instance called {@code Air} is the one shape in the whole record that a sender cannot mistake
 * for AirDrop. The name a person reads is a different field, carried in the {@code /Discover}
 * answer; this one is an identifier.
 *
 * <p># Why the ID is on disk
 *
 * <p>A receiver that draws a fresh ID on every start publishes a new instance beside the old
 * one, and every peer that remembers the old instance lists two of the same device. Nothing on
 * this side is duplicated — one responder, one port, one certificate — so there is no packet to
 * look at and fix; the duplicate lives only in the other device's cache, and it stays there for
 * the PTR lifetime, which is seventy-five minutes.
 *
 * <p>The fix needs two parts and this class is both of them. Part one is stability: an ID
 * written to disk and read back next time means the second launch announces the instance the
 * peer already knows, so nothing new appears. Part two is a memory of what came before: the IDs
 * published by earlier versions are still in somebody's cache, and the only way to take one back
 * is to send its records again with a zero TTL (RFC 6762 §10.1). So the file holds a short
 * history, newest last, and {@link #loadOrCreate} hands the old ones to the caller to withdraw.
 *
 * <p>A few entries rather than one, because a start that dies between "read" and "withdrew"
 * leaves the oldest ID behind otherwise, and the cost of remembering eight twelve-byte strings
 * is nothing. The eight is {@code discovery.remembered_peers} — it counts the name in use, so
 * the history is one shorter than the row says.
 *
 * <p>Writes go through a temporary file and a rename: a crash mid-write must not turn into an
 * unparseable ID, because a service ID that fails to parse is a new service ID, which is the
 * exact problem this file exists to prevent.
 */
final class ServiceId {
    /** In the app's private directory, beside {@code settings.conf}. */
    private static final String FILE_NAME = "service.id";

    /**
     * How many published IDs to remember, when the table cannot say.
     *
     * <p>The row is {@code discovery.remembered_peers}; this is what it was before it was a row.
     * See the class comment for why more than one ID is kept at all.
     */
    private static final int MAX_REMEMBERED = 8;

    /**
     * How many IDs to keep now: the row, clamped by it, or the constant above.
     *
     * <p>Read from the settings file that sits beside {@code service.id} rather than from a
     * {@link android.content.Context}, because this class is handed a directory and nothing else.
     */
    private static int remembered(File directory) {
        return (int) Tuning.ms(directory, "discovery.remembered_peers", MAX_REMEMBERED);
    }

    /** The current ID first, then the ones that should be withdrawn, oldest first. */
    final String current;
    final List<String> stale;

    private ServiceId(String current, List<String> stale) {
        this.current = current;
        this.stale = Collections.unmodifiableList(stale);
    }

    /**
     * Reads the ID this device last published, minting one only when there is none.
     *
     * <p>The current ID is the newest entry that has the twelve-hex-digit shape; everything
     * else in the file is an instance this device published earlier and should take back.
     * Keeping those two questions apart is what makes the file self-healing: an entry like
     * {@code Air} — the display name an early build used as an instance name — is a real ghost
     * that must be withdrawn, and it must never be chosen as the name to keep publishing.
     *
     * <p>Survives an unreadable file by treating it as absent rather than throwing: a receiver
     * that cannot start because a text file has a stray byte in it is worse off than one that
     * changes its instance name, and the changed name is recoverable — the new ID is written
     * immediately and the old one, if it was legible, is still in the history to be withdrawn on
     * the next pass.
     */
    static ServiceId loadOrCreate(File directory) {
        List<String> remembered = read(directory);
        String current = null;
        for (int i = remembered.size() - 1; i >= 0; i--) {
            if (valid(remembered.get(i))) {
                current = remembered.get(i);
                break;
            }
        }
        if (current == null) {
            current = random();
        }
        List<String> stale = new ArrayList<>();
        for (String id : remembered) {
            if (!id.equals(current)) {
                stale.add(id);
            }
        }
        // Written now, before anything is announced: if the process dies during startup the
        // next launch must still find this ID rather than invent another one.
        write(directory, current, stale);
        return new ServiceId(current, stale);
    }

    /**
     * Drops IDs that have been withdrawn, so the next start does not repeat the work.
     *
     * <p>Kept in the file until the withdrawal has actually gone out. A TTL=0 record for an
     * instance nobody remembers is harmless, so repeating one is cheap; forgetting one that never
     * left is the mistake, because nothing else will ever send it again.
     */
    void forget(File directory, List<String> withdrawn) {
        List<String> remaining = new ArrayList<>();
        for (String id : stale) {
            if (!withdrawn.contains(id)) {
                remaining.add(id);
            }
        }
        write(directory, current, remaining);
    }

    /** Six random bytes as twelve lowercase hex digits. */
    static String random() {
        byte[] six = new byte[6];
        new SecureRandom().nextBytes(six);
        StringBuilder id = new StringBuilder(12);
        for (byte b : six) {
            id.append(Character.forDigit((b >> 4) & 0xf, 16))
              .append(Character.forDigit(b & 0xf, 16));
        }
        return id.toString();
    }

    /**
     * True when {@code id} is the shape an instance name must have: twelve lowercase hex digits.
     *
     * <p>The test for "may this be our instance name from now on", not for "may this be in the
     * file". Case matters because peers compare instance names as text, so {@code 3FA9C01B22DE}
     * and {@code 3fa9c01b22de} are two devices to them even though they are the same six bytes.
     */
    static boolean valid(String id) {
        if (id == null || id.length() != 12) {
            return false;
        }
        for (int i = 0; i < 12; i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when {@code id} could be an instance name we published, whatever name it is.
     *
     * <p>Deliberately looser than {@link #valid}: the history exists to record what went out on
     * the air, and a name that violates today's rule is still a name a peer may be holding.
     * One DNS label, no whitespace, no control characters, no dot (a dot would be a second label
     * and the record would name a different service).
     */
    static boolean usable(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (id.getBytes(StandardCharsets.UTF_8).length > 63) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '.' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> read(File directory) {
        File file = directory == null ? null : new File(directory, FILE_NAME);
        if (file == null || !file.isFile()) {
            return Collections.emptyList();
        }
        List<String> ids;
        try {
            ids = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return Collections.emptyList();
        }
        // Deduplicated keeping the last occurrence, so an ID written twice (a start that
        // announced, then crashed, then started again with the same history) is not handed to
        // the withdrawal path as if it were two devices.
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : ids) {
            String id = line.trim();
            if (!usable(id)) {
                continue;
            }
            unique.remove(id);
            unique.add(id);
        }
        List<String> ordered = new ArrayList<>(unique);
        int from = Math.max(0, ordered.size() - remembered(directory));
        return ordered.subList(from, ordered.size());
    }

    private static void write(File directory, String current, List<String> stale) {
        if (directory == null) {
            return;
        }
        StringBuilder body = new StringBuilder();
        // Trim the history rather than the current ID: the ID in use is the one record that must
        // never be lost, and the oldest ghosts are the ones least likely to still be in
        // anybody's cache. The row counts the name in use too, so the history gets one fewer.
        int keep = Math.max(1, remembered(directory));
        int from = Math.max(0, stale.size() - (keep - 1));
        for (String id : stale.subList(from, stale.size())) {
            body.append(id).append('\n');
        }
        body.append(current).append('\n');
        Path target = new File(directory, FILE_NAME).toPath();
        Path temporary = new File(directory, FILE_NAME + ".tmp").toPath();
        try {
            if (!directory.exists()) {
                directory.mkdirs();
            }
            Files.write(temporary, body.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unwritable) {
            // A read-only data directory costs us a stable name, not the receiver: the ID still
            // works for this session, it just will not be the same next time.
            temporary.toFile().delete();
        }
    }
}
