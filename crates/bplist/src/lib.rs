//! Apple binary property list (`bplist00`) codec.
//!
//! AirDrop does not use OPACK on the paths this stack serves. The `/Ask` and
//! `/Discover` bodies are binary plists, and the media-capabilities blob nested
//! inside a `/Discover` answer is JSON carried as plist **data**. So a plist
//! codec is the whole requirement; there is nothing else to implement.
//!
//! # Value model
//!
//! [`Value`] keeps dictionaries as insertion-ordered pairs rather than a hash
//! map. Two reasons:
//!
//! * Apple compares plists by bytes on some paths, so field order is observable.
//! * A hash map would need `HashMap<String, Value>` and lose the order, and the
//!   `IndexMap` crate is a dependency this crate does not want.
//!
//! Lookups are linear. Real payloads have fewer than twenty keys, so a map would
//! be slower than the scan for the shape that actually arrives.
//!
//! # Fidelity
//!
//! This is a byte-for-byte port of the Java implementation that shipped in
//! `repo-v2`, including the details that were expensive to discover:
//!
//! * Strings decode as ISO-8859-1 when the marker says `0x5`, UTF-16BE when it
//!   says `0x6`, UTF-8 when `0x7`. Writing picks Latin-1 for ASCII-only text and
//!   UTF-16BE otherwise, which is what Apple's encoder does.
//! * Objects are laid out parent-first, then children, and the reference width is
//!   derived from the object count while the offset width is derived from the
//!   table offset. Both widths are written into the trailer, and a reader that
//!   guesses them differently sees a corrupt file rather than an error.
//! * The 32-byte trailer is `[6 unused][offset width][ref width][count:8]
//!   [top object:8][table offset:8]`, all big-endian.

#![forbid(unsafe_code)]

use std::fmt;

/// Seconds between the Unix epoch and Apple's 2001-01-01 reference date.
const APPLE_EPOCH: f64 = 978_307_200.0;
const MAGIC: &[u8; 8] = b"bplist00";
const TRAILER_SIZE: usize = 32;
const MAX_DEPTH: usize = 32;
const MAX_OBJECTS: u64 = 1 << 20;

/// A decoded property list value.
#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    /// `0x00`, also used for the `0x0f` fill byte.
    Null,
    Bool(bool),
    /// Every integer width collapses to `i64`; the original width is not kept
    /// because nothing in the protocol asks for it back.
    Integer(i64),
    Real(f64),
    /// Seconds since the Unix epoch. Apple stores seconds since 2001, which is
    /// converted on both sides so callers never see the reference date.
    Date(f64),
    Data(Vec<u8>),
    String(String),
    Array(Vec<Value>),
    /// Insertion-ordered key/value pairs.
    Dict(Vec<(String, Value)>),
}

