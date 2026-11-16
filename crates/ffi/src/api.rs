//! The C ABI for a transfer. The settings surface lives in [`crate::settings`] and follows
//! the same conventions.
//!
//! # Why this is the first export
//!
//! Receiving an upload is the part of the protocol a stranger can reach without being
//! invited: any peer that finishes `/Ask` gets to hand over an archive, and that archive
//! gets to name files. The predecessor's answer to it (`core/Inbox.java`, `Cpio.Sink`,
//! `DvZip.decode`, `Cpio.safeName`) is four Java classes deep, and every rule in it — no
//! symlinks, no traversal, a ` (n)` sibling instead of an overwrite, a byte budget — is
//! enforced by code that cannot be run without a phone. Here it is [`inbox`]'s rules,
//! tested on the host, reached through one function.
//!
//! # Why the archive arrives through a callback
//!
//! Not as a `(pointer, length)` pair. The budget below is 8 GiB, and a `byte[]` of that
//! size cannot exist in an Android process, so a slice-taking export would have declared
//! a limit it could never be handed. The Java side already holds an `InputStream` — the
//! socket — and streaming means the copy that used to happen on the way in is gone.
//!
//! # What does not cross
//!
//! `Bplist`, `Cpio`, `DvZip` and `DnsMessage` are not exported. Grepping the legacy tree
//! for their use finds only `AirDropClient`, `AirDropProtocol`, `AirDropServer`,
//! `HttpCall`, `Inbox`, `AirDropBrowser`, `AirDropPeer` and `MdnsResponder` — the protocol
//! layer, which is exactly what the Rust crates replace. No UI file touches any of them,
//! so an export of the codecs would have no caller once the protocol moves.
//!
//! [`inbox`]: opairdrop_airdrop::inbox

use std::ffi::c_void;
use std::io::{self, Read, Write};

use opairdrop_airdrop::inbox::{self, Error as InboxError};

use crate::buffer::{fill, OpairdropBuffer};

/// Version of this ABI, so a stale `.so` is a diagnosable mismatch instead of a mystery.
///
/// Bumped whenever an export changes meaning, but not when one is added. Version 2 added
/// the settings argument to [`opairdrop_receive_archive`]: a caller built against 1 would
/// pass four arguments where five are read, so the number is the only way for either side
/// to notice before the call. Version 3 added the [`DialCallback`] to both send exports, for
/// the same reason and with the same failure mode: an older caller would pass its context
/// where a function pointer is now read. Version 4 put `sender_id` between `model` and
/// `transfer_id` in both send exports: every argument after it shifted by two words, so a
/// caller built against 3 would hand its transfer id over as a string pointer and its paths
/// as the transfer id — a `SenderID` that happens to parse and a path list that does not.
pub const ABI_VERSION: u32 = 5;

/// Status codes. Zero is success; negative is a failure.
pub mod status {
    /// The call did what it was asked.
    pub const OK: i32 = 0;
    /// A required pointer was null.
    pub const NULL_ARGUMENT: i32 = -1;
    /// The directory was empty or not valid UTF-8.
    pub const BAD_PATH: i32 = -2;
    /// The archive could not be parsed, or a member ended early.
    pub const ARCHIVE: i32 = -3;
    /// The inbox could not be created or written.
    pub const IO: i32 = -4;
    /// The transfer would have exceeded the inbox's byte budget.
    pub const OVER_BUDGET: i32 = -5;
    /// Every numbered variant of a member's name was taken.
    pub const NO_FREE_NAME: i32 = -6;
    /// A member resolved outside the inbox and was refused.
    pub const OUTSIDE: i32 = -7;
    /// The reader callback returned a negative value, which aborted the transfer.
    pub const READER: i32 = -8;
    /// The settings could not be read: a key the table does not have, a value out of its
    /// range, or a line with no `key=value` in it.
    pub const SETTINGS: i32 = -9;
    /// The connection was not answerable: a stream failed, a request head could not be
    /// framed, or a control body parsed as something other than the dictionary the
    /// protocol requires. The message says which, because a caller's next move is to show
    /// it rather than to branch on it — the three have the same remedy, which is to wait
    /// for the next connection.
    pub const TRANSPORT: i32 = -10;
    /// The peer answered, and the answer was that it is not taking files.
    ///
    /// Its own status because it is the one failure that is not a fault: the transfer was
    /// well formed, the peer said no — it is asleep, or set to contacts only, or its
    /// screen is off. Retrying is pointless until something on that phone changes, and a
    /// caller that shows "transfer failed" for it teaches the person to retry a refusal.
    pub const REFUSED: i32 = -11;
}

