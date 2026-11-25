//! The three response shapes the AirDrop endpoints emit.
//!
//! In practice only these exist, and the exact bytes matter because the peer is
//! waiting on them:
//!
//! * [`CONTINUE`] — must reach the sender before an `/Upload` body arrives, or the
//!   sender stalls until its own timeout;
//! * a `200` with a binary plist body — `/Discover` and `/Ask`;
//! * a `200` with `Content-Length: 0` and `Connection: close` — `/Upload`, which ends
//!   the exchange.
//!
//! # One writer instead of four
//!
//! Java had `sendContinue`, `send(body, keepAlive)`, `sendEmpty`, and a
//! full-control `send(status, headers, body, keepAlive)` — four entry points into one
//! format, three of them shortcuts past the fourth, all of them writing straight to an
//! `OutputStream`. Adding a header meant deciding which four to touch.
//!
//! Here the head is built by one function into a `Vec<u8>`, and the 100-continue case
//! is a constant slice, so it cannot drift from the rest. Nothing in this crate writes
//! to a socket: the caller owns the socket, which is what lets one `Response` be
//! captured in a test and compared byte for byte.

use crate::{latin1, read_headers, read_line, Body, Error, Headers};
use std::io::Read;

/// `Server` header value. Apple's own endpoints answer with this.
pub const SERVER: &str = "AirDrop/1.0";

/// The complete `100 Continue` reply, framing-free per RFC 9110.
///
/// A constant so the upload path hands the kernel eight bytes it already has, instead
/// of formatting a status line on the one path where a millisecond of latency decides
/// whether the sender waits a full second.
pub const CONTINUE: &[u8] = b"HTTP/1.1 100 Continue\r\n\r\n";

/// Reason phrase for a status code.
pub fn reason(status: u16) -> &'static str {
    match status {
        100 => "Continue",
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        406 => "Not Acceptable",
        413 => "Payload Too Large",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        // Peers read the code, not the phrase; anything else gets a plausible word so
        // the status line stays syntactically valid.
        //
        // `403` is listed rather than left to the fallback even though a correct peer
        // ignores the phrase: it is the code this endpoint sends when a person declines a
        // transfer, and `403 Error` on the one answer that decides whether a phone says
        // "declined" or "declined, but something looked wrong" is not worth saving a line.
        _ if status < 400 => "OK",
        _ => "Error",
    }
}

/// A response head and optional body, ready to render to bytes — or, on the client
/// side, the parsed result of [`Response::read`].
///
/// One type for both directions, and one header table ([`Headers`]) for both, so
/// "repeats are merged and the first position is kept" is a property of the protocol
/// rather than of whichever side happens to be reading.
///
/// Header names and values must be ASCII and free of CR and LF. The only caller of the
/// builder is this project's own server, where every one of them is a literal, so the
/// check is a debug assertion rather than a runtime error path that could never be
/// taken — but it is present, because a header built from a peer-supplied string would
/// otherwise be a response-splitting hole.
#[derive(Debug, Clone)]
pub struct Response {
    status: u16,
    headers: Headers,
    body: Vec<u8>,
    keep_alive: bool,
}

impl Response {
    /// Starts a response. Connections stay open unless told otherwise, which is the
    /// HTTP/1.1 default and what a peer reusing its connection expects.
    pub fn new(status: u16) -> Self {
        Response {
            status,
            headers: Headers::default(),
            body: Vec::new(),
            keep_alive: true,
        }
    }

    /// Adds a header. Order is preserved as written.
    ///
    /// `with_header` rather than `header` so the builder and the [`Response::header`]
    /// accessor cannot be confused at a call site — this type is written by the server
    /// and read by the client, so both directions are in use.
    pub fn with_header(mut self, name: &str, value: &str) -> Self {
        // 和 Request::head_bytes 对称（评审 P1-7）：这里的 debug_assert 在 release
        // 下会被剥掉，而响应文案里可能带着对端给的文件名。不放行不安全的头，
        // 免得一个 CRLF 在响应里写出第二组头。
        if !is_safe_header_text(name) || !is_safe_header_text(value) {
            return self;
        }
        self.headers.insert(name.to_string(), value.to_string());
        self
    }

    /// Sets the body.
    ///
    /// `with_body` for the same reason as [`Response::with_header`]: [`Response::body`]
    /// is the accessor the client reads through.
    pub fn with_body(mut self, body: impl Into<Vec<u8>>) -> Self {
        self.body = body.into();
        self
    }

    /// Sets whether the connection survives this exchange.
    pub fn with_keep_alive(mut self, keep_alive: bool) -> Self {
        self.keep_alive = keep_alive;
        self
    }

