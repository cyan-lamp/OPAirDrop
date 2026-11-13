//! HTTP/1.1 framing for the AirDrop endpoints.
//!
//! Only what AirDrop actually uses: a request line, headers, and a body framed
//! either by `Content-Length` or by `Transfer-Encoding: chunked`. No cookies, no
//! caches, no redirects, no chunked *responses* — the three response shapes in
//! [`response`] are the entire output surface of the protocol.
//!
//! # Why the body borrows the stream
//!
//! [`Request::body`] takes `&mut R` — the same reader [`Request::read`] consumed the
//! head from — rather than taking ownership or returning a `Box<dyn Read>`. Two
//! things follow, and both are the reason the signature looks like this:
//!
//! * The caller keeps its stream, so draining one body and reading the next request
//!   off the same connection costs nothing: no `'static` bound, no `Rc`, no
//!   re-wrapping the socket per request.
//! * The reader is free to be a [`BufReader`](std::io::BufReader), which is what you
//!   want. The head is parsed a byte at a time; over a `BufReader` that is a memcpy,
//!   over a raw `TcpStream` it is one syscall per byte. And because the body borrows
//!   the *same* buffered reader, whatever the head read ahead is handed straight to
//!   the body instead of being swallowed.
//!
//! That second point is the whole bug the Java version lived with. It read the socket
//! one byte at a time on purpose — a read-ahead buffer there would have eaten upload
//! bytes the cpio reader needed — and paid a syscall per header byte for a decade of
//! uploads. Borrowing the stream makes buffering correct instead of dangerous, so the
//! trade-off disappears rather than being re-decided.
//!
//! # Deliberate differences from the Java implementation
//!
//! * `content_length()` returns `Option<u64>`. Java returned `-1` for "no usable
//!   length" and then a *second* helper clamped negatives to zero at the call site.
//!   One representation, one place to get it wrong.
//! * Header names are stored as received and compared with
//!   [`eq_ignore_ascii_case`](str::eq_ignore_ascii_case), instead of being lowercased
//!   into a second copy on insert.
//! * [`Request::read_body`] checks the limit *before* extending the buffer, so a peer
//!   promising a gigabyte costs a rejection, not a gigabyte.
//! * No `read()` override that allocates. See [`body`].

#![forbid(unsafe_code)]

mod body;
mod response;

pub use body::{Body, ChunkedWriter};
pub use response::{plist_response, reason, Response, CONTINUE, SERVER};

use std::fmt;
use std::io::{self, Read};

/// Longest request line or header line accepted, in bytes.
pub const MAX_LINE: usize = 8192;

/// Most header lines accepted in one head.
pub const MAX_HEADERS: usize = 64;

/// Byte budget the trace summary spends on the head before truncating.
pub const TRACE_HEAD_CAP: usize = 300;

/// Payload bytes per chunked frame on the way out.
///
/// 16 KiB because that is what the Java sender chose: the two implementations then put
/// their frame boundaries in the same places, so one capture can be read as evidence
/// about either. It is also small enough that a sender stalled on `100 Continue` has a
/// bounded amount of the archive queued in the kernel, not a megabyte of it.
pub const UPLOAD_CHUNK: usize = 16 * 1024;

/// Everything that can go wrong reading a request head or body.
///
/// Kept as one enum, including [`Error::Io`], so a caller writing a trace does not
/// have to flatten two error types into one message.
#[derive(Debug)]
pub enum Error {
    /// The underlying stream failed.
    Io(io::Error),
    /// The request line was not `METHOD SP TARGET [SP VERSION]`.
    BadRequestLine(String),
    /// The status line was not `VERSION SP CODE [SP REASON]`.
    BadStatusLine(String),
    /// A line exceeded [`MAX_LINE`].
    LineTooLong,
    /// The stream ended before the blank line that closes the head.
    TruncatedHead,
    /// More than [`MAX_HEADERS`] header lines.
    TooManyHeaders,
    /// The peer promised more body than it delivered.
    TruncatedBody { missing: u64 },
    /// A chunked body stopped mid-frame.
    TruncatedChunk,
    /// A chunk size line was not hexadecimal.
    BadChunkSize(String),
    /// A chunk's data was not followed by a bare line ending.
    BadChunkFrame(String),
    /// The body grew past the caller's limit.
    BodyTooLarge { limit: usize },
}

