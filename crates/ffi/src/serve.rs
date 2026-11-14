//! The endpoint: one connection, from the first request byte to the last member on disk.
//!
//! # Why this is the export that makes the rest matter
//!
//! Everything below the socket was already here and already tested — the request framing in
//! [`opairdrop_http`], the answers in [`opairdrop_airdrop::payload`], the rules that turn an
//! untrusted archive into files in [`opairdrop_airdrop::inbox`] — but the only thing Android
//! could reach was [`crate::api::opairdrop_receive_archive`], which starts *after* the
//! request has been parsed. So the app had no way to answer a peer, and every setting that
//! describes how a peer is answered answered nothing: `device.name` was stored, validated,
//! drawn on screen, and never said to anyone. One export closes that gap.
//!
//! # Why one connection per call
//!
//! The caller owns the listening socket, because on Android that is where TLS and the
//! certificate live, and both are the caller's business rather than the protocol's. What
//! crosses is one accepted stream in each direction, and this returns when the peer closes
//! — or when the endpoint closes on purpose, which it does after an upload. A caller that
//! wants a second conversation accepts a second connection, exactly as the protocol says:
//! an archive is the last thing a sender has to say.
//!
//! # Why the answers come from the settings and not from the caller
//!
//! The name in the `/Discover` and `/Ask` answers is `device.name`, with the model as the
//! fallback the table already promises, and the model in the `/Ask` answer is `device.model`
//! falling back in turn to what the caller passes. Passing them as another argument would mean
//! two answers to one question — the row on the settings screen, and whatever the caller
//! decided to pass — and the screen would be the one that lost.
//!
//! The model used to be only an argument, on the grounds that it is the one thing here the
//! operating system knows and a row for it would invite a person to rename their phone. It is
//! still the one thing the operating system knows; what changed is that renaming it turns out
//! to be a reasonable thing to want. `ReceiverModelName` is display-only on both sides of the
//! protocol — no parser reads it, no sender branches on it — so what a person enters here
//! travels verbatim, and blank means the caller's own value, which is what keeps an untouched
//! row invisible. What must *not* follow it is anything a peer acts on: the capability blob in
//! the `/Discover` answer is a constant, and the certificate and the mDNS record are built by
//! the caller from the same name, not from this string.
//!
//! # Why a failed upload still leaves its files
//!
//! An inbox refusal (over budget, a name that resolves outside, a truncated archive) ends
//! the connection with a `500`, and the report comes back as that failure's message rather
//! than as the list of what landed. That is not a detail worth a second out-parameter: the
//! members are on disk either way — each is published whole or not at all — and a caller
//! that wants to show them has the directory. What it cannot recover from the disk is *why*
//! the transfer stopped, which is what the message carries.

use std::ffi::c_void;
use std::io;
use std::path::PathBuf;

use opairdrop_airdrop::inbox::{self, Error as InboxError, Limits};
use opairdrop_airdrop::payload::Ask;
use opairdrop_airdrop::server::{Error as EndpointError, Receiver, Server, Upload};
use opairdrop_airdrop::{AskAnswer, DiscoverAnswer};

use crate::api::{
    self, status, CallbackReader, CallbackWriter, ReadCallback, Report, WriteCallback,
};
use crate::buffer::{fill, OpairdropBuffer};
use crate::settings;

/// A receiver that answers from the settings and stores what arrives.
///
/// The trait's other three methods have defaults, and this type is the reason two of them
/// exist: `saw_ask` and `saw_sender` are for a caller that wants to *watch* a conversation
/// — a log, a notification, a progress bar. Nothing here watches. The offer it already
/// accepted is in the body it is about to read, and the sender's name is cosmetic.
/// Asks a person whether a transfer should be taken.
///
/// Called with a one-line summary (`sender<TAB>files<TAB>bytes<TAB>links`, UTF-8) after the
/// offer has been read and before `/Ask` is answered. A positive return accepts; zero
/// refuses; a negative value is the caller's own error code and is treated as a refusal,
/// because the only safe reading of "the prompt broke" is "do not take what nobody saw".
pub type DecideCallback =
    unsafe extern "C" fn(context: *mut c_void, summary: *const u8, len: usize) -> i32;

/// A [`DecideCallback`] with the context it was built for.
struct Decide {
    callback: DecideCallback,
    context: *mut c_void,
}

