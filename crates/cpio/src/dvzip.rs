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
        }
    }

    /// Loads the next block into `self.block`; `false` at a clean end of body.
    fn next_block(&mut self) -> io::Result<bool> {
        let at = self.offset;
        let mut header = [0u8; 4];
        let mut got = 0;
        while got < 4 {
            match self.inner.read(&mut header[got..]) {
                Ok(0) if got == 0 => {
                    self.done = true;
                    return Ok(false);
                }
                Ok(0) => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "DVZip block header truncated")),
                Ok(n) => got += n,
                Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e),
            }
        }
        self.offset += 4;
        let raw = u32::from_be_bytes(header);
        let length = (raw & LENGTH_MASK) as usize;
        if length == 0 || length > MAX_BLOCK {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!(
                    "implausible DVZip block length {length} (header 0x{raw:08x} at offset {at})"
                ),
            ));
        }

        let mut payload = vec![0u8; length];
        let mut filled = 0;
        while filled < length {
            match self.inner.read(&mut payload[filled..]) {
                Ok(0) => {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        format!("DVZip block truncated at {filled} of {length}"),
                    ))
                }
                Ok(n) => filled += n,
                Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e),
            }
        }
        self.offset += length as u64;

        self.block = if raw & STORED != 0 {
            payload
        } else {
            inflate(&payload)?
        };
        self.position = 0;
        Ok(true)
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
fn inflate(payload: &[u8]) -> io::Result<Vec<u8>> {
    let mut out = Vec::with_capacity((payload.len() * 2).max(1024));
    flate2::read::ZlibDecoder::new(payload).read_to_end(&mut out)?;
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
