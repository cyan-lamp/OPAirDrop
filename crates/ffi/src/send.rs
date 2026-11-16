//! Outgoing transfers: one `/Discover`, one `/Ask`, one `/Upload` — on two connections.
//!
//! # Why two connections and not one
//!
//! A receiver reads **one request per connection**. `/Discover` therefore cannot be
//! followed by `/Ask` on the same socket: an iPhone answers the first and then stops
//! reading, leaving the socket open and saying nothing — no status, no reset, no byte — and
//! a sender has no way to tell that apart from a receiver that went to sleep. `/Ask` on a
//! connection of its own is answered `200` with a session, and the phone shows its prompt.
//!
//! So a caller of this module supplies three things about the wire rather than one: a read
//! callback, a write callback, and a [`DialCallback`] that opens a second connection and
//! re-points the first two at it. The library calls it once, between `/Discover` and
//! `/Ask`, and never again — `/Upload` belongs to the `/Ask` connection, because those two
//! are one conversation.
//!
//! # Why the archive is built while it is sent
//!
//! A share of a 4 GB video cannot be assembled in memory. Here the cpio writer pushes into
//! a DVZip encoder, the encoder pushes into the caller's stream, and memory is one block per
//! layer no matter how large the transfer is.
//!
//! # Why the conversation is three calls and not one
//!
//! `/Discover` learns the peer's name and what it says about itself. It is **not** a
//! permission check and its `IsAirDropable` is not a verdict: a phone set to everyone
//! answers without the key at all, and treating that silence as "no" is what once stopped
//! this sender one request short of the only request that matters. `/Ask` is the prompt —
//! the one place a person answers — and `/Upload` is the archive.
//!
//! # Why progress is counted off disk
//!
//! DVZip compresses, so bytes handed to the encoder and bytes on the wire disagree by
//! whatever the data happens to compress to. A ring driven by either would be describing the
//! encoder rather than the transfer, and would jump. The meter sits on the file reader, where
//! the number means "this much of what the person chose has been taken".

use std::ffi::c_void;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::PathBuf;

use opairdrop_airdrop::client::Error as ClientError;
use opairdrop_airdrop::{Client, Dial, Item, Sender, TransferId, PATH_ASK};
use opairdrop_cpio::{dvzip, Builder, Format, MODE_DIRECTORY, MODE_REGULAR};

use crate::api::{
    status, CallbackReader, CallbackWriter, DialCallback, ReadCallback, WriteCallback,
};
use crate::buffer::{fill, OpairdropBuffer};

/// Which part of the conversation is running, as the numbers a caller can switch on.
pub mod phase {
    /// Connected, and `/Discover` has answered: the peer exists and takes files.
    pub const READY: i32 = 0;
    /// `/Ask` is on its way and the answer is not back yet. This is the interval the other
    /// phone spends showing its accept prompt, and the only honest thing to say is "waiting".
    pub const WAITING: i32 = 1;
    /// The peer accepted; bytes are moving.
    pub const SENDING: i32 = 2;
    /// The archive is in and the peer said so.
    pub const DONE: i32 = 3;
}

/// Reports how far the transfer has got: which phase, and how many bytes off disk.
///
/// Called on the sending thread. [`phase::READY`], [`phase::WAITING`] and [`phase::DONE`]
/// arrive once each and carry the byte count as it stands; [`phase::SENDING`] arrives at most
/// once per [`REPORT_EVERY`] bytes.
pub type ProgressCallback =
    unsafe extern "C" fn(context: *mut c_void, phase: i32, sent: u64, total: u64);

/// Reports the peer's own name, once, as soon as `/Discover` answers.
///
/// Separate from [`ProgressCallback`] because a name is a string, not a phase: folding it into
/// the byte counts would either change that callback's signature (and every caller of the C
/// ABI) or smuggle text through numbers. The bytes are UTF-8 and borrowed for the call only.
pub type NameCallback =
    unsafe extern "C" fn(context: *mut c_void, name: *const u8, name_len: usize);

/// How much may pass between two reports.
///
/// 512 KiB: small enough that a 1 GB video moves visibly several times a second, large
/// enough that a hundred small files do not spend the transfer inside JNI.
const REPORT_EVERY: u64 = 512 * 1024;

/// A [`ProgressCallback`] with the context it was built for.
struct Meter {
    callback: ProgressCallback,
    context: *mut c_void,
    /// Optional peer-name callback. `None` for callers that only want byte counts.
    name_callback: Option<NameCallback>,
    /// Bytes taken off disk so far.
    sent: std::cell::Cell<u64>,
    /// Bytes the person chose, from the file sizes.
    total: u64,
    /// What `sent` was when it last reported.
    last: std::cell::Cell<u64>,
}

