//! cpio archive codec for the AirDrop `/Upload` payload.
//!
//! An upload is a `Content-Type: application/x-cpio` body, usually gzip- or
//! DVZip-wrapped, holding one cpio member per shared file plus an AppleDouble
//! `._name` sidecar. Both POSIX variants appear in the wild and are handled here:
//! `070701`/`070702` ("newc", four-byte aligned) and `070707` ("odc", octal fields,
//! no alignment anywhere).
//!
//! Reading is streaming so multi-gigabyte uploads never land in memory; the caller
//! decides what to keep through [`Sink`].
//!
//! Writing has two forms because the spec does: [`Format::Newc`] is what Apple's
//! libarchive reads, [`Format::Odc`] is what UP-2 requires a *sender* to send.
//!
//! # Why this is one writer and not two
//!
//! The Java version this replaces had four near-identical member writers — newc and
//! odc, each twice, once for an in-memory slice and once for a reader — plus two
//! separate header builders and two length calculators. Every bug in the odc path had
//! to be fixed in four places, and twice was not fixed in all four. Here the format is
//! a field on [`Builder`] and the differences are two branches: the header layout and
//! whether padding exists at all.

#![forbid(unsafe_code)]

use std::fmt;
use std::io::{self, Read, Write};
use std::time::{SystemTime, UNIX_EPOCH};

pub mod dvzip;

/// The name that ends an archive.
const TRAILER: &str = "TRAILER!!!";
/// Bytes in a "newc" header.
const NEWC_HEADER: usize = 110;
/// Bytes in an "odc" header.
const ODC_HEADER: usize = 76;
/// Largest plausible member name, NUL included.
const MAX_NAME: u64 = 4096;

/// Root directory, permissions included.
///
/// UP-2a spells the wire values out — `040755` for the root directory and `0100644`
/// for a file — and the permission half is not decoration. A header carrying only the
/// type bits (`040000`, `010000`) declares a member nobody may read; an extractor that
/// honours it creates the entry and then cannot fill it, and a receiver that
/// pre-checks the mode can refuse the archive outright while still counting every
/// byte. That is a transfer that runs to 100%, answers `200`, and leaves nothing
/// behind.
pub const MODE_DIRECTORY: u32 = 0o040755;
/// Regular file, permissions included.
pub const MODE_REGULAR: u32 = 0o010644;
/// Symlink, permissions included.
pub const MODE_SYMLINK: u32 = 0o120777;

/// The directory bit of a mode.
pub const TYPE_DIRECTORY: u32 = 0o040000;
/// The regular-file bit of a mode.
pub const TYPE_REGULAR: u32 = 0o100000;
/// The symlink bit of a mode.
pub const TYPE_SYMLINK: u32 = 0o120000;
/// Every file-type bit, so permissions can be stripped with one mask.
const TYPE_MASK: u32 = 0o170000;

/// The type bits of `mode`, permissions stripped.
///
/// Comparing a received mode against [`MODE_DIRECTORY`] directly stopped working the
/// moment that constant grew its permission bits, so anything that classifies an
/// incoming entry goes through here.
pub fn type_of(mode: u32) -> u32 {
    mode & TYPE_MASK
}

/// Anything that can go wrong reading or writing an archive.
#[derive(Debug)]
pub enum Error {
    /// The underlying reader or writer failed.
    Io(io::Error),
    /// The magic was not a cpio variant we implement.
    BadMagic(String),
    /// A fixed-width numeric field held a non-digit.
    BadNumber {
        /// Byte offset of the offending character.
        at: usize,
    },
    /// The declared member size did not fit in a `u64`.
    OversizedMember,
    /// `namesize` was zero or past [`MAX_NAME`].
    ImplausibleNameSize(u64),
    /// A header or name field ended early.
    Truncated,
    /// There was no header within four bytes of where one was expected.
    LostFraming,
    /// A member's data ended before its declared size.
    ShortMember {
        /// Member name, for the error message.
        name: String,
        /// Bytes actually available.
        got: u64,
        /// Bytes the header promised.
        want: u64,
    },
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "{e}"),
            Self::BadMagic(tag) => write!(f, "unknown cpio magic '{tag}'"),
            Self::BadNumber { at } => write!(f, "bad cpio numeric field at {at}"),
            Self::OversizedMember => write!(f, "cpio member size does not fit in 64 bits"),
            Self::ImplausibleNameSize(n) => write!(f, "implausible cpio name size {n}"),
            Self::Truncated => write!(f, "truncated cpio header or name"),
            Self::LostFraming => write!(f, "lost cpio framing: no header within four bytes"),
            Self::ShortMember { name, got, want } => write!(
                f,
                "member {name} ended {got} bytes into a declared {want}"
            ),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<io::Error> for Error {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

/// One archive member held in memory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    /// Member name, already normalized by [`safe_name`].
    pub name: String,
    /// Type and permission bits, as they appeared on the wire.
    pub mode: u32,
    /// Member contents.
    pub data: Vec<u8>,
}