impl PartialEq for Error {
    fn eq(&self, other: &Self) -> bool {
        // `io::Error` is the only variant that cannot be compared structurally, and
        // its kind is the part a test can meaningfully pin.
        match (self, other) {
            (Error::Io(a), Error::Io(b)) => a.kind() == b.kind(),
            (Error::BadRequestLine(a), Error::BadRequestLine(b)) => a == b,
            (Error::BadStatusLine(a), Error::BadStatusLine(b)) => a == b,
            (Error::LineTooLong, Error::LineTooLong) => true,
            (Error::TruncatedHead, Error::TruncatedHead) => true,
            (Error::TooManyHeaders, Error::TooManyHeaders) => true,
            (Error::TruncatedBody { missing: a }, Error::TruncatedBody { missing: b }) => a == b,
            (Error::TruncatedChunk, Error::TruncatedChunk) => true,
            (Error::BadChunkSize(a), Error::BadChunkSize(b)) => a == b,
            (Error::BadChunkFrame(a), Error::BadChunkFrame(b)) => a == b,
            (Error::BodyTooLarge { limit: a }, Error::BodyTooLarge { limit: b }) => a == b,
            _ => false,
        }
    }
}

impl Eq for Error {}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Io(error) => write!(f, "{error}"),
            Error::BadRequestLine(line) => write!(f, "bad request line: {line}"),
            Error::BadStatusLine(line) => write!(f, "bad status line: {line}"),
            Error::LineTooLong => write!(f, "line longer than {MAX_LINE} bytes"),
            Error::TruncatedHead => f.write_str("headers truncated"),
            Error::TooManyHeaders => write!(f, "more than {MAX_HEADERS} headers"),
            Error::TruncatedBody { missing } => {
                write!(f, "body truncated, {missing} bytes missing")
            }
            Error::TruncatedChunk => f.write_str("chunk data truncated"),
            Error::BadChunkSize(line) => write!(f, "bad chunk size '{line}'"),
            Error::BadChunkFrame(line) => write!(f, "chunk not followed by a line ending: '{line}'"),
            Error::BodyTooLarge { limit } => write!(f, "body exceeds {limit} bytes"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<Error> for io::Error {
    fn from(error: Error) -> io::Error {
        match error {
            // Already an `io::Error`; re-wrapping would bury the original kind.
            Error::Io(inner) => inner,
            other => io::Error::new(io::ErrorKind::InvalidData, other),
        }
    }
}

/// Request headers, in arrival order, with repeats merged.
///
/// A `Vec` and not a map: the trace shows the head in the order it arrived, and a
/// hash map would print a different order every run — the same defect that made the
/// Java mDNS responder publish TXT records in a random order. Lookup is linear over
/// at most [`MAX_HEADERS`] entries, which is not a cost worth a second structure.
#[derive(Debug, Default, Clone)]
pub struct Headers {
    entries: Vec<(String, String)>,
}

impl Headers {
    /// Number of distinct header names.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Whether the head carried no headers at all.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Header pairs in arrival order, names as received.
    pub fn entries(&self) -> &[(String, String)] {
        &self.entries
    }

    /// Value of `name`, matched case-insensitively. Repeats come back joined by `", "`.
    pub fn get(&self, name: &str) -> Option<&str> {
        self.entries
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    /// Appends a header, merging into an existing entry of the same name so the
    /// first occurrence keeps its position — `LinkedHashMap` semantics, which the
    /// order-sensitive `Connection: keep-alive` + `Connection: close` case relies on.
    pub(crate) fn insert(&mut self, name: String, value: String) {
        match self
            .entries
            .iter_mut()
            .find(|(key, _)| key.eq_ignore_ascii_case(&name))
        {
            Some((_, existing)) => {
                existing.push_str(", ");
                existing.push_str(&value);
            }
            None => self.entries.push((name, value)),
        }
    }

    /// Declared body length, or `None` when absent, unparseable, or negative.
    ///
    /// AirDrop sends no length on some control requests, and the body is then empty.
    pub(crate) fn content_length(&self) -> Option<u64> {
        self.get("content-length")
            .and_then(|raw| raw.trim().parse::<u64>().ok())
    }

    /// Whether the body uses chunked transfer coding.
    pub(crate) fn is_chunked(&self) -> bool {
        self.get("transfer-encoding")
            .is_some_and(|value| contains_token(value, "chunked"))
    }

    /// Opens the body these headers describe.
    ///
    /// Chunked framing wins over a `Content-Length` if both appear, because that is what
    /// RFC 9112 says and what a bogus header would break. One place decides this for both
    /// directions: a response that framed its body differently from a request would make
    /// the client and the server disagree about where a body ends.
    pub(crate) fn body<'a, R: Read>(&self, input: &'a mut R) -> Body<'a, R> {
        if self.is_chunked() {
            Body::chunked(input)
        } else {
            match self.content_length() {
                Some(remaining) if remaining > 0 => Body::bounded(input, remaining),
                _ => Body::empty(),
            }
        }
    }

    /// Whether the connection survives the exchange these headers belong to.
    pub(crate) fn keep_alive(&self, version: &str) -> bool {
        if let Some(value) = self.get("connection") {
            if contains_token(value, "close") {
                return false;
            }
            if contains_token(value, "keep-alive") {
                return true;
            }
        }
        !version.contains("1.0")
    }
}