impl Decide {
    /// Asks, and reads everything but a positive answer as a refusal.
    fn take(&self, offer: &Ask) -> bool {
        let summary = summary_of(offer);
        // SAFETY: the caller of `opairdrop_serve_decided` promised the callback and its
        // context for the duration of the call, and the bytes outlive the invocation.
        let answer = unsafe { (self.callback)(self.context, summary.as_ptr(), summary.len()) };
        answer > 0
    }
}

/// The line a person gets to decide on: who, how many, how much.
///
/// Tab-separated rather than pretty-printed, because the caller renders it and knows the
/// language: three numbers and a name do not deserve a sentence assembled down here.
///
/// The fourth field is the link count, and it is not decoration. A share from Safari is a
/// links offer — `Files` empty, `Items` full — so a three-field summary describes it as
/// "0 files", which reads as "there is nothing to take" on the one prompt where the person
/// is deciding. A real peer asked exactly that way; the count alone could not say so.
fn summary_of(offer: &Ask) -> String {
    let bytes: u64 = offer.files.iter().filter_map(|file| file.size).sum();
    format!(
        "{}\t{}\t{}\t{}",
        offer.display_name(),
        offer.files.len(),
        bytes,
        offer.links.len()
    )
}

pub(crate) struct Store {
    /// What both answers call this device.
    name: String,
    /// What the `/Ask` answer calls the hardware. Not the same string as `name`: a person
    /// may rename the phone, and the peer shows the model beside the name it was given.
    model: String,
    /// Where members are written, already created by the inbox on the first upload.
    directory: PathBuf,
    /// Who to ask before taking a transfer, or `None` to take every one.
    decide: Option<Decide>,
    /// The budget and the collision rule, both from the settings.
    limits: Limits,
    /// What the connection has stored so far, in the frame a caller reads.
    landed: Report,
}

impl Store {
    /// What the connection left behind.
    pub(crate) fn landed(&self) -> &Report {
        &self.landed
    }
}

impl Receiver for Store {
    fn discover(&mut self) -> DiscoverAnswer {
        DiscoverAnswer::new(self.name.clone())
    }

    /// Late exactly when there is somebody to ask: an early answer is written before the
    /// offer is read, and a prompt that has not seen the offer cannot describe it.
    fn answers_ask_early(&self) -> bool {
        self.decide.is_none()
    }

    fn accept(&mut self, offer: &Ask) -> bool {
        match &self.decide {
            None => true,
            Some(decide) => decide.take(offer),
        }
    }

    fn ask(&mut self, _offer: Option<&Ask>) -> AskAnswer {
        AskAnswer::new(self.name.clone(), self.model.clone())
    }

    fn saw_ask(&mut self, _offer: &Ask) {}

    fn upload(&mut self, body: &mut dyn std::io::Read, _upload: &Upload<'_>) -> io::Result<()> {
        // The body is de-chunked but still wrapped — gzip or DVZip blocks — and the inbox
        // sniffs and unwraps it. Doing that here would mean two places that know what an
        // upload looks like.
        let received =
            inbox::receive_with(&self.directory, self.limits, body).map_err(io::Error::other)?;
        self.landed.bytes += received.bytes;
        self.landed.names.extend(received.names);
        Ok(())
    }
}

/// Serves one connection, taking every transfer without asking anybody.
///
/// The reference behaviour, and the one to use when there is nobody to ask: an endpoint
/// that answers `/Ask` before reading the offer cannot stall on a person.
///
/// # Safety
///
/// `directory` must point at `directory_len` bytes of UTF-8 readable for the duration;
/// `settings` and `model` must point at their stated lengths of readable bytes, or be null;
/// `read` and `write` must be valid function pointers whose `context` stays valid for the
/// duration; `report` must point at a zeroed [`OpairdropBuffer`] that outlives the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_serve(
    directory: *const u8,
    directory_len: usize,
    settings_text: *const u8,
    settings_len: usize,
    model: *const u8,
    model_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    // SAFETY: every pointer is promised by the caller for this call.
    unsafe {
        serve(
            directory,
            directory_len,
            settings_text,
            settings_len,
            model,
            model_len,
            read,
            write,
            context,
            None,
            report,
        )
    }
}

