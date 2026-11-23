//! Request body framing: `Content-Length`, chunked transfer coding, or nothing.
//!
//! Internally an enum rather than a `Box<dyn Read>`. A boxed reader would force a
//! `'static` bound, which forces the caller to hand over ownership of its socket and
//! wrap it in something reference-counted — for a body that lives exactly as long as
//! one request. The enum borrows instead, so the server can read a request, drain its
//! body, and read the next request from the same stream without any plumbing.
//!
//! # What this replaced
//!
//! The Java version had two `InputStream` subclasses and a third "no body" case
//! spelled as `Bounded(in, 0)`, each overriding both `read()` and
//! `read(buffer, offset, length)`. Every `read()` override allocated a fresh
//! `byte[1]` to delegate through, and the two implementations drifted: only `Chunked`
//! handled EOF-before-data, only `Bounded` reported how many bytes were missing. Here
//! there is one `fill` state machine, `Read::read` is the only required method, and
//! "no body" is a variant instead of a degenerate length.

use std::io::{self, Read, Write};

use crate::{read_line, read_some, Error};

/// A request body, borrowing the stream its head was read from.
pub struct Body<'a, R: Read> {
    inner: Inner<'a, R>,
}

enum Inner<'a, R: Read> {
    /// The request carried neither a length nor chunked framing.
    Empty,
    /// Exactly `remaining` more bytes.
    Bounded { input: &'a mut R, remaining: u64 },
    /// A sequence of hex-sized chunks.
    Chunked { input: &'a mut R, state: ChunkState },
    /// Everything up to end of stream.
    ///
    /// Only a *response* can be framed this way, and only when its peer reused the
    /// connection for nothing else: HTTP says a response with neither a length nor
    /// chunked coding ends when the connection does. A request in that shape means "no
    /// body at all", which is [`Inner::Empty`]. Java collapsed both onto "read until
    /// close", which is why its client could hang forever on a live connection that had
    /// simply finished its answer.
    UntilEof { input: &'a mut R },
}

/// Where a chunked body is in its framing, plus the line scratch buffer.
///
/// The buffer is reused for every chunk-size line and chunk terminator, so a body of a
/// thousand chunks allocates once.
struct ChunkState {
    remaining: u64,
    finished: bool,
    line: Vec<u8>,
}

impl ChunkState {
    fn new() -> Self {
        ChunkState {
            remaining: 0,
            finished: false,
            line: Vec::new(),
        }
    }
}

impl<'a, R: Read> Body<'a, R> {
    pub(crate) fn empty() -> Self {
        Body {
            inner: Inner::Empty,
        }
    }

    pub(crate) fn bounded(input: &'a mut R, remaining: u64) -> Self {
        Body {
            inner: Inner::Bounded { input, remaining },
        }
    }

    pub(crate) fn chunked(input: &'a mut R) -> Self {
        Body {
            inner: Inner::Chunked {
                input,
                state: ChunkState::new(),
            },
        }
    }

    pub(crate) fn until_eof(input: &'a mut R) -> Self {
        Body {
            inner: Inner::UntilEof { input },
        }
    }

    /// Reads the whole body into memory, refusing anything larger than `limit`.
    ///
    /// The limit is checked before the buffer grows, not after: a peer that declares a
    /// gigabyte must cost a rejection, not a gigabyte of reservation. Java checked
    /// after writing, so it allocated `limit` plus the read size before giving up.
    pub fn read_to_vec(mut self, limit: usize) -> Result<Vec<u8>, Error> {
        let mut out = Vec::new();
        let mut buffer = [0u8; 8192];
        loop {
            let read = self.fill(&mut buffer)?;
            if read == 0 {
                return Ok(out);
            }
            if out.len() + read > limit {
                return Err(Error::BodyTooLarge { limit });
            }
            out.extend_from_slice(&buffer[..read]);
        }
    }

    /// The one framing machine. Returns `0` at the end of the body.
    fn fill(&mut self, buf: &mut [u8]) -> Result<usize, Error> {
        // A zero-length read is not "end of body" for the framing state, and reporting
        // it as such would end a body early for a caller that asked for nothing.
        if buf.is_empty() {
            return Ok(0);
        }
        match &mut self.inner {
            Inner::Empty => Ok(0),
            Inner::Bounded { input, remaining } => {
                if *remaining == 0 {
                    return Ok(0);
                }
                let want = (*remaining).min(buf.len() as u64) as usize;
                let read = read_some(input, &mut buf[..want])?;
                if read == 0 {
                    return Err(Error::TruncatedBody { missing: *remaining });
                }
                *remaining -= read as u64;
                Ok(read)
            }
            Inner::Chunked { input, state } => loop {
                if state.remaining > 0 {
                    let want = state.remaining.min(buf.len() as u64) as usize;
                    let read = read_some(input, &mut buf[..want])?;
                    if read == 0 {
                        return Err(Error::TruncatedChunk);
                    }
                    state.remaining -= read as u64;
                    if state.remaining == 0 {
                        consume_chunk_end(input, state)?;
                    }
                    return Ok(read);
                }
                if state.finished {
                    return Ok(0);
                }
                if !next_chunk(input, state)? {
                    return Ok(0);
                }
            },
            Inner::UntilEof { input } => read_some(input, buf),
        }
    }
}

impl<R: Read> Read for Body<'_, R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.fill(buf).map_err(io::Error::from)
    }
}