impl Meter {
    fn advance(&self, bytes: u64) {
        let sent = self.sent.get() + bytes;
        self.sent.set(sent);
        if sent.saturating_sub(self.last.get()) >= REPORT_EVERY || sent >= self.total {
            self.last.set(sent);
            self.say(phase::SENDING);
        }
    }

    /// Announces a phase with the byte count as it stands.
    fn say(&self, phase: i32) {
        // SAFETY: the caller of `opairdrop_send_with_progress` promised the callback and its
        // context for the duration of the call.
        unsafe { (self.callback)(self.context, phase, self.sent.get(), self.total) };
    }

    /// Announces the peer's name. Called once, right after `/Discover` answers.
    fn say_name(&self, name: &str) {
        if let Some(callback) = self.name_callback {
            // SAFETY: `name` borrows a live `String` for the duration of this call, and the
            // context is the same one promised for the byte callback.
            unsafe { callback(self.context, name.as_ptr(), name.len()) };
        }
    }
}

/// A file read through a [`Meter`].
struct Counted<'a> {
    file: File,
    meter: &'a Meter,
}

impl Read for Counted<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let got = self.file.read(buffer)?;
        self.meter.advance(got as u64);
        Ok(got)
    }
}

/// What a transfer that went through reports.
struct Sent {
    /// The receiver's own name. A prompt the person saw named the peer; so does the report.
    peer: String,
    /// Payload bytes the archive carried, which is what `/Ask` offered.
    bytes: u64,
}

/// A reason to stop, with the status the caller branches on.
#[derive(Debug)]
struct Failure {
    status: i32,
    message: String,
}

impl Failure {
    fn new(status: i32, message: impl Into<String>) -> Failure {
        Failure {
            status,
            message: message.into(),
        }
    }

    fn transport(message: impl std::fmt::Display) -> Failure {
        Failure::new(status::TRANSPORT, message.to_string())
    }
}

/// Sends `paths` to the peer on the far end of the connection, taking every offer.
///
/// `settings` is the stored settings text, the same bytes [`crate::settings`] hands the
/// receive side. Only `http.response_limit` is read here, but it is passed as the whole text
/// rather than as the one number: the sender will want a second row eventually, and a
/// boundary that carries one integer would have to grow a second parameter to do it.
///
/// `sender_id` is the `SenderID` this device goes by, and it is a parameter of its own rather
/// than a second life for `transfer_id`: the transfer id names one transfer, this names the
/// sender, and it has to be the **same string on every transfer** or a receiver sees a new
/// device each time. The caller derives it from its own identity so it survives restarts
/// without being stored separately.
///
/// # Safety
///
/// `host` must point at `host_len` bytes of UTF-8 readable for the duration; `settings`,
/// `sender_name`, `model`, `sender_id` and `transfer_id` likewise at their stated lengths, or
/// be null where optional; `paths` must point at `paths_len` bytes of NUL-separated UTF-8; `read` and `write`
/// must be valid function pointers whose `context` stays valid, and `dial` one that opens a
/// second connection and re-points them at it; `report` must point at a zeroed
/// [`OpairdropBuffer`] that outlives the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_send(
    host: *const u8,
    host_len: usize,
    settings: *const u8,
    settings_len: usize,
    sender_name: *const u8,
    sender_name_len: usize,
    model: *const u8,
    model_len: usize,
    sender_id: *const u8,
    sender_id_len: usize,
    transfer_id: *const u8,
    transfer_id_len: usize,
    paths: *const u8,
    paths_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    dial: DialCallback,
    context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    // SAFETY: every pointer below is promised by the caller for this call.
    unsafe {
        drive(
            host,
            host_len,
            settings,
            settings_len,
            sender_name,
            sender_name_len,
            model,
            model_len,
            sender_id,
            sender_id_len,
            transfer_id,
            transfer_id_len,
            paths,
            paths_len,
            read,
            write,
            Some(dial),
            context,
            None,
            report,
        )
    }
}

/// Sends `paths`, reporting progress as the files are read.
///
/// # Safety
///
/// As [`opairdrop_send`], plus: `progress` must be a valid function pointer whose
/// `progress_context` stays valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_send_with_progress(
    host: *const u8,
    host_len: usize,
    settings: *const u8,
    settings_len: usize,
    sender_name: *const u8,
    sender_name_len: usize,
    model: *const u8,
    model_len: usize,
    sender_id: *const u8,
    sender_id_len: usize,
    transfer_id: *const u8,
    transfer_id_len: usize,
    paths: *const u8,
    paths_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    dial: DialCallback,
    context: *mut c_void,
    progress: ProgressCallback,
    progress_context: *mut c_void,
    peer_name: Option<NameCallback>,
    report: *mut OpairdropBuffer,
) -> i32 {
    let gauge = Meter {
        callback: progress,
        context: progress_context,
        name_callback: peer_name,
        sent: std::cell::Cell::new(0),
        total: 0,
        last: std::cell::Cell::new(0),
    };
    // SAFETY: as above, plus the callback pair the caller promises.
    unsafe {
        drive(
            host,
            host_len,
            settings,
            settings_len,
            sender_name,
            sender_name_len,
            model,
            model_len,
            sender_id,
            sender_id_len,
            transfer_id,
            transfer_id_len,
            paths,
            paths_len,
            read,
            write,
            Some(dial),
            context,
            Some(gauge),
            report,
        )
    }
}

