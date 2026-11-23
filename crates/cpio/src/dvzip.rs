//! DVZip, the block compression that wraps an AirDrop `/Upload` body.
//!
//! The format is a bare sequence of blocks with no header, no footer and no count:
//! four big-endian bytes of length, then that many bytes. The top bit of the length
//! is a flag, not part of the number — set means the block is stored verbatim, clear
//! means it is a zlib stream. That one bit is why the format needs no per-block type
//! field, and why a block that would grow under compression costs four bytes instead
//! of a decision.
//!
//! Blocks are independent: the compressor starts fresh every [`CHUNK`] bytes instead
//! of carrying a dictionary across the file. Independence is what lets a receiver
//! decode from any block boundary and lets a sender emit a block the moment it has
//! one, which is why a transfer can start before the file is done being read.

use std::io::{self, Read, Write};

/// Input bytes per block when we write a body.
pub const CHUNK: usize = 64 * 1024;

/// Largest payload length accepted from a block header when reading.
///
/// Deliberately larger than [`CHUNK`], and the distinction is not academic: tying
/// the two together is what broke the first real upload. A phone sends its blocks in
/// 128 KiB slices, so its third block arrived as a deflated length of 126136 — under
/// its own chunk size, over ours. The reader refused a body that was perfectly
/// framed, and because the refusal happens inside the archive reader the phone only
/// saw the transfer fail.
///
/// The length field *is* the framing. There is no second copy of the size to
/// cross-check against, so a cap cannot verify anything; all it can do is reject a
/// valid stream from a sender that chose bigger blocks than we did, or — with no cap
/// at all — allocate whatever four desynchronised bytes happen to say. One megabyte
/// keeps both failures away: any chunk size in real use (64, 128, 256 KiB) fits with
/// room to spare, and four random bytes still land under it only one time in four
/// thousand, after which the deflate itself fails on the garbage.
pub const MAX_BLOCK: usize = 1024 * 1024;

/// Low 31 bits of a block header: the payload length.
const LENGTH_MASK: u32 = 0x7fff_ffff;

/// High bit of a block header: the payload is stored, not deflated.
const STORED: u32 = 0x8000_0000;

/// True when these four bytes could be a block header.
///
/// Sniffing is how the archive reader tells a DVZip body from a gzip one and from a
/// bare archive without trusting the content type, so this has to be conservative. A
/// block never exceeds [`MAX_BLOCK`], and no cpio magic reduces to a plausible
/// length: `070707` reads as 808 million because ASCII digits are large bytes. Zero
/// is excluded too — an empty first block is legal in the format but has never been
/// worth producing, and treating an empty body as DVZip would hide a truncated
/// upload.
pub fn looks_like_block(header: &[u8]) -> bool {
    if header.len() < 4 {
        return false;
    }
    let raw = u32::from_be_bytes([header[0], header[1], header[2], header[3]]);
    let length = raw & LENGTH_MASK;
    length > 0 && (length as usize) <= MAX_BLOCK
}

/// Writes a DVZip body: each [`CHUNK`]-sized slice written here becomes one block,
/// deflated when that is smaller and stored when it is not.
///
/// Nothing is emitted until a block is full, so a caller that writes less than
/// 64 KiB in total produces one block at [`finish`](Encoder::finish) — the common
/// case for a text file, and the reason a small transfer costs four bytes of
/// overhead rather than a compressed stream that grew.
pub struct Encoder<W: Write> {
    out: W,
    block: Vec<u8>,
}

impl<W: Write> Encoder<W> {
    /// Wraps `out`; every block is written to it as it fills.
    pub fn new(out: W) -> Self {
        Self {
            out,
            block: Vec::with_capacity(CHUNK),
        }
    }

    /// Emits the final, partial block. Does not close the underlying stream.
    pub fn finish(&mut self) -> io::Result<()> {
        self.emit()?;
        self.out.flush()
    }

    /// Unwraps the encoder. Any buffered partial block is discarded, so call
    /// [`finish`](Encoder::finish) first.
    pub fn into_inner(self) -> W {
        self.out
    }