/// A parsed request head. The body stays in the stream; see [`Request::body`].
#[derive(Debug)]
pub struct Request {
    method: String,
    path: String,
    version: String,
    headers: Headers,
}

impl Request {
    /// Reads a request head, returning `Ok(None)` when the peer closed cleanly.
    ///
    /// `Ok(None)` means "this connection is done". Anything that is neither a
    /// complete head nor a clean close is an error, because a silently dropped
    /// request is worse than a rejected one: the peer is waiting on a response
    /// that will never come.
    pub fn read<R: Read>(input: &mut R) -> Result<Option<Request>, Error> {
        let mut line = Vec::new();

        // Senders may leave empty lines between pipelined requests.
        let request_line = loop {
            if !read_line(input, &mut line)? {
                return Ok(None);
            }
            if !line.is_empty() {
                break line.clone();
            }
        };

        let first_space = request_line.iter().position(|&byte| byte == b' ');
        let first_space = match first_space {
            Some(index) if index > 0 => index,
            // Either no space at all, or a line that starts with one.
            _ => return Err(Error::BadRequestLine(latin1(&request_line))),
        };
        let second_space = request_line[first_space + 1..]
            .iter()
            .position(|&byte| byte == b' ')
            .map(|index| index + first_space + 1);

        let method = latin1(&request_line[..first_space]);
        let (path, version) = match second_space {
            Some(index) => (
                latin1(&request_line[first_space + 1..index]),
                latin1(trim_ascii(&request_line[index + 1..])),
            ),
            None => (latin1(&request_line[first_space + 1..]), String::from("HTTP/1.1")),
        };

        let headers = read_headers(input, &mut line)?;

        Ok(Some(Request {
            method,
            path,
            version,
            headers,
        }))
    }

    /// Starts a request to send.
    ///
    /// The version is fixed at `HTTP/1.1`: every AirDrop peer speaks it, and a
    /// configurable version would only invite a `1.0` that changes the keep-alive
    /// default under the caller's feet.
    pub fn new(method: &str, path: &str) -> Request {
        Request {
            method: method.to_string(),
            path: path.to_string(),
            version: String::from("HTTP/1.1"),
            headers: Headers::default(),
        }
    }

    /// Adds a header, preserving the order written.
    ///
    /// Named `with_header` so it cannot be mistaken for the read-side [`Request::header`]
    /// accessor, which the server uses on requests it received. The check for splitting
    /// characters happens once, in [`Request::head_bytes`], over every header at once —
    /// there is exactly one place where a request becomes bytes.
    pub fn with_header(mut self, name: &str, value: &str) -> Self {
        self.headers.insert(name.to_string(), value.to_string());
        self
    }

    /// The head alone, for a request whose body has to wait for `100 Continue`.
    ///
    /// `body_len` is written as `Content-Length` unless the caller already set one or
    /// asked for chunked framing. Splitting the head from the body is what lets an
    /// upload announce `Expect: 100-continue` and then hold the archive back until the
    /// receiver says it is willing to read it.
    pub fn head_bytes(&self, body_len: u64) -> Vec<u8> {
        let mut out = Vec::with_capacity(160);
        push(&mut out, &self.method);
        out.push(b' ');
        push(&mut out, &self.path);
        out.push(b' ');
        push(&mut out, &self.version);
        out.extend_from_slice(b"\r\n");
        for (name, value) in self.headers.entries() {
            debug_assert!(
                is_safe_header_text(name) && is_safe_header_text(value),
                "header {name:?}: {value:?} would split the request"
            );
            push(&mut out, name);
            out.extend_from_slice(b": ");
            push(&mut out, value);
            out.extend_from_slice(b"\r\n");
        }
        if !self.is_chunked() && self.content_length().is_none() {
            push(&mut out, "Content-Length: ");
            push(&mut out, &body_len.to_string());
            out.extend_from_slice(b"\r\n");
        }
        out.extend_from_slice(b"\r\n");
        out
    }

    /// The whole request, head and framed body.
    ///
    /// Chunked framing is applied when the caller asked for it by header; otherwise the
    /// body follows the head verbatim under the length `head_bytes` declared.
    pub fn to_bytes(&self, body: &[u8]) -> Vec<u8> {
        let mut out = self.head_bytes(body.len() as u64);
        if self.is_chunked() {
            out.extend_from_slice(&body::encode_chunked(body, UPLOAD_CHUNK));
        } else {
            out.extend_from_slice(body);
        }
        out
    }

    /// Request method, as sent.
    pub fn method(&self) -> &str {
        &self.method
    }

