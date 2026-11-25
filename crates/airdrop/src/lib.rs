//! The AirDrop request/response contract, expressed as typed objects.
//!
//! Everything on this layer is a binary plist ([`opairdrop_bplist`]). The paths and
//! field names below are the ones Apple's own client sends and accepts, which is also
//! what OpenDrop speaks.
//!
//! # What is here, and what is not yet
//!
//! This crate owns the *semantics*: which keys a body carries, in which order, and what
//! a receiver must conclude when a key is missing. It does not own the socket, the TLS
//! session, or the archive — those arrive separately, and each one can be tested
//! without the others.
//!
//! # Key order is part of the contract
//!
//! A plist dictionary is unordered, so key order is not supposed to matter, which is
//! exactly why it is pinned here: keeping the captured order makes two bodies
//! comparable byte for byte, and a diff between "the body that produced a prompt" and
//! "the body that was ignored" is the only debugging tool this protocol has ever
//! offered. Every builder in [`payload`] therefore inserts keys in the order the run
//! that worked sent them, and the tests assert that order rather than just the values.

#![forbid(unsafe_code)]

pub mod client;
pub mod inbox;
pub mod payload;
pub mod server;

pub use client::{offered_bytes, Client, Dial, DEFAULT_RESPONSE_LIMIT};
pub use inbox::{Inbox, Received, DEFAULT_BYTE_LIMIT};
pub use payload::{
    ask_request, discover_request, Ask, AskAnswer, DiscoverAnswer, Item, Sender, DEFAULT_FILE_TYPE,
};
pub use server::{AcceptAll, Receiver, Server, Upload};

use std::fmt;

/// Discovery and file request.
pub const PATH_DISCOVER: &str = "/Discover";
/// The offer that makes the receiver prompt its user.
pub const PATH_ASK: &str = "/Ask";
/// The transfer itself, as a cpio archive.
pub const PATH_UPLOAD: &str = "/Upload";

/// `User-Agent` every AirDrop endpoint sends.
pub const USER_AGENT: &str = "AirDrop/1.0";

/// Content type of a `/Discover` or `/Ask` body.
pub const OCTET_STREAM: &str = "application/octet-stream";

/// The upload body type a sender must assume.
///
/// UP-1b is explicit: a sender that does not know the receiver's capabilities has to
/// send DVZip, so this is `x-dvzip` and the body is a DVZip block sequence over a cpio
/// archive. `application/x-cpio` is what we *accept* from others, not what we send.
pub const UPLOAD_CONTENT_TYPE: &str = "application/x-dvzip";