/// Both exports, once the arguments are pointers and the difference is one option.
///
/// # Safety
///
/// As [`opairdrop_send`]; `meter` must be `None` or a meter whose callback is valid.
#[allow(clippy::too_many_arguments)]
unsafe fn drive(
    host: *const u8,
    host_len: usize,
    settings: *const u8,
    settings_len: usize,
    sender_name: *const u8,
    sender_name_len: usize,
    model: *const u8,
    model_len: usize,
    sender_id: *const u8,
    sender_id_len: usize,
    transfer_id: *const u8,
    transfer_id_len: usize,
    paths: *const u8,
    paths_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    dial: Option<DialCallback>,
    context: *mut c_void,
    meter: Option<Meter>,
    report: *mut OpairdropBuffer,
) -> i32 {
    if report.is_null() || host.is_null() || paths.is_null() || transfer_id.is_null() {
        return status::NULL_ARGUMENT;
    }

    // SAFETY: every region is promised by the caller, and each null case is taken before the
    // corresponding `from_raw_parts`.
    let parsed = unsafe {
        (|| -> Result<(&str, &str, Sender, Vec<PathBuf>, usize), Failure> {
            let host = text(host, host_len)?;
            let transfer_text = text(transfer_id, transfer_id_len)?;
            // No settings text at all is "nothing configured", which is every default. Text
            // that is there but does not parse is refused instead: the person edited a row,
            // and a send that silently ignored it is the one outcome that leaves them no way
            // to find out.
            let settings = text_or(settings, settings_len, "");
            let limit = match crate::settings::values_from(settings.as_bytes()) {
                Ok(values) => crate::settings::response_limit(&values),
                Err(message) => return Err(Failure::new(status::SETTINGS, message)),
            };
            let sender = Sender::new(
                text_or(sender_name, sender_name_len, ""),
                text_or(model, model_len, ""),
                sender_identity(text_or(sender_id, sender_id_len, ""), transfer_text),
            );
            let paths = path_list(text(paths, paths_len)?)?;
            Ok((host, transfer_text, sender, paths, limit))
        })()
    };
    let (host, transfer_text, sender, paths, limit) = match parsed {
        Ok(parsed) => parsed,
        Err(failure) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, failure.message.into_bytes()) };
            return failure.status;
        }
    };
    let Some(id) = parse_transfer_id(transfer_text) else {
        // SAFETY: as above.
        unsafe { fill(report, b"the transfer id is not 32 hexadecimal digits".to_vec()) };
        return status::BAD_PATH;
    };

    // The total is known before anything is read: it is what `/Ask` offers, and a ring needs
    // its denominator before the first report.
    let mut meter = meter;
    if let Some(gauge) = &mut meter {
        gauge.total = paths
            .iter()
            .filter_map(|path| fs::metadata(path).ok())
            .map(|metadata| metadata.len())
            .sum();
    }

    // SAFETY: the caller guarantees the callbacks and their context for the duration.
    let reader = CallbackReader::new(read, context);
    let writer = CallbackWriter::new(write, context);

    // The client is built here rather than inside `transfer` so that the two settings that
    // shape it — the `Host` header and the row `http.response_limit` — are applied in the same
    // scope that read them. A wire that is handed in already limited cannot be raised by
    // accident downstream.
    let mut client = Client::new(
        Wire {
            reader,
            writer,
            reopen: dial.map(|callback| (callback, context)),
        },
        host,
    )
    .with_limit(limit);
    match transfer(&mut client, &sender, &id, &paths, meter.as_ref()) {
        Ok(sent) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, frame(&sent)) };
            status::OK
        }
        Err(failure) => {
            // SAFETY: as above.
            unsafe { fill(report, failure.message.into_bytes()) };
            failure.status
        }
    }
}

