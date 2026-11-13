package com.oshare.airdrop.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The AirDrop request/response contract, expressed as typed objects.
 *
 * <p>Everything on this layer is a binary plist; see {@link Bplist}. The paths and
 * field names below are the ones Apple's own client sends and accepts, which is
 * also what OpenDrop speaks.
 */
public final class AirDropProtocol {
    public static final String PATH_DISCOVER = "/Discover";
    public static final String PATH_ASK = "/Ask";
    public static final String PATH_UPLOAD = "/Upload";

    public static final String USER_AGENT = "AirDrop/1.0";

    /**
     * The upload body type a sender must assume.
     *
     * <p>UP-1b is explicit: a sender that does not know the receiver's capabilities has
     * to send DVZip, so this is {@code x-dvzip} and the body is a {@link DvZip} block
     * sequence over a cpio archive. {@code application/x-cpio} is what we accept from
     * others, not what we send.
     */
    public static final String UPLOAD_CONTENT_TYPE = "application/x-dvzip";

    /** The archive types an upload may declare; UP-4 wants anything else answered 406. */
    public static boolean isArchiveType(String contentType) {
        if (contentType == null) return false;
        String value = contentType.toLowerCase(java.util.Locale.ROOT);
        return value.contains("application/x-dvzip")
            || value.contains("application/x-cpio")
            || value.contains("application/octet-stream");
    }

    public static final String OCTET_STREAM = "application/octet-stream";