/// Reads the next chunk-size line. Returns `false` after the terminal chunk.
fn next_chunk<R: Read>(input: &mut R, state: &mut ChunkState) -> Result<bool, Error> {
    if !read_line(input, &mut state.line)? {
        return Err(Error::TruncatedChunk);
    }
    let size = {
        // Chunk extensions (`5;foo=bar`) have never been used by anything, but a peer
        // is allowed to send them and reading one as part of the size would fail.
        let line = &state.line;
        let end = line.iter().position(|&byte| byte == b';').unwrap_or(line.len());
        parse_hex(crate::trim_ascii(&line[..end]))?
    };
    if size == 0 {
        // Trailer section: header lines, then a blank line that may be the end of the
        // stream rather than an empty line — a peer closing right after the last chunk
        // has still delivered a complete body.
        //
        // 评审 P1-6：trailer 逐行读没有上限的话，对端可以一直滴灌合法的
        // "头一行 + 非空" 序列让这个循环永不返回。行数用 MAX_HEADERS 同款上限，
        // 字节数一并计入。
        let mut trailer_lines = 0usize;
        let mut trailer_bytes = 0usize;
        while read_line(input, &mut state.line)? && !state.line.is_empty() {
            trailer_lines += 1;
            trailer_bytes += state.line.len();
            if trailer_lines > crate::MAX_HEADERS || trailer_bytes > crate::MAX_HEADERS * 1024 {
                return Err(Error::TrailerOverrun);
            }
        }
        state.finished = true;
        return Ok(false);
    }
    state.remaining = size;
    Ok(true)
}

/// Consumes the line ending that closes a chunk's data.
///
/// Tolerant of a bare LF, strict about anything else: `abcXX` means the next read is
/// out of frame, and continuing would feed those bytes to the caller as if they were
/// payload. Catching the desync here is the reason this check exists at all — the cpio
/// reader downstream reports a framing loss only after it has already mis-parsed.
fn consume_chunk_end<R: Read>(input: &mut R, state: &mut ChunkState) -> Result<(), Error> {
    if !read_line(input, &mut state.line)? {
        return Err(Error::TruncatedChunk);
    }
    if !state.line.is_empty() {
        return Err(Error::BadChunkFrame(crate::latin1(&state.line)));
    }
    Ok(())
}

/// Parses a chunk size, rejecting overflow instead of wrapping it.
fn parse_hex(bytes: &[u8]) -> Result<u64, Error> {
    if bytes.is_empty() {
        return Err(Error::BadChunkSize(String::new()));
    }
    let mut value: u64 = 0;
    for &byte in bytes {
        let digit = match (byte as char).to_digit(16) {
            Some(digit) => digit as u64,
            None => return Err(Error::BadChunkSize(crate::latin1(bytes))),
        };
        value = match value.checked_mul(16).and_then(|value| value.checked_add(digit)) {
            Some(value) => value,
            // Java rejected every size above i64::MAX here; rejecting only what does
            // not fit in u64 keeps more legal framings and still refuses the attack.
            None => return Err(Error::BadChunkSize(crate::latin1(bytes))),
        };
    }
    Ok(value)
}

