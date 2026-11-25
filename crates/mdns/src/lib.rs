//! DNS wire codec covering the subset mDNS needs.
//!
//! Name compression on parse, uncompressed names on write, and the record types
//! AirDrop discovery uses (`PTR`, `SRV`, `TXT`, `A`, `AAAA`, `NSEC`).
//!
//! # Shape
//!
//! [`Message`] borrows the packet it was parsed from. Records keep an offset and a
//! length into that packet rather than a copied `Vec<u8>`, because a compressed name
//! inside SRV or NSEC rdata is a pointer into the *whole* message — a copy of the
//! rdata alone cannot be resolved. Kept this way, [`Record::target`] costs nothing and
//! a listener never allocates per packet.
//!
//! TXT values keep insertion order in a `Vec<(String, String)>` rather than a
//! `HashMap`: DNS-SD publishes `h=`, `fl=` and a handful of others, so linear lookup
//! beats hashing, ordering stays predictable in logs, and the crate needs no
//! dependency.

#![forbid(unsafe_code)]

use std::fmt;

/// IPv4 address record.
pub const TYPE_A: u16 = 1;
/// Domain-name pointer, the one that lists instances of a service.
pub const TYPE_PTR: u16 = 12;
/// DNS-SD key/value strings.
pub const TYPE_TXT: u16 = 16;
/// IPv6 address record.
pub const TYPE_AAAA: u16 = 28;
/// Service location: priority, weight, port, target host.
pub const TYPE_SRV: u16 = 33;
/// Negative response assertion, what hosts use to say "no more records".
pub const TYPE_NSEC: u16 = 47;
/// Matches any record type.
pub const TYPE_ANY: u16 = 255;

/// Internet class.
pub const CLASS_IN: u16 = 1;
/// Everything but the top bit.
pub const CLASS_MASK: u16 = 0x7fff;
/// Question flag: the sender wants the answer unicast instead of multicast (QU bit).
pub const CLASS_UNICAST: u16 = 0x8000;

/// This message is a response.
pub const FLAG_RESPONSE: u16 = 0x8000;
/// The responder owns the records it is sending.
pub const FLAG_AUTHORITATIVE: u16 = 0x0400;

/// A malformed packet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// Fewer than twelve bytes, so not even the header was there.
    ShortHeader,
    /// A name ran past the end of the packet.
    NameOverrun,
    /// A label's declared length ran past the end of the packet.
    LabelOverrun,
    /// A label used the `0x40` or `0x80` prefix, which mDNS does not allow.
    UnsupportedLabelType,
    /// A compression pointer lost its second byte.
    TruncatedPointer,
    /// A compression pointer pointed at another pointer, sixty-four times over.
    CompressionLoop,
    /// A question or record ran past the end of the packet.
    RecordOverrun,
    /// rdata was shorter than the record type requires.
    ShortRdata {
        /// The record type whose rdata was too short.
        record_type: u16,
    },
    /// A TXT string's declared length ran past the end of the rdata.
    TxtOverrun,
    /// A label exceeded 63 bytes, which length-prefixing cannot express.
    LabelTooLong,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ShortHeader => write!(f, "short DNS header"),
            Self::NameOverrun => write!(f, "name overruns packet"),
            Self::LabelOverrun => write!(f, "label overruns packet"),
            Self::UnsupportedLabelType => write!(f, "unsupported DNS label type"),
            Self::TruncatedPointer => write!(f, "truncated DNS compression pointer"),
            Self::CompressionLoop => write!(f, "DNS name compression loop"),
            Self::RecordOverrun => write!(f, "record overruns packet"),
            Self::ShortRdata { record_type } => write!(f, "short rdata for type {record_type}"),
            Self::TxtOverrun => write!(f, "TXT string overruns record"),
            Self::LabelTooLong => write!(f, "DNS label exceeds 63 bytes"),
        }
    }
}

impl std::error::Error for Error {}

/// One question in the question section.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Question {
    /// The queried name, in presentation form.
    pub name: String,
    /// Record type asked for.
    pub record_type: u16,
    /// Class, with the QU bit stripped.
    pub class: u16,
    /// Set when the querier asked for a direct answer rather than a multicast one.
    pub unicast: bool,
}