/// Which string the peer will file this device under.
///
/// `SenderID` is not the transfer id, and handing one over for the other is the mistake the
/// separate parameter exists to stop. A receiver groups a sender's activity by this string, so
/// a value drawn per transfer reads as a brand new device every time — the caller derives it
/// from its own identity instead (see `Native.send`), which is also why it arrives as an
/// argument: the derivation needs the certificate, and the certificate is the caller's.
///
/// A caller that supplies nothing still gets a body a receiver can read, with the transfer id
/// standing in. Nothing is grouped across transfers then, which is the degradation and not a
/// malformed offer: the field is free-form text in the plist, so a value that happens to be the
/// transfer id is still a well-formed one.
fn sender_identity<'a>(sender_id: &'a str, transfer_id: &'a str) -> &'a str {
    if sender_id.is_empty() {
        transfer_id
    } else {
        sender_id
    }
}

/// The conversation itself, once the arguments are strings and paths.
///
/// The stream arrives already limited: `http.response_limit` is applied by the caller, on the
/// same line that reads it, so there is exactly one place where that row becomes a number.
fn transfer(
    client: &mut Client<Wire>,
    sender: &Sender,
    id: &TransferId,
    paths: &[PathBuf],
    meter: Option<&Meter>,
) -> Result<Sent, Failure> {
    let files = describe(paths)?;
    let items: Vec<Item> = files.iter().map(|(item, _)| item.clone()).collect();
    let total = opairdrop_airdrop::offered_bytes(&items);

    // `/Discover` first, for the name and for what the peer says it can take. Fatal only if
    // it fails outright: a `200` whose boolean says no is not a failure, and `/Ask` below is
    // still the request that makes the other phone show a prompt.
    let discovered = client.discover(sender).map_err(describe_error)?;
    // `/Discover` carries the name a person reads (ReceiverComputerName). The mDNS instance
    // name is a hash on current iPhones, so this is the first moment the UI can show "青"
    // instead of `a651a674f3f5`. Report it before the phase so the name is already there when
    // the "connected" state paints. `/Ask` carries the same field as a fallback for receivers
    // that leave it blank here, reported after the answer below.
    let peer_name = if discovered.computer_name.is_empty() {
        None
    } else {
        Some(discovered.computer_name.clone())
    };
    if let Some(gauge) = meter {
        if let Some(name) = &peer_name {
            gauge.say_name(name);
        }
        gauge.say(phase::READY);
    }

    // The discovery connection is spent: a receiver reads one request per connection, so
    // `/Ask` has to arrive on one of its own. The phone does not refuse the second request
    // — it answers nothing at all, which from here is indistinguishable from a receiver that
    // went to sleep, and cost this sender forty-five seconds of silence per attempt. See
    // `Client::renew`.
    client.renew().map_err(describe_error)?;

    if let Some(gauge) = meter {
        gauge.say(phase::WAITING);
    }
    let asked = client.ask(sender, id, &items, &[]).map_err(describe_error)?;

    let peer = match peer_name {
        Some(name) => name,
        None => {
            let fallback = asked.computer_name.clone();
            if let Some(gauge) = meter {
                gauge.say_name(&fallback);
            }
            fallback
        }
    };
    if let Some(gauge) = meter {
        gauge.say(phase::SENDING);
    }


    client
        .upload(id, total, |out| archive(out, &files, meter))
        .map_err(describe_error)?;

    if let Some(gauge) = meter {
        // The last report: a ring that stops at 97% because the final block was small is a
        // ring that makes people think it failed.
        gauge.say(phase::DONE);
    }

    Ok(Sent { peer, bytes: total })
}

/// One file as `/Ask` describes it, paired with where its bytes are.
fn describe(paths: &[PathBuf]) -> Result<Vec<(Item, PathBuf)>, Failure> {
    let mut files = Vec::with_capacity(paths.len());
    for path in paths {
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .ok_or_else(|| {
                Failure::new(
                    status::BAD_PATH,
                    format!("{} has no name that can be sent", path.display()),
                )
            })?
            .to_string();
        let metadata = fs::metadata(path)
            .map_err(|error| Failure::new(status::IO, format!("{}: {error}", path.display())))?;
        if !metadata.is_file() {
            return Err(Failure::new(
                status::BAD_PATH,
                format!("{} is not a regular file", path.display()),
            ));
        }
        let item = Item::new(name.clone())
            .with_size(metadata.len())
            .with_file_type(file_type(&name));
        files.push((item, path.clone()));
    }
    Ok(files)
}