/// Supplies the archive's bytes, in order, until it returns zero.
///
/// Return the number of bytes written into `buf`, `0` at the end, or a negative number to
/// abort — the negative value is passed back through [`status::READER`] and is the
/// caller's own code, so a caller can say *why* it gave up. The callback must not write
/// more than `len` bytes, and must not return a positive number without filling that
/// many.
pub type ReadCallback = unsafe extern "C" fn(context: *mut c_void, buf: *mut u8, len: usize) -> isize;

/// Consumes bytes the library produces, in order.
///
/// Return the number of bytes written, or a negative number to abort — the negative value
/// is the caller's own code and is passed back through [`status::TRANSPORT`]. Zero is not
/// "nothing to do" but "made no progress", which ends the write rather than spinning on a
/// peer that will never accept more.
pub type WriteCallback =
    unsafe extern "C" fn(context: *mut c_void, buf: *const u8, len: usize) -> isize;

/// Opens a fresh connection to the same peer and points the read and write callbacks at it.
///
/// Return zero when the callbacks now read and write on a new connection, or a negative
/// number when no new one could be opened — the value is the caller's own and is passed back
/// through [`status::TRANSPORT`].
///
/// This exists because a receiver reads **one request per connection**. `/Discover` and
/// `/Ask` therefore cannot share a socket: an iPhone answers the first and then stops
/// reading, leaving the socket open and saying nothing, which from this side is
/// indistinguishable from a receiver that went to sleep. The library calls this once,
/// between the two, and the caller is expected to close the old connection and open a new
/// one — the same pair of read and write callbacks then serve the second request, because
/// what changed is which socket they are pointed at, not how bytes cross the boundary.
pub type DialCallback = unsafe extern "C" fn(context: *mut c_void) -> isize;

/// What one archive left behind, in the only shape that crosses the boundary.
///
/// The frame is eight big-endian bytes of payload total, then the names as NUL-terminated
/// UTF-8 in archive order. NUL separates because a file name cannot contain one, which is
/// the same reason a cpio name field is NUL-terminated.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Report {
    /// Payload bytes written. Header overhead and dropped members do not count.
    pub bytes: u64,
    /// Names as stored, relative to the inbox, including directories.
    pub names: Vec<String>,
}

impl Report {
    /// Renders the frame a caller receives.
    pub fn encode(&self) -> Vec<u8> {
        let mut frame =
            Vec::with_capacity(8 + self.names.iter().map(|name| name.len() + 1).sum::<usize>());
        frame.extend_from_slice(&self.bytes.to_be_bytes());
        for name in &self.names {
            frame.extend_from_slice(name.as_bytes());
            frame.push(0);
        }
        frame
    }

    /// Reads a frame back.
    ///
    /// Nothing in Rust calls this: it is here so the Java side has a reference parser next
    /// to the encoder rather than a description of one in a comment, and so the tests can
    /// assert on what a caller would actually decode.
    pub fn decode(frame: &[u8]) -> Option<Report> {
        if frame.len() < 8 {
            return None;
        }
        let (head, rest) = frame.split_at(8);
        let bytes = u64::from_be_bytes(head.try_into().ok()?);
        let names = rest
            .split(|byte| *byte == 0)
            .filter(|name| !name.is_empty())
            .map(|name| String::from_utf8(name.to_vec()).ok())
            .collect::<Option<Vec<_>>>()?;
        Some(Report { bytes, names })
    }
}

/// Reports the version of the ABI the loaded library was built against.
#[no_mangle]
pub extern "C" fn opairdrop_abi_version() -> u32 {
    ABI_VERSION
}