    /// Request target, as sent — `/Discover`, `/Ask`, `/Upload`.
    pub fn path(&self) -> &str {
        &self.path
    }

    /// Protocol version, defaulting to `HTTP/1.1` when the peer omitted it.
    pub fn version(&self) -> &str {
        &self.version
    }

    /// All headers.
    pub fn headers(&self) -> &Headers {
        &self.headers
    }

    /// One header value, case-insensitively.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.get(name)
    }

    /// Declared body length, or `None` when absent, unparseable, or negative.
    ///
    /// AirDrop sends no length on some control requests, and the body is then empty.
    pub fn content_length(&self) -> Option<u64> {
        self.headers.content_length()
    }

    /// Whether the body uses chunked transfer coding.
    pub fn is_chunked(&self) -> bool {
        self.headers.is_chunked()
    }

    /// Whether the peer is waiting for `100 Continue` before sending the body.
    ///
    /// Every AirDrop upload announces this. Failing to answer it stalls the sender
    /// until its own timeout, which looks exactly like a network stall.
    pub fn expects_continue(&self) -> bool {
        self.header("expect")
            .is_some_and(|value| contains_token(value, "100-continue"))
    }

    /// Whether the connection should stay open after this exchange.
    ///
    /// HTTP/1.1 defaults to keep-alive, HTTP/1.0 to close unless it opts in — and
    /// older AirDrop peers still send the opt-in.
    pub fn keep_alive(&self) -> bool {
        self.headers.keep_alive(&self.version)
    }

    /// Opens the body. Chunked framing wins over a `Content-Length` if both appear,
    /// because that is what RFC 9112 says and what a bogus header would break.
    pub fn body<'a, R: Read>(&self, input: &'a mut R) -> Body<'a, R> {
        self.headers.body(input)
    }

    /// Reads the whole body, refusing anything larger than `limit` bytes.
    pub fn read_body<R: Read>(&self, input: &mut R, limit: usize) -> Result<Vec<u8>, Error> {
        self.body(input).read_to_vec(limit)
    }

    /// The head as one line, for a trace.
    ///
    /// Written because a framing summary was not enough to explain a stall: a phone
    /// posted `/Discover`, described as "chunked close", then sent no body byte for
    /// the rest of the connection's life. The summary could not say whether it had
    /// promised a body at all, or promised a size it never delivered. The headers
    /// answer that, and they exist exactly once, on the one request in this protocol
    /// that has ever stalled.
    ///
    /// Capped at `cap` bytes so a chatty or hostile peer cannot write a megabyte of
    /// header into a trace file. Names print as received.
    pub fn head_summary(&self, cap: usize) -> String {
        let mut out = String::with_capacity(160);
        for (name, value) in self.headers.entries() {
            if !out.is_empty() {
                out.push_str("; ");
            }
            out.push_str(name);
            out.push_str(": ");
            out.push_str(value);
            if out.len() > cap {
                out.push_str(" ...");
                break;
            }
        }
        if out.is_empty() {
            out.push('-');
        }
        out
    }
}

/// Reads header lines up to and including the blank line that ends a head.
///
/// Shared by [`Request::read`] and [`Response::read`] — the two differ only in their
/// first line. A header table parsed two ways is how `Connection: close, keep-alive`
/// ends up meaning two different things depending on who read it.
pub(crate) fn read_headers<R: Read>(input: &mut R, line: &mut Vec<u8>) -> Result<Headers, Error> {
    let mut headers = Headers::default();
    let mut count = 0usize;
    loop {
        if !read_line(input, line)? {
            return Err(Error::TruncatedHead);
        }
        if line.is_empty() {
            return Ok(headers);
        }
        count += 1;
        if count > MAX_HEADERS {
            return Err(Error::TooManyHeaders);
        }
        let colon = match line.iter().position(|&byte| byte == b':') {
            Some(index) if index > 0 => index,
            // Obs-fold continuations and junk lines are skipped, not guessed at.
            _ => continue,
        };
        headers.insert(
            latin1(trim_ascii(&line[..colon])),
            latin1(trim_ascii(&line[colon + 1..])),
        );
    }
}

/// Reads one line into `buf`, returning `false` only at a clean end of stream.
///
/// Accepts a bare LF as a line ending: a peer that sends one is malformed but not
/// ambiguous, and rejecting it would fail an exchange whose framing is fine. A lone
/// CR is not a line ending and stays in the buffer, where the caller's parse will
/// reject it.
pub(crate) fn read_line<R: Read>(input: &mut R, buf: &mut Vec<u8>) -> Result<bool, Error> {
    buf.clear();
    let mut byte = [0u8; 1];
    loop {
        let read = read_some(input, &mut byte)?;
        if read == 0 {
            // A partial line at EOF is still a line; the caller decides what it means.
            return Ok(!buf.is_empty());
        }
        if byte[0] == b'\n' {
            if buf.last() == Some(&b'\r') {
                buf.pop();
            }
            return Ok(true);
        }
        if buf.len() >= MAX_LINE {
            return Err(Error::LineTooLong);
        }
        buf.push(byte[0]);
    }
}