impl Value {
    /// Builds a dictionary from alternating key/value arguments.
    ///
    /// ```ignore
    /// let ask = dict([("SenderComputerName", "Air".into())]);
    /// ```
    pub fn dict<I, K>(pairs: I) -> Value
    where
        I: IntoIterator<Item = (K, Value)>,
        K: Into<String>,
    {
        Value::Dict(pairs.into_iter().map(|(k, v)| (k.into(), v)).collect())
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::String(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_data(&self) -> Option<&[u8]> {
        match self {
            Value::Data(d) => Some(d),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[Value]> {
        match self {
            Value::Array(a) => Some(a),
            _ => None,
        }
    }

    pub fn as_dict(&self) -> Option<&[(String, Value)]> {
        match self {
            Value::Dict(d) => Some(d),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Value::Integer(i) => Some(*i),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Value::Bool(b) => Some(*b),
            // Apple writes flags as booleans but readers in the wild also write 0/1.
            Value::Integer(i) => Some(*i != 0),
            _ => None,
        }
    }

    /// Dictionary lookup. `None` for a missing key *or* a non-dictionary value,
    /// so callers can chain without matching on shape first.
    pub fn get(&self, key: &str) -> Option<&Value> {
        self.as_dict()?
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v)
    }

    pub fn get_str(&self, key: &str) -> Option<&str> {
        self.get(key)?.as_str()
    }

    pub fn get_data(&self, key: &str) -> Option<&[u8]> {
        self.get(key)?.as_data()
    }

    pub fn get_array(&self, key: &str) -> &[Value] {
        self.get(key)
            .and_then(Value::as_array)
            .unwrap_or(&[])
    }

    pub fn get_i64(&self, key: &str, fallback: i64) -> i64 {
        self.get(key).and_then(Value::as_i64).unwrap_or(fallback)
    }

    pub fn get_bool(&self, key: &str, fallback: bool) -> bool {
        self.get(key).and_then(Value::as_bool).unwrap_or(fallback)
    }
}

/// Everything that can go wrong while decoding.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Error {
    /// Fewer bytes than the header plus the trailer.
    TooShort,
    /// The eight-byte magic is not `bplist00`.
    BadMagic,
    /// A width field in the trailer is zero or wider than eight bytes.
    BadWidth(u8),
    /// The object count is zero or implausible.
    BadCount(u64),
    /// The top-object index is outside the object table.
    BadTopObject(u64),
    /// The offset table would run past the trailer.
    BadOffsetTable,
    /// An object offset points outside the payload.
    BadObjectOffset(u32),
    /// An object reference points outside the object table.
    BadReference(u32),
    /// A marker byte this codec does not implement.
    UnsupportedMarker(u8),
    /// A value claims more bytes than the payload holds.
    Truncated,
    /// Nesting exceeded [`MAX_DEPTH`].
    TooDeep,
    /// An object references itself directly or transitively.
    Cyclic(u32),
    /// An extended length is malformed.
    BadExtendedLength,
    /// The root object is a dictionary was required but it was not one.
    NotADictionary,
    /// The shared-reference DAG expands past the decode budget.
    TooManyNodes,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::TooShort => write!(f, "plist shorter than header plus trailer"),
            Error::BadMagic => write!(f, "not a bplist00 payload"),
            Error::BadWidth(w) => write!(f, "bad field width {w}"),
            Error::BadCount(n) => write!(f, "implausible object count {n}"),
            Error::BadTopObject(n) => write!(f, "top object {n} out of range"),
            Error::BadOffsetTable => write!(f, "offset table outside payload"),
            Error::BadObjectOffset(i) => write!(f, "object {i} offset outside payload"),
            Error::BadReference(i) => write!(f, "object reference {i} out of range"),
            Error::UnsupportedMarker(m) => write!(f, "unsupported marker 0x{m:02x}"),
            Error::Truncated => write!(f, "object runs past payload end"),
            Error::TooDeep => write!(f, "nesting deeper than {MAX_DEPTH}"),
            Error::Cyclic(i) => write!(f, "cyclic object reference at {i}"),
            Error::BadExtendedLength => write!(f, "malformed extended length"),
            Error::NotADictionary => write!(f, "root object is not a dictionary"),
            Error::TooManyNodes => write!(f, "shared references expand past the decode budget"),
        }
    }
}

impl std::error::Error for Error {}

/// Decodes the root object of a `bplist00` payload.
pub fn parse(payload: &[u8]) -> Result<Value, Error> {
    let mut reader = Reader::new(payload)?;
    let top = reader.top;
    reader.object(top, 0)
}

/// Decodes a payload whose root must be a dictionary.
///
/// Every request and answer in the protocol is a dictionary, and every caller
/// immediately does `get(...)` on it, so the shape check belongs here rather
/// than being repeated at each call site.
pub fn parse_dict(payload: &[u8]) -> Result<Value, Error> {
    let root = parse(payload)?;
    match root {
        Value::Dict(_) => Ok(root),
        _ => Err(Error::NotADictionary),
    }
}

/// Encodes a value as a `bplist00` payload.
pub fn encode(value: &Value) -> Vec<u8> {
    Encoder::new().encode(value)
}