    fn emit(&mut self) -> io::Result<()> {
        if self.block.is_empty() {
            return Ok(());
        }
        let compressed = deflate(&self.block)?;
        if !compressed.is_empty() && compressed.len() < self.block.len() {
            let header = (compressed.len() as u32) & LENGTH_MASK;
            self.out.write_all(&header.to_be_bytes())?;
            self.out.write_all(&compressed)?;
        } else {
            // Storing is not a fallback, it is the better answer: the flag costs one
            // bit and saves a deflate that added bytes to incompressible data.
            let header = STORED | self.block.len() as u32;
            self.out.write_all(&header.to_be_bytes())?;
            self.out.write_all(&self.block)?;
        }
        self.block.clear();
        Ok(())
    }
}

impl<W: Write> Write for Encoder<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let mut rest = buf;
        while !rest.is_empty() {
            if self.block.len() == CHUNK {
                self.emit()?;
            }
            let take = (CHUNK - self.block.len()).min(rest.len());
            self.block.extend_from_slice(&rest[..take]);
            rest = &rest[take..];
        }
        Ok(buf.len())
    }

    /// A block cannot be flushed half-full: its length is the frame. This flushes
    /// only what has already been emitted.
    fn flush(&mut self) -> io::Result<()> {
        self.out.flush()
    }
}

/// Decodes a DVZip body, presenting it as the byte stream that was compressed.
///
/// One block is held at a time, so memory stays at two blocks regardless of upload
/// size. A defect in the middle of a transfer surfaces as an [`io::Error`] rather
/// than as a short file, because a short file that looks complete is the one failure
/// a receiver cannot detect afterwards.
pub struct Decoder<R: Read> {
    inner: R,
    block: Vec<u8>,
    position: usize,
    done: bool,
    /// Bytes consumed from `inner` so far, so a bad header can name its offset.
    offset: u64,
    /// The first [`HEAD_DUMP`] bytes of the body, kept only to explain a bad header.
    ///
    /// A refused length says what the four bytes were, which is not enough to tell a
    /// desynchronised reader from a sender that framed something else. 2026-11-19
    /// 20:57:55 is the case that forced this: an iPhone offered a 1.9 MB photo and the
    /// reader stopped 77 ms later at `offset 115` on `0x00000000`, so the first block
    /// was 111 bytes — legal per DVZ-3, and nothing like the 64 KiB of DVZ-2. Either
    /// the sender emitted a block that size or we read the body from the wrong place,
    /// and the four bytes at the failure point cannot say which.
    head: Vec<u8>,
    /// The first [`DECODED_DUMP`] bytes of the first block, after it was inflated.
    ///
    /// The raw head answers a different question than it looks like it answers: when the
    /// payload is deflated, all it shows past `78 9c` is entropy. Whether those 111 bytes
    /// were a cpio header the sender chose to compress or the middle of a stream we
    /// started reading too late is only visible in the *decoded* bytes — `070701…` means
    /// the sender really framed a tiny block, gibberish means we were not at a boundary.
    /// Worth the extra sixty bytes: reproducing this costs the person on the other phone
    /// another tap, so one log line has to be enough to decide.
    decoded: Vec<u8>,
    /// Blocks accepted before the refusal, so "the second block" can be said out loud.
    blocks: u32,
    /// Empty blocks skipped so far.
    ///
    /// Not part of [`Decoder::blocks`]: they carry nothing, and counting them as blocks
    /// would make the diagnostic say three when the sender framed two.
    empty: u32,
    /// How many empty blocks have been skipped since the last real one.
    ///
    /// Reset by every block with a payload. An endless run of zeros is not a body we can
    /// wait out, so it gets a ceiling; see [`MAX_EMPTY_RUN`].
    run: u32,
}

/// How much of the body [`Decoder::head`] keeps. Two block headers, one 64-byte block,
/// and a little slack: enough to see a framing mistake, short enough to fit a log line.
const HEAD_DUMP: usize = 96;

/// How much of the first block's inflate output [`Decoder::decoded`] keeps.
const DECODED_DUMP: usize = 64;