/// One `read`, retrying on `Interrupted` so a signal cannot truncate a body.
pub(crate) fn read_some<R: Read>(input: &mut R, buf: &mut [u8]) -> Result<usize, Error> {
    loop {
        match input.read(buf) {
            Ok(read) => return Ok(read),
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(Error::Io(error)),
        }
    }
}

/// Whether header text can be written without splitting a message.
///
/// Names and values must be ASCII and free of CR, LF and NUL. Every caller in this
/// project passes a literal, so the check is a `debug_assert` at the call site rather
/// than a runtime error path that could never be taken — but it exists, because a header
/// built from a peer-supplied string would otherwise be a request-splitting hole.
pub(crate) fn is_safe_header_text(text: &str) -> bool {
    text.bytes().all(|byte| byte != b'\r' && byte != b'\n' && byte != 0)
}

/// Appends ASCII text to a byte buffer.
pub(crate) fn push(out: &mut Vec<u8>, text: &str) {
    out.extend_from_slice(text.as_bytes());
}

/// Decodes bytes one-to-one into chars, the way `ISO-8859-1` does in Java.
///
/// Header bytes are not required to be UTF-8, and replacing them with U+FFFD would
/// make two different heads print identically in a trace. Latin-1 maps every byte to
/// a char, so a trace is a faithful record and the round trip is lossless.
fn latin1(bytes: &[u8]) -> String {
    bytes.iter().map(|&byte| byte as char).collect()
}

pub(crate) fn trim_ascii(bytes: &[u8]) -> &[u8] {
    let is_space = |byte: &u8| matches!(byte, b' ' | b'\t' | b'\r' | b'\n');
    let start = bytes.iter().position(|byte| !is_space(byte)).unwrap_or(bytes.len());
    let end = bytes.iter().rposition(|byte| !is_space(byte)).map_or(start, |index| index + 1);
    &bytes[start..end]
}