impl Entry {
    /// Builds a regular-file entry.
    pub fn file(name: impl Into<String>, data: impl Into<Vec<u8>>) -> Self {
        Self {
            name: name.into(),
            mode: MODE_REGULAR,
            data: data.into(),
        }
    }

    /// Builds a directory entry with no contents.
    pub fn directory(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            mode: MODE_DIRECTORY,
            data: Vec::new(),
        }
    }

    /// True when this member declares a directory type.
    pub fn is_directory(&self) -> bool {
        type_of(self.mode) == TYPE_DIRECTORY
    }
}

/// Receives the members of an archive as they stream past.
pub trait Sink {
    /// Called once per member, before its bytes are copied.
    ///
    /// Return a writer to keep the contents, or `None` to discard them. [`close`] is
    /// called once the member has been copied, so the sink knows when to publish.
    ///
    /// [`close`]: Sink::close
    fn open(&mut self, name: &str, size: u64, mode: u32) -> Option<&mut dyn Write>;

    /// Called after a member opened by [`open`](Sink::open) has been fully written.
    fn close(&mut self) {}
}

/// A [`Sink`] that keeps every member in memory.
///
/// Meant for tests and small payloads; a real upload goes to disk through a sink that
/// opens a file per member.
#[derive(Default)]
pub struct MemorySink {
    entries: Vec<Entry>,
    current: Vec<u8>,
    name: String,
    mode: u32,
    opened: bool,
}

impl MemorySink {
    /// Consumes the sink, yielding the collected members in archive order.
    pub fn into_entries(self) -> Vec<Entry> {
        self.entries
    }
}

impl Sink for MemorySink {
    fn open(&mut self, name: &str, _size: u64, mode: u32) -> Option<&mut dyn Write> {
        self.current.clear();
        self.name.clear();
        self.name.push_str(name);
        self.mode = mode;
        self.opened = true;
        Some(&mut self.current as &mut dyn Write)
    }

    fn close(&mut self) {
        if self.opened {
            self.opened = false;
            self.entries.push(Entry {
                name: std::mem::take(&mut self.name),
                mode: self.mode,
                data: std::mem::take(&mut self.current),
            });
        }
    }
}

/// Reads every member of an archive into memory.
///
/// Only meant for small payloads such as tests; use [`extract`] for real uploads.
pub fn read(archive: &[u8]) -> Result<Vec<Entry>, Error> {
    let mut sink = MemorySink::default();
    extract(archive, &mut sink)?;
    Ok(sink.into_entries())
}