impl Question {
    /// True when this question covers `other` and `other_type`.
    ///
    /// [`TYPE_ANY`] on either side matches everything, which is how an mDNS probe
    /// asks "does anyone own this name at all".
    pub fn matches(&self, other: &str, other_type: u16) -> bool {
        if other_type != TYPE_ANY && self.record_type != TYPE_ANY && self.record_type != other_type
        {
            return false;
        }
        same_name(&self.name, other)
    }
}

/// One resource record.
///
/// `rdata` stays in the message buffer so that compressed names inside SRV and NSEC
/// can still be resolved; [`rdata`](Record::rdata) hands out the slice when a caller
/// genuinely needs the bytes.
#[derive(Debug, Clone)]
pub struct Record<'a> {
    /// Owner name, in presentation form.
    pub name: String,
    /// Record type.
    pub record_type: u16,
    /// Class, with the QU bit stripped.
    pub class: u16,
    /// Time to live in seconds. mDNS uses 120 for PTR/SRV/TXT and 4500 for addresses.
    pub ttl: u32,
    data: &'a [u8],
    offset: usize,
    length: usize,
}

impl<'a> Record<'a> {
    /// The raw rdata bytes.
    pub fn rdata(&self) -> &'a [u8] {
        &self.data[self.offset..self.offset + self.length]
    }

    /// The target host of an SRV record.
    ///
    /// Requires seven bytes, not six: an SRV with only the priority, weight and port has
    /// no name at all, and resolving its "target" would read whatever bytes follow the
    /// record in the packet. The shortest legal target is the root label, one byte.
    pub fn target(&self) -> Result<String, Error> {
        self.require_length(7)?;
        let mut cursor = self.offset + 6;
        read_name(self.data, &mut cursor)
    }

    /// The port of an SRV record.
    pub fn port(&self) -> Result<u16, Error> {
        self.require_length(6)?;
        Ok(u16::from_be_bytes([
            self.data[self.offset + 4],
            self.data[self.offset + 5],
        ]))
    }

    /// The priority of an SRV record.
    pub fn priority(&self) -> Result<u16, Error> {
        self.require_length(2)?;
        Ok(u16::from_be_bytes([
            self.data[self.offset],
            self.data[self.offset + 1],
        ]))
    }

    /// The address of an AAAA record, in RFC 5952 presentation form.
    ///
    /// Lowercase hex, and the longest run of two or more zero groups collapses to
    /// `::`. A single zero group is written as `0`: compressing it saves one byte and
    /// makes the address harder to read.
    pub fn address_v6(&self) -> Result<String, Error> {
        self.require_length(16)?;
        let mut groups = [0u16; 8];
        for (i, group) in groups.iter_mut().enumerate() {
            *group = u16::from_be_bytes([
                self.data[self.offset + i * 2],
                self.data[self.offset + i * 2 + 1],
            ]);
        }

        let mut best_start = None;
        let mut best_length = 0;
        let mut i = 0;
        while i < 8 {
            if groups[i] != 0 {
                i += 1;
                continue;
            }
            let start = i;
            while i < 8 && groups[i] == 0 {
                i += 1;
            }
            if i - start > best_length {
                best_start = Some(start);
                best_length = i - start;
            }
        }
        if best_length < 2 {
            best_start = None;
        }

        let mut out = String::with_capacity(39);
        let mut i = 0;
        while i < 8 {
            if best_start == Some(i) {
                out.push_str("::");
                i += best_length;
                continue;
            }
            if !out.is_empty() && !out.ends_with(':') {
                out.push(':');
            }
            out.push_str(&format!("{:x}", groups[i]));
            i += 1;
        }
        Ok(out)
    }

    /// The address of an A record.
    pub fn address_v4(&self) -> Result<[u8; 4], Error> {
        self.require_length(4)?;
        Ok([
            self.data[self.offset],
            self.data[self.offset + 1],
            self.data[self.offset + 2],
            self.data[self.offset + 3],
        ])
    }

    /// The key/value pairs of a TXT record.
    pub fn txt(&self) -> Result<Txt, Error> {
        let end = self.offset + self.length;
        let mut cursor = self.offset;
        let mut txt = Txt::default();
        while cursor < end {
            let len = self.data[cursor] as usize;
            cursor += 1;
            if cursor + len > end {
                return Err(Error::TxtOverrun);
            }
            txt.push(&self.data[cursor..cursor + len]);
            cursor += len;
        }
        Ok(txt)
    }

    fn require_length(&self, min: usize) -> Result<(), Error> {
        if self.length < min {
            return Err(Error::ShortRdata {
                record_type: self.record_type,
            });
        }
        Ok(())
    }
}