    /// Renders the head and body into one buffer.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(160 + self.body.len());
        push(&mut out, "HTTP/1.1 ");
        push(&mut out, &self.status.to_string());
        push(&mut out, " ");
        push(&mut out, reason(self.status));
        push(&mut out, "\r\n");
        push(&mut out, "Server: ");
        push(&mut out, SERVER);
        push(&mut out, "\r\n");
        for (name, value) in self.headers.entries() {
            push(&mut out, name);
            push(&mut out, ": ");
            push(&mut out, value);
            push(&mut out, "\r\n");
        }
        // A 1xx or 204 carries no body framing at all, so a Content-Length there would
        // be a framing error rather than a harmless extra.
        if self.status != 100 && self.status != 204 {
            push(&mut out, "Content-Length: ");
            push(&mut out, &self.body.len().to_string());
            push(&mut out, "\r\n");
        }
        push(&mut out, "Connection: ");
        push(
            &mut out,
            if self.keep_alive {
                "keep-alive"
            } else {
                "close"
            },
        );
        push(&mut out, "\r\n\r\n");
        out.extend_from_slice(&self.body);
        out
    }

    /// Reads a response, body included.
    ///
    /// `limit` caps the body, and it is checked before the buffer grows rather than
    /// after: every answer in this protocol is a plist of a few hundred bytes or an
    /// empty confirmation, so a peer declaring a megabyte is either not the peer we
    /// think it is or is trying to make us reserve one.
    ///
    /// An interim `1xx` comes back as it arrives instead of being skipped here. It is
    /// not the answer to the request, and only the caller knows whether it asked for
    /// one; Java skipped the whole range inside its reader, which quietly ate every
    /// informational status the protocol might ever grow.
    pub fn read<R: Read>(input: &mut R, limit: usize) -> Result<Response, Error> {
        let mut line = Vec::new();
        if !read_line(input, &mut line)? {
            return Err(Error::TruncatedHead);
        }
        let text = latin1(&line);
        let (version, status) = status_line(&text)?;
        let headers = read_headers(input, &mut line)?;

        // 1xx, 204 and 304 carry no body by definition. Framing one anyway is the
        // difference between reading the next answer and reading the rest of the
        // connection: a `100 Continue` has no length, and "no length" on a response means
        // "until the peer closes" — which, on a socket nobody is going to close, is a
        // hang with no error to report.
        let body = if status < 200 || status == 204 || status == 304 {
            Vec::new()
        } else if headers.is_chunked() {
            Body::chunked(input).read_to_vec(limit)?
        } else if let Some(length) = headers.content_length() {
            if length > limit as u64 {
                return Err(Error::BodyTooLarge { limit });
            }
            Body::bounded(input, length).read_to_vec(limit)?
        } else {
            Body::until_eof(input).read_to_vec(limit)?
        };

        let keep_alive = headers.keep_alive(&version);
        Ok(Response {
            status,
            headers,
            body,
            keep_alive,
        })
    }

    /// Status code, as received.
    pub fn status(&self) -> u16 {
        self.status
    }

    /// Whether the code is in the `2xx` range.
    pub fn ok(&self) -> bool {
        (200..300).contains(&self.status)
    }

    /// Whether this is an interim answer rather than the final one.
    pub fn is_informational(&self) -> bool {
        (100..200).contains(&self.status)
    }

    /// One header value, matched case-insensitively; repeats come back joined by `", "`.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.get(name)
    }

    /// All headers, in arrival order, names as received.
    pub fn headers(&self) -> &Headers {
        &self.headers
    }

    /// The body, empty for an answer that carried none.
    pub fn body(&self) -> &[u8] {
        &self.body
    }

    /// Whether the connection survives this answer.
    pub fn keeps_alive(&self) -> bool {
        self.keep_alive
    }
}

/// Parses `HTTP/1.1 200 OK` into the version and the code.
///
/// The reason phrase is deliberately outside the parse: it is free text, peers disagree
/// about it, and nothing in this protocol reads it. The code has to be exactly three
/// ASCII digits — Java ran `Integer.parseInt` on whatever followed the space, which turns
/// a truncated `20` into a status of twenty and a `2e2` into two hundred.
fn status_line(line: &str) -> Result<(String, u16), Error> {
    let mut parts = line.splitn(3, ' ');
    let version = parts.next().unwrap_or("");
    let digits = parts.next().unwrap_or("");
    if !version.starts_with("HTTP/")
        || digits.len() != 3
        || !digits.bytes().all(|byte| byte.is_ascii_digit())
    {
        return Err(Error::BadStatusLine(line.to_string()));
    }
    let status = digits
        .parse::<u16>()
        .map_err(|_| Error::BadStatusLine(line.to_string()))?;
    Ok((version.to_string(), status))
}