/// Serves one connection, asking `decide` before every transfer is taken.
///
/// The offer is read first and then shown to `decide`, because a prompt that names nothing
/// is a prompt nobody can answer: the endpoint reads the offer, hands over a one-line
/// summary, and answers `/Ask` with `200` or `503` according to the reply. A `decide` that
/// returns zero, or a callback that fails, is a refusal — the safe direction, since the
/// alternative is accepting what a person never saw.
///
/// This export exists beside [`opairdrop_serve`] rather than changing its signature, so a
/// caller built against the old one keeps working and keeps its early answers.
///
/// # Safety
///
/// As [`opairdrop_serve`], plus: `decide` must be a valid function pointer whose
/// `decide_context` stays valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_serve_decided(
    directory: *const u8,
    directory_len: usize,
    settings_text: *const u8,
    settings_len: usize,
    model: *const u8,
    model_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    decide: DecideCallback,
    decide_context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    // SAFETY: as above, plus the callback pair the caller promises.
    unsafe {
        serve(
            directory,
            directory_len,
            settings_text,
            settings_len,
            model,
            model_len,
            read,
            write,
            context,
            Some(Decide {
                callback: decide,
                context: decide_context,
            }),
            report,
        )
    }
}

/// Serves one connection: reads requests from `read`, writes answers to `write`.
///
/// `directory` is where an upload's members are written; it is created if it does not
/// exist. `settings` is the stored settings text — `key=value` per line — or null for the
/// table's defaults. `model` is what the operating system calls this hardware, or null for
/// nothing.
///
/// `report` receives a [`Report`] frame on success, or the failure's message as UTF-8 on
/// failure — the status says which. It must be zero-initialized and released with
/// [`crate::buffer::opairdrop_buffer_free`] once read.
///
/// # Safety
///
/// `directory` must point at `directory_len` bytes of UTF-8 readable for the duration;
/// `settings` and `model` must point at their stated lengths of readable bytes, or be null;
/// `read` and `write` must be valid function pointers whose `context` stays valid for the
/// duration; `report` must point at a zeroed [`OpairdropBuffer`] that outlives the call.
#[allow(clippy::too_many_arguments)]
unsafe fn serve(
    directory: *const u8,
    directory_len: usize,
    settings_text: *const u8,
    settings_len: usize,
    model: *const u8,
    model_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    decide: Option<Decide>,
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

    // SAFETY: `settings_text` is null or points at `settings_len` readable bytes, per the
    // contract. The null case is taken before `from_raw_parts`, which requires a non-null
    // pointer even for an empty slice.
    let settings_text = if settings_text.is_null() {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(settings_text, settings_len) }
    };
    let values = match settings::values_from(settings_text) {
        Ok(values) => values,
        Err(message) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, message.into_bytes()) };
            return status::SETTINGS;
        }
    };

    // SAFETY: as above, with `model` in place of the settings.
    let model = if model.is_null() {
        ""
    } else {
        let bytes = unsafe { std::slice::from_raw_parts(model, model_len) };
        match std::str::from_utf8(bytes) {
            Ok(text) => text,
            Err(_) => {
                // SAFETY: as above.
                unsafe { fill(report, b"the model name is not valid UTF-8".to_vec()) };
                return status::BAD_PATH;
            }
        }
    };

    let mut server = Server::new(Store {
        name: settings::name(&values, model),
        model: settings::model(&values, model),
        directory: PathBuf::from(directory),
        limits: settings::limits(&values),
        landed: Report::default(),
        decide,
    });

    // SAFETY: the caller guarantees `read`, `write` and `context` for the duration of this
    // call, which is the contract both adapters state.
    let mut reader = CallbackReader::new(read, context);
    let mut writer = CallbackWriter::new(write, context);

    let outcome = server.serve(&mut reader, &mut writer);
    match outcome {
        Ok(()) => {
            let frame = server.receiver().landed().encode();
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, frame) };
            status::OK
        }
        Err(failure) => {
            let code = classify(&failure);
            // SAFETY: as above.
            unsafe { fill(report, failure.to_string().into_bytes()) };
            code
        }
    }
}