/// Writes a body as chunked transfer coding without ever holding it whole.
///
/// This is the sending side of the framing [`Body::chunked`] reads. It exists because an
/// archive has no length until it has been compressed: buffering it in order to measure
/// it would undo the reason for streaming it, and a `Content-Length` would require a
/// number nobody has yet. `Write` is the interface because that is what a compressor
/// wants to write into — the caller passes this to the encoder and never sees the frames.
///
/// `#[must_use]` because the terminal chunk comes only from [`ChunkedWriter::finish`].
/// Dropping the writer with bytes still buffered yields a frame sequence with no end,
/// which the receiver reports as a truncated upload rather than as a bug on this side —
/// the least diagnosable failure in the protocol, so the compiler is asked to object.
#[must_use = "a chunked body needs finish(), or the upload arrives truncated"]
pub struct ChunkedWriter<'a, W: Write> {
    inner: &'a mut W,
    /// Holds a partial frame; never larger than `chunk`.
    buffer: Vec<u8>,
    chunk: usize,
}

impl<'a, W: Write> ChunkedWriter<'a, W> {
    /// Starts a chunked body with `chunk` payload bytes per frame.
    pub fn new(inner: &'a mut W, chunk: usize) -> ChunkedWriter<'a, W> {
        let chunk = chunk.max(1);
        ChunkedWriter {
            inner,
            buffer: Vec::with_capacity(chunk),
            chunk,
        }
    }

    /// Emits the partial frame, then the terminal chunk, and flushes the stream.
    ///
    /// Consuming `self` is what makes "wrote the body but never ended it" unrepresentable
    /// after this call; the attribute on the type catches the case where it was never
    /// called at all.
    pub fn finish(mut self) -> io::Result<()> {
        self.emit()?;
        self.inner.write_all(b"0\r\n\r\n")?;
        self.inner.flush()
    }

    /// Writes whatever is buffered as one frame.
    fn emit(&mut self) -> io::Result<()> {
        if self.buffer.is_empty() {
            return Ok(());
        }
        let mut head = Vec::with_capacity(18);
        push_hex(&mut head, self.buffer.len());
        head.extend_from_slice(b"\r\n");
        self.inner.write_all(&head)?;
        self.inner.write_all(&self.buffer)?;
        self.inner.write_all(b"\r\n")?;
        self.buffer.clear();
        Ok(())
    }
}

impl<W: Write> Write for ChunkedWriter<'_, W> {
    /// Takes everything offered, emitting a frame whenever the buffer fills.
    ///
    /// Never reports a short write, so a caller's `write_all` does not have to loop on the
    /// frame boundaries — which would be the one place where the framing became visible
    /// to the layer above it.
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let mut rest = buf;
        while !rest.is_empty() {
            let room = self.chunk - self.buffer.len();
            let take = room.min(rest.len());
            self.buffer.extend_from_slice(&rest[..take]);
            rest = &rest[take..];
            if self.buffer.len() == self.chunk {
                self.emit()?;
            }
        }
        Ok(buf.len())
    }

    /// Emits the partial frame so the bytes are on the wire, then flushes the stream.
    ///
    /// A progress meter calls this to make "sent" mean sent; the framing stays legal
    /// because the partial frame is a frame.
    fn flush(&mut self) -> io::Result<()> {
        self.emit()?;
        self.inner.flush()
    }
}

/// Frames `body` as chunked transfer coding, terminal chunk included.
///
/// The buffered entry point, for the small control bodies; it is the same framer as
/// [`ChunkedWriter`], so the two cannot disagree about where a frame ends.
pub(crate) fn encode_chunked(body: &[u8], chunk: usize) -> Vec<u8> {
    debug_assert!(chunk > 0, "a zero chunk size would never make progress");
    let chunk = chunk.max(1);
    let mut out = Vec::with_capacity(body.len() + body.len() / chunk * 8 + 16);
    {
        let mut writer = ChunkedWriter::new(&mut out, chunk);
        writer
            .write_all(body)
            .expect("writing to a Vec cannot fail");
        writer.finish().expect("writing to a Vec cannot fail");
    }
    // An empty body produces exactly `0\r\n\r\n` here, which is what makes a zero-byte
    // upload a legal framing rather than a special case in the sender.
    out
}