/// Convenience for the `200` + plist answers of `/Discover` and `/Ask`.
pub fn plist_response(body: Vec<u8>, keep_alive: bool) -> Response {
    Response::new(200)
        .with_header("Content-Type", "application/octet-stream")
        .with_body(body)
        .with_keep_alive(keep_alive)
}

impl From<Error> for Response {
    /// Turns a framing failure into the reply the peer should get for it.
    ///
    /// Every variant that points at the peer's own bytes becomes `400`; a stream that
    /// failed under us becomes `500`, because the peer did nothing wrong. Having this
    /// in one place is why the server never has to invent a status code per call site.
    fn from(error: Error) -> Response {
        let status = match error {
            Error::Io(_) => 500,
            Error::BodyTooLarge { .. } => 413,
            _ => 400,
        };
        Response::new(status).with_keep_alive(false)
    }
}

fn is_safe_header_text(text: &str) -> bool {
    text.bytes()
        .all(|byte| byte != b'\r' && byte != b'\n' && byte != 0)
}

fn push(out: &mut Vec<u8>, text: &str) {
    out.extend_from_slice(text.as_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn head(response: &Response) -> String {
        let bytes = response.to_bytes();
        let text = String::from_utf8_lossy(&bytes).into_owned();
        text.split_once("\r\n\r\n")
            .expect("blank line")
            .0
            .to_string()
    }

    /// Header lookup over the rendered head, so assertions read the bytes and not the
    /// struct that produced them.
    fn header_value(response: &Response, name: &str) -> Option<String> {
        head(response).split("\r\n").skip(1).find_map(|line| {
            let (key, value) = line.split_once(": ")?;
            key.eq_ignore_ascii_case(name).then(|| value.to_string())
        })
    }

    #[test]
    fn continue_is_exactly_eight_bytes_of_head() {
        assert_eq!(CONTINUE, b"HTTP/1.1 100 Continue\r\n\r\n" as &[u8]);
        assert_eq!(CONTINUE.len(), 25);
    }

    #[test]
    fn a_plist_response_is_byte_identical_to_what_java_wrote() {
        let response = plist_response(b"plist".to_vec(), true);
        assert_eq!(
            String::from_utf8(response.to_bytes()).unwrap(),
            "HTTP/1.1 200 OK\r\n\
             Server: AirDrop/1.0\r\n\
             Content-Type: application/octet-stream\r\n\
             Content-Length: 5\r\n\
             Connection: keep-alive\r\n\
             \r\n\
             plist"
        );
    }

    #[test]
    fn an_empty_rejection_carries_a_zero_length_and_closes() {
        let response = Response::new(406).with_keep_alive(false);
        assert_eq!(
            header_value(&response, "content-length").as_deref(),
            Some("0")
        );
        assert_eq!(
            header_value(&response, "connection").as_deref(),
            Some("close")
        );
        assert!(response.to_bytes().ends_with(b"\r\n\r\n"));
    }

    #[test]
    fn a_continue_response_omits_content_length() {
        let response = Response::new(100);
        assert_eq!(header_value(&response, "content-length"), None);
        let same = Response::new(204);
        assert_eq!(header_value(&same, "content-length"), None);
    }

    #[test]
    fn reason_phrases_cover_the_codes_this_server_sends() {
        assert_eq!(reason(100), "Continue");
        assert_eq!(reason(200), "OK");
        assert_eq!(reason(400), "Bad Request");
        assert_eq!(reason(403), "Forbidden");
        assert_eq!(reason(404), "Not Found");
        assert_eq!(reason(405), "Method Not Allowed");
        assert_eq!(reason(406), "Not Acceptable");
        assert_eq!(reason(413), "Payload Too Large");
        assert_eq!(reason(500), "Internal Server Error");
        assert_eq!(reason(503), "Service Unavailable");
        // Unknown codes stay syntactically valid.
        assert_eq!(reason(201), "OK");
        assert_eq!(reason(418), "Error");
    }

    #[test]
    fn framing_failures_map_to_the_status_the_peer_should_see() {
        assert_eq!(Response::from(Error::TruncatedHead).status, 400);
        assert_eq!(Response::from(Error::BadChunkSize("zz".into())).status, 400);
        assert_eq!(Response::from(Error::BodyTooLarge { limit: 1 }).status, 413);
        // A stream that failed under us is not the peer's fault.
        let io = Error::Io(std::io::Error::from(std::io::ErrorKind::BrokenPipe));
        assert_eq!(Response::from(io).status, 500);
    }

    #[test]
    fn headers_render_in_the_order_they_were_added() {
        let response = Response::new(200)
            .with_header("Content-Type", "application/x-cpio")
            .with_header("X-One", "1")
            .with_header("X-Two", "2");
        let rendered = head(&response);
        let order: Vec<&str> = rendered
            .split("\r\n")
            .skip(1)
            .filter_map(|line| line.split_once(": ").map(|(key, _)| key))
            .collect();
        assert_eq!(
            order,
            [
                "Server",
                "Content-Type",
                "X-One",
                "X-Two",
                "Content-Length",
                "Connection"
            ]
        );
    }

    #[test]
    fn a_body_is_appended_after_the_blank_line_untouched() {
        let payload: Vec<u8> = (0..=255u8).collect();
        let response = Response::new(200).with_body(payload.clone());
        let bytes = response.to_bytes();
        assert!(bytes.ends_with(&payload));
        assert_eq!(
            header_value(&response, "content-length"),
            Some("256".into())
        );
    }

    /// One KiB is the limit every read test uses, so the number in a failure is the
    /// test's, not a constant that might drift.
    const LIMIT: usize = 1024;

    fn read_all(bytes: &[u8]) -> Response {
        let mut cursor = bytes;
        Response::read(&mut cursor, LIMIT).expect("reads")
    }

    #[test]
    fn an_answer_written_by_this_crate_reads_back_intact() {
        let written = plist_response(b"bplist00".to_vec(), true).to_bytes();
        let response = read_all(&written);
        assert_eq!(response.status(), 200);
        assert!(response.ok());
        assert_eq!(response.body(), b"bplist00");
        assert_eq!(
            response.header("content-type"),
            Some("application/octet-stream")
        );
        assert!(response.keeps_alive());
    }

    #[test]
    fn an_interim_answer_carries_no_body_and_leaves_the_next_one_in_the_stream() {
        let mut stream: &[u8] =
            b"HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";
        let interim = Response::read(&mut stream, LIMIT).expect("reads the interim");
        assert_eq!(interim.status(), 100);
        assert!(interim.is_informational());
        assert!(!interim.ok());
        assert!(
            interim.body().is_empty(),
            "100 has no body, not one until close"
        );

        // The point of not consuming the body here: the real answer is still queued.
        let answer = Response::read(&mut stream, LIMIT).expect("reads the answer");
        assert_eq!(answer.status(), 200);
        assert_eq!(answer.body(), b"ok");
        assert!(stream.is_empty());
    }

    #[test]
    fn a_chunked_answer_is_reassembled_in_order() {
        let mut stream: &[u8] =
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n";
        let response = Response::read(&mut stream, LIMIT).expect("reads");
        assert_eq!(response.body(), b"abcde");
        assert!(stream.is_empty());
    }

    #[test]
    fn an_answer_with_no_framing_ends_where_the_connection_does() {
        let mut stream: &[u8] = b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nunframed";
        let response = Response::read(&mut stream, LIMIT).expect("reads");
        assert_eq!(response.body(), b"unframed");
        assert!(
            !response.keeps_alive(),
            "close is why reading to the end worked"
        );
    }

    #[test]
    fn a_rejected_answer_is_read_as_a_status_and_nothing_else() {
        let mut stream: &[u8] =
            b"HTTP/1.1 406 Not Acceptable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        let response = Response::read(&mut stream, LIMIT).expect("reads");
        assert_eq!(response.status(), 406);
        assert!(!response.ok());
        assert!(response.body().is_empty());
        assert!(!response.keeps_alive());
    }

    #[test]
    fn a_status_line_that_is_not_three_digits_is_rejected() {
        // Java's `Integer.parseInt` read `2e2` as 200 and `20` as 20 — a truncated line
        // became a plausible answer. Neither parses here.
        for line in [
            "HTTP/1.1 2e2 OK",
            "HTTP/1.1 20 OK",
            "HTTP/1.1 2000 OK",
            "HTTP/1.1",
            "ICY 200 OK",
        ] {
            let bytes = format!("{line}\r\n\r\n").into_bytes();
            let mut stream = bytes.as_slice();
            assert_eq!(
                Response::read(&mut stream, LIMIT).unwrap_err(),
                Error::BadStatusLine(line.to_string()),
                "{line:?} should not parse"
            );
        }
    }

    #[test]
    fn a_declared_length_past_the_limit_is_refused_before_a_byte_is_read() {
        // The stream is only a head and no body: if the limit were checked after reading,
        // this would fail as a truncation instead of as the refusal it is.
        let mut stream: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n";
        assert_eq!(
            Response::read(&mut stream, LIMIT).unwrap_err(),
            Error::BodyTooLarge { limit: LIMIT }
        );
    }

    #[test]
    fn a_closed_stream_instead_of_a_status_line_is_a_truncated_head() {
        let mut stream: &[u8] = b"";
        assert_eq!(
            Response::read(&mut stream, LIMIT).unwrap_err(),
            Error::TruncatedHead
        );
    }
}