/// The status that best describes why a connection ended badly.
///
/// An inbox refusal keeps its own code, because a caller can do something with it — say the
/// transfer was too large rather than that the network failed — while the three transport
/// failures share one, because their only remedy is to accept the next connection. The
/// downcast is how the inbox's refusal survives being wrapped in an [`io::Error`] by the
/// endpoint: the variant is the same value that was wrapped, not a copy of its text.
fn classify(failure: &EndpointError) -> i32 {
    match failure {
        EndpointError::Io(error) => match error
            .get_ref()
            .and_then(|inner| inner.downcast_ref::<InboxError>())
        {
            Some(refusal) => api::classify(refusal),
            None => status::TRANSPORT,
        },
        EndpointError::Http(_) | EndpointError::Protocol(_) => status::TRANSPORT,
    }
}

#[cfg(test)]
mod tests {
    use std::io::{Cursor, Read as _};

    use super::*;

    /// A connection held in memory: requests go in, answers come out.
    ///
    /// The callbacks are the real ones the C ABI takes, so a bug in the adapters shows up
    /// here rather than on a phone.
    struct Wire {
        requests: Cursor<Vec<u8>>,
        answers: Vec<u8>,
        /// What to answer on the write side instead of storing: a negative number, for the
        /// tests that need a caller to give up.
        refuse: Option<isize>,
    }

    impl Wire {
        fn new(requests: Vec<u8>) -> Wire {
            Wire {
                requests: Cursor::new(requests),
                answers: Vec::new(),
                refuse: None,
            }
        }
    }

    unsafe extern "C" fn read_wire(context: *mut c_void, buf: *mut u8, len: usize) -> isize {
        // SAFETY: every test passes the `Wire` it created as the context.
        let wire = unsafe { &mut *(context as *mut Wire) };
        let target = unsafe { std::slice::from_raw_parts_mut(buf, len) };
        match wire.requests.read(target) {
            Ok(got) => got as isize,
            Err(_) => -1,
        }
    }

    unsafe extern "C" fn write_wire(context: *mut c_void, buf: *const u8, len: usize) -> isize {
        // SAFETY: as above.
        let wire = unsafe { &mut *(context as *mut Wire) };
        if let Some(code) = wire.refuse {
            return code;
        }
        let source = unsafe { std::slice::from_raw_parts(buf, len) };
        wire.answers.extend_from_slice(source);
        len as isize
    }