/// Streams an archive into `sink` and returns how many non-directory members were
/// stored.
///
/// Entries whose name would escape the target directory are handed to the sink as
/// skipped, and are neither written nor counted.
pub fn extract<R: Read>(archive: R, sink: &mut dyn Sink) -> Result<u64, Error> {
    let mut input = Push::new(decompress(archive)?);
    let mut stored = 0u64;

    while input.seek_magic()? {
        let mut magic = [0u8; 6];
        input.read_exact(&mut magic)?;
        let format = match &magic {
            b"070701" | b"070702" => Format::Newc,
            b"070707" => Format::Odc,
            other => {
                return Err(Error::BadMagic(String::from_utf8_lossy(other).into_owned()));
            }
        };

        let mut header = vec![0u8; format.header_len()];
        header[..6].copy_from_slice(&magic);
        input.read_exact(&mut header[6..])?;

        let (size, name_size, mode) = match format {
            // size@54 w8, namesize@94 w8, mode@14 w8, all hex
            Format::Newc => (
                number(&header, 54, 8, 16)?,
                number(&header, 94, 8, 16)?,
                number(&header, 14, 8, 16)? as u32,
            ),
            // mode@18 w6, namesize@59 w6, filesize@65 w11, all octal
            Format::Odc => (
                number(&header, 65, 11, 8)?,
                number(&header, 59, 6, 8)?,
                number(&header, 18, 6, 8)? as u32,
            ),
        };
        if name_size == 0 || name_size > MAX_NAME {
            return Err(Error::ImplausibleNameSize(name_size));
        }

        let mut raw_name = vec![0u8; name_size as usize];
        input.read_exact(&mut raw_name)?;
        let name = cstring(&raw_name);
        if name == TRAILER {
            return Ok(stored);
        }

        // newc aligns header+name to four bytes. odc aligns nothing: the data starts at
        // the byte right after the name's NUL, however odd that offset is.
        if format == Format::Newc {
            let name_pad = pad_for(NEWC_HEADER as u64 + name_size);
            input.skip(name_pad as usize)?;
        }

        match safe_name(&name) {
            Some(safe) => {
                let target = sink.open(&safe, size, mode);
                // `open` already yields the writer by value, so there is nothing to
                // re-borrow here: both earlier spellings derefed a reference that was
                // never a reference.
                input.copy_to(target, &name, size)?;
                sink.close();
                if type_of(mode) != TYPE_DIRECTORY {
                    stored += 1;
                }
            }
            None => {
                input.copy_to(None, &name, size)?;
            }
        }
    }
    Ok(stored)
}

/// Normalizes an archive member name into a relative path, or `None` when the name is
/// absolute, empty, or tries to walk out of the target directory.
///
/// Backslashes are left alone. The Java version turned them into separators, which on
/// a Unix receiver is wrong twice over: `\` is a legal filename character, so a
/// shared file called `a\b` came out as `a/b`, and the rewrite bought no safety
/// because cpio paths never use `\` as a separator in the first place.
pub fn safe_name(raw: &str) -> Option<String> {
    let mut name = raw;
    while let Some(rest) = name.strip_prefix("./") {
        name = rest;
    }
    while let Some(rest) = name.strip_prefix('/') {
        name = rest;
    }
    if name.is_empty() || name == "." {
        return None;
    }
    let mut cleaned = String::with_capacity(name.len());
    for part in name.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." || part.contains('\0') {
            return None;
        }
        if !cleaned.is_empty() {
            cleaned.push('/');
        }
        cleaned.push_str(part);
    }
    if cleaned.is_empty() {
        None
    } else {
        Some(cleaned)
    }
}

/// Unwraps the transport compression, if there is any.
///
/// Three layers can sit between an `/Upload` body and a cpio header, and the receiver
/// cannot be told which one it got — only the content type says, and peers disagree
/// about it. So the first bytes decide: gzip announces itself, a DVZip block
/// announces a length that could not be a cpio magic, and anything else is a bare
/// archive.
///
/// DVZip is the case that matters in practice: UP-1b says a sender that does not know
/// the receiver's capabilities must assume DVZip, so an Apple peer's upload is a block
/// sequence rather than a zlib stream.
fn decompress<R: Read>(mut raw: R) -> Result<Body<R>, Error> {
    let mut head = Vec::with_capacity(4);
    let mut one = [0u8; 1];
    while head.len() < 4 {
        match raw.read(&mut one) {
            Ok(0) => break,
            Ok(_) => head.push(one[0]),
            Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e.into()),
        }
    }
    let pre = Prefixed {
        head: head.clone(),
        at: 0,
        inner: raw,
    };
    if head.len() >= 2 && head[0] == 0x1f && head[1] == 0x8b {
        return Ok(Body::Gzip(flate2::read::GzDecoder::new(pre)));
    }
    if head.len() == 4 && dvzip::looks_like_block(&head) {
        return Ok(Body::Dvzip(dvzip::Decoder::new(pre)));
    }
    Ok(Body::Bare(pre))
}

/// The three transports an `/Upload` body can arrive in, as one reader.
///
/// An enum rather than `Box<dyn Read>`: a boxed trait object would need `'static`,
/// which would force every caller to hand over an owned reader instead of a borrowed
/// slice of the socket buffer.
enum Body<R: Read> {
    Bare(Prefixed<R>),
    Gzip(flate2::read::GzDecoder<Prefixed<R>>),
    Dvzip(dvzip::Decoder<Prefixed<R>>),
}