/// The archive the upload carries: cpio per member, DVZip per block.
///
/// Three parts, in the order the reference sender writes them: the `.` directory member
/// that is the destination folder, one regular-file member per offer, then the trailer. The
/// first part is not decoration — it is the only thing in the archive that says where the
/// members go, and an archive that opens with a file describes a file with no folder around
/// it.
fn archive(
    out: &mut dyn Write,
    files: &[(Item, PathBuf)],
    meter: Option<&Meter>,
) -> io::Result<()> {
    // Odc is what the protocol's upload section asks a sender to produce.
    let mut builder = Builder::new(dvzip::Encoder::new(out), Format::Odc);
    // An empty `.` member: a directory with no contents costs a header and a name byte, and
    // it is what makes the members after it land in one folder rather than at the root.
    builder
        .add(".", MODE_DIRECTORY, &[])
        .map_err(io::Error::other)?;
    for (item, path) in files {
        let size = item.size.unwrap_or(0);
        // The member name is the offer's own `FileBomPath`, `./` and all. UP-7 lets a
        // receiver drop a member it was never offered, and "offered" means the string that
        // went out in `/Ask` — so normalising the path here, which reads like tidying,
        // turns every member into a file that arrives as nothing. `Item::bom_path` answers
        // with the same string the offer used, by construction.
        let member = item.bom_path();
        let file = File::open(path)
            .map_err(|error| io::Error::new(error.kind(), format!("{}: {error}", path.display())))?;
        match meter {
            Some(gauge) => {
                let mut counted = Counted { file, meter: gauge };
                builder
                    .add_reader(&member, MODE_REGULAR, size, &mut counted)
                    .map_err(io::Error::other)?;
            }
            None => {
                let mut plain = file;
                builder
                    .add_reader(&member, MODE_REGULAR, size, &mut plain)
                    .map_err(io::Error::other)?;
            }
        }
    }
    let mut encoder = builder.finish().map_err(io::Error::other)?;
    // Without this the last partial block never leaves the encoder.
    encoder.finish()
}

/// The Apple UTI for a file name.
fn file_type(name: &str) -> &'static str {
    let extension = name
        .rsplit_once('.')
        .map(|(_, extension)| extension.to_ascii_lowercase());
    match extension.as_deref() {
        Some("jpg") | Some("jpeg") => "public.jpeg",
        Some("png") => "public.png",
        Some("heic") | Some("heif") => "public.heic",
        Some("gif") => "com.compuserve.gif",
        Some("mov") => "com.apple.quicktime-movie",
        Some("mp4") | Some("m4v") => "public.mpeg-4",
        Some("pdf") => "com.adobe.pdf",
        Some("txt") => "public.plain-text",
        Some("zip") => "public.zip-archive",
        _ => opairdrop_airdrop::payload::DEFAULT_FILE_TYPE,
    }
}

/// A transfer id from the caller's 32 hexadecimal digits.
///
/// Dashes are accepted and ignored, so a caller can pass `UUID.toString()` unchanged.
fn parse_transfer_id(text: &str) -> Option<TransferId> {
    let mut bytes = [0u8; 16];
    let mut index = 0;
    let mut digits = 0;
    let mut high: Option<u8> = None;
    for character in text.chars() {
        if character == '-' {
            continue;
        }
        let value = character.to_digit(16)? as u8;
        match high {
            None => high = Some(value),
            Some(first) => {
                if index == bytes.len() {
                    return None;
                }
                bytes[index] = (first << 4) | value;
                index += 1;
                digits += 2;
                high = None;
            }
        }
    }
    if high.is_some() || digits != 32 {
        return None;
    }
    Some(TransferId::from_bytes16(&bytes))
}

/// Renders what a finished transfer reports: tab-separated `key value` lines.
fn frame(sent: &Sent) -> Vec<u8> {
    let peer = sent.peer.replace(['\n', '\t'], " ");
    format!("peer\t{peer}\nbytes\t{}\n", sent.bytes).into_bytes()
}

/// Turns a client error into a status and a message.
///
/// A `503` on `/Ask` is the one answer that means *the person on the other end said no*, or
/// rather that there is no person to ask: it is what a phone set to "contacts only" tells an
/// anonymous sender, and it arrives instead of a prompt. Everything else is a transport
/// failure and is reported as one.
fn describe_error(error: ClientError) -> Failure {
    match &error {
        ClientError::Refused {
            path, status: 503, ..
        } if *path == PATH_ASK => {
            Failure::new(status::REFUSED, "the peer is not accepting files")
        }
        _ => Failure::transport(error),
    }
}