/// A parsed DNS message, borrowing the packet it came from.
#[derive(Debug, Clone)]
pub struct Message<'a> {
    data: &'a [u8],
    id: u16,
    flags: u16,
    questions: Vec<Question>,
    records: Vec<Record<'a>>,
}

impl<'a> Message<'a> {
    /// Parses a packet, following compression pointers.
    pub fn parse(data: &'a [u8]) -> Result<Self, Error> {
        if data.len() < 12 {
            return Err(Error::ShortHeader);
        }
        let id = u16::from_be_bytes([data[0], data[1]]);
        let flags = u16::from_be_bytes([data[2], data[3]]);
        let question_count = u16::from_be_bytes([data[4], data[5]]) as usize;
        let answer_count = u16::from_be_bytes([data[6], data[7]]) as usize;
        let authority_count = u16::from_be_bytes([data[8], data[9]]) as usize;
        let additional_count = u16::from_be_bytes([data[10], data[11]]) as usize;

        let mut cursor = 12;
        // 容量按报文实际长度钳制（评审 P1-2）：四个计数都在报文头里、完全由对端控制，
        // 一条 17 字节的报文可以声称 0xffff 条记录，直接按计数预分配就是 12.58 MB 的
        // 放大。每条记录在线上最少 5 字节（name 压缩指针 2 + type 2 + class 1 是理想
        // 下界，取 5 更保守），按它定容量上界即可；计数本身仍按原逻辑消费，多出来的
        // 会在循环里自然走到 ShortHeader。
        let mut questions = Vec::with_capacity(question_count.min(data.len() / 5));
        for _ in 0..question_count {
            let name = read_name(data, &mut cursor)?;
            need(data, cursor, 4)?;
            let record_type = u16::from_be_bytes([data[cursor], data[cursor + 1]]);
            let class = u16::from_be_bytes([data[cursor + 2], data[cursor + 3]]);
            cursor += 4;
            questions.push(Question {
                name,
                record_type,
                class: class & CLASS_MASK,
                unicast: class & CLASS_UNICAST != 0,
            });
        }

        let total = answer_count + authority_count + additional_count;
        // 同上：容量按报文长度钳制，不按对端声称的计数预分配（评审 P1-2）。
        // 每条记录线上最少 11 字节（name 2 + type 2 + class 2 + ttl 4 + rdlength 1）。
        let mut records = Vec::with_capacity(total.min(data.len() / 11));
        for _ in 0..total {
            let name = read_name(data, &mut cursor)?;
            need(data, cursor, 10)?;
            let record_type = u16::from_be_bytes([data[cursor], data[cursor + 1]]);
            let class = u16::from_be_bytes([data[cursor + 2], data[cursor + 3]]);
            let ttl = u32::from_be_bytes([
                data[cursor + 4],
                data[cursor + 5],
                data[cursor + 6],
                data[cursor + 7],
            ]);
            let length = u16::from_be_bytes([data[cursor + 8], data[cursor + 9]]) as usize;
            cursor += 10;
            need(data, cursor, length)?;
            records.push(Record {
                name,
                record_type,
                class: class & CLASS_MASK,
                ttl,
                data,
                offset: cursor,
                length,
            });
            cursor += length;
        }

        Ok(Self {
            data,
            id,
            flags,
            questions,
            records,
        })
    }

    /// The transaction id. Always zero for mDNS.
    pub fn id(&self) -> u16 {
        self.id
    }

    /// The raw flags word.
    pub fn flags(&self) -> u16 {
        self.flags
    }

    /// True when this message is a response rather than a query.
    pub fn is_response(&self) -> bool {
        self.flags & FLAG_RESPONSE != 0
    }

    /// The question section.
    pub fn questions(&self) -> &[Question] {
        &self.questions
    }