/// Advertised receive capabilities: what this receiver claims it can store untouched.
///
/// # Why this is not `{"Version": 1}` any more
///
/// It used to be, and the note beside it said that leaving out codecs made senders fall
/// back to "legacy formats … which is exactly what we can store without transcoding".
/// The first half is right and the second half is backwards. DISC-3 is explicit:
///
/// > A receiver that leaves out `Codecs` and `ContainerFormats` gets media in widely
/// > supported formats (JPEG instead of HEIC).
///
/// "Widely supported" is decided by the *sender*, and for video it means H.264 — so an
/// iPhone holding a 4K HEVC clip had to transcode it to `avc1` before it could offer it.
/// That transcode happens after `/Discover` is answered and before `/Ask` is posted, so
/// the sender had nothing to post for the length of the encode: measured on this device,
/// `Discover → Ask` was 21.9 s for a 27 MB clip, 41.5 s for a 93 MB one, and 601.9 s for a
/// 502 MB one, against 1.1–12 s for photos that need no transcode. The two facts a person
/// sees — "a big file from an iPhone takes ages to prompt" and "a 93 MB video comes back
/// as 26.5 MB" — are that one encode, seen from two ends. Analysed off disk, the file that
/// arrived was `avc1` with 450 samples where the original was `hvc1` with 900: same
/// duration, same 3840×2160, audio track byte-identical, video re-encoded and halved.
///
/// # What the capture taken after this constant was written actually showed
///
/// Two separate runs measured this constant, and neither showed a transcode-free send. The
/// numbers above therefore describe the *behaviour* and not the *fix*, which is why they are
/// kept as the statement of the symptom rather than as evidence that the symptom is gone.
///
/// The failure of both runs has a cause worth recording, because it invalidates any test
/// that does not check it first: `receive.media_capabilities` was set on the device, and a
/// non-blank row wins over this constant in `settings::media_capabilities`. The row held a
/// 1295-byte verbatim capture of an iPhone's own answer — and that capture declares
/// `ContainerFormats: {"public.heif-standard": …}` and *no* `public.mpeg-4` at all. So every
/// run that pasted the capture in was testing a blob that says nothing about MP4, while
/// this constant says `public.mpeg-4`, and the constant never reached an iPhone. Clearing
/// the row is what makes a test of this constant a test of this constant.
///
/// A second finding stands regardless of the row, and constrains what any capability blob
/// can achieve: the `/Ask` that arrived with the transcoded clip carried
/// `ConvertMediaFormats = false` and `ShouldConvertMediaFormats = false` per file, and
/// declared `FileSize = 27822213` — the size *after* the re-encode, not the 93 MB on the
/// iPhone. A sender decides the size before it asks, so this blob is read before `/Ask` is
/// built, exactly as DISC-3 says; what it cannot do is express a preference about a
/// container the sender has already chosen to produce. `/Ask` reports the outcome of a
/// decision this receiver was consulted about but does not control.
///
/// # Why `hvc1` and `public.mpeg-4`, and nothing else
///
/// Storing HEVC needs no work here: an upload is written to disk as the bytes it arrived
/// as, and nothing in this stack decodes video. So claiming `hvc1` is a statement about
/// the inbox, not about a player, and it satisfies DISC-3's "MUST NOT advertise a codec it
/// can't store".
///
/// The shape of `Codecs` and `ContainerFormats` is *not* specified — DISC-3 gives
/// `{"Version": 1}` as its only example and never types the two keys, so this blob is
/// copied field for field from what an iPhone answers when *it* is the receiver. That
/// capture was taken by sending one file from this device to an iPhone and saving its
/// `/Discover` answer (1295 bytes of `ReceiverMediaCapabilities`):
///
/// ```text
/// {"SupportsAdjustmentBaseResources":true,"Version":3,
///  "Codecs":{"hvc1":{"Profiles":{"VTIsHDRAllowedOnDevice":true,
///    "VTSupportedProfiles":[1,2,4],
///    "VTPerProfileSupport":{"1":{"VTMaxDecodeLevel":180,"VTIsHardwareAccelerated":true}, …}}},
///   "CodecSupport":{"VTCodecSupportDict":{"hvc1":{…},"apcs":{…},…}}},
///  "ContainerFormats":{"public.heif-standard":{"HeifSubtypes":[…]}},
///  "Vendor":{"com.apple":{…}}}
/// ```
///
/// An earlier version of this constant was modelled on the macOS 10.13.3 sample that
/// OpenDrop ships *commented out*, and it did not work: a 93 MB clip still arrived as
/// 26.5 MB with `Discover → Ask` 11.5 s apart. Four differences explain that, and all
/// four are the kind that parse cleanly and silently yield nothing:
///
/// * `Version` is `3`, not `1` — a sender may gate the whole blob on it;
/// * the per-profile key is `VTMaxDecodeLevel`, not `VTMaxPlaybackLevel`;
/// * profiles are `[1, 2, 4]` — Main, Main 10, Main 4:4:4. `3` is Main Still Picture,
///   which no video sender asks about;
/// * every profile carries `VTIsHardwareAccelerated`, without which a profile can read as
///   supported-but-software and not worth sending the original for.
///
/// # What is deliberately left out
///
/// * **`CodecSupport` and the `VTDoVi*` / `VTIsHDRAllowedOnDevice` keys.** They are
///   about *playback* (Dolby Vision, HDR), not about storing bytes. This stack never
///   decodes video, and DISC-3 only forbids under-claiming what can be stored; claiming a
///   display capability the device may not have would be borrowing an identity again.
/// * **`public.heif-standard`.** Photos work today: they arrive as JPEG, immediately, and
///   nothing about them is broken. Declaring HEIF would change the one path that has no
///   complaint against it, in exchange for a format this device stores but cannot show.
/// * **`Vendor`.** `com.apple` is a claim to be an Apple device. A sender is entitled to
///   act on it, and the point of this blob is to describe *this* device.
///
/// `public.mpeg-4` carries no sub-keys: `HeifSubtypes` is the only nesting the capture
/// shows, and inventing a video equivalent of it would be a guess dressed as a fact.
/// Presence of the container is the claim.
///
/// # The revert path
///
/// Changing this blob has broken the flow before (`payload::discover_request` records a
/// run where a larger answer left `/Ask` silent), and a wrong guess here is not visible
/// until a real iPhone tries to send. So the value is only the *default*: the settings row
/// `receive.media_capabilities` overrides it per device, and setting that row to
/// `{"Version": 1}` restores the old behaviour without a rebuild. See `Store::discover`.
pub const MEDIA_CAPABILITIES: &str = concat!(
    "{\"Version\": 3, ",
    "\"Codecs\": {\"hvc1\": {\"Profiles\": {",
    "\"VTSupportedProfiles\": [1, 2, 4], ",
    "\"VTPerProfileSupport\": {",
    "\"1\": {\"VTMaxDecodeLevel\": 180, \"VTIsHardwareAccelerated\": true}, ",
    "\"2\": {\"VTMaxDecodeLevel\": 180, \"VTIsHardwareAccelerated\": true}, ",
    "\"4\": {\"VTMaxDecodeLevel\": 180, \"VTIsHardwareAccelerated\": true}",
    "}}}}, ",
    "\"ContainerFormats\": {\"public.mpeg-4\": {}}",
    "}"
);