/// How many empty blocks in a row are still treated as "the sender paused".
///
/// An empty block is four zero bytes and says nothing; one of them between two real
/// blocks is a sender flushing its buffer before it is full, and skipping it is exactly
/// equivalent to those four bytes not being there. See [`Decoder::next_block`] for why
/// this is not an error at all. A thousand in a row is four kilobytes of zeros and no
/// sender does that, which makes it the right place to stop waiting and complain.
const MAX_EMPTY_RUN: u32 = 1024;

/// Bytes as space-separated hex, or `-` when there are none.
fn hex_of(bytes: &[u8]) -> String {
    if bytes.is_empty() {
        return "-".to_string();
    }
    let mut out = String::with_capacity(bytes.len() * 3);
    for (index, byte) in bytes.iter().enumerate() {
        if index > 0 {
            out.push(' ');
        }
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

impl<R: Read> Decoder<R> {
    /// Wraps `inner`, which must be positioned at a block header.
    pub fn new(inner: R) -> Self {
        Self {
            inner,
            block: Vec::new(),
            position: 0,
            done: false,
            offset: 0,
            head: Vec::new(),
            decoded: Vec::new(),
            blocks: 0,
            empty: 0,
            run: 0,
        }
    }

    /// Copies bytes of the body into [`Decoder::head`] until it is full.
    fn keep(&mut self, bytes: &[u8]) {
        let room = HEAD_DUMP - self.head.len();
        if room > 0 {
            let take = room.min(bytes.len());
            self.head.extend_from_slice(&bytes[..take]);
        }
    }

    /// Everything the decoder knows about how the body started, as one clause.
    ///
    /// Only ever built on a refusal, so the cost of formatting a hundred bytes one at a
    /// time is paid at most once per transfer.
    fn diagnosis(&self) -> String {
        let mut out = format!("{} block(s) were read first", self.blocks);
        if self.empty > 0 {
            out.push_str(&format!(", {} empty block(s) skipped", self.empty));
        }
        out.push_str(&format!("; the body began 0x{}", hex_of(&self.head)));
        out.push_str(&format!("; block one decoded to 0x{}", hex_of(&self.decoded)));
        out
    }

    /// Loads the next block into `self.block`; `false` at a clean end of body.
    ///
    /// # An empty block is not an error
    ///
    /// The length field *is* the framing (DVZ-1), and a zero length carries no payload at
    /// all. Skipping it is therefore exactly equivalent to those four bytes not being in
    /// the body: nothing is inflated, nothing is produced, nothing takes a place. This
    /// used to be an "implausible length" refusal, and that refusal threw away a transfer
    /// whose framing was perfect — all the person on the phone saw was the archive could
    /// not be read.
    ///
    /// 2026-11-19 21:40:08 is the case in full. The body was, byte for byte:
    ///
    /// ```text
    /// 00 00 00 55  78 9c …    85 bytes, inflating to a cpio "070701" header
    /// 00 01 db 0e  78 9c …    121614 bytes, inflating to exactly 128 KiB
    /// 00 00 00 00             an empty block
    /// ```
    ///
    /// The arithmetic settles the question of blame: 4 + 85 + 4 + 121614 = 121707, and
    /// 121707 is the offset the refusal named. We were on a perfect block boundary and
    /// had read every byte of two valid blocks, so the only wrong judgement in the chain
    /// was ours about the last four bytes. The sender flushes when its buffer is empty;
    /// that is a zero-length block, and the format has no way to say "nothing" otherwise.
    ///
    /// [`MAX_EMPTY_RUN`] keeps "skip it" from turning into "wait forever" on a body that
    /// degenerates into zeros.
    fn next_block(&mut self) -> io::Result<bool> {
        loop {
            let at = self.offset;
            let mut header = [0u8; 4];
            let mut got = 0;
            while got < 4 {
                match self.inner.read(&mut header[got..]) {
                    Ok(0) if got == 0 => {
                        self.done = true;
                        return Ok(false);
                    }
                    Ok(0) => {
                        self.keep(&header[..got]);
                        return Err(io::Error::new(
                            io::ErrorKind::UnexpectedEof,
                            "DVZip block header truncated",
                        ));
                    }
                    Ok(n) => got += n,
                    Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Err(e) => return Err(e),
                }
            }
            self.offset += 4;
            self.keep(&header);
            let raw = u32::from_be_bytes(header);
            let length = (raw & LENGTH_MASK) as usize;
            if length == 0 {
                self.empty += 1;
                self.run += 1;
                if self.run > MAX_EMPTY_RUN {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!(
                            "DVZip body has no end: {} empty blocks in a row at offset {at}; {}",
                            self.run,
                            self.diagnosis()
                        ),
                    ));
                }
                continue;
            }
            self.run = 0;
            if length > MAX_BLOCK {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!(
                        "implausible DVZip block length {length} (header 0x{raw:08x} at offset {at}); {}",
                        self.diagnosis()
                    ),
                ));
            }

            let mut payload = vec![0u8; length];
            let mut filled = 0;
            while filled < length {
                let read = match self.inner.read(&mut payload[filled..]) {
                    Ok(n) => n,
                    Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Err(e) => return Err(e),
                };
                if read == 0 {
                    self.keep(&payload[..filled]);
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        format!(
                            "DVZip block truncated at {filled} of {length}; {}",
                            self.diagnosis()
                        ),
                    ));
                }
                filled += read;
            }
            self.offset += length as u64;
            self.keep(&payload);

            self.block = if raw & STORED != 0 {
                payload
            } else {
                inflate(&payload)?
            };
            // Only the first block: the question this answers is about where the body
            // started, and block two onwards would push the first out of the log line.
            if self.decoded.is_empty() {
                let take = DECODED_DUMP.min(self.block.len());
                self.decoded.extend_from_slice(&self.block[..take]);
            }
            self.blocks += 1;
            self.position = 0;
            return Ok(true);
        }
    }
}