/// Receives one archive into `directory`, creating it if needed.
///
/// `settings` is the stored settings text — `key=value` per line — or null, which means
/// "the table's defaults". It is passed as text rather than as a handful of numbers because
/// the text is what the settings screen already holds and what the config file already
/// contains: one form on the wire, one parser, and no second list of parameters to keep in
/// step with [`opairdrop_settings::CATALOG`].
///
/// `report` receives a [`Report`] frame on success, or the failure's message as UTF-8 on
/// failure — the status says which. It must be zero-initialized, and must be released
/// with [`crate::buffer::opairdrop_buffer_free`] once read.
///
/// # Safety
///
/// `directory` must point at `directory_len` bytes of UTF-8 readable for the duration of
/// the call, `settings` at `settings_len` bytes of UTF-8 or be null; `read` must be a
/// callback whose `context` stays valid for the duration; `report` must point at a zeroed
/// [`OpairdropBuffer`] that outlives the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_receive_archive(
    directory: *const u8,
    directory_len: usize,
    settings: *const u8,
    settings_len: usize,
    read: ReadCallback,
    context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    if report.is_null() || directory.is_null() {
        return status::NULL_ARGUMENT;
    }

    // SAFETY: the caller guarantees the region is readable for the length given.
    let directory = unsafe { std::slice::from_raw_parts(directory, directory_len) };

    let Ok(directory) = std::str::from_utf8(directory) else {
        // SAFETY: `report` is non-null and zeroed, per the contract.
        unsafe { fill(report, b"the inbox path is not valid UTF-8".to_vec()) };
        return status::BAD_PATH;
    };
    if directory.is_empty() {
        // SAFETY: as above.
        unsafe { fill(report, b"the inbox path is empty".to_vec()) };
        return status::BAD_PATH;
    }

    // SAFETY: `settings` is null or points at `settings_len` readable bytes, per the
    // contract. The null case is taken before `from_raw_parts`, which requires a
    // non-null pointer even for an empty slice.
    let settings = if settings.is_null() {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(settings, settings_len) }
    };
    let limits = match crate::settings::values_from(settings) {
        Ok(values) => crate::settings::limits(&values),
        Err(message) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, message.into_bytes()) };
            return status::SETTINGS;
        }
    };

    // SAFETY: `read` and `context` are the caller's side of the contract on
    // `ReadCallback`, which they guarantee for the duration of this call.
    let mut reader = CallbackReader::new(read, context);

    let outcome = inbox::receive_with(directory, limits, &mut reader);

    // The reader's own refusal explains the failure better than the archive error it was
    // wrapped in, so it is reported first.
    if let Some(code) = reader.failure {
        let message = format!("the reader aborted with {code}");
        // SAFETY: `report` is non-null and zeroed, per the contract.
        unsafe { fill(report, message.into_bytes()) };
        return status::READER;
    }

    match outcome {
        Ok(received) => {
            let frame = Report {
                bytes: received.bytes,
                names: received.names,
            }
            .encode();
            // SAFETY: as above.
            unsafe { fill(report, frame) };
            status::OK
        }
        Err(error) => {
            let code = classify(&error);
            // SAFETY: as above.
            unsafe { fill(report, error.to_string().into_bytes()) };
            code
        }
    }
}

/// Adapts the caller's callback to [`Read`], remembering whether it gave up.
///
/// Public within the crate because a served connection reads from the same callback shape:
/// an upload body is the caller's stream either way, and a second adapter would be a second
/// place for the `overfilled its buffer` check to be forgotten.
pub(crate) struct CallbackReader {
    read: ReadCallback,
    context: *mut c_void,
    /// The negative value the callback returned, kept so it can be told apart from a
    /// malformed archive once the extractor has wrapped it in an I/O error.
    pub(crate) failure: Option<isize>,
}

impl CallbackReader {
    /// Wraps `read`, whose `context` must stay valid for as long as this value lives.
    pub(crate) fn new(read: ReadCallback, context: *mut c_void) -> CallbackReader {
        CallbackReader {
            read,
            context,
            failure: None,
        }
    }
}

impl Read for CallbackReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        // SAFETY: the callback and its context are valid for this call, and `buf` is
        // writable for `buf.len()` bytes, which is what the callback is promised.
        let got = unsafe { (self.read)(self.context, buf.as_mut_ptr(), buf.len()) };
        if got < 0 {
            self.failure = Some(got);
            return Err(io::Error::other(format!("the reader aborted with {got}")));
        }
        let got = got as usize;
        if got > buf.len() {
            self.failure = Some(-1);
            return Err(io::Error::other(
                "the reader reported more bytes than it was given room for",
            ));
        }
        Ok(got)
    }
}

/// Adapts the caller's callback to [`Write`], remembering whether it refused.
///
/// The mirror of [`CallbackReader`], and for the same reason: an upload's answers go back
/// up the connection the caller owns, and a callback that gave up must not be retried once
/// the peer has stopped reading.
pub(crate) struct CallbackWriter {
    write: WriteCallback,
    context: *mut c_void,
    /// The negative value the callback returned, kept so the caller's own code survives
    /// into the status instead of becoming a generic write failure.
    pub(crate) failure: Option<isize>,
}

impl CallbackWriter {
    /// Wraps `write`, whose `context` must stay valid for as long as this value lives.
    pub(crate) fn new(write: WriteCallback, context: *mut c_void) -> CallbackWriter {
        CallbackWriter {
            write,
            context,
            failure: None,
        }
    }
}