/// Whether a `Content-Type` declares an archive this receiver can unpack.
///
/// UP-4 wants anything else answered `406`.
pub fn is_archive_type(content_type: &str) -> bool {
    // "contains" and not "equals": senders append parameters, and at least one has sent
    // a doubled type. Comparing the whole value would reject both.
    [
        "application/x-dvzip",
        "application/x-cpio",
        "application/octet-stream",
    ]
    .iter()
    .any(|declared| opairdrop_http::contains_token(content_type, declared))
}

/// A transfer identifier.
///
/// Uppercase, because every Apple implementation prints UUIDs that way and the receiver
/// echoes this exact string back in the `TransferID` header of the upload that follows.
/// UP-9 makes the echo a requirement, so the sender must reuse the value it offered in
/// `/Ask` rather than minting a new one — which is why this is a type the caller holds
/// and passes in, instead of something a builder invents internally.
///
/// # Why there is no `random()`
///
/// Randomness is the platform's job. Android has `UUID.randomUUID`; keeping the entropy
/// source out of this crate means the codec layer stays deterministic and testable, and
/// no dependency is added for sixteen bytes.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct TransferId(String);

impl TransferId {
    /// Formats sixteen random bytes as an uppercase version-4 UUID.
    ///
    /// The version and variant bits are overwritten, as `UUID.randomUUID` does: a
    /// receiver that validates the shape of an identifier it was handed is entitled to
    /// find a well-formed one.
    pub fn from_bytes16(bytes: &[u8; 16]) -> TransferId {
        let mut octets = *bytes;
        octets[6] = (octets[6] & 0x0f) | 0x40;
        octets[8] = (octets[8] & 0x3f) | 0x80;
        let mut text = String::with_capacity(36);
        for (index, byte) in octets.iter().enumerate() {
            if matches!(index, 4 | 6 | 8 | 10) {
                text.push('-');
            }
            text.push(HEX[(byte >> 4) as usize] as char);
            text.push(HEX[(byte & 0x0f) as usize] as char);
        }
        TransferId(text)
    }

    /// The identifier as it appears on the wire.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

const HEX: &[u8; 16] = b"0123456789ABCDEF";

impl fmt::Display for TransferId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// Anything that can go wrong turning bytes into protocol objects, or back.
#[derive(Debug)]
pub enum Error {
    /// The body was not a decodable `bplist00` dictionary.
    Plist(opairdrop_bplist::Error),
}

impl PartialEq for Error {
    fn eq(&self, other: &Self) -> bool {
        match (self, other) {
            (Error::Plist(a), Error::Plist(b)) => a == b,
        }
    }
}

impl Eq for Error {}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Plist(error) => write!(f, "plist: {error}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Plist(error) => Some(error),
        }
    }
}