impl<R: Read> Read for Body<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::Bare(inner) => inner.read(buf),
            Self::Gzip(inner) => inner.read(buf),
            Self::Dvzip(inner) => inner.read(buf),
        }
    }
}

/// A reader that first replays the bytes already consumed while sniffing.
struct Prefixed<R> {
    head: Vec<u8>,
    at: usize,
    inner: R,
}

impl<R: Read> Read for Prefixed<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.at < self.head.len() {
            let take = buf.len().min(self.head.len() - self.at);
            buf[..take].copy_from_slice(&self.head[self.at..self.at + take]);
            self.at += take;
            return Ok(take);
        }
        self.inner.read(buf)
    }
}

/// Which on-wire variant an archive uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    /// `070701`/`070702`, four-byte aligned. What Apple's libarchive reads.
    Newc,
    /// `070707`, octal, no alignment. What UP-2 requires a sender to send.
    Odc,
}

impl Format {
    /// Bytes in this format's header, magic included.
    pub const fn header_len(self) -> usize {
        match self {
            Self::Newc => NEWC_HEADER,
            Self::Odc => ODC_HEADER,
        }
    }

    /// Exact byte count a member occupies, header, name and padding included.
    ///
    /// A sender needs this before writing anything: `/Upload` is chunked, so the body
    /// length is never on the wire, but a progress bar still has to know what "done"
    /// means.
    pub fn member_len(self, name: &str, size: u64) -> u64 {
        let name_size = name.len() as u64 + 1;
        match self {
            Self::Newc => {
                let head = NEWC_HEADER as u64 + name_size;
                head + pad_for(head) + size + pad_for(size)
            }
            Self::Odc => ODC_HEADER as u64 + name_size + size,
        }
    }

    /// Bytes this format's terminating member occupies.
    pub fn trailer_len(self) -> u64 {
        self.member_len(TRAILER, 0)
    }
}

/// Builds an archive, one member at a time.
///
/// [`Builder::add`] keeps the whole member in hand; [`Builder::add_reader`] streams it
/// from a reader with no full-size buffer, which is what a shared video needs: cpio is
/// a plain byte stream, so nothing requires a member to be contiguous in memory.
pub struct Builder<W: Write> {
    out: W,
    format: Format,
    inode: u32,
    mtime: Option<u64>,
}

impl<W: Write> Builder<W> {
    /// Starts an archive in `format`, writing to `out`.
    pub fn new(out: W, format: Format) -> Self {
        Self {
            out,
            format,
            inode: 0,
            mtime: None,
        }
    }

    /// Pins the modification time instead of reading the clock, so output is
    /// reproducible. Tests rely on this; production does not.
    pub fn with_mtime(mut self, seconds: u64) -> Self {
        self.mtime = Some(seconds);
        self
    }

    /// Appends one member from memory.
    pub fn add(&mut self, name: &str, mode: u32, data: &[u8]) -> Result<(), Error> {
        self.write_head(name, mode, data.len() as u64)?;
        self.out.write_all(data)?;
        self.write_data_pad(data.len() as u64)?;
        Ok(())
    }

    /// Appends one member, copying exactly `size` bytes from `reader`.
    ///
    /// A reader that ends early is an error, not a short member: a short member has no
    /// honest fix on either side of the wire.
    pub fn add_reader<R: Read>(
        &mut self,
        name: &str,
        mode: u32,
        size: u64,
        reader: &mut R,
    ) -> Result<(), Error> {
        self.write_head(name, mode, size)?;
        copy_exact(&mut self.out, reader, size, name)?;
        self.write_data_pad(size)?;
        Ok(())
    }

    /// Writes the terminating member and returns the underlying writer.
    pub fn finish(mut self) -> Result<W, Error> {
        // The trailer is an ordinary member whose name happens to be the sentinel, so
        // it goes through the same path as everything else. Writing its header by hand
        // was a bug worth remembering: the name bytes were left out, leaving a header
        // that promised eleven name bytes and delivered one NUL, so every archive
        // ended in `Truncated` on the reading side.
        self.write_head(TRAILER, 0, 0)?;
        Ok(self.out)
    }