struct Reader<'a> {
    data: &'a [u8],
    offsets: Vec<usize>,
    ref_size: usize,
    top: u32,
    visiting: Vec<bool>,
    /// 剩余可解码节点数。`visiting` 只防环不防 DAG：一份 184 字节、33 层共享引用的
    /// 报文能展开 2^33 个 `Value`（评审 P0-2/P1-3），预算把展开总量钉死。
    budget: usize,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Result<Reader<'a>, Error> {
        if data.len() < MAGIC.len() + TRAILER_SIZE {
            return Err(Error::TooShort);
        }
        if &data[..MAGIC.len()] != MAGIC {
            return Err(Error::BadMagic);
        }

        let trailer = data.len() - TRAILER_SIZE;
        let offset_width = data[trailer + 6] as usize;
        let ref_width = data[trailer + 7] as usize;
        // Both fields carry a *byte* count, not a bit count, and any value from
        // 1 to 8 is legal: Apple's own reader (`_getSizedInt`) shifts the bytes
        // in one at a time and never demands a power of two. Our writer only ever
        // picks 1/2/4/8 (see `width_for`), but iOS widens to three bytes once an
        // object table or an object index crosses 0xffff — which is exactly what
        // a multi-megabyte `FileIcon` in an `/Ask` body does. This check used to
        // mirror `validWidth()` in the legacy Java port and rejected those
        // payloads with "bad field width 3"; the restriction was stricter than
        // the format and was inherited, not required.
        for width in [offset_width, ref_width] {
            if !(1..=8).contains(&width) {
                return Err(Error::BadWidth(width as u8));
            }
        }

        let count = read_unsigned(data, trailer + 8, 8)
            .ok_or(Error::Truncated)?;
        let top = read_unsigned(data, trailer + 16, 8)
            .ok_or(Error::Truncated)?;
        let table = read_unsigned(data, trailer + 24, 8)
            .ok_or(Error::Truncated)? as usize;

        if count == 0 || count > MAX_OBJECTS {
            return Err(Error::BadCount(count));
        }
        if top >= count {
            return Err(Error::BadTopObject(top));
        }
        // 全程 checked：三个数都是对端从报文尾部塞进来的，`table + count * width`
        // 在 usize 上会回绕（评审 P0-2：`table = u64::MAX, count = 1, width = 8`
        // 时 release 下 `table + 8` 回绕成 7，旧检查放行，随后越界 panic）。
        let table_end = table
            .checked_add((count as usize).checked_mul(offset_width).ok_or(Error::BadOffsetTable)?)
            .ok_or(Error::BadOffsetTable)?;
        if table < MAGIC.len() || table_end > trailer {
            return Err(Error::BadOffsetTable);
        }

        // 容量按表的真实跨度钳制，不按对端声称的 count 预分配。
        let mut offsets = Vec::with_capacity(((trailer - table) / offset_width).min(count as usize));
        for i in 0..count as usize {
            let offset = read_unsigned(data, table + i * offset_width, offset_width)
                .ok_or(Error::BadObjectOffset(i as u32))? as usize;
            if offset < MAGIC.len() || offset >= trailer {
                return Err(Error::BadObjectOffset(i as u32));
            }
            offsets.push(offset);
        }

        let visiting = vec![false; offsets.len()];
        Ok(Reader {
            data,
            offsets,
            ref_size: ref_width,
            top: top as u32,
            visiting,
            budget: MAX_OBJECTS as usize,
        })
    }

    fn object(&mut self, index: u32, depth: usize) -> Result<Value, Error> {
        if depth > MAX_DEPTH {
            return Err(Error::TooDeep);
        }
        let index_usize = index as usize;
        if index_usize >= self.offsets.len() {
            return Err(Error::BadReference(index));
        }
        if self.visiting[index_usize] {
            return Err(Error::Cyclic(index));
        }
        if self.budget == 0 {
            return Err(Error::TooManyNodes);
        }
        self.budget -= 1;
        self.visiting[index_usize] = true;

        let offset = self.offsets[index_usize];
        let marker = self.data[offset];
        let kind = marker >> 4;
        let info = (marker & 0x0f) as usize;

        let value = match kind {
            0x0 => match marker {
                0x00 | 0x0f => Value::Null,
                0x08 => Value::Bool(false),
                0x09 => Value::Bool(true),
                other => return Err(Error::UnsupportedMarker(other)),
            },
            0x1 => {
                let width = 1usize << info;
                self.need(offset + 1, width)?;
                // Integers are read as *unsigned* at their stored width, which is
                // both what Apple writes and what the Java implementation this
                // replaced did. Apple only ever emits a negative integer at eight
                // bytes (or sixteen, where the low eight carry the value), always
                // in two's complement — so the eight-byte case already comes out
                // negative through the `as i64` cast, while narrower widths are
                // always non-negative.
                //
                // Sign-extending the narrow widths is a trap: our own writer picks
                // one byte for 0x80..0xff, so 128 would decode as -128 and fail to
                // round-trip. Do not "fix" this back.
                let (start, take) = if width > 8 {
                    (offset + 1 + width - 8, 8)
                } else {
                    (offset + 1, width)
                };
                Value::Integer(read_unsigned(self.data, start, take)
                    .ok_or(Error::Truncated)? as i64)
            }
            0x2 => match info {
                0x2 => {
                    self.need(offset + 1, 4)?;
                    let bits = read_unsigned(self.data, offset + 1, 4)
                        .ok_or(Error::Truncated)? as u32;
                    Value::Real(f32::from_bits(bits) as f64)
                }
                0x3 => {
                    self.need(offset + 1, 8)?;
                    Value::Real(f64::from_bits(
                        read_unsigned(self.data, offset + 1, 8).ok_or(Error::Truncated)?,
                    ))
                }
                _ => return Err(Error::UnsupportedMarker(marker)),
            },
            0x3 => {
                if info != 0x3 {
                    return Err(Error::UnsupportedMarker(marker));
                }
                self.need(offset + 1, 8)?;
                let seconds = f64::from_bits(
                    read_unsigned(self.data, offset + 1, 8).ok_or(Error::Truncated)?,
                );
                Value::Date(seconds + APPLE_EPOCH)
            }
            0x4 => {
                let (start, count) = self.length(offset, info)?;
                self.need(start, count)?;
                Value::Data(self.data[start..start + count].to_vec())
            }
            0x5 => {
                let (start, count) = self.length(offset, info)?;
                self.need(start, count)?;
                // ISO-8859-1 is byte-for-byte Latin-1, so each byte is a char.
                Value::String(self.data[start..start + count].iter().map(|&b| b as char).collect())
            }
            0x6 => {
                let (start, count) = self.length(offset, info)?;
                // count 是对端给的扩展长度，count*2 在 usize 上会回绕成 0（评审 P1-4），
                // 回绕后 need 放行、切片拿到空串——比 panic 更难查。
                let bytes = count.checked_mul(2).ok_or(Error::Truncated)?;
                self.need(start, bytes)?;
                Value::String(decode_utf16be(&self.data[start..start + bytes]))
            }
            0x7 => {
                let (start, count) = self.length(offset, info)?;
                self.need(start, count)?;
                Value::String(String::from_utf8_lossy(&self.data[start..start + count]).into_owned())
            }
            0x8 => {
                let width = info + 1;
                self.need(offset + 1, width)?;
                Value::Integer(read_unsigned(self.data, offset + 1, width)
                    .ok_or(Error::Truncated)? as i64)
            }
            0xa | 0xc => {
                let (start, count) = self.length(offset, info)?;
                if count > self.data.len() / self.ref_size {
                    return Err(Error::Truncated);
                }
                self.need(start, count * self.ref_size)?;
                let mut items = Vec::with_capacity(count);
                for i in 0..count {
                    let reference = read_unsigned(self.data, start + i * self.ref_size, self.ref_size)
                        .ok_or(Error::Truncated)? as u32;
                    items.push(self.object(reference, depth + 1)?);
                }
                Value::Array(items)
            }
            0xd => {
                let (start, count) = self.length(offset, info)?;
                if count > self.data.len() / self.ref_size {
                    return Err(Error::Truncated);
                }
                self.need(start, count * self.ref_size * 2)?;
                let mut pairs = Vec::with_capacity(count);
                for i in 0..count {
                    let key_ref = read_unsigned(self.data, start + i * self.ref_size, self.ref_size)
                        .ok_or(Error::Truncated)? as u32;
                    let value_ref = read_unsigned(
                        self.data,
                        start + (count + i) * self.ref_size,
                        self.ref_size,
                    )
                    .ok_or(Error::Truncated)? as u32;
                    let key = self.object(key_ref, depth + 1)?;
                    let value = self.object(value_ref, depth + 1)?;
                    let key = match key {
                        Value::String(s) => s,
                        other => format!("{other:?}"),
                    };
                    pairs.push((key, value));
                }
                Value::Dict(pairs)
            }
            _ => return Err(Error::UnsupportedMarker(marker)),
        };

        self.visiting[index_usize] = false;
        Ok(value)
    }

    /// Resolves a variable-length object into `(payload offset, element count)`.
    ///
    /// The count is `info` itself unless it is `0x0f`, in which case an integer
    /// object follows and carries the real count.
    fn length(&self, offset: usize, info: usize) -> Result<(usize, usize), Error> {
        if info != 0x0f {
            return Ok((offset + 1, info));
        }
        self.need(offset + 1, 2)?;
        let marker = self.data[offset + 1];
        if marker >> 4 != 0x1 {
            return Err(Error::BadExtendedLength);
        }
        let width = 1usize << (marker & 0x0f);
        if width > 8 {
            return Err(Error::BadExtendedLength);
        }
        self.need(offset + 2, width)?;
        let count = read_unsigned(self.data, offset + 2, width).ok_or(Error::Truncated)?;
        if count > usize::MAX as u64 {
            return Err(Error::BadExtendedLength);
        }
        Ok((offset + 2 + width, count as usize))
    }

    fn need(&self, offset: usize, length: usize) -> Result<(), Error> {
        if offset > self.data.len() || length > self.data.len() - offset {
            return Err(Error::Truncated);
        }
        Ok(())
    }
}

