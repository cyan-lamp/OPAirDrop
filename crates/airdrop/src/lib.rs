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

/// Advertised receive capabilities, byte for byte what OpenDrop serves in the field.
///
/// The space after the colon is not a typo: Python's `json.JSONEncoder` emits it, and
/// this blob has been copied off the wire ever since. Staying at `Version 1` with no
/// codec or container entries makes senders fall back to legacy formats — JPEG instead
/// of HEIF — which is exactly what we can store without transcoding.
pub const MEDIA_CAPABILITIES: &str = "{\"Version\": 1}";

/// Whether a `Content-Type` declares an archive this receiver can unpack.
///
/// UP-4 wants anything else answered `406`.
pub fn is_archive_type(content_type: &str) -> bool {
    // "contains" and not "equals": senders append parameters, and at least one has sent
    // a doubled type. Comparing the whole value would reject both.
    ["application/x-dvzip", "application/x-cpio", "application/octet-stream"]
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
    fn media_capabilities_keep_the_encoder_space() {
        // A sender parses this as JSON; a missing space would not break it, but changing
        // it would silently end our ability to compare against captures.
        assert_eq!(MEDIA_CAPABILITIES, "{\"Version\": 1}");
    }
}