    fn write_head(&mut self, name: &str, mode: u32, size: u64) -> Result<(), Error> {
        self.inode = self.inode.wrapping_add(1);
        let head = head_bytes(
            self.format,
            name,
            mode,
            size,
            self.inode,
            self.mtime.unwrap_or_else(now_seconds),
        );
        self.out.write_all(&head)?;
        self.out.write_all(name.as_bytes())?;
        self.out.write_all(&[0])?;
        if self.format == Format::Newc {
            let head_len = NEWC_HEADER as u64 + name.len() as u64 + 1;
            write_padding(&mut self.out, head_len)?;
        }
        Ok(())
    }

    fn write_data_pad(&mut self, size: u64) -> Result<(), Error> {
        if self.format == Format::Newc {
            write_padding(&mut self.out, size)?;
        }
        Ok(())
    }
}

/// Convenience wrapper: build a whole archive in memory.
pub fn to_vec(entries: &[Entry], format: Format) -> Result<Vec<u8>, Error> {
    let mut builder = Builder::new(Vec::new(), format);
    for entry in entries {
        builder.add(&entry.name, entry.mode, &entry.data)?;
    }
    builder.finish()
}

/// The header text for one member, without the name or its NUL.
///
/// Field order is POSIX's. The two odc fields that differ from everything else —
/// `mtime` and `filesize` — are 11 digits for a reason: octal seconds stopped fitting
/// in six in 1971 and octal sizes stopped fitting well before files did. Truncating
/// rather than overflowing keeps a header well-formed when a value is out of range,
/// which is what cpio itself does.
fn head_bytes(
    format: Format,
    name: &str,
    mode: u32,
    size: u64,
    inode: u32,
    mtime: u64,
) -> Vec<u8> {
    let name_size = name.len() as u64 + 1;
    let mut head = String::with_capacity(format.header_len());
    match format {
        Format::Newc => {
            head.push_str("070701");
            head.push_str(&hex_field(inode as u64)); // ino
            head.push_str(&hex_field(mode as u64)); // mode
            head.push_str(&hex_field(0)); // uid
            head.push_str(&hex_field(0)); // gid
            head.push_str(&hex_field(1)); // nlink
            head.push_str(&hex_field(mtime));
            head.push_str(&hex_field(size));
            head.push_str(&hex_field(0)); // devmajor
            head.push_str(&hex_field(0)); // devminor
            head.push_str(&hex_field(0)); // rdevmajor
            head.push_str(&hex_field(0)); // rdevminor
            head.push_str(&hex_field(name_size));
            head.push_str(&hex_field(0)); // check
        }
        Format::Odc => {
            head.push_str("070707");
            head.push_str(&oct_field(0, 6)); // dev
            head.push_str(&oct_field(inode as u64, 6)); // ino
            head.push_str(&oct_field(mode as u64, 6)); // mode
            head.push_str(&oct_field(0, 6)); // uid
            head.push_str(&oct_field(0, 6)); // gid
            let nlink = if type_of(mode) == TYPE_DIRECTORY { 2 } else { 1 };
            head.push_str(&oct_field(nlink, 6));
            head.push_str(&oct_field(0, 6)); // rdev
            head.push_str(&oct_field(mtime, 11));
            head.push_str(&oct_field(name_size, 6));
            head.push_str(&oct_field(size, 11));
        }
    }
    debug_assert_eq!(head.len(), format.header_len());
    head.into_bytes()
}

/// A zero-padded hexadecimal field of exactly eight digits.
fn hex_field(value: u64) -> String {
    format!("{:08x}", value & 0xffff_ffff)
}

/// A zero-padded octal field of exactly `width` digits, keeping the low-order digits
/// when the value does not fit — the same truncation cpio itself performs.
fn oct_field(value: u64, width: usize) -> String {
    let digits = format!("{value:o}");
    let digits = if digits.len() > width {
        &digits[digits.len() - width..]
    } else {
        &digits[..]
    };
    format!("{digits:0>width$}")
}

fn now_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn pad_for(length: u64) -> u64 {
    match length % 4 {
        0 => 0,
        remainder => 4 - remainder,
    }
}

fn write_padding<W: Write>(out: &mut W, length: u64) -> io::Result<()> {
    match pad_for(length) {
        0 => Ok(()),
        1 => out.write_all(&[0]),
        2 => out.write_all(&[0, 0]),
        _ => out.write_all(&[0, 0, 0]),
    }
}

