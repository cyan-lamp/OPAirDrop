//! Outgoing transfers: one `/Discover`, one `/Ask`, one `/Upload`.
//!
//! # Why the archive is built while it is sent
//!
//! A share of a 4 GB video cannot be assembled in memory. Here the cpio writer pushes into
//! a DVZip encoder, the encoder pushes into the caller's stream, and memory is one block per
//! layer no matter how large the transfer is.
//!
//! # Why the conversation is three calls and not one
//!
//! `/Discover` asks whether the peer takes transfers at all, and it is the only call whose
//! answer can be "no" before the other phone has shown a prompt. Doing it first is what lets
//! the UI say "this iPhone is not accepting files" instead of raising a prompt and then
//! failing behind it. `/Ask` is the prompt. `/Upload` is the archive.
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
use opairdrop_airdrop::{Client, Item, Sender, TransferId};
use opairdrop_cpio::{dvzip, Builder, Format, MODE_REGULAR};

use crate::api::{status, CallbackReader, CallbackWriter, ReadCallback, WriteCallback};
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

/// How much may pass between two reports.
///
/// 512 KiB: small enough that a 1 GB video moves visibly several times a second, large
/// enough that a hundred small files do not spend the transfer inside JNI.
const REPORT_EVERY: u64 = 512 * 1024;

/// A [`ProgressCallback`] with the context it was built for.
struct Meter {
    callback: ProgressCallback,
    context: *mut c_void,
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
/// # Safety
///
/// `host` must point at `host_len` bytes of UTF-8 readable for the duration; `settings`,
/// `sender_name`, `model` and `transfer_id` likewise at their stated lengths, or be null where
/// optional; `paths` must point at `paths_len` bytes of NUL-separated UTF-8; `read` and `write`
/// must be valid function pointers whose `context` stays valid; `report` must point at a zeroed
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
    transfer_id: *const u8,
    transfer_id_len: usize,
    paths: *const u8,
    paths_len: usize,
    read: ReadCallback,
    write: WriteCallback,
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
            transfer_id,
            transfer_id_len,
            paths,
            paths_len,
            read,
            write,
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
    transfer_id: *const u8,
    transfer_id_len: usize,
    paths: *const u8,
    paths_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    progress: ProgressCallback,
    progress_context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    let gauge = Meter {
        callback: progress,
        context: progress_context,
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
            transfer_id,
            transfer_id_len,
            paths,
            paths_len,
            read,
            write,
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
    transfer_id: *const u8,
    transfer_id_len: usize,
    paths: *const u8,
    paths_len: usize,
    read: ReadCallback,
    write: WriteCallback,
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
            let id = text(transfer_id, transfer_id_len)?;
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
                id,
            );
            let paths = path_list(text(paths, paths_len)?)?;
            Ok((host, id, sender, paths, limit))
        })()
    };
    let (host, id, sender, paths, limit) = match parsed {
        Ok(parsed) => parsed,
        Err(failure) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, failure.message.into_bytes()) };
            return failure.status;
        }
    };
    let Some(id) = parse_transfer_id(id) else {
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
    let mut client = Client::new(Wire { reader, writer }, host).with_limit(limit);
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

    // `/Discover` first: a peer that is not accepting answers 503 here, and that answer has
    // to arrive before the other phone shows a prompt.
    let discovered = client.discover(sender).map_err(describe_error)?;
    if let Some(gauge) = meter {
        gauge.say(phase::READY);
    }
    if let Some(gauge) = meter {
        gauge.say(phase::WAITING);
    }
    let asked = client.ask(sender, id, &items, &[]).map_err(describe_error)?;
    if let Some(gauge) = meter {
        gauge.say(phase::SENDING);
    }

    let peer = if discovered.computer_name.is_empty() {
        asked.computer_name.clone()
    } else {
        discovered.computer_name.clone()
    };

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
fn archive(
    out: &mut dyn Write,
    files: &[(Item, PathBuf)],
    meter: Option<&Meter>,
) -> io::Result<()> {
    // Odc is what the protocol's upload section asks a sender to produce.
    let mut builder = Builder::new(dvzip::Encoder::new(out), Format::Odc);
    for (item, path) in files {
        let size = item.size.unwrap_or(0);
        let file = File::open(path)
            .map_err(|error| io::Error::new(error.kind(), format!("{}: {error}", path.display())))?;
        match meter {
            Some(gauge) => {
                let mut counted = Counted { file, meter: gauge };
                builder
                    .add_reader(&item.name, MODE_REGULAR, size, &mut counted)
                    .map_err(io::Error::other)?;
            }
            None => {
                let mut plain = file;
                builder
                    .add_reader(&item.name, MODE_REGULAR, size, &mut plain)
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
fn describe_error(error: ClientError) -> Failure {
    match error {
        ClientError::Unavailable { computer_name } => {
            let who = if computer_name.is_empty() {
                "the peer".to_string()
            } else {
                computer_name
            };
            Failure::new(status::REFUSED, format!("{who} is not accepting files"))
        }
        other => Failure::transport(other),
    }
}

/// One connection as a single `Read + Write`, which is what [`Client`] takes.
struct Wire {
    reader: CallbackReader,
    writer: CallbackWriter,
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
        assert_eq!(files[0].0.size, Some(5));
        assert_eq!(files[1].0.size, Some(200_000));

        let mut body = Vec::new();
        archive(&mut body, &files, None).unwrap();

        let mut plain = Vec::new();
        dvzip::Decoder::new(&body[..])
            .read_to_end(&mut plain)
            .unwrap();
        let entries = opairdrop_cpio::read(&plain).unwrap();

        let names: Vec<&str> = entries.iter().map(|entry| entry.name.as_str()).collect();
        assert_eq!(names, vec!["note.txt", "photo.jpg"]);
        assert_eq!(entries[0].data, b"hello");
        assert_eq!(entries[1].data.len(), 200_000);

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