/// Reads a big-endian unsigned integer of `width` bytes.
///
/// Returns `None` when `offset + width` runs past `data` — callers turn that
/// into their own error. The bounds check lives here so no call site can
/// re-derive it wrong (评审 P0-2：旧实现无边界检查，`read_unsigned(data, table +
/// i * width, width)` 在回绕后的 table 上直接越界 panic)。
fn read_unsigned(data: &[u8], offset: usize, width: usize) -> Option<u64> {
    let bytes = data.get(offset..offset.checked_add(width)?)?;
    let mut value = 0u64;
    for &b in bytes {
        value = (value << 8) | b as u64;
    }
    Some(value)
}

fn decode_utf16be(bytes: &[u8]) -> String {
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|pair| u16::from_be_bytes([pair[0], pair[1]]))
        .collect();
    String::from_utf16_lossy(&units)
}

// ------------------------------------------------------------------- encoding

enum Node {
    Null,
    Bool(bool),
    Int(i64),
    Real(f64),
    Date(f64),
    Data(Vec<u8>),
    Str(String),
    Array(Vec<u32>),
    Dict { keys: Vec<u32>, values: Vec<u32> },
}

struct Encoder {
    nodes: Vec<Node>,
}

impl Encoder {
    fn new() -> Encoder {
        Encoder { nodes: Vec::new() }
    }