    /// Answers, authorities and additionals, in packet order.
    pub fn records(&self) -> &[Record<'a>] {
        &self.records
    }

    /// Every record of one type, in packet order.
    pub fn records_of_type(&self, record_type: u16) -> impl Iterator<Item = &Record<'a>> {
        self.records
            .iter()
            .filter(move |record| record.record_type == record_type)
    }

    /// The packet this message was parsed from.
    pub fn as_bytes(&self) -> &'a [u8] {
        self.data
    }
}

/// Builds a DNS message with uncompressed names, which is what mDNS uses on the wire.
#[derive(Debug, Clone)]
pub struct Builder {
    id: u16,
    flags: u16,
    body: Vec<u8>,
    questions: u16,
    records: u16,
}

impl Builder {
    /// Starts a message.
    pub fn new(id: u16, flags: u16) -> Self {
        Self {
            id,
            flags,
            body: Vec::with_capacity(256),
            questions: 0,
            records: 0,
        }
    }

    /// Starts a response with the authoritative bit set, as an mDNS responder does.
    pub fn response() -> Self {
        Self::new(0, FLAG_RESPONSE | FLAG_AUTHORITATIVE)
    }

    /// Appends a question.
    pub fn question(mut self, name: &str, record_type: u16, class: u16) -> Result<Self, Error> {
        write_name(&mut self.body, name)?;
        self.body.extend_from_slice(&record_type.to_be_bytes());
        self.body.extend_from_slice(&class.to_be_bytes());
        self.questions += 1;
        Ok(self)
    }

    /// Appends an answer. The class is always [`CLASS_IN`]; mDNS has no other.
    pub fn answer(
        mut self,
        name: &str,
        record_type: u16,
        ttl: u32,
        rdata: &[u8],
    ) -> Result<Self, Error> {
        write_name(&mut self.body, name)?;
        self.body.extend_from_slice(&record_type.to_be_bytes());
        self.body.extend_from_slice(&CLASS_IN.to_be_bytes());
        self.body.extend_from_slice(&ttl.to_be_bytes());
        let length = u16::try_from(rdata.len()).map_err(|_| Error::RecordOverrun)?;
        self.body.extend_from_slice(&length.to_be_bytes());
        self.body.extend_from_slice(rdata);
        self.records += 1;
        Ok(self)
    }

    /// Appends a full SRV record's rdata: priority, weight, port, target.
    pub fn srv_rdata(
        priority: u16,
        weight: u16,
        port: u16,
        target: &str,
    ) -> Result<Vec<u8>, Error> {
        let mut rdata = Vec::with_capacity(6 + target.len() + 2);
        rdata.extend_from_slice(&priority.to_be_bytes());
        rdata.extend_from_slice(&weight.to_be_bytes());
        rdata.extend_from_slice(&port.to_be_bytes());
        write_name(&mut rdata, target)?;
        Ok(rdata)
    }

    /// Builds the TXT rdata: one length-prefixed string per key.
    pub fn txt_rdata(pairs: &[(&str, &str)]) -> Result<Vec<u8>, Error> {
        let mut rdata = Vec::with_capacity(64);
        for (key, value) in pairs {
            let entry = if value.is_empty() {
                (*key).to_string()
            } else {
                format!("{key}={value}")
            };
            let len = u8::try_from(entry.len()).map_err(|_| Error::TxtOverrun)?;
            rdata.push(len);
            rdata.extend_from_slice(entry.as_bytes());
        }
        Ok(rdata)
    }

    /// Serializes the message.
    pub fn build(self) -> Vec<u8> {
        let mut out = Vec::with_capacity(12 + self.body.len());
        out.extend_from_slice(&self.id.to_be_bytes());
        out.extend_from_slice(&self.flags.to_be_bytes());
        out.extend_from_slice(&self.questions.to_be_bytes());
        out.extend_from_slice(&self.records.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes()); // authorities
        out.extend_from_slice(&0u16.to_be_bytes()); // additionals
        out.extend_from_slice(&self.body);
        out
    }
}

/// Builds a single-question query, uncompressed, mDNS style.
pub fn query(name: &str, record_type: u16) -> Result<Vec<u8>, Error> {
    Ok(Builder::new(0, 0)
        .question(name, record_type, CLASS_IN)?
        .build())
}