impl<R: Read> Read for Decoder<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        while self.position == self.block.len() {
            if self.done || !self.next_block()? {
                return Ok(0);
            }
        }
        let take = buf.len().min(self.block.len() - self.position);
        buf[..take].copy_from_slice(&self.block[self.position..self.position + take]);
        self.position += take;
        Ok(take)
    }
}

/// Inflates one block into a buffer sized by what the block actually yields.
///
/// The output cannot be sized from the header: the header carries the *compressed*
/// length, and how far deflate got on this particular slice is only knowable by
/// inflating it. A fixed output buffer is therefore wrong in the quietest possible
/// way — it does not fail, it returns a short block, and the archive reader then
/// reports a corrupt member many kilobytes later. That is exactly what a 64 KiB
/// buffer did to the first 128 KiB block a phone sent.
///
/// The initial guess is twice the compressed size, which covers the usual case (a
/// block is a slice of a file, and slices of files compress) in one allocation.
/// The largest output one compressed block may inflate to (评审 P1-1)。
///
/// zlib 的极限压缩比约 1000:1：`MAX_BLOCK` 只限压缩后 1 MiB，一个合法块可解出
/// 约 1 GiB，而 `read_to_end` 会照单全收。原始块本来就是文件切片（≤ [`MAX_BLOCK`]），
/// 所以输出上限取 [`MAX_BLOCK`] 的两倍——既给重整路径留了余量，又把放大钉在 2 MiB。
const MAX_INFLATED: usize = 2 * MAX_BLOCK;

fn inflate(payload: &[u8]) -> io::Result<Vec<u8>> {
    let mut out = Vec::with_capacity((payload.len() * 2).max(1024));
    let mut decoder = flate2::read::ZlibDecoder::new(payload).take(MAX_INFLATED as u64 + 1);
    decoder.read_to_end(&mut out)?;
    if out.len() > MAX_INFLATED {
        // take 留了 1 字节余量：读满即说明对端声明的块远超任何合法文件切片。
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "inflated block exceeds the output ceiling",
        ));
    }
    Ok(out)
}