    /// A directory of this test's own, removed when the test ends.
    fn scratch(name: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!("opairdrop-serve-{name}"));
        let _ = std::fs::remove_dir_all(&path);
        std::fs::create_dir_all(&path).expect("the scratch directory is creatable");
        path
    }

    /// Drives one conversation and returns the status, the buffer's bytes, and the answers.
    fn serve(directory: &str, settings: &str, model: &str, requests: Vec<u8>) -> (i32, Vec<u8>, Vec<u8>) {
        let mut wire = Wire::new(requests);
        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: the strings and the wire outlive the call, and `out` is a zeroed buffer
        // on this frame.
        let outcome = unsafe {
            opairdrop_serve(
                directory.as_ptr(),
                directory.len(),
                settings.as_ptr(),
                settings.len(),
                model.as_ptr(),
                model.len(),
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                &mut out,
            )
        };
        // SAFETY: the call above filled the buffer, one way or the other.
        let bytes = unsafe { out.as_slice() }.to_vec();
        // SAFETY: the buffer came from the call above.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
        (outcome, bytes, wire.answers)
    }

    /// What the test's own decision callback was told, and what it answered.
    struct Verdict {
        accept: bool,
        summary: String,
    }

    unsafe extern "C" fn decide_with(context: *mut c_void, summary: *const u8, len: usize) -> i32 {
        // SAFETY: every test passes the `Verdict` it created as the context.
        let verdict = unsafe { &mut *(context as *mut Verdict) };
        let bytes = unsafe { std::slice::from_raw_parts(summary, len) };
        verdict.summary = String::from_utf8_lossy(bytes).into_owned();
        if verdict.accept {
            1
        } else {
            0
        }
    }

    /// Like [`serve`], but through the export that asks first.
    fn serve_decided(
        directory: &str,
        settings: &str,
        model: &str,
        requests: Vec<u8>,
        accept: bool,
    ) -> (i32, Vec<u8>, String) {
        let mut wire = Wire::new(requests);
        let mut verdict = Verdict {
            accept,
            summary: String::new(),
        };
        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: the strings, the wire and the verdict outlive the call, `out` is zeroed.
        let outcome = unsafe {
            opairdrop_serve_decided(
                directory.as_ptr(),
                directory.len(),
                settings.as_ptr(),
                settings.len(),
                model.as_ptr(),
                model.len(),
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                decide_with,
                &mut verdict as *mut Verdict as *mut c_void,
                &mut out,
            )
        };
        // SAFETY: the call above filled the buffer, one way or the other.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
        (outcome, wire.answers, verdict.summary)
    }

    #[test]
    fn a_decided_endpoint_describes_the_offer_and_refuses_when_told_no() {
        let root = scratch("decided-no");
        let files = [opairdrop_airdrop::Item::new("a.pdf").with_size(7)];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("Ada 的 iPhone", "iPhone14,3", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &files,
            &[],
        );

        let (code, answers, summary) = serve_decided(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            false,
        );

        assert_eq!(code, status::OK, "a refusal is an answer, not a failure");
        assert!(
            String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 503"),
            "the peer is told, in the one place it is still listening"
        );
        assert_eq!(summary, "Ada 的 iPhone\t1\t7\t0");
    }

    #[test]
    fn a_decided_endpoint_takes_the_transfer_when_told_yes() {
        let root = scratch("decided-yes");
        let files = [opairdrop_airdrop::Item::new("a.pdf").with_size(7)];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("Mac", "MacBookPro18,3", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &files,
            &[],
        );

        let (code, answers, summary) = serve_decided(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            true,
        );

        assert_eq!(code, status::OK);
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 200"));
        assert_eq!(summary, "Mac\t1\t7\t0");
    }

    #[test]
    fn a_links_offer_says_links_rather_than_zero_files() {
        // The bug this guards: a Safari share offers no files, only `Items`. A three-field
        // summary counts `files` and reports zero, so the one prompt where the person decides
        // reads "0 files" — which says there is nothing to take. A real peer asked that way.
        let root = scratch("decided-links");
        let links = [
            "https://example.com/a".to_string(),
            "https://example.com/b".to_string(),
        ];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("链接探针", "iPhone18,1", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &[],
            &links,
        );

        let (_, _, summary) = serve_decided(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            false,
        );

        assert_eq!(summary, "链接探针\t0\t0\t2", "the link count is the fourth field");
    }

    #[test]
    fn the_model_row_travels_in_the_ask_answer() {
        let root = scratch("model-row");
        let files = [opairdrop_airdrop::Item::new("a.pdf").with_size(7)];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("Ada 的 iPhone", "iPhone14,3", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &files,
            &[],
        );
        let settings = format!(
            "{}\ndevice.model=iPhone 18\ndevice.name=书房\n",
            opairdrop_settings::HEAD
        );

        let (code, answers, _) = serve_decided(
            root.to_str().expect("UTF-8"),
            &settings,
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            true,
        );

        assert_eq!(code, status::OK);
        let answer = AskAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        // The row wins over the caller's model, and the name is the one the person typed
        // rather than the model standing in for it.
        assert_eq!(answer.model_name, "iPhone 18");
        assert_eq!(answer.computer_name, "书房");
    }

    /// The bytes of one request: a head with its `Content-Length`, then its body.
    fn post(path: &str, content_type: &str, body: &[u8]) -> Vec<u8> {
        let mut request = format!(
            "POST {path} HTTP/1.1\r\nHost: air\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\n\r\n",
            body.len()
        )
        .into_bytes();
        request.extend_from_slice(body);
        request
    }

    /// A one-file archive holding `name`, as the sender would frame it.
    fn archive(name: &str, payload: &[u8]) -> Vec<u8> {
        opairdrop_cpio::to_vec(
            &[opairdrop_cpio::Entry::file(name, payload.to_vec())],
            opairdrop_cpio::Format::Newc,
        )
        .expect("the fixture archive builds")
    }

    /// The body of the one answer in `answers`, past its blank line.
    ///
    /// One request in, one answer out, so there is no need to frame: the end of the buffer
    /// is the end of the body, and a second answer would mean the endpoint had answered a
    /// request nobody sent.
    fn body_of(answers: &[u8]) -> &[u8] {
        let split = answers
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .expect("an answer has a head and a blank line");
        &answers[split + 4..]
    }

    #[test]
    fn a_discovered_receiver_answers_with_the_name_it_was_given() {
        let root = scratch("name");
        let settings = "device.name=书房里的手机\n";

        let (code, _, answers) = serve(
            root.to_str().expect("UTF-8"),
            settings,
            "OPPO PJZ110",
            post("/Discover", "application/x-apple-binary-plist", &[]),
        );

        assert_eq!(code, status::OK);
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 200"));
        // Parsed rather than searched for: a bplist stores a non-ASCII string as UTF-16,
        // so a byte-window search would fail on exactly the case worth testing.
        let answer = DiscoverAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        assert_eq!(answer.computer_name, "书房里的手机");
        assert!(answer.airdropable, "a peer must be told this device accepts files");
    }

    #[test]
    fn an_empty_name_falls_back_to_the_model_as_the_table_promises() {
        let root = scratch("fallback");

        let (code, _, answers) = serve(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Discover", "application/x-apple-binary-plist", &[]),
        );

        assert_eq!(code, status::OK);
        let answer = DiscoverAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        assert_eq!(answer.computer_name, "OPPO PJZ110");
    }

    #[test]
    fn an_upload_lands_and_its_names_come_back_in_the_report() {
        let root = scratch("upload");
        let payload = vec![7u8; 4096];
        let body = archive("note.txt", &payload);

        let (code, report, answers) = serve(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Upload", "application/x-cpio", &body),
        );

        assert_eq!(code, status::OK);
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 200"));
        let report = Report::decode(&report).expect("a frame came back");
        assert_eq!(report.names, ["note.txt"]);
        assert_eq!(report.bytes, 4096);
        assert_eq!(
            std::fs::read(root.join("note.txt")).expect("the member is on disk"),
            payload
        );
    }

    #[test]
    fn an_upload_ends_the_connection_so_a_second_one_is_not_read() {
        let root = scratch("twice");
        let mut requests = post("/Upload", "application/x-cpio", &archive("a.txt", b"one"));
        requests.extend(post("/Upload", "application/x-cpio", &archive("b.txt", b"two")));

        let (code, report, _) = serve(root.to_str().expect("UTF-8"), "", "", requests);

        // The protocol's own rule, pinned here because it is easy to read the loop in
        // `Server::serve` as "keep going until the peer closes": an archive is the last
        // thing a sender has to say, so the first upload's `200` is followed by a close and
        // the bytes behind it are never parsed. A sender with a second transfer opens a
        // second connection.
        assert_eq!(code, status::OK);
        let report = Report::decode(&report).expect("a frame came back");
        assert_eq!(report.names, ["a.txt"]);
        assert_eq!(report.bytes, 3);
        assert!(root.join("b.txt").symlink_metadata().is_err());
    }

    #[test]
    fn a_member_that_is_not_an_archive_keeps_its_own_status() {
        let root = scratch("refused");

        // Not cpio, not gzip, not DVZip: the inbox refuses it, the endpoint answers `500`,
        // and the refusal has to survive being wrapped in an I/O error.
        let (code, message, answers) = serve(
            root.to_str().expect("UTF-8"),
            "",
            "",
            post("/Upload", "application/x-cpio", b"this is not an archive"),
        );

        assert_eq!(code, status::ARCHIVE, "{}", String::from_utf8_lossy(&message));
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 500"));
    }

    #[test]
    fn a_writer_that_gives_up_is_a_transport_failure_and_is_not_retried() {
        let root = scratch("writer");
        let mut wire = Wire::new(post("/Discover", "application/x-apple-binary-plist", &[]));
        wire.refuse = Some(-9);
        let mut out = OpairdropBuffer::EMPTY;
        let path = root.to_str().expect("UTF-8");

        // SAFETY: as in `serve` above, with a wire that refuses to be written to.
        let code = unsafe {
            opairdrop_serve(
                path.as_ptr(),
                path.len(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                0,
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                &mut out,
            )
        };

        assert_eq!(code, status::TRANSPORT);
        // SAFETY: the call above filled the buffer, one way or the other.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
    }

    #[test]
    fn an_empty_path_is_refused_before_any_stream_is_touched() {
        let mut wire = Wire::new(Vec::new());
        let mut out = OpairdropBuffer::EMPTY;
        let path = "";

        // SAFETY: the pointers are valid for the call; the empty path is what is refused.
        let code = unsafe {
            opairdrop_serve(
                path.as_ptr(),
                path.len(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                0,
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                &mut out,
            )
        };

        assert_eq!(code, status::BAD_PATH);
        // SAFETY: the call above filled the buffer, one way or the other.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
    }
}