/// Asks a peer for its own name, and nothing else.
///
/// # Why a separate export
///
/// The device list needs the name a person reads before anyone taps a tile. That name lives
/// only in the `/Discover` answer, which normally arrives as the first step of a transfer —
/// but a transfer also promises `/Ask` and `/Upload`, and those need a second connection and
/// a person's decision. This export stops after the first request: one TLS connection (made
/// by the caller, which owns the pinning), one `/Discover`, one answer, done. No prompt
/// appears on the peer — `/Ask` is the only request that shows one.
///
/// The answer is written to `report` as the bare UTF-8 name on success; on failure, as the
/// message, with a negative status. An empty name is a valid answer (some receivers leave
/// the field blank) and still returns `OK`.
///
/// # Safety
///
/// `report`, `host` and the sender strings must be valid for their lengths; `read`/`write`
/// must be a callback pair valid for `context` for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_discover_name(
    host: *const u8,
    host_len: usize,
    settings: *const u8,
    settings_len: usize,
    sender_name: *const u8,
    sender_name_len: usize,
    model: *const u8,
    model_len: usize,
    sender_id: *const u8,
    sender_id_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    if report.is_null() || host.is_null() {
        return status::NULL_ARGUMENT;
    }
    // SAFETY: the caller promises the regions below for their lengths.
    let parsed = unsafe {
        (|| -> Result<(&str, Sender, usize), Failure> {
            let host = text(host, host_len)?;
            let settings = text_or(settings, settings_len, "");
            let limit = match crate::settings::values_from(settings.as_bytes()) {
                Ok(values) => crate::settings::response_limit(&values),
                Err(message) => return Err(Failure::new(status::SETTINGS, message)),
            };
            let sender = Sender::new(
                text_or(sender_name, sender_name_len, ""),
                text_or(model, model_len, ""),
                // No transfer id here — this request is not part of one, so the sender id
                // stands on its own (see sender_identity's rule for the empty case).
                text_or(sender_id, sender_id_len, ""),
            );
            Ok((host, sender, limit))
        })()
    };
    let (host, sender, limit) = match parsed {
        Ok(parsed) => parsed,
        Err(failure) => {
            unsafe { fill(report, failure.message.into_bytes()) };
            return failure.status;
        }
    };

    // The callbacks and their context are promised valid for this call by the caller.
    let reader = CallbackReader::new(read, context);
    let writer = CallbackWriter::new(write, context);
    // No `reopen`: `/Discover` is one request on one connection. A phone that answers it and
    // stops reading is exactly why the transfer path renews — but there is no follow-up here.
    let mut client = Client::new(
        Wire { reader, writer, reopen: None },
        host,
    )
    .with_limit(limit);
    match client.discover(&sender) {
        Ok(answer) => {
            unsafe { fill(report, answer.computer_name.into_bytes()) };
            status::OK
        }
        Err(error) => {
            let failure = describe_error(error);
            unsafe { fill(report, failure.message.into_bytes()) };
            failure.status
        }
    }
}

/// One connection as a single `Read + Write`, which is what [`Client`] takes.
struct Wire {
    reader: CallbackReader,
    writer: CallbackWriter,
    /// How a second connection is obtained, and the context the callback was built for. The
    /// same context the reader and writer use: what changes is which socket it points at.
    reopen: Option<(DialCallback, *mut c_void)>,
}

impl Read for Wire {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.reader.read(buffer)
    }
}

impl Write for Wire {
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.writer.write(buffer)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.writer.flush()
    }
}

impl Dial for Wire {
    fn dial(&mut self) -> io::Result<()> {
        let Some((callback, context)) = self.reopen else {
            // Not a fault in the peer: this call was made by a caller that has no way to
            // open a second connection, and the request that follows cannot be sent. Saying
            // so is better than writing `/Ask` onto a socket its receiver has already
            // finished with and waiting for the silence that produces.
            return Err(io::Error::other(
                "no second connection: this caller cannot renew the stream",
            ));
        };
        // SAFETY: the caller of the send export promised this callback and its context for
        // the duration of the call.
        let code = unsafe { callback(context) };
        if code < 0 {
            return Err(io::Error::other(format!(
                "the caller could not open a second connection ({code})"
            )));
        }
        Ok(())
    }
}

/// Reads a promised region as UTF-8.
///
/// # Safety
///
/// `pointer` must point at `len` readable bytes.
unsafe fn text<'a>(pointer: *const u8, len: usize) -> Result<&'a str, Failure> {
    // SAFETY: the caller guarantees the region.
    let bytes = unsafe { std::slice::from_raw_parts(pointer, len) };
    std::str::from_utf8(bytes)
        .map_err(|_| Failure::new(status::BAD_PATH, "an argument is not valid UTF-8"))
}

/// Reads an optional region as UTF-8, answering `fallback` for a null pointer.
///
/// # Safety
///
/// `pointer` must be null, or point at `len` readable bytes.
unsafe fn text_or(pointer: *const u8, len: usize, fallback: &str) -> &str {
    if pointer.is_null() {
        return fallback;
    }
    // SAFETY: guaranteed by the caller for the non-null case.
    unsafe { text(pointer, len) }.unwrap_or(fallback)
}