/// Reads a name, following compression pointers, and leaves `cursor` past it.
///
/// `cursor` only advances for the bytes the name occupies at its own position: a
/// pointer consumes two bytes there and then jumps, so a name followed by more fields
/// does not leave the caller reading from inside a jump target.
pub fn read_name(data: &[u8], cursor: &mut usize) -> Result<String, Error> {
    let mut name = String::new();
    let mut at = *cursor;
    let mut guard = 0;
    let mut jumped = false;

    loop {
        if at >= data.len() {
            return Err(Error::NameOverrun);
        }
        let len = data[at] as usize;
        if len & 0xc0 == 0xc0 {
            if at + 1 >= data.len() {
                return Err(Error::TruncatedPointer);
            }
            let pointer = ((len & 0x3f) << 8) | data[at + 1] as usize;
            if !jumped {
                *cursor = at + 2;
            }
            jumped = true;
            at = pointer;
            guard += 1;
            if guard > 64 {
                return Err(Error::CompressionLoop);
            }
            continue;
        }
        if len == 0 {
            if !jumped {
                *cursor = at + 1;
            }
            break;
        }
        if len & 0xc0 != 0 {
            return Err(Error::UnsupportedLabelType);
        }
        if at + 1 + len > data.len() {
            return Err(Error::LabelOverrun);
        }
        // 评审 P1-7：mDNS 名字会被 HTTP 头引用（SRV target → `Host:`），带控制字符的
        // label 能把 CRLF 塞进一个头值里（响应拆分）。来源在此拒收，写头的地方就不必
        // 每一处都防。
        let label = &data[at + 1..at + 1 + len];
        if label.iter().any(|&b| b < 0x20 || b == 0x7f) {
            return Err(Error::UnsupportedLabelType);
        }
        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(&String::from_utf8_lossy(label));
        at += 1 + len;
    }
    Ok(name)
}

/// Writes a name as length-prefixed labels.
///
/// Labels are separated by unescaped dots only: a `\.` stays inside its label as the
/// two bytes `\` and `.`, which is how DNS-SD spells an instance name containing a
/// dot. One trailing dot is dropped, because the root label is written separately.
pub fn write_name(out: &mut Vec<u8>, name: &str) -> Result<(), Error> {
    let trimmed = match name.strip_suffix("\\.") {
        Some(_) => name,
        None => name.strip_suffix('.').unwrap_or(name),
    };
    if trimmed.is_empty() {
        out.push(0);
        return Ok(());
    }

    let mut start = 0;
    let mut chars = trimmed.char_indices();
    while let Some((i, c)) = chars.next() {
        match c {
            '\\' => {
                // The escaped character is part of the label, whatever it is.
                chars.next();
            }
            '.' => {
                write_label(out, &trimmed[start..i])?;
                start = i + 1;
            }
            _ => {}
        }
    }
    write_label(out, &trimmed[start..])?;
    out.push(0);
    Ok(())
}

fn write_label(out: &mut Vec<u8>, label: &str) -> Result<(), Error> {
    if label.is_empty() {
        return Ok(());
    }
    let raw = label.as_bytes();
    if raw.len() > 63 {
        return Err(Error::LabelTooLong);
    }
    out.push(raw.len() as u8);
    out.extend_from_slice(raw);
    Ok(())
}

fn need(data: &[u8], at: usize, length: usize) -> Result<(), Error> {
    if at + length > data.len() {
        return Err(Error::RecordOverrun);
    }
    Ok(())
}

/// Case-insensitive DNS name comparison, trailing dot ignored.
pub fn same_name(left: &str, right: &str) -> bool {
    trim_dot(left).to_lowercase() == trim_dot(right).to_lowercase()
}

/// A name without its trailing dot.
pub fn trim_dot(name: &str) -> &str {
    if name.len() > 1 {
        name.strip_suffix('.').unwrap_or(name)
    } else {
        name
    }
}

/// DNS-SD TXT payload: key/value pairs, bare keys allowed.
///
/// Order is preserved. `h=` (the certificate hash) and `fl=` (flags) are the two
/// AirDrop puts on the wire, and a caller that logs TXT wants to see them in the order
/// the peer wrote them.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Txt {
    pairs: Vec<(String, String)>,
}