    fn encode(mut self, value: &Value) -> Vec<u8> {
        self.add(value);

        let mut out = Vec::with_capacity(256);
        out.extend_from_slice(MAGIC);

        let ref_width = width_for((self.nodes.len() - 1) as u64);
        let mut positions = Vec::with_capacity(self.nodes.len());
        for index in 0..self.nodes.len() {
            positions.push(out.len() as u64);
            self.write_node(&mut out, index, ref_width);
        }

        let table_offset = out.len() as u64;
        let offset_width = width_for(table_offset);
        for position in positions {
            write_unsigned(&mut out, position, offset_width);
        }

        out.extend_from_slice(&[0u8; 6]);
        out.push(offset_width as u8);
        out.push(ref_width as u8);
        write_unsigned(&mut out, self.nodes.len() as u64, 8);
        write_unsigned(&mut out, 0, 8);
        write_unsigned(&mut out, table_offset, 8);
        out
    }

    /// Appends a value to the object table and returns its index.
    ///
    /// The index is reserved before the children are added, so a parent always
    /// sits at a lower index than its contents. That is what Apple does, and it
    /// keeps the output byte-identical to the Java encoder this replaced.
    fn add(&mut self, value: &Value) -> u32 {
        let index = self.nodes.len() as u32;
        self.nodes.push(Node::Null);

        let node = match value {
            Value::Null => Node::Null,
            Value::Bool(b) => Node::Bool(*b),
            Value::Integer(i) => Node::Int(*i),
            Value::Real(r) => Node::Real(*r),
            Value::Date(d) => Node::Date(*d),
            Value::Data(d) => Node::Data(d.clone()),
            Value::String(s) => Node::Str(s.clone()),
            Value::Array(items) => {
                let refs = items.iter().map(|item| self.add(item)).collect();
                Node::Array(refs)
            }
            Value::Dict(pairs) => {
                let mut keys = Vec::with_capacity(pairs.len());
                for (key, _) in pairs {
                    keys.push(self.add(&Value::String(key.clone())));
                }
                let mut values = Vec::with_capacity(pairs.len());
                for (_, item) in pairs {
                    values.push(self.add(item));
                }
                Node::Dict { keys, values }
            }
        };

        self.nodes[index as usize] = node;
        index
    }

    fn write_node(&self, out: &mut Vec<u8>, index: usize, ref_width: usize) {
        match &self.nodes[index] {
            Node::Null => out.push(0x00),
            Node::Bool(b) => out.push(if *b { 0x09 } else { 0x08 }),
            Node::Int(i) => write_integer(out, *i),
            Node::Real(r) => {
                out.push(0x23);
                write_unsigned(out, r.to_bits(), 8);
            }
            Node::Date(seconds) => {
                out.push(0x33);
                write_unsigned(out, (seconds - APPLE_EPOCH).to_bits(), 8);
            }
            Node::Data(bytes) => {
                write_header(out, 0x4, bytes.len());
                out.extend_from_slice(bytes);
            }
            Node::Str(text) => {
                if text.is_ascii() {
                    write_header(out, 0x5, text.len());
                    out.extend_from_slice(text.as_bytes());
                } else {
                    let units: Vec<u16> = text.encode_utf16().collect();
                    write_header(out, 0x6, units.len());
                    for unit in units {
                        out.extend_from_slice(&unit.to_be_bytes());
                    }
                }
            }
            Node::Array(refs) => {
                write_header(out, 0xa, refs.len());
                for &r in refs {
                    write_unsigned(out, r as u64, ref_width);
                }
            }
            Node::Dict { keys, values } => {
                write_header(out, 0xd, keys.len());
                for &r in keys.iter().chain(values.iter()) {
                    write_unsigned(out, r as u64, ref_width);
                }
            }
        }
    }
}

fn write_integer(out: &mut Vec<u8>, value: i64) {
    // Apple picks the narrowest unsigned width that fits a non-negative value and
    // always uses eight bytes for negative ones.
    let width: usize = if (0..=0xff).contains(&value) {
        1
    } else if (0..=0xffff).contains(&value) {
        2
    } else if (0..=0xffff_ffff).contains(&value) {
        4
    } else {
        8
    };
    out.push(0x10 | width.trailing_zeros() as u8);
    write_unsigned(out, value as u64, width);
}

fn write_header(out: &mut Vec<u8>, kind: u8, count: usize) {
    if count < 0x0f {
        out.push((kind << 4) | count as u8);
    } else {
        out.push((kind << 4) | 0x0f);
        write_integer(out, count as i64);
    }
}

fn write_unsigned(out: &mut Vec<u8>, value: u64, width: usize) {
    for shift in (0..width).rev() {
        out.push((value >> (shift * 8)) as u8);
    }
}