/// Appends `value` in lowercase hexadecimal, without a `0x` and without padding.
fn push_hex(out: &mut Vec<u8>, value: usize) {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut buffer = [0u8; 16];
    let mut index = buffer.len();
    let mut rest = value;
    loop {
        index -= 1;
        buffer[index] = DIGITS[rest & 0xf];
        rest >>= 4;
        if rest == 0 {
            break;
        }
    }
    out.extend_from_slice(&buffer[index..]);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_sizes_parse_both_cases() {
        assert_eq!(parse_hex(b"0").unwrap(), 0);
        assert_eq!(parse_hex(b"1a").unwrap(), 26);
        assert_eq!(parse_hex(b"1A").unwrap(), 26);
        assert_eq!(parse_hex(b"ffff").unwrap(), 0xffff);
    }

    #[test]
    fn hex_sizes_reject_junk_empty_and_overflow() {
        assert!(parse_hex(b"").is_err());
        assert!(parse_hex(b"zz").is_err());
        assert!(parse_hex(b"-1").is_err());
        // 17 hex digits cannot fit in a u64; wrapping would silently accept it.
        assert!(parse_hex(b"10000000000000000").is_err());
        assert!(matches!(
            parse_hex(b"10000000000000000").unwrap_err(),
            Error::BadChunkSize(_)
        ));
    }

    #[test]
    fn empty_body_reports_end_immediately() {
        // `Inner::Empty` holds no reader, so the only thing to supply is the type.
        let body: Body<'_, &[u8]> = Body::empty();
        assert_eq!(body.read_to_vec(16).unwrap(), b"");
    }

    #[test]
    fn a_zero_length_read_does_not_end_a_body() {
        let mut input = "abc".as_bytes();
        let mut body = Body::bounded(&mut input, 3);
        assert_eq!(body.read(&mut []).unwrap(), 0);
        // Still three bytes to deliver; the empty read must not have counted as EOF.
        assert_eq!(body.read_to_vec(3).unwrap(), b"abc");
    }

    #[test]
    fn an_empty_body_is_just_the_terminal_chunk() {
        assert_eq!(encode_chunked(b"", 16), b"0\r\n\r\n");
    }

    #[test]
    fn payload_is_cut_at_the_chunk_size_and_the_last_frame_is_short() {
        let framed = encode_chunked(&vec![b'x'; 40_000], 16 * 1024);
        assert!(framed.starts_with(b"4000\r\n"));
        let mut rest = framed.as_slice();
        for expected in [16_384usize, 16_384, 7_232] {
            let end = rest.iter().position(|&byte| byte == b'\r').expect("size line");
            let hex = std::str::from_utf8(&rest[..end]).unwrap();
            assert_eq!(usize::from_str_radix(hex, 16).unwrap(), expected);
            let after = end + 2 + expected;
            assert_eq!(&rest[after..after + 2], b"\r\n", "frame {expected} closed");
            rest = &rest[after + 2..];
        }
        // 16384 + 16384 + 7232 is the whole payload, and the terminal chunk follows.
        assert_eq!(rest, b"0\r\n\r\n");
    }

    #[test]
    fn frames_written_by_the_encoder_read_back_through_the_decoder() {
        let payload: Vec<u8> = (0..40_000).map(|index| (index % 251) as u8).collect();
        let framed = encode_chunked(&payload, 16 * 1024);
        let mut cursor = framed.as_slice();
        let body = Body::chunked(&mut cursor);
        assert_eq!(body.read_to_vec(1 << 20).unwrap(), payload);
    }

    #[test]
    fn the_streaming_writer_and_the_buffered_framer_agree_byte_for_byte() {
        // One framer, two doors. If these ever diverge, an upload's framing depends on
        // which entry point the caller happened to use.
        for length in [0usize, 1, 16 * 1024 - 1, 16 * 1024, 16 * 1024 + 1, 40_000] {
            let payload = vec![b'q'; length];
            let framed = encode_chunked(&payload, 16 * 1024);
            let mut streamed = Vec::new();
            {
                let mut writer = ChunkedWriter::new(&mut streamed, 16 * 1024);
                // Irregular write sizes on purpose: a compressor emits 32 KiB at a time,
                // then a header, then three bytes.
                for piece in payload.chunks(7) {
                    writer.write_all(piece).unwrap();
                }
                writer.finish().unwrap();
            }
            assert_eq!(streamed, framed, "length {length}");
        }
    }

    #[test]
    fn flushing_an_unfinished_frame_keeps_the_framing_legal() {
        let mut out = Vec::new();
        {
            let mut writer = ChunkedWriter::new(&mut out, 1024);
            writer.write_all(b"partial").unwrap();
            writer.flush().unwrap();
            writer.write_all(b"more").unwrap();
            writer.finish().unwrap();
        }
        // The flush closed the first frame early instead of leaving it pending, so both
        // halves are frames and the terminal chunk still ends the body.
        assert_eq!(out, b"7\r\npartial\r\n4\r\nmore\r\n0\r\n\r\n");
    }

    #[test]
    fn until_eof_takes_everything_and_calls_it_done() {
        let mut input = &b"answer with no framing"[..];
        let body = Body::until_eof(&mut input);
        assert_eq!(body.read_to_vec(64).unwrap(), b"answer with no framing");
    }
}