impl Write for CallbackWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        // SAFETY: the callback and its context are valid for this call, and `buf` is
        // readable for `buf.len()` bytes, which is what the callback is promised.
        let put = unsafe { (self.write)(self.context, buf.as_ptr(), buf.len()) };
        if put < 0 {
            self.failure = Some(put);
            return Err(io::Error::other(format!("the writer aborted with {put}")));
        }
        let put = put as usize;
        if put > buf.len() {
            self.failure = Some(-1);
            return Err(io::Error::other(
                "the writer reported more bytes than it was offered",
            ));
        }
        Ok(put)
    }

    /// A no-op: the callback writes straight through, so there is nothing buffered here to
    /// push out, and claiming otherwise would promise a flush this layer cannot perform.
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Maps a refusal onto the one status a caller can branch on.
pub(crate) fn classify(error: &InboxError) -> i32 {
    match error {
        InboxError::Archive(_) => status::ARCHIVE,
        InboxError::Io(_) => status::IO,
        InboxError::OverBudget { .. } => status::OVER_BUDGET,
        InboxError::NoFreeName { .. } => status::NO_FREE_NAME,
        InboxError::Outside { .. } => status::OUTSIDE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_report_frame_starts_with_the_byte_total_and_then_the_names() {
        let frame = Report {
            bytes: 0x0102_0304_0506_0708,
            names: vec!["note.txt".into(), "photos/one.jpg".into()],
        }
        .encode();

        assert_eq!(&frame[..8], &[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(&frame[8..], b"note.txt\0photos/one.jpg\0");
    }

    #[test]
    fn an_empty_report_is_eight_zero_bytes_and_decodes_to_nothing() {
        let frame = Report::default().encode();

        assert_eq!(frame, vec![0u8; 8]);
        assert_eq!(Report::decode(&frame), Some(Report::default()));
    }

    #[test]
    fn a_frame_survives_a_round_trip_with_a_unicode_name() {
        let report = Report {
            bytes: 4096,
            names: vec!["假期/照片.jpg".into()],
        };

        assert_eq!(Report::decode(&report.encode()), Some(report));
    }

    #[test]
    fn a_frame_shorter_than_its_head_decodes_to_nothing() {
        assert_eq!(Report::decode(&[0u8; 7]), None);
        assert_eq!(Report::decode(&[]), None);
    }

    #[test]
    fn a_name_that_is_not_utf8_decodes_to_nothing() {
        let mut frame = Report::default().encode();
        frame.push(0xff);
        frame.push(0);

        assert_eq!(Report::decode(&frame), None);
    }

    #[test]
    fn every_refusal_has_its_own_status() {
        let cases: [(InboxError, i32); 5] = [
            (
                InboxError::Archive(opairdrop_cpio::Error::Truncated),
                status::ARCHIVE,
            ),
            (InboxError::Io(std::io::Error::other("disk")), status::IO),
            (InboxError::OverBudget { limit: 1 }, status::OVER_BUDGET),
            (
                InboxError::NoFreeName { name: "x".into() },
                status::NO_FREE_NAME,
            ),
            (InboxError::Outside { name: "x".into() }, status::OUTSIDE),
        ];

        for (error, expected) in cases {
            assert_eq!(classify(&error), expected, "{error:?}");
        }
    }

    #[test]
    fn a_reader_that_gives_up_is_not_mistaken_for_a_broken_archive() {
        let mut reader = CallbackReader {
            read: give_up,
            context: std::ptr::null_mut(),
            failure: None,
        };
        let mut sink = Vec::new();

        let failure = reader
            .read_to_end(&mut sink)
            .expect_err("a negative return is an error");

        assert_eq!(reader.failure, Some(-5));
        assert!(failure.to_string().contains("-5"), "{failure}");
    }

    #[test]
    fn a_reader_that_overfills_its_buffer_is_refused() {
        let mut reader = CallbackReader {
            read: overfill,
            context: std::ptr::null_mut(),
            failure: None,
        };
        let mut buffer = [0u8; 4];

        let failure = reader.read(&mut buffer).expect_err("a bare lie is caught");

        assert_eq!(reader.failure, Some(-1));
        assert!(failure.to_string().contains("more bytes"), "{failure}");
    }

    unsafe extern "C" fn give_up(_context: *mut c_void, _buf: *mut u8, _len: usize) -> isize {
        -5
    }

    unsafe extern "C" fn overfill(_context: *mut c_void, _buf: *mut u8, len: usize) -> isize {
        (len + 1) as isize
    }
}