fn width_for(value: u64) -> usize {
    if value <= 0xff {
        1
    } else if value <= 0xffff {
        2
    } else if value <= 0xffff_ffff {
        4
    } else {
        8
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ask_payload() -> Value {
        Value::dict([
            ("SenderComputerName", Value::String("Air".into())),
            ("SenderModelName", Value::String("OPPO PJZ110".into())),
            ("BundleID", Value::String("com.apple.DocumentsApp".into())),
            ("ConvertMediaFormats", Value::Integer(0)),
            (
                "Files",
                Value::Array(vec![Value::dict([
                    ("FileName", Value::String("报告 2026.pdf".into())),
                    ("FileType", Value::String("com.adobe.pdf".into())),
                    ("FileBomPath", Value::String("./报告 2026.pdf".into())),
                    ("FileIsDirectory", Value::Bool(false)),
                    ("ConvertMediaFormats", Value::Integer(0)),
                ])]),
            ),
            ("SenderRecordData", Value::Data(vec![0x00, 0xff, 0x10])),
        ])
    }

    #[test]
    fn round_trips_a_real_ask_payload() {
        let payload = ask_payload();
        let bytes = encode(&payload);
        assert_eq!(&bytes[..8], MAGIC);
        let decoded = parse_dict(&bytes).expect("decodes");
        assert_eq!(decoded, payload);
    }

    #[test]
    fn keeps_insertion_order() {
        let bytes = encode(&Value::dict([
            ("z", Value::Integer(1)),
            ("a", Value::Integer(2)),
            ("m", Value::Integer(3)),
        ]));
        let decoded = parse(&bytes).unwrap();
        let keys: Vec<&str> = decoded
            .as_dict()
            .unwrap()
            .iter()
            .map(|(k, _)| k.as_str())
            .collect();
        assert_eq!(keys, ["z", "a", "m"]);
    }

    #[test]
    fn encodes_ascii_as_latin1_and_everything_else_as_utf16() {
        let ascii = encode(&Value::String("plain".into()));
        // 0x55 == ASCII string, five characters.
        assert_eq!(ascii[8], 0x55);
        let wide = encode(&Value::String("报告".into()));
        // 0x62 == UTF-16BE string, two units.
        assert_eq!(wide[8], 0x62);
        assert_eq!(parse(&wide).unwrap(), Value::String("报告".into()));
    }

    #[test]
    fn picks_the_narrowest_integer_width() {
        assert_eq!(encode(&Value::Integer(0))[8], 0x10);
        assert_eq!(encode(&Value::Integer(0xff))[8], 0x10);
        assert_eq!(encode(&Value::Integer(0x100))[8], 0x11);
        assert_eq!(encode(&Value::Integer(0x1_0000))[8], 0x12);
        assert_eq!(encode(&Value::Integer(0x1_0000_0000))[8], 0x13);
    }

    #[test]
    fn round_trips_negative_and_large_integers() {
        for value in [-1i64, -128, -32768, i64::MIN, i64::MAX, 0, 1, 4_294_967_296] {
            let bytes = encode(&Value::Integer(value));
            assert_eq!(parse(&bytes).unwrap(), Value::Integer(value), "{value}");
        }
    }

    #[test]
    fn one_byte_values_above_0x7f_stay_non_negative() {
        // 0x80..0xff occupy a single byte. They are positive values, so decoding
        // must not sign-extend them into -128..-1 (this was a real bug: the
        // encoder picked one byte, the decoder then read it as signed).
        for value in [0x7f, 0x80, 0x81, 0xfe, 0xff, 0x100, 0xffff, 0x1_0000] {
            let bytes = encode(&Value::Integer(value));
            assert_eq!(parse(&bytes).unwrap(), Value::Integer(value), "{value:#x}");
        }
    }

    #[test]
    fn round_trips_dates_and_reals() {
        let date = Value::Date(1_700_000_000.0);
        assert_eq!(parse(&encode(&date)).unwrap(), date);
        let real = Value::Real(0.5);
        assert_eq!(parse(&encode(&real)).unwrap(), real);
    }

    #[test]
    fn round_trips_an_array_long_enough_for_an_extended_length() {
        let items: Vec<Value> = (0..300).map(Value::Integer).collect();
        let value = Value::Array(items);
        let bytes = encode(&value);
        // 0xaf marks the extended length; the integer that follows carries 300.
        assert_eq!(bytes[8], 0xaf);
        assert_eq!(parse(&bytes).unwrap(), value);
    }

    #[test]
    fn round_trips_nested_empty_containers() {
        let value = Value::dict([
            ("empty_dict", Value::Dict(Vec::new())),
            ("empty_array", Value::Array(Vec::new())),
            ("empty_data", Value::Data(Vec::new())),
            ("empty_string", Value::String(String::new())),
        ]);
        assert_eq!(parse(&encode(&value)).unwrap(), value);
    }

    /// Assembles a payload by hand so trailer bookkeeping cannot be miscounted.
    ///
    /// `objects` are concatenated straight after the magic; the offset table gets
    /// one byte per object, and the trailer names one-byte widths for both the
    /// offset table and object references. Handwritten fixtures used to hardcode
    /// the offset-table position, which silently pointed at the last object byte
    /// and made cycles look like `BadObjectOffset`.
    fn hand_built(objects: &[&[u8]], top: u64) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(MAGIC);
        let mut offsets = Vec::new();
        for object in objects {
            offsets.push(bytes.len() as u8);
            bytes.extend_from_slice(object);
        }
        let table = bytes.len() as u64;
        bytes.extend_from_slice(&offsets);
        bytes.extend_from_slice(&[0, 0, 0, 0, 0, 0, 1, 1]);
        bytes.extend_from_slice(&(objects.len() as u64).to_be_bytes());
        bytes.extend_from_slice(&top.to_be_bytes());
        bytes.extend_from_slice(&table.to_be_bytes());
        bytes
    }

    #[test]
    fn parses_a_hand_built_empty_dictionary() {
        // The smallest legal payload: one 0xd0 object and a trailer that names
        // both widths as one byte.
        assert_eq!(parse(&hand_built(&[&[0xd0]], 0)).unwrap(), Value::Dict(Vec::new()));
    }

    #[test]
    fn accepts_three_byte_field_widths() {
        // iOS widens both trailer widths to three bytes once a multi-megabyte
        // `FileIcon` in an `/Ask` body pushes the object table past 0xffff.
        // Three is not a power of two, and the old guard — a straight port of
        // `validWidth()` from the legacy Java implementation — rejected the
        // whole payload with "bad field width 3", so any transfer of a few
        // megabytes failed while small ones went through.
        //
        // Hand-built so the widths are exactly what the test claims: one
        // dictionary with a single entry, both references three bytes wide, and
        // a three-byte offset table.
        let mut bytes = Vec::new();
        bytes.extend_from_slice(MAGIC);
        let dict_at = bytes.len();
        bytes.push(0xd1); // dictionary with one entry
        bytes.extend_from_slice(&[0, 0, 1]); // key   -> object 1
        bytes.extend_from_slice(&[0, 0, 2]); // value -> object 2
        let key_at = bytes.len();
        bytes.push(0x51); // ASCII string, one character
        bytes.push(b'k');
        let value_at = bytes.len();
        bytes.extend_from_slice(&[0x10, 0x01]); // integer 1
        let table = bytes.len();
        for at in [dict_at, key_at, value_at] {
            bytes.extend_from_slice(&(at as u64).to_be_bytes()[5..]);
        }
        // [6 unused][offset width][ref width][count][top][table offset]
        bytes.extend_from_slice(&[0, 0, 0, 0, 0, 0, 3, 3]);
        bytes.extend_from_slice(&3u64.to_be_bytes());
        bytes.extend_from_slice(&0u64.to_be_bytes());
        bytes.extend_from_slice(&(table as u64).to_be_bytes());

        assert_eq!(
            parse(&bytes).unwrap(),
            Value::dict([("k", Value::Integer(1))])
        );
    }

    #[test]
    fn still_rejects_impossible_field_widths() {
        // Zero would make the offset table unreadable and anything past eight
        // bytes cannot describe a payload that fits in memory, so the widened
        // check keeps those out.
        for width in [0u8, 9, 255] {
            let mut bytes = hand_built(&[&[0xd0]], 0);
            let trailer = bytes.len() - TRAILER_SIZE;
            bytes[trailer + 6] = width;
            assert_eq!(parse(&bytes), Err(Error::BadWidth(width)), "{width}");
        }
    }

    #[test]
    fn rejects_a_bad_magic() {
        let mut bytes = encode(&Value::Integer(1));
        bytes[0] = b'X';
        assert_eq!(parse(&bytes), Err(Error::BadMagic));
    }

    #[test]
    fn rejects_a_truncated_payload() {
        let bytes = encode(&ask_payload());
        assert_eq!(parse(&bytes[..20]), Err(Error::TooShort));
        assert!(parse(&bytes[..bytes.len() - 1]).is_err());
    }

    #[test]
    fn rejects_a_cycle() {
        // One array object whose single element refers back to itself.
        assert_eq!(
            parse(&hand_built(&[&[0xa1, 0x00]], 0)),
            Err(Error::Cyclic(0))
        );
    }

    #[test]
    fn rejects_a_reference_past_the_table() {
        // An array of one pointing at object 7, which does not exist.
        assert_eq!(
            parse(&hand_built(&[&[0xa1, 0x07]], 0)),
            Err(Error::BadReference(7))
        );
    }

    #[test]
    fn dict_helpers_are_tolerant() {
        let value = ask_payload();
        assert_eq!(value.get_str("SenderComputerName"), Some("Air"));
        assert_eq!(value.get_str("missing"), None);
        assert_eq!(value.get_i64("missing", 7), 7);
        assert_eq!(value.get_array("missing").len(), 0);
        // A missing flag falls back to whatever the caller asked for.
        assert!(value.get_bool("missing", true));
        // An integer flag reads back as a boolean, because Apple's writers in the
        // wild emit both 0x09 and 0x10 0x01 for "true".
        assert!(!value.get_bool("ConvertMediaFormats", true));
    }

    #[test]
    fn nested_files_array_survives_the_round_trip_byte_for_byte() {
        let payload = ask_payload();
        let first = encode(&payload);
        let second = encode(&parse(&first).unwrap());
        assert_eq!(first, second, "encoding is deterministic");
    }

    // ---------------------------------------------------------------- P0-2 回归
    // 三个用例都出自 2026-11-23 评审：对端完全可控的报文尾部，旧代码在 release 下
    // 直接越界 panic（panic="abort" 时等于整个接收进程死亡）。

    /// 把报文尾部的表偏移写成 u64::MAX：`table + count * width` 在 usize 上回绕成
    /// 小数，旧检查放行后 `read_unsigned` 越界 panic。现在必须是干净的 BadOffsetTable。
    #[test]
    fn wrapped_table_offset_is_rejected_not_panic() {
        let mut bytes = encode(&ask_payload());
        // trailer 是最后 32 字节，表偏移在其中第 24..32 字节，即整个报文的最后 8 字节。
        let n = bytes.len();
        bytes[n - 8..].copy_from_slice(&u64::MAX.to_be_bytes());
        assert_eq!(
            parse(&bytes),
            Err(Error::BadOffsetTable),
            "wrapped table offset must be rejected without any arithmetic wrap"
        );
    }

    /// UTF-16 字符串的扩展长度声明 count = 2^63：`count * 2` 回绕成 0，旧代码
    /// need 放行、切片拿到空串——畸形输入被当成功（评审 P1-4）。
    #[test]
    fn utf16_length_that_overflows_when_doubled_is_rejected() {
        // 一个 0x6f 对象（UTF-16 字符串，扩展长度 8 字节）加最小 trailer。
        let mut bytes = MAGIC.to_vec();
        bytes.extend_from_slice(&[0x6f, 0x13]); // extended-length marker, width 8
        bytes.extend_from_slice(&0x8000_0000_0000_0000u64.to_be_bytes()); // count = 2^63
        // offset 表单独放（表里存的是"对象的偏移"，不是对象本身）：
        bytes.push(8); // 表只有一项，值 8 = 对象起点
        let table = bytes.len() as u64 - 1;
        // trailer: 6 个保留字节 + offset_width(1) + ref_width(1) + count(8) + top(8) + table(8)
        bytes.extend_from_slice(&[0u8; 6]);
        bytes.push(1); // offset_width
        bytes.push(1); // ref_width
        bytes.extend_from_slice(&1u64.to_be_bytes()); // count
        bytes.extend_from_slice(&0u64.to_be_bytes()); // top object
        bytes.extend_from_slice(&table.to_be_bytes());
        assert_eq!(
            parse(&bytes),
            Err(Error::Truncated),
            "count*2 wrapping to zero must not be mistaken for an empty string"
        );
    }

    /// 共享引用 DAG：33 层、每层两个引用指向下一层，展开 2^32 个节点。
    /// `visiting` 只防环防不了这个；解码预算把它变成 TooManyNodes 而不是
    /// 1M 次展开后内存爆炸。
    #[test]
    fn shared_reference_dag_hits_the_decode_budget() {
        const LEVELS: usize = 33;
        let mut bytes = MAGIC.to_vec();
        let mut offsets = Vec::new();
        for i in 0..LEVELS {
            offsets.push(bytes.len() as u64);
            if i + 1 == LEVELS {
                bytes.extend_from_slice(&[0x10, 0x00]); // int 0：叶子
            } else {
                // 0xa2 = 两元素数组，ref_size 1，两个引用都指向下一层
                bytes.extend_from_slice(&[0xa2, (i + 1) as u8, (i + 1) as u8]);
            }
        }
        let table = bytes.len() as u64;
        for off in &offsets {
            bytes.push(*off as u8);
        }
        bytes.extend_from_slice(&[0u8; 6]);
        bytes.push(1); // offset_width
        bytes.push(1); // ref_width
        bytes.extend_from_slice(&(LEVELS as u64).to_be_bytes()); // count
        bytes.extend_from_slice(&0u64.to_be_bytes()); // top
        bytes.extend_from_slice(&table.to_be_bytes());
        assert_eq!(parse(&bytes), Err(Error::TooManyNodes));
    }
}