fn copy_exact<W: Write, R: Read>(
    out: &mut W,
    reader: &mut R,
    size: u64,
    name: &str,
) -> Result<(), Error> {
    let mut buffer = vec![0u8; 16 * 1024];
    let mut remaining = size;
    while remaining > 0 {
        let want = buffer.len().min(remaining as usize);
        let read = reader.read(&mut buffer[..want])?;
        if read == 0 {
            return Err(Error::ShortMember {
                name: name.to_string(),
                got: size - remaining,
                want: size,
            });
        }
        out.write_all(&buffer[..read])?;
        remaining -= read as u64;
    }
    Ok(())
}

/// Parses a fixed-width ASCII number field.
fn number(header: &[u8], offset: usize, width: usize, radix: u32) -> Result<u64, Error> {
    let mut value = 0u64;
    for i in 0..width {
        let at = offset + i;
        let byte = *header.get(at).ok_or(Error::BadNumber { at })?;
        let digit = (byte as char)
            .to_digit(radix)
            .ok_or(Error::BadNumber { at })?;
        value = value * radix as u64 + digit as u64;
    }
    Ok(value)
}

/// The bytes up to the first NUL, decoded as UTF-8 with replacement.
fn cstring(raw: &[u8]) -> String {
    let end = raw.iter().position(|&b| b == 0).unwrap_or(raw.len());
    String::from_utf8_lossy(&raw[..end]).into_owned()
}

/// A reader with a small pushback window, so framing can be re-examined without
/// re-reading the source.
struct Push<R> {
    inner: R,
    hold: Vec<u8>,
    at: usize,
}

impl<R: Read> Push<R> {
    fn new(inner: R) -> Self {
        Self {
            inner,
            hold: Vec::new(),
            at: 0,
        }
    }