    /**
     * A fresh transfer identifier, uppercased because every Apple implementation
     * prints UUIDs that way and the receiver echoes this one back in the
     * {@code TransferID} header of the upload that follows.
     */
    public static String newTransferId() {
        return java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Advertised receive capabilities, byte-for-byte what OpenDrop serves in the
     * field (Python's {@code json.JSONEncoder} emits the space after the colon).
     * Staying at {@code Version 1} with no codec or container entries makes senders
     * fall back to legacy formats (JPEG instead of HEIF), which is exactly what we
     * can store without transcoding.
     */
    public static final String MEDIA_CAPABILITIES = "{\"Version\": 1}";

    private AirDropProtocol() {
    }

    /** One entry of the {@code Files} array in an {@code /Ask} request. */
    public static final class Item {
        public final String name;
        public final String type;
        public final String bomPath;
        public final boolean directory;
        /** Declared size, or {@code -1} when it is not known. */
        public final long size;

        Item(String name, String type, String bomPath, boolean directory) {
            this(name, type, bomPath, directory, -1L);
        }

        Item(String name, String type, String bomPath, boolean directory, long size) {
            this.name = name == null ? "" : name;
            this.type = type == null ? "public.content" : type;
            this.bomPath = bomPath == null ? "" : bomPath;
            this.directory = directory;
            this.size = size < 0L ? -1L : size;
        }

        /** Archive member name, derived from {@code FileBomPath} when present. */
        public String memberName() {
            String candidate = bomPath.isEmpty() ? name : bomPath;
            String safe = Cpio.safeName(candidate);
            return safe == null ? Cpio.safeName(name) : safe;
        }

        @Override
        public String toString() {
            return name + " (" + type + (directory ? ", directory)" : ")");
        }
    }

    /** A parsed {@code /Ask} request. */
    public static final class Ask {
        public final String senderName;
        public final String senderModel;
        public final String senderId;
        public final String bundleId;
        public final boolean convertMediaFormats;
        public final List<Item> files;
        public final List<String> links;
        public final boolean hasIcon;

        Ask(String senderName, String senderModel, String senderId, String bundleId,
            boolean convertMediaFormats, List<Item> files, List<String> links, boolean hasIcon) {
            this.senderName = senderName == null ? "" : senderName;
            this.senderModel = senderModel == null ? "" : senderModel;
            this.senderId = senderId == null ? "" : senderId;
            this.bundleId = bundleId == null ? "" : bundleId;
            this.convertMediaFormats = convertMediaFormats;
            this.files = Collections.unmodifiableList(files);
            this.links = Collections.unmodifiableList(links);
            this.hasIcon = hasIcon;
        }

        /** Name to show on the receive card. */
        public String displayName() {
            return senderName.isEmpty() ? senderModel : senderName;
        }

        /** Short description of what is being offered, for the receive prompt. */
        public String summary() {
            if (!files.isEmpty()) {
                return files.size() == 1 ? files.get(0).name : files.size() + " files";
            }
            if (!links.isEmpty()) {
                return links.size() == 1 ? links.get(0) : links.size() + " links";
            }
            return "";
        }
    }

    /** Parses an {@code /Ask} body. */
    public static Ask ask(byte[] body) throws Bplist.FormatException {
        Map<String, Object> map = Bplist.parseDictionary(body);
        List<Item> files = new ArrayList<>();
        for (Object raw : Bplist.list(map, "Files")) {
            if (!(raw instanceof Map)) continue;
            Map<?, ?> entry = (Map<?, ?>) raw;
            files.add(new Item(
                Bplist.string(entry, "FileName"),
                Bplist.string(entry, "FileType"),
                Bplist.string(entry, "FileBomPath"),
                Bplist.flag(entry, "FileIsDirectory", false),
                Bplist.number(entry, "FileSize", -1L)));
        }
        List<String> links = new ArrayList<>();
        for (Object raw : Bplist.list(map, "Items")) {
            if (raw instanceof String) links.add((String) raw);
        }
        return new Ask(
            Bplist.string(map, "SenderComputerName"),
            Bplist.string(map, "SenderModelName"),
            Bplist.string(map, "SenderID"),
            Bplist.string(map, "BundleID"),
            Bplist.flag(map, "ConvertMediaFormats", false),
            files,
            links,
            Bplist.bytes(map, "FileIcon") != null);
    }

    /** Builds the {@code /Ask} answer that tells the sender who we are. */
    public static byte[] askAnswer(String computerName, String modelName) {
        return Bplist.encode(Bplist.dictionary(
            "ReceiverComputerName", computerName,
            "ReceiverModelName", modelName));
    }

    /** Builds the {@code /Discover} answer with the default capability blob. */
    public static byte[] discoverAnswer(String computerName, String modelName) {
        return discoverAnswer(computerName, modelName,
            MEDIA_CAPABILITIES.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the {@code /Discover} answer with an explicit capability blob.
     *
     * <p>The three keys here are not a guess: they are the three a current iPhone puts on
     * the wire, in this order, captured from its own answer -- {@code ReceiverComputerName},
     * {@code ReceiverMediaCapabilities}, {@code IsAirDropable}. An earlier version of this
     * method sent {@code ReceiverModelName} in the third slot instead, on the reasoning
     * that it described the device more usefully. It does, and it also breaks the whole
     * flow: the sender looks for {@code IsAirDropable} to decide whether the peer can take
     * a transfer at all, finds no boolean, and concludes it cannot. The visible symptom was
     * a device that appeared in the share sheet, opened a TLS connection, asked
     * {@code /Discover}, received a 200, and then vanished without ever posting
     * {@code /Ask} -- the sender had learned everything about us except the one bit that
     * said we accept files.
     *
     * <p>{@code ReceiverModelName} is therefore gone. The phone's own answer carries
     * {@code model=null}, so nothing downstream needs it. A sender that wants a model
     * reads it from the certificate, not from here.
     */
    public static byte[] discoverAnswer(String computerName, String modelName, byte[] mediaCapabilities) {
        return Bplist.encode(Bplist.dictionary(
            "ReceiverComputerName", computerName,
            "ReceiverMediaCapabilities", mediaCapabilities,
            "IsAirDropable", Boolean.TRUE));
    }

    /**
     * Builds a {@code /Discover} request body: exactly the three fields a sender fills in.
     *
     * <p>§6.3 calls an empty dictionary a valid request, and it is — but it is also a
     * request some receivers ignore. The one this was measured against answers a
     * three-field body in the same millisecond and closes the connection with a TLS
     * alert in under a second when the body is the 42-byte empty dictionary. So the
     * three fields go in even though the certificate already carries all of them: a
     * name is what the answer has to be addressed to.
     *
     * <p>Nothing else goes in. {@code DeviceSupportFlags} is the obvious candidate — a
     * Mac sends one, and 111611 is the value a current iPhone reports about itself — but
     * adding it here made the receiver's answer *shorter* (1142 bytes over two keys
     * instead of 1437 over three) while the /Ask behind it stayed silent, so it is not
     * the missing piece and a sender that claims Mac-sized capabilities changes what the
     * receiver expects to be able to do. The body below is the one that was on the wire
     * the first time this phone ever showed a transfer prompt, and it has been left
     * byte-for-byte at that shape.
     */
    public static byte[] discoverRequest(String computerName, String modelName, String senderId) {
        return Bplist.encode(Bplist.dictionary(
            "SenderComputerName", computerName,
            "SenderModelName", modelName,
            "SenderID", senderId));
    }

    /**
     * Builds an {@code /Ask} request body, in the shape §6.4 calls <em>current</em>.
     *
     * <p>The distinction is not cosmetic. §6.4 documents two shapes. The legacy one is
     * what AirDrop 1.0 sent and what most independent implementations still send; the
     * current one adds {@code TransferID}, {@code TransferType}, and a per-file
     * {@code FileSize}. ASK-7 states the consequence of getting it wrong: a receiver
     * given the legacy shape "may answer 200 without ever prompting its user, and then
     * drop the /Upload". A 200 that never asked anybody looks exactly like consent at
     * the HTTP layer, so the sender waits on a file that was never accepted — and
     * reports a protocol failure that is really a missing field.
     *
     * <p>{@code TransferID} is also the sender's own promise: UP-9 requires the upload's
     * {@code TransferID} header to equal the id offered here, so the caller passes the
     * id in rather than letting this method invent one it would then have to be told
     * about out of band.
     *
     * <p>{@code Items} and {@code Files} are always present, even when one of them is
     * empty. A links offer carries an empty {@code Files}, a file offer an empty
     * {@code Items}, and both shapes are what the receiver parses. Omitting the empty
     * one is the difference between an offer the receiver reads and an offer it
     * rejects: an {@code /Ask} that named only {@code Files} was answered by an iPhone
     * with a bare TCP reset — no status, no body, no prompt — while the {@code /Discover}
     * on the same connection had just been answered 200. A key that says "there are no
     * links" is cheaper than a reset.
     *
     * <p>Each entry says {@code ShouldConvertMediaFormats} and nothing else about
     * conversion. §6.4 names that spelling; {@code ConvertMediaFormats} is the
     * top-level field and it appears once, at the top level. An earlier revision wrote
     * both spellings into every entry as a hedge against either being the real one, and
     * that revision is the one the receiver ignored — 558 bytes, acknowledged to the
     * last byte, then 67 seconds of silence and a reset. The body here is the 522-byte
     * one from the run that produced a prompt, so the hedge is gone.
     *
     * <p>The keys are in the order that run sent them. A bplist is a dictionary and
     * order is not supposed to matter, which is exactly why it is kept: it makes two
     * captures comparable byte by byte.
     *
     * <p>Files whose size is unknown go out without {@code FileSize} rather than with a
     * zero. A receiver that believes the declaration would take the transfer for an
     * empty one.
     */
    public static byte[] askRequest(String computerName, String modelName, String senderId,
        String transferId, List<Item> files, List<String> links) {
        List<Object> entries = new ArrayList<>();
        for (Item item : files) {
            Map<String, Object> entry = Bplist.dictionary(
                "FileName", item.name,
                "FileType", item.type,
                "FileBomPath", item.bomPath,
                "FileIsDirectory", item.directory,
                "ShouldConvertMediaFormats", Boolean.FALSE);
            if (item.size >= 0L) entry.put("FileSize", item.size);
            entries.add(entry);
        }
        Map<String, Object> body = Bplist.dictionary(
            "SenderComputerName", computerName,
            "SenderModelName", modelName,
            "SenderID", senderId,
            "BundleID", "com.apple.finder",
            "ConvertMediaFormats", Boolean.FALSE,
            "TransferID", Bplist.dictionary("id", transferId),
            "TransferType", Bplist.dictionary(files.isEmpty() ? "links" : "files",
                Bplist.dictionary()));
        body.put("Files", entries);
        body.put("Items", new ArrayList<Object>(links));
        return Bplist.encode(body);
    }
}