impl From<opairdrop_bplist::Error> for Error {
    fn from(error: opairdrop_bplist::Error) -> Error {
        Error::Plist(error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn archive_types_are_matched_loosely_but_not_blindly() {
        assert!(is_archive_type("application/x-dvzip"));
        assert!(is_archive_type("application/x-cpio"));
        assert!(is_archive_type("application/octet-stream"));
        // Case is not part of a media type's meaning.
        assert!(is_archive_type("Application/X-CPIO"));
        // Parameters are legal on any media type.
        assert!(is_archive_type("application/x-cpio; charset=binary"));
        // The 406 rule has to actually reject something.
        assert!(!is_archive_type("text/plain"));
        assert!(!is_archive_type("application/zip"));
        assert!(!is_archive_type(""));
    }

    #[test]
    fn transfer_ids_are_uppercase_version_4_uuids() {
        let id = TransferId::from_bytes16(&[0x00; 16]);
        assert_eq!(id.as_str(), "00000000-0000-4000-8000-000000000000");
        assert_eq!(id.as_str().len(), 36);

        let id = TransferId::from_bytes16(&[0xff; 16]);
        assert_eq!(id.as_str(), "FFFFFFFF-FFFF-4FFF-BFFF-FFFFFFFFFFFF");

        // The version and variant bits are forced, not passed through.
        let id = TransferId::from_bytes16(&[
            0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xf0, 0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc,
            0xde, 0xf0,
        ]);
        assert_eq!(id.as_str(), "12345678-9ABC-4EF0-9234-56789ABCDEF0");
    }

    #[test]
    fn media_capabilities_name_hevc_and_leave_photos_alone() {
        // What the blob has to say, and what it must not say.
        //
        // These are the load-bearing facts rather than the exact bytes: without an HEVC
        // entry an iPhone transcodes a 4K clip to H.264 *before* offering it (see the
        // constant), which is the delay and the size loss seen from both ends.
        assert!(
            MEDIA_CAPABILITIES.contains("\"Version\": 3"),
            "an iPhone answers Version 3; a sender may gate the whole blob on the version"
        );
        assert!(
            MEDIA_CAPABILITIES.contains("\"hvc1\""),
            "without an HEVC codec entry a sender re-encodes video before sending it"
        );
        assert!(
            MEDIA_CAPABILITIES.contains("\"public.mpeg-4\""),
            "the codec entry means nothing without a container that can carry it"
        );
        // The four things the first attempt got wrong. Each one parses cleanly and
        // silently yields nothing, so they are asserted by name.
        assert!(
            MEDIA_CAPABILITIES.contains("\"VTMaxDecodeLevel\""),
            "the per-profile key is VTMaxDecodeLevel; VTMaxPlaybackLevel was read as absent"
        );
        assert!(
            MEDIA_CAPABILITIES.contains("\"VTIsHardwareAccelerated\": true"),
            "a profile without this can read as software-only and not worth sending for"
        );
        assert!(
            MEDIA_CAPABILITIES.contains("[1, 2, 4]"),
            "profiles are Main, Main 10 and Main 4:4:4; 3 is Main Still, which no video \
             sender asks about"
        );
        assert!(
            !MEDIA_CAPABILITIES.contains("VTDoVi") && !MEDIA_CAPABILITIES.contains("HDR"),
            "Dolby Vision and HDR are playback claims, and this stack never decodes"
        );
        assert!(
            !MEDIA_CAPABILITIES.contains("public.heif"),
            "photos already arrive as JPEG, immediately; declaring HEIF would change the \
             one path nothing is wrong with"
        );
        assert!(
            !MEDIA_CAPABILITIES.contains("\"Vendor\""),
            "com.apple is a claim to be an Apple device, and a sender may act on it"
        );
        assert!(
            !MEDIA_CAPABILITIES.contains("avc1"),
            "H.264 is what the fallback already produces; claiming it is not the change"
        );

        // A sender parses this as JSON. There is no JSON parser in this crate's dependency
        // list and one is not worth adding for a constant, so the shape is checked by
        // hand: balanced braces and brackets, and an even number of unescaped quotes.
        let mut depth = 0i32;
        let mut brackets = 0i32;
        let mut escaped = false;
        let mut quotes = 0usize;
        for byte in MEDIA_CAPABILITIES.bytes() {
            match (escaped, byte) {
                (true, _) => escaped = false,
                (false, b'\\') => escaped = true,
                (false, b'"') => quotes += 1,
                (false, b'{') => depth += 1,
                (false, b'}') => depth -= 1,
                (false, b'[') => brackets += 1,
                (false, b']') => brackets -= 1,
                _ => {}
            }
            assert!(
                depth >= 0 && brackets >= 0,
                "a closing bracket arrives too early"
            );
        }
        assert_eq!(depth, 0, "the object is not closed");
        assert_eq!(brackets, 0, "the array is not closed");
        assert_eq!(quotes % 2, 0, "a string is left open");
    }
}