impl Txt {
    /// Parses TXT rdata directly.
    pub fn parse_rdata(rdata: &[u8]) -> Result<Self, Error> {
        let mut txt = Self::default();
        let mut cursor = 0;
        while cursor < rdata.len() {
            let len = rdata[cursor] as usize;
            cursor += 1;
            if cursor + len > rdata.len() {
                return Err(Error::TxtOverrun);
            }
            txt.push(&rdata[cursor..cursor + len]);
            cursor += len;
        }
        Ok(txt)
    }

    /// Adds one length-prefixed string.
    ///
    /// A string without `=` is a bare key with an empty value, which DNS-SD allows and
    /// which a receiver must not confuse with a missing key: `has("fl")` is the test
    /// for presence, `get("fl")` returns `""` for both cases.
    pub fn push(&mut self, entry: &[u8]) {
        let entry = String::from_utf8_lossy(entry);
        if entry.is_empty() {
            return;
        }
        match entry.find('=') {
            Some(0) | None => self.pairs.push((entry.into_owned(), String::new())),
            Some(split) => self
                .pairs
                .push((entry[..split].to_string(), entry[split + 1..].to_string())),
        }
    }

    /// The value for `key`, or `""` when absent.
    pub fn get(&self, key: &str) -> &str {
        self.pairs
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
            .unwrap_or("")
    }

    /// True when `key` was present, whatever its value.
    pub fn has(&self, key: &str) -> bool {
        self.pairs.iter().any(|(k, _)| k == key)
    }

    /// All pairs, in wire order.
    pub fn pairs(&self) -> &[(String, String)] {
        &self.pairs
    }
}