/// Whether a comma-separated header value contains `token`, case-insensitively.
///
/// Allocates nothing: lowercasing the value to search it would be one allocation per
/// header per request on the hot path of every upload.
///
/// Public because the endpoint layer decides `406` the same way — a `Content-Type`
/// that "contains `application/x-cpio`" is an archive — and two implementations of
/// "contains, ignoring case" is how one of them ends up case-sensitive.
pub fn contains_token(value: &str, token: &str) -> bool {
    let value = value.as_bytes();
    let token = token.as_bytes();
    !token.is_empty()
        && value.len() >= token.len()
        && value
            .windows(token.len())
            .any(|window| window.eq_ignore_ascii_case(token))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Reads a head and leaves the body in the stream, mirroring how the server uses it.
    fn read(raw: &str) -> Result<Option<Request>, Error> {
        let mut input = raw.as_bytes();
        Request::read(&mut input)
    }

    fn read_ok(raw: &str) -> Request {
        read(raw).expect("head parses").expect("head present")
    }

    #[test]
    fn request_line_and_headers() {
        let request = read_ok(
            "POST /Ask HTTP/1.1\r\n\
             Host: 169.254.9.14:8770\r\n\
             User-Agent: AirDrop/1.0\r\n\
             Content-Type: application/octet-stream\r\n\
             Accept-Encoding: br, gzip, deflate\r\n\
             Content-Length: 5\r\n\
             Connection: keep-alive\r\n\
             \r\n",
        );
        assert_eq!(request.method(), "POST");
        assert_eq!(request.path(), "/Ask");
        assert_eq!(request.version(), "HTTP/1.1");
        assert_eq!(request.header("user-agent"), Some("AirDrop/1.0"));
        assert_eq!(request.header("User-Agent"), Some("AirDrop/1.0"));
        assert_eq!(request.header("accept-encoding"), Some("br, gzip, deflate"));
        assert_eq!(request.content_length(), Some(5));
        assert_eq!(request.header("x-missing"), None);
        assert_eq!(request.headers().len(), 6);
    }

    #[test]
    fn repeated_headers_merge_in_arrival_order() {
        let request = read_ok(
            "GET /Discover HTTP/1.1\r\nConnection: keep-alive\r\nConnection: close\r\n\r\n",
        );
        assert_eq!(request.header("connection"), Some("keep-alive, close"));
        assert!(!request.keep_alive(), "close wins in a merged value");
        // Merging must not add an entry: the first occurrence keeps its position.
        assert_eq!(request.headers().len(), 1);
    }

    #[test]
    fn headers_keep_the_order_they_arrived_in() {
        let request = read_ok("GET / HTTP/1.1\r\nZeta: 1\r\nAlpha: 2\r\nMu: 3\r\n\r\n");
        let names: Vec<&str> = request
            .headers()
            .entries()
            .iter()
            .map(|(name, _)| name.as_str())
            .collect();
        assert_eq!(names, ["Zeta", "Alpha", "Mu"]);
        assert_eq!(request.head_summary(TRACE_HEAD_CAP), "Zeta: 1; Alpha: 2; Mu: 3");
    }

    #[test]
    fn stray_blank_lines_before_a_request_are_tolerated() {
        let request = read_ok("\r\n\r\nGET /Discover HTTP/1.1\r\n\r\n");
        assert_eq!(request.path(), "/Discover");
    }

    #[test]
    fn bare_lf_line_endings_are_accepted() {
        let request = read_ok("GET /Ask HTTP/1.1\nContent-Length: 0\n\n");
        assert_eq!(request.path(), "/Ask");
        assert_eq!(request.content_length(), Some(0));
    }

    #[test]
    fn a_request_line_without_a_version_defaults_to_http_11() {
        let request = read_ok("GET /Discover\r\n\r\n");
        assert_eq!(request.method(), "GET");
        assert_eq!(request.path(), "/Discover");
        assert_eq!(request.version(), "HTTP/1.1");
    }

    #[test]
    fn content_length_body_is_exact() {
        let mut input = "POST /Discover HTTP/1.1\r\nContent-Length: 4\r\n\r\nplstTRAILING".as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.read_body(&mut input, 1024).unwrap(), b"plst");
        // The stream is positioned at the byte after the body, not past it.
        assert_eq!(input, b"TRAILING");
    }

    #[test]
    fn chunked_body_dechunks() {
        let mut input =
            "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
                .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert!(request.is_chunked());
        assert_eq!(request.read_body(&mut input, 1024).unwrap(), b"Wikipedia");
    }

    #[test]
    fn chunked_body_survives_byte_at_a_time_reads() {
        let mut input = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n1\r\nd\r\n0\r\n\r\n"
            .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        let mut body = request.body(&mut input);
        let mut out = Vec::new();
        let mut byte = [0u8; 1];
        while body.read(&mut byte).unwrap() == 1 {
            out.push(byte[0]);
        }
        assert_eq!(out, b"abcd");
    }

    #[test]
    fn chunk_extensions_are_ignored() {
        let mut input = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5;foo=bar\r\nhello\r\n0\r\n\r\n"
            .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.read_body(&mut input, 64).unwrap(), b"hello");
    }

    #[test]
    fn chunk_sizes_are_case_insensitive_hex() {
        let mut input = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1A\r\nabcdefghijklmnopqrstuvwxyz\r\n0\r\n\r\n"
            .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.read_body(&mut input, 64).unwrap().len(), 26);
    }

    #[test]
    fn trailers_after_the_last_chunk_are_consumed() {
        let mut input = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\nX-Trace: 1\r\n\r\n"
            .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.read_body(&mut input, 64).unwrap(), b"abc");
        // Everything before the trailer section was consumed; nothing follows.
        assert!(input.is_empty());
    }

    #[test]
    fn chunked_wins_over_content_length() {
        let mut input =
            "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n6\r\nabcdef\r\n0\r\n\r\n"
                .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.read_body(&mut input, 64).unwrap(), b"abcdef");
    }

    #[test]
    fn bad_chunk_size_is_rejected() {
        let mut input = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nabc\r\n0\r\n\r\n"
            .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(
            request.read_body(&mut input, 64).unwrap_err(),
            Error::BadChunkSize("zz".into())
        );
    }

    #[test]
    fn a_chunk_not_followed_by_a_line_ending_is_rejected() {
        let mut input = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX\r\n0\r\n\r\n"
            .as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert!(matches!(
            request.read_body(&mut input, 64).unwrap_err(),
            Error::BadChunkFrame(_)
        ));
    }

    #[test]
    fn expects_continue_is_detected() {
        let request = read_ok(
            "POST /Upload HTTP/1.1\r\nExpect: 100-continue\r\nTransfer-Encoding: chunked\r\n\r\n",
        );
        assert!(request.expects_continue());

        let plain = read_ok("POST /Ask HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
        assert!(!plain.expects_continue());
    }

    #[test]
    fn keep_alive_rules() {
        assert!(!read_ok("POST /Ask HTTP/1.0\r\n\r\n").keep_alive());
        assert!(read_ok("POST /Ask HTTP/1.1\r\n\r\n").keep_alive());
        assert!(!read_ok("POST /Ask HTTP/1.1\r\nConnection: close\r\n\r\n").keep_alive());
        assert!(read_ok("POST /Ask HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").keep_alive());
    }

    #[test]
    fn missing_length_means_empty_body() {
        let mut input = "POST /Discover HTTP/1.1\r\nHost: x\r\n\r\n".as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.content_length(), None);
        assert!(request.read_body(&mut input, 1024).unwrap().is_empty());
    }

    #[test]
    fn unusable_content_length_is_treated_as_absent() {
        for raw in ["Content-Length: -5", "Content-Length: abc", "Content-Length: "] {
            let request = read_ok(&format!("POST /Ask HTTP/1.1\r\n{raw}\r\n\r\n"));
            assert_eq!(request.content_length(), None, "{raw}");
        }
    }

    #[test]
    fn truncated_body_is_reported() {
        let mut input = "POST /Ask HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(
            request.read_body(&mut input, 1024).unwrap_err(),
            Error::TruncatedBody { missing: 7 }
        );
    }

    #[test]
    fn truncated_chunk_is_reported() {
        let mut input = "POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n9\r\nabc".as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(
            request.read_body(&mut input, 64).unwrap_err(),
            Error::TruncatedChunk
        );
    }

    /// The limit has to be checked before the buffer grows, or a peer that promises a
    /// gigabyte gets a gigabyte of reservation out of a 32-byte header.
    #[test]
    fn body_limit_is_refused_before_growing() {
        let raw = format!("POST /Ask HTTP/1.1\r\nContent-Length: 100\r\n\r\n{}", "x".repeat(100));
        let mut input = raw.as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(
            request.read_body(&mut input, 50).unwrap_err(),
            Error::BodyTooLarge { limit: 50 }
        );
    }

    #[test]
    fn a_body_of_exactly_the_limit_is_accepted() {
        let raw = format!("POST /Ask HTTP/1.1\r\nContent-Length: 50\r\n\r\n{}", "x".repeat(50));
        let mut input = raw.as_bytes();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.read_body(&mut input, 50).unwrap().len(), 50);
    }

    #[test]
    fn bad_request_line_is_rejected() {
        for raw in ["GARBAGE\r\n\r\n", " GET /Discover HTTP/1.1\r\n\r\n"] {
            assert!(
                matches!(read(raw), Err(Error::BadRequestLine(_))),
                "{raw:?} was accepted"
            );
        }
    }

    #[test]
    fn truncated_head_is_rejected() {
        assert_eq!(
            read("GET /Discover HTTP/1.1\r\nHost: x\r\n").unwrap_err(),
            Error::TruncatedHead
        );
    }

    #[test]
    fn header_count_is_capped() {
        let mut raw = String::from("GET / HTTP/1.1\r\n");
        for index in 0..=MAX_HEADERS {
            raw.push_str(&format!("X-Pad-{index}: 1\r\n"));
        }
        raw.push_str("\r\n");
        assert_eq!(read(&raw).unwrap_err(), Error::TooManyHeaders);
    }

    #[test]
    fn header_lines_without_a_name_are_skipped() {
        let request = read_ok("GET / HTTP/1.1\r\n: orphan\r\nX-Real: 1\r\n\r\n");
        assert_eq!(request.headers().len(), 1);
        assert_eq!(request.header("x-real"), Some("1"));
    }

    #[test]
    fn over_long_line_is_refused() {
        let raw = format!("GET /{} HTTP/1.1\r\n\r\n", "x".repeat(MAX_LINE));
        assert_eq!(read(&raw).unwrap_err(), Error::LineTooLong);
    }

    #[test]
    fn clean_end_of_stream_returns_none() {
        assert!(read("").unwrap().is_none());
        assert!(read("\r\n").unwrap().is_none());
        assert!(read("\r\n\r\n\r\n").unwrap().is_none());
    }

    /// The reason the body borrows the stream rather than owning it: one connection,
    /// many requests, no re-wrapping and no lost read-ahead.
    #[test]
    fn the_same_stream_carries_the_next_request_after_the_body() {
        let raw = "POST /Ask HTTP/1.1\r\nContent-Length: 2\r\n\r\nhi\
                   GET /Discover HTTP/1.1\r\nContent-Length: 2\r\n\r\nyo\
                   ";
        let mut input = std::io::BufReader::new(raw.as_bytes());
        let first = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(first.path(), "/Ask");
        assert_eq!(first.read_body(&mut input, 64).unwrap(), b"hi");
        let second = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(second.path(), "/Discover");
        assert_eq!(second.read_body(&mut input, 64).unwrap(), b"yo");
        assert!(Request::read(&mut input).unwrap().is_none());
    }

    #[test]
    fn non_ascii_header_bytes_round_trip_through_a_trace() {
        // 0xff is not UTF-8; a lossy decode would print U+FFFD and hide the difference
        // between this head and one whose byte really was 0xfd.
        let mut raw = b"GET / HTTP/1.1\r\nX-Odd: ".to_vec();
        raw.push(0xff);
        raw.extend_from_slice(b"\r\n\r\n");
        let mut input = raw.as_slice();
        let request = Request::read(&mut input).unwrap().unwrap();
        assert_eq!(request.header("x-odd"), Some("\u{00ff}"));
        assert!(request.head_summary(TRACE_HEAD_CAP).contains('\u{00ff}'));
    }

    #[test]
    fn head_summary_is_capped_and_never_empty() {
        assert_eq!(read_ok("GET / HTTP/1.1\r\n\r\n").head_summary(TRACE_HEAD_CAP), "-");
        let mut raw = String::from("GET / HTTP/1.1\r\n");
        for index in 0..8 {
            raw.push_str(&format!("X-Pad-{index}: {}\r\n", "y".repeat(60)));
        }
        raw.push_str("\r\n");
        let summary = read_ok(&raw).head_summary(TRACE_HEAD_CAP);
        assert!(summary.len() <= TRACE_HEAD_CAP + 64, "{}", summary.len());
        assert!(summary.ends_with(" ..."));
    }

    #[test]
    fn contains_token_matches_whole_runs_not_just_prefixes() {
        assert!(contains_token("br, gzip, chunked", "chunked"));
        assert!(contains_token("CHUNKED", "chunked"));
        assert!(!contains_token("chunkedx", "chunkedx-more"), "longer token");
        assert!(!contains_token("", "chunked"));
        assert!(!contains_token("chunked", ""), "empty token never matches");
    }

    /// The head a sender puts in front of an `/Upload`, in the order `HttpCall` wrote it:
    /// `Host`, `User-Agent`, `Content-Type`, the protocol headers, the framing header,
    /// `Connection` last. Order is asserted because a receiver that reads headers with a
    /// `LinkedHashMap` sees it, and the two implementations are compared by capture.
    #[test]
    fn an_upload_head_is_written_in_the_order_java_wrote_it() {
        let head = Request::new("POST", "/Upload")
            .with_header("Host", "phone.local")
            .with_header("User-Agent", "AirDrop/1.0")
            .with_header("Content-Type", "application/x-cpio")
            .with_header("TransferID", "2A2A2A2A-2A2A-4A2A-AA2A-2A2A2A2A2A2A")
            .with_header("Transfer-Encoding", "chunked")
            .with_header("Connection", "keep-alive")
            .head_bytes(0);
        let text = String::from_utf8(head).unwrap();
        let lines: Vec<&str> = text.split("\r\n").collect();
        assert_eq!(lines[0], "POST /Upload HTTP/1.1");
        assert_eq!(
            lines[1..7],
            [
                "Host: phone.local",
                "User-Agent: AirDrop/1.0",
                "Content-Type: application/x-cpio",
                "TransferID: 2A2A2A2A-2A2A-4A2A-AA2A-2A2A2A2A2A2A",
                "Transfer-Encoding: chunked",
                "Connection: keep-alive",
            ]
        );
        assert_eq!(lines[7], "", "the head ends with exactly one blank line");
        // Chunked framing declares its own length, so a `Content-Length: 0` here would
        // be a second, contradictory answer to the same question.
        assert!(!text.contains("Content-Length"));
    }

    #[test]
    fn a_chunked_body_is_framed_in_sixteen_kibibyte_frames() {
        let request = Request::new("POST", "/Upload").with_header("Transfer-Encoding", "chunked");
        let body = vec![b'z'; UPLOAD_CHUNK + 5];
        let bytes = request.to_bytes(&body);
        let head = request.head_bytes(body.len() as u64);
        assert_eq!(&bytes[..head.len()], head.as_slice());
        let framed = &bytes[head.len()..];
        assert!(framed.starts_with(b"4000\r\n"));
        assert_eq!(
            framed,
            [
                b"4000\r\n".as_slice(),
                &body[..UPLOAD_CHUNK],
                b"\r\n5\r\n",
                &body[UPLOAD_CHUNK..],
                b"\r\n0\r\n\r\n",
            ]
            .concat()
        );
    }

    #[test]
    fn a_request_with_a_known_length_carries_no_chunk_framing() {
        let request = Request::new("POST", "/Discover").with_header("Content-Type", "application/x-bplist");
        let bytes = request.to_bytes(b"abc");
        assert!(bytes.ends_with(b"\r\n\r\nabc"));
        let text = String::from_utf8_lossy(&bytes).into_owned();
        assert!(text.contains("Content-Length: 3"));
        assert!(!text.contains("chunked"));
    }
}