fn deflate(block: &[u8]) -> io::Result<Vec<u8>> {
    let mut encoder =
        flate2::write::ZlibEncoder::new(Vec::with_capacity(block.len()), flate2::Compression::default());
    encoder.write_all(block)?;
    encoder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic, barely compressible bytes.
    fn noise(seed: u32, len: usize) -> Vec<u8> {
        let mut state = seed | 1;
        (0..len)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                (state >> 24) as u8
            })
            .collect()
    }

    fn round_trip(data: &[u8]) -> Vec<u8> {
        let mut encoder = Encoder::new(Vec::new());
        encoder.write_all(data).unwrap();
        encoder.finish().unwrap();
        let body = encoder.into_inner();

        let mut decoded = Vec::new();
        Decoder::new(&body[..]).read_to_end(&mut decoded).unwrap();
        decoded
    }

    #[test]
    fn compresses_a_compressible_body() {
        let data = vec![b'a'; 200 * 1024];
        let mut encoder = Encoder::new(Vec::new());
        encoder.write_all(&data).unwrap();
        encoder.finish().unwrap();
        let body = encoder.into_inner();
        assert!(body.len() < data.len() / 10, "body was {} bytes", body.len());
        assert_eq!(round_trip(&data), data);
    }

    #[test]
    fn stores_an_incompressible_body() {
        let data = noise(7, 4096);
        let mut encoder = Encoder::new(Vec::new());
        encoder.write_all(&data).unwrap();
        encoder.finish().unwrap();
        let body = encoder.into_inner();
        let raw = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
        assert_ne!(raw & STORED, 0, "stored flag should be set for noise");
        assert_eq!(raw & LENGTH_MASK, data.len() as u32);
        assert_eq!(round_trip(&data), data);
    }

    #[test]
    fn splits_at_chunk_boundaries() {
        let data = noise(11, CHUNK * 2 + 1234);
        let mut encoder = Encoder::new(Vec::new());
        encoder.write_all(&data).unwrap();
        encoder.finish().unwrap();
        let body = encoder.into_inner();

        // Walk the framing rather than the decoder, so a decoder bug cannot hide a
        // writer bug.
        let mut at = 0;
        let mut blocks = 0;
        while at < body.len() {
            let raw = u32::from_be_bytes([body[at], body[at + 1], body[at + 2], body[at + 3]]);
            at += 4 + (raw & LENGTH_MASK) as usize;
            blocks += 1;
        }
        assert_eq!(at, body.len());
        assert_eq!(blocks, 3);
        assert_eq!(round_trip(&data), data);
    }

    #[test]
    fn empty_body_decodes_to_nothing() {
        let body = Vec::new();
        let mut decoded = Vec::new();
        Decoder::new(&body[..]).read_to_end(&mut decoded).unwrap();
        assert!(decoded.is_empty());
    }

    #[test]
    fn a_phone_sized_block_decodes() {
        // 128 KiB slices, which is what an iPhone sends and what our own encoder never
        // produces. The reference sender records the failure this guards against: its
        // reader had `MAX_BLOCK` tied to its own 64 KiB chunk, so a phone's third block
        // -- deflated to 126136 B, perfectly framed -- was refused, and from the phone's
        // side the transfer simply failed.
        const PHONE: usize = 128 * 1024;
        let plain = noise(9, PHONE * 2 + 500);
        let mut body = Vec::new();
        for slice in plain.chunks(PHONE) {
            let compressed = deflate(slice).unwrap();
            if compressed.len() < slice.len() {
                body.extend_from_slice(&(compressed.len() as u32).to_be_bytes());
                body.extend_from_slice(&compressed);
            } else {
                body.extend_from_slice(&(0x8000_0000u32 | slice.len() as u32).to_be_bytes());
                body.extend_from_slice(slice);
            }
        }
        let mut decoded = Vec::new();
        Decoder::new(&body[..]).read_to_end(&mut decoded).unwrap();
        assert_eq!(decoded, plain, "a 128 KiB block is a legal block");
    }

    #[test]
    fn rejects_implausible_length() {
        let body = (MAX_BLOCK as u32 + 1).to_be_bytes();
        let err = Decoder::new(&body[..]).read_to_end(&mut Vec::new()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("implausible"), "{err}");
    }

    #[test]
    fn a_refused_header_reports_what_came_before_it() {
        // A four-byte stored block followed by a length over the cap. The refusal is about
        // the second header, but the bytes that matter are the first block's: without them
        // a log line cannot tell a sender that framed an 111-byte block from a reader that
        // started at the wrong offset. 2026-11-19 20:57:55 was exactly that question.
        //
        // The second header used to be four zero bytes, which is no longer a refusal --
        // 2026-11-19 21:40:08 reclassified an empty block as legal, see `next_block`.
        let mut body = Vec::new();
        body.extend_from_slice(&(0x8000_0000u32 | 4).to_be_bytes());
        body.extend_from_slice(b"abcd");
        body.extend_from_slice(&(MAX_BLOCK as u32 + 1).to_be_bytes());

        let err = Decoder::new(&body[..]).read_to_end(&mut Vec::new()).unwrap_err();
        let text = err.to_string();
        assert!(text.contains("at offset 8"), "{text}");
        assert!(text.contains("1 block(s) were read first"), "{text}");
        assert!(
            text.contains("the body began 0x80 00 00 04 61 62 63 64 00 10 00 01"),
            "the body so far should be in the message: {text}"
        );
        assert!(
            text.contains("block one decoded to 0x61 62 63 64"),
            "the first block's content should be in the message: {text}"
        );
    }

    #[test]
    fn an_empty_block_between_two_real_ones_is_skipped() {
        // Byte for byte the body that failed at 2026-11-19 21:40:08: two framed blocks and
        // four zeros the sender emitted when it flushed an empty buffer. The arithmetic
        // proves we were at a correct boundary -- 4 + 85 + 4 + 121614 = 121707, the offset
        // the old code refused -- so only the empty block stands between this and a
        // complete decode.
        let first = b"070701 the start of a cpio header";
        let second = noise(13, 128 * 1024);
        let mut body = Vec::new();
        body.extend_from_slice(&(0x8000_0000u32 | first.len() as u32).to_be_bytes());
        body.extend_from_slice(first);
        body.extend_from_slice(&[0u8; 4]);
        body.extend_from_slice(&(0x8000_0000u32 | second.len() as u32).to_be_bytes());
        body.extend_from_slice(&second);

        let mut decoded = Vec::new();
        Decoder::new(&body[..])
            .read_to_end(&mut decoded)
            .expect("an empty block carries nothing and must not fail the body");
        let mut expected = Vec::from(&first[..]);
        expected.extend_from_slice(&second);
        assert_eq!(decoded, expected);
    }

    #[test]
    fn a_body_of_zeros_does_not_hang() {
        let body = vec![0u8; 4 * (MAX_EMPTY_RUN as usize + 1)];
        let err = Decoder::new(&body[..]).read_to_end(&mut Vec::new()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("has no end"), "{err}");
    }

    #[test]
    fn a_trailing_empty_block_ends_the_body_cleanly() {
        let mut body = Vec::new();
        body.extend_from_slice(&(0x8000_0000u32 | 3).to_be_bytes());
        body.extend_from_slice(b"abc");
        body.extend_from_slice(&[0u8; 4]);

        let mut decoded = Vec::new();
        Decoder::new(&body[..]).read_to_end(&mut decoded).unwrap();
        assert_eq!(decoded, b"abc");
    }

    #[test]
    fn rejects_truncated_payload() {
        let body = [0u8, 0, 0, 10, 1, 2, 3];
        let err = Decoder::new(&body[..]).read_to_end(&mut Vec::new()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::UnexpectedEof);
    }

    #[test]
    fn rejects_truncated_header() {
        let body = [0u8, 0, 0];
        let err = Decoder::new(&body[..]).read_to_end(&mut Vec::new()).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::UnexpectedEof);
    }

    #[test]
    fn sniffing_rejects_cpio_magic() {
        assert!(!looks_like_block(b"070701"));
        assert!(!looks_like_block(b"070707"));
        assert!(!looks_like_block(&[0, 0, 0, 0]));
        assert!(!looks_like_block(&[0, 0, 0]));
        assert!(looks_like_block(&[0, 0, 0, 4]));
        assert!(looks_like_block(&(MAX_BLOCK as u32).to_be_bytes()));
    }
}