impl fmt::Display for Txt {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("{")?;
        for (i, (key, value)) in self.pairs.iter().enumerate() {
            if i > 0 {
                f.write_str(", ")?;
            }
            if value.is_empty() {
                write!(f, "{key}")?;
            } else {
                write!(f, "{key}={value}")?;
            }
        }
        f.write_str("}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SERVICE: &str = "_airdrop._tcp.local";

    #[test]
    fn query_round_trip() {
        let bytes = query(SERVICE, TYPE_PTR).unwrap();
        let message = Message::parse(&bytes).unwrap();
        assert!(!message.is_response());
        assert_eq!(message.id(), 0);
        assert_eq!(message.questions().len(), 1);
        let question = &message.questions()[0];
        assert_eq!(question.name, SERVICE);
        assert_eq!(question.record_type, TYPE_PTR);
        assert_eq!(question.class, CLASS_IN);
        assert!(!question.unicast);
    }

    #[test]
    fn query_wire_bytes_are_mdns_shaped() {
        let bytes = query("_a._tcp.local", TYPE_PTR).unwrap();
        // header: id 0, flags 0, one question, no records
        assert_eq!(&bytes[..6], &[0, 0, 0, 0, 0, 1]);
        // "_a" + "_tcp" + "local" + root
        assert_eq!(bytes[12], 2);
        assert_eq!(&bytes[13..15], b"_a");
        assert_eq!(bytes[15], 4);
        assert_eq!(&bytes[16..20], b"_tcp");
        assert_eq!(bytes[20], 5);
        assert_eq!(&bytes[21..26], b"local");
        assert_eq!(bytes[26], 0);
        assert_eq!(&bytes[27..29], &[0, 12]); // PTR
        assert_eq!(&bytes[29..31], &[0, 1]); // IN
        assert_eq!(bytes.len(), 31);
    }

    #[test]
    fn response_with_srv_and_txt_round_trips() {
        let srv = Builder::srv_rdata(0, 0, 8770, "phone.local.").unwrap();
        let txt = Builder::txt_rdata(&[("h", "deadbeef"), ("fl", "")]).unwrap();
        let bytes = Builder::response()
            .answer("Name._airdrop._tcp.local", TYPE_SRV, 120, &srv)
            .unwrap()
            .answer("Name._airdrop._tcp.local", TYPE_TXT, 4500, &txt)
            .unwrap()
            .build();

        let message = Message::parse(&bytes).unwrap();
        assert!(message.is_response());
        assert_eq!(message.flags() & FLAG_AUTHORITATIVE, FLAG_AUTHORITATIVE);
        assert_eq!(message.records().len(), 2);

        let record = message.records_of_type(TYPE_SRV).next().unwrap();
        assert_eq!(record.port().unwrap(), 8770);
        assert_eq!(record.priority().unwrap(), 0);
        assert_eq!(record.target().unwrap(), "phone.local");
        assert_eq!(record.ttl, 120);

        let record = message.records_of_type(TYPE_TXT).next().unwrap();
        let txt = record.txt().unwrap();
        assert_eq!(txt.get("h"), "deadbeef");
        assert!(txt.has("fl"));
        assert_eq!(txt.get("fl"), "");
        assert!(!txt.has("rp"));
    }

    #[test]
    fn compression_pointers_are_followed() {
        // Header plus one answer whose owner name is a pointer to offset 12, where the
        // question name lives.
        let mut packet = Vec::new();
        packet.extend_from_slice(&[0, 0, 0x84, 0x00, 0, 1, 0, 1, 0, 0, 0, 0]);
        packet.extend_from_slice(&query(SERVICE, TYPE_PTR).unwrap()[12..]);
        // owner name: a pointer to offset 12, where the question's name begins
        packet.extend_from_slice(&[0xc0, 0x0c]);
        packet.extend_from_slice(&TYPE_PTR.to_be_bytes());
        packet.extend_from_slice(&CLASS_IN.to_be_bytes());
        packet.extend_from_slice(&120u32.to_be_bytes());
        // rdata: a single-label name "x" followed by the root label
        packet.extend_from_slice(&3u16.to_be_bytes());
        packet.extend_from_slice(&[1, b'x', 0]);

        let message = Message::parse(&packet).unwrap();
        assert_eq!(message.records().len(), 1);
        assert_eq!(message.records()[0].name, SERVICE);
        assert_eq!(message.records()[0].rdata(), &[1, b'x', 0]);
    }

    #[test]
    fn compression_loop_is_refused() {
        let mut packet = Vec::new();
        packet.extend_from_slice(&[0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
        // A pointer at offset 12 that points at itself.
        packet.extend_from_slice(&[0xc0, 0x0c]);
        let err = Message::parse(&packet).unwrap_err();
        assert_eq!(err, Error::CompressionLoop);
    }

    #[test]
    fn escaped_dot_stays_inside_a_label() {
        let mut out = Vec::new();
        write_name(&mut out, "a\\.b._airdrop._tcp.local.").unwrap();
        // "a\.b" is four bytes; the backslash does not split the label.
        assert_eq!(out[0], 4);
        assert_eq!(&out[1..5], b"a\\.b");
        assert_eq!(out[5], 8);
        assert_eq!(&out[6..14], b"_airdrop");
        assert_eq!(out[14], 4);
        assert_eq!(&out[15..19], b"_tcp");
        assert_eq!(out[19], 5);
        assert_eq!(&out[20..25], b"local");
        assert_eq!(out[25], 0);
        assert_eq!(out.len(), 26);

        // And the reader hands the escapes back untouched, so the peer layer can
        // decode them once, in one place.
        let mut cursor = 0;
        assert_eq!(
            read_name(&out, &mut cursor).unwrap(),
            "a\\.b._airdrop._tcp.local"
        );
        assert_eq!(cursor, out.len());
    }

    #[test]
    fn trailing_dot_is_dropped_once() {
        let mut a = Vec::new();
        write_name(&mut a, "host.local.").unwrap();
        let mut b = Vec::new();
        write_name(&mut b, "host.local").unwrap();
        assert_eq!(a, b);

        // A name that ends in an *escaped* dot keeps it: that dot is part of the last
        // label, not the root label.
        let mut out = Vec::new();
        write_name(&mut out, "host.local\\.").unwrap();
        assert_eq!(out[0], 4);
        assert_eq!(&out[1..5], b"host");
        assert_eq!(out[5], 7);
        assert_eq!(&out[6..13], b"local\\.");
    }

    #[test]
    fn overlong_label_is_refused() {
        let long = "a".repeat(64);
        let mut out = Vec::new();
        assert_eq!(write_name(&mut out, &long), Err(Error::LabelTooLong));
    }

    #[test]
    fn ipv6_presentation() {
        let cases: [([u8; 16], &str); 6] = [
            ([0; 16], "::"),
            (
                [
                    0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f, 0, 1,
                ],
                "fe80::1a2b:3c4d:5e6f:1",
            ),
            ([0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], "1::1"),
            (
                [0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8],
                "1:2:3:4:5:6:7:8",
            ),
            ([0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], "1::2"),
            // A single zero group is not worth the two bytes `::` costs.
            (
                [0, 1, 0, 0, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7],
                "1:0:2:3:4:5:6:7",
            ),
        ];
        for (bytes, expected) in cases {
            assert_eq!(v6_of(&bytes), expected, "for {bytes:02x?}");
        }
    }

    /// Builds a one-record AAAA answer and reads its address back.
    fn v6_of(bytes: &[u8; 16]) -> String {
        let packet = Builder::response()
            .answer("h.local", TYPE_AAAA, 120, bytes)
            .unwrap()
            .build();
        let message = Message::parse(&packet).unwrap();
        message.records()[0].address_v6().unwrap()
    }

    #[test]
    fn ipv4_presentation() {
        let packet = Builder::response()
            .answer("h.local", TYPE_A, 120, &[192, 168, 1, 4])
            .unwrap()
            .build();
        let message = Message::parse(&packet).unwrap();
        assert_eq!(message.records()[0].address_v4().unwrap(), [192, 168, 1, 4]);
    }

    #[test]
    fn short_rdata_is_reported_with_its_type() {
        let packet = Builder::response()
            .answer("h.local", TYPE_SRV, 120, &[0, 0])
            .unwrap()
            .build();
        let message = Message::parse(&packet).unwrap();
        assert_eq!(
            message.records()[0].target(),
            Err(Error::ShortRdata {
                record_type: TYPE_SRV
            })
        );
    }

    #[test]
    fn txt_parsing_edge_cases() {
        let rdata = Builder::txt_rdata(&[("h", "abc"), ("fl", ""), ("rp", "x=y")]).unwrap();
        let txt = Txt::parse_rdata(&rdata).unwrap();
        assert_eq!(txt.get("h"), "abc");
        assert!(txt.has("fl"));
        assert_eq!(txt.get("rp"), "x=y");
        assert_eq!(txt.pairs().len(), 3);
        assert_eq!(txt.to_string(), "{h=abc, fl, rp=x=y}");
    }

    #[test]
    fn txt_overrun_is_refused() {
        assert_eq!(Txt::parse_rdata(&[5, b'a']), Err(Error::TxtOverrun));
    }

    #[test]
    fn any_matches_everything() {
        let question = Question {
            name: SERVICE.to_string(),
            record_type: TYPE_ANY,
            class: CLASS_IN,
            unicast: false,
        };
        assert!(question.matches("_AIRDROp._tcp.local.", TYPE_SRV));
        assert!(!question.matches("_other._tcp.local", TYPE_SRV));

        let question = Question {
            record_type: TYPE_SRV,
            ..question
        };
        assert!(question.matches(SERVICE, TYPE_ANY));
        assert!(!question.matches(SERVICE, TYPE_TXT));
    }

    #[test]
    fn names_compare_case_insensitively() {
        assert!(same_name("_AirDrop._tcp.local.", "_airdrop._tcp.local"));
        assert!(!same_name("a.local", "b.local"));
        assert_eq!(trim_dot("."), ".");
        assert_eq!(trim_dot("a."), "a");
    }

    #[test]
    fn short_header_is_refused() {
        assert_eq!(Message::parse(&[0; 11]).unwrap_err(), Error::ShortHeader);
    }

    #[test]
    fn truncated_packet_is_refused() {
        let bytes = query(SERVICE, TYPE_PTR).unwrap();
        assert!(Message::parse(&bytes[..bytes.len() - 2]).is_err());
    }

    #[test]
    fn trailing_garbage_after_records_is_ignored() {
        let mut bytes = query(SERVICE, TYPE_PTR).unwrap();
        bytes.extend_from_slice(&[0xff; 8]);
        assert_eq!(Message::parse(&bytes).unwrap().questions().len(), 1);
    }
}