/// Splits the NUL-separated path list.
///
/// NUL separates because a path cannot contain one — the same framing the report uses for
/// names.
fn path_list(text: &str) -> Result<Vec<PathBuf>, Failure> {
    let paths: Vec<PathBuf> = text
        .split('\0')
        .filter(|path| !path.is_empty())
        .map(PathBuf::from)
        .collect();
    if paths.is_empty() {
        return Err(Failure::new(status::BAD_PATH, "no files were given"));
    }
    Ok(paths)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_uuid_with_dashes_is_the_same_id_as_without() {
        let dashed = parse_transfer_id("1B4E28BA-2FA1-11D2-883F-0016D3CCA427").unwrap();
        let plain = parse_transfer_id("1b4e28ba2fa111d2883f0016d3cca427").unwrap();
        assert_eq!(dashed.as_str(), plain.as_str());
        assert_eq!(dashed.as_str(), "1B4E28BA-2FA1-41D2-883F-0016D3CCA427");
    }

    #[test]
    fn short_and_non_hex_ids_are_refused() {
        assert!(parse_transfer_id("1B4E28BA").is_none());
        assert!(parse_transfer_id("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ").is_none());
        assert!(parse_transfer_id("").is_none());
    }

    #[test]
    fn the_sender_id_is_kept_apart_from_the_transfer_id() {
        // A `SenderID` drawn per transfer is what this separation exists to prevent: the
        // receiver files two offers from the same phone under two devices.
        let identity = "1F0E2C34-AAAA-4BBB-8CCC-0123456789AB";
        let transfer = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
        assert_eq!(sender_identity(identity, transfer), identity);
    }

    #[test]
    fn a_caller_without_a_sender_id_still_offers_a_readable_body() {
        let transfer = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
        assert_eq!(sender_identity("", transfer), transfer);
    }

    #[test]
    fn known_extensions_get_an_apple_type_and_others_get_the_default() {
        assert_eq!(file_type("IMG_0001.JPG"), "public.jpeg");
        assert_eq!(file_type("clip.mov"), "com.apple.quicktime-movie");
        assert_eq!(
            file_type("no-extension"),
            opairdrop_airdrop::payload::DEFAULT_FILE_TYPE
        );
    }

    #[test]
    fn the_path_list_keeps_order_and_drops_empty_entries() {
        let paths = path_list("/a/one\0\0/b/two").unwrap();
        assert_eq!(paths, vec![PathBuf::from("/a/one"), PathBuf::from("/b/two")]);
        assert!(path_list("").is_err());
    }

    #[test]
    fn the_archive_holds_the_files_it_offered() {
        let root = std::env::temp_dir().join(format!("opairdrop-send-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();

        let note = root.join("note.txt");
        let photo = root.join("photo.jpg");
        std::fs::write(&note, b"hello").unwrap();
        // Larger than one DVZip block, so the archive crosses a block boundary.
        std::fs::write(&photo, vec![7u8; 200_000]).unwrap();

        let files = describe(&[note.clone(), photo.clone()]).unwrap();
        assert_eq!(files[0].0.name, "note.txt");
        assert_eq!(files[0].0.file_type, "public.plain-text");
        // The offer announces `./note.txt` and the archive carries `./note.txt`: not the
        // normalised `note.txt`, which is what a receiver matching members against the
        // offer would have found nothing for.
        assert_eq!(files[0].0.bom_path, "./note.txt");
        assert_eq!(files[0].0.bom_path(), "./note.txt");
        assert_eq!(files[0].0.size, Some(5));
        assert_eq!(files[1].0.size, Some(200_000));

        let mut body = Vec::new();
        archive(&mut body, &files, None).unwrap();

        let mut plain = Vec::new();
        dvzip::Decoder::new(&body[..])
            .read_to_end(&mut plain)
            .unwrap();

        // The archive on the wire, read before any of our own parsing normalises it: the
        // first member is the `040755` directory `.`, and each file member carries the
        // offer's path with its `./` still on the front.
        assert_eq!(&plain[0..6], b"070707", "the first member is an odc header");
        assert_eq!(&plain[18..24], b"040755", "the first member is a directory");
        assert_eq!(&plain[76..78], b".\0", "and it is named `.`");
        assert!(
            plain.windows(11).any(|window| window == b"./note.txt\0"),
            "the member name is the `FileBomPath` the offer announced"
        );

        // The file member's own type bits. This is the one field a receiver classifies a
        // member by, and getting it wrong costs the whole transfer: `010644` is a named
        // pipe, so an iPhone takes the upload, counts the bytes, then resets the
        // connection when the archive reader reaches this header.
        let name_at = plain
            .windows(11)
            .position(|window| window == b"./note.txt\0")
            .expect("the file member");
        let head_at = name_at - 76;
        assert_eq!(&plain[head_at..head_at + 6], b"070707");
        assert_eq!(
            &plain[head_at + 18..head_at + 24],
            b"100644",
            "a regular file is 0100644 in octal"
        );

        let entries = opairdrop_cpio::read(&plain).unwrap();
        let names: Vec<&str> = entries.iter().map(|entry| entry.name.as_str()).collect();
        // What a receiver lands after sanitising: the `.` folder is the destination itself,
        // so it has nowhere to go, and `./x` becomes `x` inside that folder.
        assert_eq!(names, vec!["note.txt", "photo.jpg"]);
        assert_eq!(entries[0].data, b"hello");
        assert_eq!(entries[1].data.len(), 200_000);

        let _ = std::fs::remove_dir_all(&root);
    }

    /// Several members in one archive, which is the shape the picker's
    /// `SEND_MULTIPLE` produces and the one nothing had checked end to end.
    ///
    /// The single-file case was compared byte for byte against the reference sender, so
    /// what is left to pin here is the part that only exists with more than one file:
    /// member order, inode numbering continuing across members, and a DVZip block
    /// sequence that still lands exactly on the end of the body.
    #[test]
    fn several_files_archive_in_the_order_they_were_offered() {
        let root = std::env::temp_dir().join(format!("opairdrop-send-many-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();

        let first = root.join("a.txt");
        let second = root.join("报告.txt");
        let third = root.join("b.jpg");
        std::fs::write(&first, b"hello").unwrap();
        std::fs::write(&second, "七个字".as_bytes()).unwrap();
        // Two blocks' worth, so the archive crosses a boundary in the middle of a member.
        std::fs::write(&third, vec![9u8; 70_000]).unwrap();

        let files = describe(&[first.clone(), second.clone(), third.clone()]).unwrap();
        let mut body = Vec::new();
        archive(&mut body, &files, None).unwrap();

        // Every block header has to add up to the body exactly: a frame that overruns is
        // a receiver waiting for bytes that never come.
        let mut at = 0;
        while at + 4 <= body.len() {
            let raw = u32::from_be_bytes([body[at], body[at + 1], body[at + 2], body[at + 3]]);
            at += 4 + (raw & 0x7fff_ffff) as usize;
        }
        assert_eq!(at, body.len(), "block framing covers the body exactly");

        let mut plain = Vec::new();
        dvzip::Decoder::new(&body[..])
            .read_to_end(&mut plain)
            .unwrap();

        // Walk the odc members by hand: `read()` would sanitise the names this test is
        // here to check.
        let mut head_at = 0;
        let mut seen: Vec<(String, u32, String, u64)> = Vec::new();
        while head_at + 76 <= plain.len() {
            let field = |at: usize, len: usize| {
                String::from_utf8(plain[head_at + at..head_at + at + len].to_vec()).unwrap()
            };
            let name_size = usize::from_str_radix(&field(59, 6), 8).unwrap();
            let size = u64::from_str_radix(&field(65, 11), 8).unwrap();
            let name = String::from_utf8(plain[head_at + 76..head_at + 76 + name_size - 1].to_vec())
                .unwrap();
            seen.push((
                name.clone(),
                u32::from_str_radix(&field(12, 6), 8).unwrap(),
                field(18, 6),
                size,
            ));
            head_at += 76 + name_size + size as usize;
            if name == "TRAILER!!!" {
                break;
            }
        }
        assert_eq!(head_at, plain.len(), "the trailer ends the archive exactly");

        // The `./` on each member is the `FileBomPath` the offer announced; the receiver
        // finds a member by that string and drops one it was not offered.
        let names: Vec<&str> = seen.iter().map(|(name, ..)| name.as_str()).collect();
        assert_eq!(
            names,
            vec![".", "./a.txt", "./报告.txt", "./b.jpg", "TRAILER!!!"]
        );
        // Inodes run 0, 1, 2, 3 and the trailer goes back to 0, which is what the
        // reference sender writes.
        let inodes: Vec<u32> = seen.iter().map(|(_, ino, ..)| *ino).collect();
        assert_eq!(inodes, vec![0, 1, 2, 3, 0]);
        // Modes: the directory, then three regular files, then the sentinel's zero.
        let modes: Vec<&str> = seen.iter().map(|(_, _, mode, _)| mode.as_str()).collect();
        assert_eq!(modes, vec!["040755", "100644", "100644", "100644", "000000"]);
        let sizes: Vec<u64> = seen.iter().map(|(_, _, _, size)| *size).collect();
        // `七个字` is nine bytes, not three: the header counts UTF-8 bytes.
        assert_eq!(sizes, vec![0, 5, 9, 70_000, 0]);

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_directory_is_refused_rather_than_sent_as_an_empty_file() {
        let root = std::env::temp_dir().join(format!("opairdrop-send-dir-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        assert!(describe(std::slice::from_ref(&root)).is_err());
        let _ = std::fs::remove_dir_all(&root);
    }
}