    /// Ensures at least `want` bytes sit in `hold[at..]`, returning how many are
    /// available (fewer only at end of stream).
    fn fill(&mut self, want: usize) -> Result<usize, Error> {
        let mut chunk = [0u8; 512];
        while self.hold.len() - self.at < want {
            let read = match self.inner.read(&mut chunk) {
                Ok(0) => break,
                Ok(n) => n,
                Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e.into()),
            };
            if self.at > 0 {
                self.hold.drain(..self.at);
                self.at = 0;
            }
            self.hold.extend_from_slice(&chunk[..read]);
        }
        Ok(self.hold.len() - self.at)
    }

    fn read_exact(&mut self, dst: &mut [u8]) -> Result<(), Error> {
        let mut off = 0;
        while off < dst.len() {
            let available = self.fill(dst.len() - off)?;
            if available == 0 {
                return Err(Error::Truncated);
            }
            let take = available.min(dst.len() - off);
            dst[off..off + take].copy_from_slice(&self.hold[self.at..self.at + take]);
            self.at += take;
            off += take;
        }
        Ok(())
    }

    fn skip(&mut self, count: usize) -> Result<(), Error> {
        let mut left = count;
        while left > 0 {
            let available = self.fill(left)?;
            if available == 0 {
                return Err(Error::Truncated);
            }
            let take = available.min(left);
            self.at += take;
            left -= take;
        }
        Ok(())
    }

    fn read_some(&mut self, buf: &mut [u8]) -> Result<usize, Error> {
        if buf.is_empty() {
            return Ok(0);
        }
        // Nothing pushed back: read straight into the caller's buffer instead of
        // staging every byte through `hold`.
        if self.at == self.hold.len() {
            self.hold.clear();
            self.at = 0;
            loop {
                match self.inner.read(buf) {
                    Ok(n) => return Ok(n),
                    Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Err(e) => return Err(e.into()),
                }
            }
        }
        let available = self.fill(1)?;
        let take = available.min(buf.len());
        buf[..take].copy_from_slice(&self.hold[self.at..self.at + take]);
        self.at += take;
        Ok(take)
    }

    fn copy_to(
        &mut self,
        mut out: Option<&mut dyn Write>,
        name: &str,
        count: u64,
    ) -> Result<(), Error> {
        let mut buffer = vec![0u8; 64 * 1024];
        let mut remaining = count;
        while remaining > 0 {
            let want = buffer.len().min(remaining as usize);
            let read = self.read_some(&mut buffer[..want])?;
            if read == 0 {
                return Err(Error::ShortMember {
                    name: name.to_string(),
                    got: count - remaining,
                    want: count,
                });
            }
            if let Some(writer) = out.as_deref_mut() {
                writer.write_all(&buffer[..read])?;
            }
            remaining -= read as u64;
        }
        Ok(())
    }

    /// Leaves the reader positioned at a header magic, absorbing at most three padding
    /// bytes. `false` at a clean end of archive.
    fn seek_magic(&mut self) -> Result<bool, Error> {
        let available = self.fill(10)?;
        if available < 6 {
            // Only trailing padding remains.
            self.at += available;
            return Ok(false);
        }
        for skip in 0..=3 {
            if skip + 5 <= available && self.hold[self.at + skip..self.at + skip + 5] == *b"07070" {
                self.at += skip;
                return Ok(true);
            }
        }
        Err(Error::LostFraming)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Vec<Entry> {
        vec![
            Entry::directory("Payload"),
            Entry::file("Payload/a.txt", b"hello cpio".to_vec()),
            Entry::file("Payload/b", b"xy".to_vec()),
        ]
    }

    #[test]
    fn newc_round_trip() {
        let entries = sample();
        let bytes = to_vec(&entries, Format::Newc).unwrap();
        assert_eq!(read(&bytes).unwrap(), entries);
    }

    #[test]
    fn odc_round_trip() {
        let entries = sample();
        let bytes = to_vec(&entries, Format::Odc).unwrap();
        assert_eq!(read(&bytes).unwrap(), entries);
    }

    #[test]
    fn odc_aligns_nothing() {
        // "ab" plus NUL is three bytes, so the data starts at an odd offset (79). The
        // Java writer padded that to 80 and thereby inserted a stray zero byte in front
        // of every such file: photos that would not open, at 100% and HTTP 200.
        let entries = vec![Entry::file("ab", b"XY".to_vec())];
        let bytes = to_vec(&entries, Format::Odc).unwrap();
        let member = Format::Odc.member_len("ab", 2);
        assert_eq!(bytes.len() as u64, member + Format::Odc.trailer_len());
        assert_eq!(&bytes[ODC_HEADER + 3..ODC_HEADER + 5], b"XY");
        assert_eq!(read(&bytes).unwrap(), entries);
    }

    #[test]
    fn newc_pads_to_four() {
        // 110 + 3 = 113, padded to 116, then two data bytes, padded to 120.
        assert_eq!(Format::Newc.member_len("ab", 2), 120);
        assert_eq!(Format::Odc.member_len("ab", 2), 76 + 3 + 2);
    }

    #[test]
    fn trailer_lengths_match_what_is_written() {
        for format in [Format::Newc, Format::Odc] {
            let bytes = to_vec(&[], format).unwrap();
            assert_eq!(bytes.len() as u64, format.trailer_len());
            assert!(read(&bytes).unwrap().is_empty());
        }
    }

    #[test]
    fn trailing_bytes_after_trailer_are_ignored() {
        let mut bytes = to_vec(&sample(), Format::Odc).unwrap();
        bytes.extend_from_slice(b"garbage");
        assert_eq!(read(&bytes).unwrap(), sample());
    }

    #[test]
    fn gzip_wrapper_is_transparent() {
        let plain = to_vec(&sample(), Format::Odc).unwrap();
        let mut encoder =
            flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&plain).unwrap();
        let gzipped = encoder.finish().unwrap();
        assert_eq!(&gzipped[..2], &[0x1f, 0x8b]);
        assert_eq!(read(&gzipped).unwrap(), sample());
    }

    #[test]
    fn dvzip_wrapper_is_transparent() {
        let plain = to_vec(&sample(), Format::Odc).unwrap();
        let mut encoder = dvzip::Encoder::new(Vec::new());
        encoder.write_all(&plain).unwrap();
        encoder.finish().unwrap();
        let body = encoder.into_inner();
        assert_ne!(&body[..2], &[0x1f, 0x8b]);
        assert_eq!(read(&body).unwrap(), sample());
    }

    #[test]
    fn bare_archive_is_not_mistaken_for_compression() {
        // "070707" as a big-endian length is 808 million, past MAX_BLOCK, so the sniffer
        // falls through to the bare case.
        let bytes = to_vec(&sample(), Format::Odc).unwrap();
        assert!(!dvzip::looks_like_block(&bytes[..4]));
        assert_eq!(read(&bytes).unwrap(), sample());
    }

    #[test]
    fn streaming_member_matches_in_memory() {
        let data = b"streamed member".to_vec();
        let mut builder = Builder::new(Vec::new(), Format::Odc).with_mtime(0);
        builder.add("f", MODE_REGULAR, &data).unwrap();
        let expected = builder.finish().unwrap();

        let mut builder = Builder::new(Vec::new(), Format::Odc).with_mtime(0);
        let mut source = &data[..];
        builder
            .add_reader("f", MODE_REGULAR, data.len() as u64, &mut source)
            .unwrap();
        assert_eq!(builder.finish().unwrap(), expected);
    }

    #[test]
    fn short_reader_is_an_error() {
        let mut builder = Builder::new(Vec::new(), Format::Odc);
        let mut source = &b"abc"[..];
        let err = builder
            .add_reader("f", MODE_REGULAR, 99, &mut source)
            .unwrap_err();
        match err {
            Error::ShortMember { got, want, .. } => assert_eq!((got, want), (3, 99)),
            other => panic!("unexpected error: {other}"),
        }
    }

    #[test]
    fn truncated_archive_is_an_error() {
        let mut bytes = to_vec(&sample(), Format::Odc).unwrap();
        bytes.truncate(bytes.len() - 40);
        assert!(read(&bytes).is_err());
    }

    #[test]
    fn empty_input_is_an_empty_archive() {
        assert!(read(&[]).unwrap().is_empty());
    }

    #[test]
    fn bad_magic_is_rejected() {
        // `07070` is the prefix newc and odc share, so framing accepts it and the
        // unknown version is reported as what it is. Garbage that does not even look
        // like cpio is `LostFraming` instead — see the test below.
        let err = read(b"070709abcdefghij").unwrap_err();
        assert!(matches!(err, Error::BadMagic(_)), "{err}");
    }

    #[test]
    fn lost_framing_is_rejected() {
        let err = read(b"xxxxxxxxxxxxxxxx").unwrap_err();
        assert!(matches!(err, Error::LostFraming), "{err}");
    }

    #[test]
    fn permission_bits_survive_the_round_trip() {
        let entries = vec![
            Entry::directory("dir"),
            Entry::file("dir/f", b"1".to_vec()),
        ];
        for format in [Format::Newc, Format::Odc] {
            let parsed = read(&to_vec(&entries, format).unwrap()).unwrap();
            assert_eq!(parsed[0].mode, MODE_DIRECTORY);
            assert_eq!(parsed[1].mode, MODE_REGULAR);
            assert!(parsed[0].is_directory());
            assert!(!parsed[1].is_directory());
        }
    }

    #[test]
    fn names_are_normalized_and_traversal_refused() {
        assert_eq!(safe_name("./a/b").as_deref(), Some("a/b"));
        assert_eq!(safe_name("/a//b/./c").as_deref(), Some("a/b/c"));
        assert_eq!(safe_name("a\\b").as_deref(), Some("a\\b"));
        assert_eq!(safe_name("../etc/passwd"), None);
        assert_eq!(safe_name("a/../../b"), None);
        assert_eq!(safe_name(""), None);
        assert_eq!(safe_name("."), None);
        assert_eq!(safe_name("./"), None);
    }

    #[test]
    fn escaping_members_are_skipped_but_streamed_past() {
        let mut bytes = Vec::new();
        {
            let mut builder = Builder::new(&mut bytes, Format::Odc);
            builder.add("../evil", MODE_REGULAR, b"payload").unwrap();
            builder.add("good", MODE_REGULAR, b"ok").unwrap();
            builder.finish().unwrap();
        }
        let entries = read(&bytes).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "good");
    }

    #[test]
    fn directory_members_are_not_counted_as_stored() {
        let bytes = to_vec(&sample(), Format::Odc).unwrap();
        let mut sink = MemorySink::default();
        assert_eq!(extract(&bytes[..], &mut sink).unwrap(), 2);
    }

    #[test]
    fn utf8_names_survive() {
        let entries = vec![Entry::file("照片/一号.png", b"x".to_vec())];
        for format in [Format::Newc, Format::Odc] {
            let bytes = to_vec(&entries, format).unwrap();
            assert_eq!(read(&bytes).unwrap(), entries);
        }
    }
}
