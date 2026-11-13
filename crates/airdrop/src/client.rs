//! The sending endpoint: three requests, in the order a transfer needs them.
//!
//! [`Client`] owns the socket and nothing else. It does not discover peers, open TLS, or
//! build archives; the platform layer hands it a stream that is already connected and
//! pinned to the peer's certificate, and this module turns that into a transfer. The split
//! is the same one [`crate::server`] makes: everything here can be tested against a slice
//! of bytes, and the one thing that cannot — a real connection — is proved once in
//! `tests/loopback.rs` against our own receiver.
//!
//! # The order is the protocol
//!
//! `POST /Discover` returns the receiver's name and whether it is willing. `POST /Ask`
//! returns the names to show in a progress dialog and is the point of no return: after a
//! `200`, the receiver has promised the prompt is showing. `POST /Upload` carries the
//! archive. One [`TransferId`] is minted for the transfer and appears in both `/Ask` and
//! the `TransferID` header of the `/Upload` that follows (UP-9), so it belongs to the
//! caller rather than to any one request.
//!
//! # No `Expect: 100-continue`, on purpose
//!
//! An earlier sender announced the upload body and waited for the interim answer, on the
//! reasoning that a receiver which refused the content type would say `406` without
//! reading a byte. The receiver on the other end was an iPhone, and an iPhone never sends
//! `100`: it simply waits for bytes. Every upload therefore sat in that wait for its full
//! budget and only then began sending, which for a 3 KB file read as a hung transfer
//! rather than a deadline — the visible symptom was a progress bar at zero for ninety
//! seconds on a link that was working.
//!
//! So the body goes out immediately, and [`crate::server`] still honours an `Expect` from
//! a peer that sends one. The refusal path is not lost: a receiver that will not take the
//! archive closes on the head, and the write that follows fails on a socket rather than
//! after a megabyte of it.
//!
//! # Success is not a status code
//!
//! UP-5 is explicit that an upload is answered `200` with nothing after it, and that a
//! `200` carrying a body is an error report. Treating the status alone as success is how a
//! transfer that the receiver rejected — a full disk, a name collision, a policy — gets
//! reported to the user as delivered. [`Client::upload`] checks both halves.

use std::io::{self, Read, Write};

use opairdrop_http::{ChunkedWriter, Request, Response, UPLOAD_CHUNK};

use crate::payload::{ask_request, discover_request, AskAnswer, DiscoverAnswer, Item, Sender};
use crate::{
    Error as ProtocolError, TransferId, OCTET_STREAM, PATH_ASK, PATH_DISCOVER, PATH_UPLOAD,
    UPLOAD_CONTENT_TYPE, USER_AGENT,
};

/// Largest answer body this client will read.
///
/// A `/Discover` or `/Ask` answer is a few hundred bytes. The only large body on the send
/// side is an error report, and 1 MiB is what the Java sender allowed for one — room for a
/// plist carrying a reason and a file name, and not room for a peer to fill memory.
pub const DEFAULT_RESPONSE_LIMIT: usize = 1 << 20;

/// The `TotalBytes` an offer promises: the sum of the sizes it declared (UP-10).
///
/// A member with no declared size contributes nothing rather than an estimate. An estimate
/// would make the header a guess, and a receiver comparing the total against what arrives
/// cannot tell a guessed total from a short transfer — the header would stop being evidence
/// exactly when it is needed.
///
/// Saturating rather than wrapping: the sum of declared file sizes cannot overflow in
/// practice, and a total that clamps at `u64::MAX` is a better answer than a panic inside a
/// transfer that is otherwise fine.
pub fn offered_bytes(files: &[Item]) -> u64 {
    files
        .iter()
        .filter_map(|item| item.size)
        .fold(0u64, |total, size| total.saturating_add(size))
}

/// A sender over an already-connected stream.
///
/// One instance serves one connection, which is what makes reuse work: `/Ask` and
/// `/Upload` share a TLS session when the peer keeps it alive, and a second session costs
/// another key exchange on a link whose handshake has been measured at 28 s.
pub struct Client<S> {
    stream: S,
    host: String,
    limit: usize,
    closed: bool,
}

impl<S: Read + Write> Client<S> {
    /// Wraps a connected stream.
    ///
    /// `host` is the `Host` header, and the caller supplies it because only the caller
    /// knows how the peer was reached: a peer is normally addressed by its mDNS instance
    /// name, but a caller that only has an address uses that. HTTP/1.1 requires the header
    /// and there is nothing here to derive it from.
    pub fn new(stream: S, host: impl Into<String>) -> Client<S> {
        Client {
            stream,
            host: host.into(),
            limit: DEFAULT_RESPONSE_LIMIT,
            closed: false,
        }
    }

    /// Changes how large an answer body may be.
    pub fn with_limit(mut self, limit: usize) -> Client<S> {
        self.limit = limit;
        self
    }

    /// Asks whether the peer accepts transfers at all, and learns its name.
    ///
    /// Returns [`Error::Unavailable`] when the answer says it does not. That case is worth
    /// its own error rather than a `Ok(answer)` the caller has to remember to check: a
    /// receiver that appears in a device list, completes a TLS handshake, answers
    /// `/Discover` with `200` and then never posts `/Ask` is exactly what a missing
    /// `IsAirDropable` looks like from the outside, and the difference between "this peer
    /// is not accepting" and "this peer is broken" is the whole diagnosis.
    pub fn discover(&mut self, sender: &Sender) -> Result<DiscoverAnswer, Error> {
        self.open(PATH_DISCOVER)?;
        let body = discover_request(sender);
        let request = self.head(PATH_DISCOVER, OCTET_STREAM, &[], Framing::Length(body.len()));
        self.stream
            .write_all(&request.to_bytes(&body))
            .map_err(Error::Io)?;
        let answer = self.read_answer()?;
        if !answer.ok() {
            return Err(Error::Refused {
                path: PATH_DISCOVER,
                status: answer.status(),
                body: answer.body().to_vec(),
            });
        }
        let parsed = DiscoverAnswer::parse(answer.body()).map_err(Error::Protocol)?;
        if !parsed.airdropable {
            return Err(Error::Unavailable {
                computer_name: parsed.computer_name,
            });
        }
        Ok(parsed)
    }

    /// Offers `files` and `links`, returning the names to show while the archive is sent.
    ///
    /// The offer is the only place a receiver learns what is coming, so the same `files`
    /// list the archive is built from belongs here: an offer that describes one set of
    /// names and an archive that carries another is a prompt the user cannot recognise.
    pub fn ask(
        &mut self,
        sender: &Sender,
        transfer_id: &TransferId,
        files: &[Item],
        links: &[String],
    ) -> Result<AskAnswer, Error> {
        self.open(PATH_ASK)?;
        let body = ask_request(sender, transfer_id, files, links);
        let request = self.head(PATH_ASK, OCTET_STREAM, &[], Framing::Length(body.len()));
        self.stream
            .write_all(&request.to_bytes(&body))
            .map_err(Error::Io)?;
        let answer = self.read_answer()?;
        if !answer.ok() {
            return Err(Error::Refused {
                path: PATH_ASK,
                status: answer.status(),
                body: answer.body().to_vec(),
            });
        }
        AskAnswer::parse(answer.body()).map_err(Error::Protocol)
    }

    /// Sends the archive, framed chunked, and insists the answer be empty.
    ///
    /// `archive` writes the archive into a [`Write`] this function frames as chunked
    /// transfer coding: neither side has to know the archive's length, which is the whole
    /// reason a sender buffers it through DVZip instead of declaring a size. The closure
    /// takes `&mut dyn Write` rather than a generic so that a caller with an
    /// `opairdrop_cpio::Writer` — or a `File` it is streaming from disk — can pass it
    /// without the archive having to be assembled in memory first.
    ///
    /// `total_bytes` is [`offered_bytes`] of the same list passed to [`Client::ask`].
    pub fn upload<F>(
        &mut self,
        transfer_id: &TransferId,
        total_bytes: u64,
        archive: F,
    ) -> Result<(), Error>
    where
        F: FnOnce(&mut dyn Write) -> io::Result<()>,
    {
        self.open(PATH_UPLOAD)?;
        let total = total_bytes.to_string();
        let request = self.head(
            PATH_UPLOAD,
            UPLOAD_CONTENT_TYPE,
            &[("TransferID", transfer_id.as_str()), ("TotalBytes", &total)],
            Framing::Chunked,
        );
        self.stream
            .write_all(&request.head_bytes(0))
            .map_err(Error::Io)?;

        // The body follows the head with nothing in between, for the reason in the module
        // docs. An error out of `archive` leaves the frame sequence unterminated, and the
        // connection is then unusable — which is the honest outcome, because the archive
        // that went out is not the archive that was offered.
        {
            let mut writer = ChunkedWriter::new(&mut self.stream, UPLOAD_CHUNK);
            archive(&mut writer).map_err(Error::Io)?;
            // `ChunkedWriter` is `#[must_use]` for this call: without it the receiver
            // waits for a terminal chunk that never comes and the transfer ends in a
            // timeout with no error anywhere.
            writer.finish().map_err(Error::Io)?;
        }

        let answer = self.read_answer()?;
        if !answer.ok() {
            return Err(Error::Refused {
                path: PATH_UPLOAD,
                status: answer.status(),
                body: answer.body().to_vec(),
            });
        }
        if !answer.body().is_empty() {
            // UP-5. The status said the archive was taken; the body says otherwise, and the
            // body is the side with a reason in it.
            return Err(Error::Reported {
                status: answer.status(),
                body: answer.body().to_vec(),
            });
        }
        Ok(())
    }

    /// Whether the peer has asked for the connection to be closed.
    pub fn is_closed(&self) -> bool {
        self.closed
    }

    /// The stream, for a caller that wants to close it or read its addresses after a
    /// conversation the peer ended.
    pub fn into_inner(self) -> S {
        self.stream
    }

    /// Writes one head, in the order the reference sender wrote it.
    ///
    /// One function, because the header order is what makes our bytes comparable with a
    /// capture from the implementation that works, and a second copy of the order would
    /// drift the first time one of the two gained a header. The framing header sits
    /// immediately before `Connection` for the same reason: that is where Java put it, and
    /// a capture diff should only ever show values that differ, never headers that moved.
    fn head(
        &self,
        path: &str,
        content_type: &str,
        extra: &[(&str, &str)],
        framing: Framing,
    ) -> Request {
        let mut request = Request::new("POST", path)
            .with_header("Host", &self.host)
            .with_header("User-Agent", USER_AGENT)
            .with_header("Content-Type", content_type);
        for (name, value) in extra {
            request = request.with_header(name, value);
        }
        // Exactly one of the two appears. A chunked body has no length to declare, and
        // `Content-Length: 0` beside it would be a second, contradictory answer to the same
        // question — which some receivers believe.
        let request = match framing {
            Framing::Chunked => request.with_header("Transfer-Encoding", "chunked"),
            Framing::Length(length) => {
                let declared = length.to_string();
                request.with_header("Content-Length", &declared)
            }
        };
        request.with_header("Connection", "keep-alive")
    }

    /// Refuses to write onto a connection the peer has finished with.
    ///
    /// A write to a closed socket fails anyway, but it fails as a broken pipe at the point
    /// of writing, which reads as a network fault. Naming the answer that closed the
    /// connection turns the same failure into the statement it actually is: the receiver
    /// was done, and the caller has to open a new connection before asking anything else.
    fn open(&self, path: &'static str) -> Result<(), Error> {
        if self.closed {
            return Err(Error::Closed { requested: path });
        }
        Ok(())
    }

    /// Reads one answer, skipping any interim ones in front of it.
    ///
    /// `1xx` is skipped here rather than inside the reader because only the sender knows it
    /// is waiting for a final answer; a reader that swallowed them would leave the peer's
    /// `100` indistinguishable from a missing one for anyone who wanted to look.
    fn read_answer(&mut self) -> Result<Response, Error> {
        let limit = self.limit;
        loop {
            let answer = Response::read(&mut self.stream, limit).map_err(Error::Http)?;
            if answer.is_informational() {
                continue;
            }
            if !answer.keeps_alive() {
                self.closed = true;
            }
            return Ok(answer);
        }
    }
}

/// How the body after a head announces its own end.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Framing {
    Chunked,
    Length(usize),
}

/// Anything that can stop a transfer on the sending side.
#[derive(Debug)]
pub enum Error {
    /// The transport failed: the peer reset, or a write could not complete.
    Io(io::Error),
    /// An answer could not be framed.
    Http(opairdrop_http::Error),
    /// An answer parsed as something other than the dictionary the protocol requires.
    Protocol(ProtocolError),
    /// A status this point in the conversation does not accept.
    ///
    /// The body is kept because it is where a peer puts its reason, and a refusal without
    /// one is a number.
    Refused {
        path: &'static str,
        status: u16,
        body: Vec<u8>,
    },
    /// An upload answered `200` and then said something anyway.
    ///
    /// UP-5: the empty body is the success. A body here is an error report from a receiver
    /// that had already accepted the archive, and reporting it as delivered would be a lie
    /// the user acts on.
    Reported { status: u16, body: Vec<u8> },
    /// The receiver answered `/Discover` and stated it cannot take a transfer.
    Unavailable { computer_name: String },
    /// The peer asked for the connection to be closed, and a request followed anyway.
    Closed { requested: &'static str },
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Io(error) => write!(f, "transport: {error}"),
            Error::Http(error) => write!(f, "answer: {error}"),
            Error::Protocol(error) => write!(f, "protocol: {error}"),
            Error::Refused {
                path,
                status,
                body,
            } => write!(f, "{path} answered {status}: {}", describe(body)),
            Error::Reported { status, body } => write!(
                f,
                "upload answered {status} with {} bytes, which UP-5 defines as a failure: {}",
                body.len(),
                describe(body)
            ),
            Error::Unavailable { computer_name } => write!(
                f,
                "{computer_name:?} answered that it cannot accept a transfer, \
                 so no offer was made"
            ),
            Error::Closed { requested } => write!(
                f,
                "the receiver closed the connection, so {requested} cannot follow on it"
            ),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Io(error) => Some(error),
            Error::Http(error) => Some(error),
            Error::Protocol(error) => Some(error),
            Error::Refused { .. } | Error::Reported { .. } | Error::Unavailable { .. } => None,
            Error::Closed { .. } => None,
        }
    }
}

/// A short printable rendering of an answer body, for an error message.
///
/// Error bodies in this protocol are plists carrying a reason string, so the useful part is
/// ASCII with a binary header in front of it. Non-printable bytes become dots and the whole
/// thing is capped, because a peer that answers with a megabyte of nonsense must not become
/// a megabyte of log.
fn describe(body: &[u8]) -> String {
    const CAP: usize = 200;
    let mut out = String::with_capacity(body.len().min(CAP) + 8);
    for &byte in body.iter().take(CAP) {
        out.push(if (0x20..0x7f).contains(&byte) {
            byte as char
        } else {
            '.'
        });
    }
    if body.is_empty() {
        out.push_str("(no body)");
    } else if body.len() > CAP {
        out.push_str("...");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use opairdrop_bplist::Value;

    /// A stream that answers from a canned script and keeps every byte written to it.
    ///
    /// Both halves of the conversation on one value, because a client's whole job is to
    /// interleave them on one socket. A test that handed reads and writes to separate
    /// objects could not catch a head written before the previous answer was read.
    struct Wire {
        script: Vec<u8>,
        read_at: usize,
        written: Vec<u8>,
    }

    impl Wire {
        fn answering(script: Vec<u8>) -> Wire {
            Wire {
                script,
                read_at: 0,
                written: Vec::new(),
            }
        }

        /// Everything written, as text — the archive included, which is what makes the
        /// frame assertions below possible.
        fn written(&self) -> String {
            String::from_utf8_lossy(&self.written).to_string()
        }

        /// Where the blank line that ends the head begins.
        ///
        /// Found on bytes rather than on the lossy text so that an archive containing
        /// something that is not UTF-8 cannot shift every offset that follows it.
        fn end_of_head(&self) -> usize {
            self.written
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap_or(self.written.len())
        }

        /// The head alone, without the blank line.
        fn head(&self) -> String {
            String::from_utf8_lossy(&self.written[..self.end_of_head()]).to_string()
        }

        fn head_lines(&self) -> Vec<String> {
            self.head().split("\r\n").map(str::to_string).collect()
        }

        /// Everything after the head — the framed body, when there is one.
        fn body(&self) -> Vec<u8> {
            let at = self.end_of_head().saturating_add(4).min(self.written.len());
            self.written[at..].to_vec()
        }
    }

    impl Read for Wire {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            let rest = &self.script[self.read_at..];
            if rest.is_empty() {
                return Ok(0);
            }
            let take = rest.len().min(buf.len());
            buf[..take].copy_from_slice(&rest[..take]);
            self.read_at += take;
            Ok(take)
        }
    }

    impl Write for Wire {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.written.extend_from_slice(buf);
            Ok(buf.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    fn id() -> TransferId {
        TransferId::from_bytes16(&[0x37; 16])
    }

    fn sender() -> Sender {
        Sender::new("Ada's iPhone", "iPhone", "9C:1F:2B")
    }

    fn answer(status: u16, body: Vec<u8>) -> Vec<u8> {
        Response::new(status).with_body(body).to_bytes()
    }

    fn closed_answer(status: u16, body: Vec<u8>) -> Vec<u8> {
        Response::new(status)
            .with_body(body)
            .with_keep_alive(false)
            .to_bytes()
    }

    #[test]
    fn the_discovery_head_is_written_in_the_order_the_reference_sender_wrote_it() {
        let script = answer(200, DiscoverAnswer::new("Air").encode());
        let mut client = Client::new(Wire::answering(script), "air.local");
        let found = client.discover(&sender()).expect("answers");
        assert_eq!(found.computer_name, "Air");
        assert!(found.airdropable);

        let wire = client.into_inner();
        let lines = wire.head_lines();
        assert_eq!(lines[0], "POST /Discover HTTP/1.1");
        assert_eq!(lines[1], "Host: air.local");
        assert_eq!(lines[2], "User-Agent: AirDrop/1.0");
        assert_eq!(lines[3], "Content-Type: application/octet-stream");
        assert!(lines[4].starts_with("Content-Length: "), "{:?}", lines[4]);
        assert_eq!(lines[5], "Connection: keep-alive");
        assert_eq!(lines.len(), 6, "one blank line and nothing after it");

        // The body follows the head with no framing of its own: a control body has a
        // length, and chunking a few hundred bytes would only make two implementations
        // disagree about where it ends.
        assert_eq!(wire.body(), discover_request(&sender()));
    }

    #[test]
    fn the_sender_never_announces_a_body_it_waits_to_send() {
        // The incident, as an assertion: a `100-continue` sender stalls against a receiver
        // that never answers one, and the iPhone never answers one.
        let script = answer(200, DiscoverAnswer::new("Air").encode());
        let mut client = Client::new(Wire::answering(script), "air");
        client.discover(&sender()).expect("answers");
        let wire = client.into_inner();
        assert!(!wire.written().contains("Expect"));
        assert!(!wire.written().to_lowercase().contains("100-continue"));
    }

    #[test]
    fn a_receiver_that_states_it_cannot_take_a_transfer_is_reported_with_its_name() {
        // The exact shape of the incident: a plist with no `IsAirDropable` at all. The
        // sender used to read the absent boolean as "fine", offer, and then watch the peer
        // vanish; it now stops with a name in the message.
        let body = opairdrop_bplist::encode(&Value::dict([(
            "ReceiverComputerName",
            Value::String("Air".to_string()),
        )]));
        let mut client = Client::new(Wire::answering(answer(200, body)), "air");
        let error = client.discover(&sender()).expect_err("refuses");
        match &error {
            Error::Unavailable { computer_name } => assert_eq!(computer_name, "Air"),
            other => panic!("expected an unavailable receiver, got {other:?}"),
        }
        assert!(error.to_string().contains("Air"));
    }

    #[test]
    fn the_ask_answer_names_the_receiver_for_the_prompt() {
        let script = answer(200, AskAnswer::new("Air", "MacBook Pro").encode());
        let mut client = Client::new(Wire::answering(script), "air");
        let files = [Item::new("a.jpg").with_size(10)];
        let names = client.ask(&sender(), &id(), &files, &[]).expect("offers");
        assert_eq!(names.computer_name, "Air");
        assert_eq!(names.model_name, "MacBook Pro");

        let wire = client.into_inner();
        assert_eq!(wire.head_lines()[0], "POST /Ask HTTP/1.1");
        assert_eq!(wire.body(), ask_request(&sender(), &id(), &files, &[]));
    }

    #[test]
    fn a_refused_offer_is_reported_with_its_status_and_its_reason() {
        // A `400` from a receiver that could not read the offer. The body is where the
        // reason lives, and a refusal reported as a bare number sends the reader to a
        // capture to find out what happened.
        let mut client = Client::new(Wire::answering(answer(400, b"bad offer".to_vec())), "air");
        let error = client
            .ask(&sender(), &id(), &[Item::new("a")], &[])
            .expect_err("refuses");
        match &error {
            Error::Refused { path, status, body } => {
                assert_eq!(*path, PATH_ASK);
                assert_eq!(*status, 400);
                assert_eq!(body, b"bad offer");
            }
            other => panic!("expected a refusal, got {other:?}"),
        }
        assert!(error.to_string().contains("bad offer"), "{error}");
    }

    #[test]
    fn an_upload_that_answers_two_hundred_with_a_body_is_a_failure() {
        // UP-5. The receiver accepted the archive and then said it could not store it, and
        // the only place that lives is the body of a `200`.
        let script = answer(200, b"disk full".to_vec());
        let mut client = Client::new(Wire::answering(script), "air");
        let error = client
            .upload(&id(), 0, |out| out.write_all(b""))
            .expect_err("refuses");
        match &error {
            Error::Reported { status, body } => {
                assert_eq!(*status, 200);
                assert_eq!(body, b"disk full");
            }
            other => panic!("expected a report, got {other:?}"),
        }
        assert!(error.to_string().contains("disk full"), "{error}");
    }

    #[test]
    fn an_upload_that_answers_two_hundred_with_nothing_is_the_success() {
        let mut client = Client::new(Wire::answering(answer(200, Vec::new())), "air");
        client
            .upload(&id(), 0, |out| out.write_all(b""))
            .expect("accepted");
        assert!(!client.is_closed(), "the peer kept the connection");
    }

    #[test]
    fn an_archive_with_no_members_is_one_short_frame_then_the_terminal_one() {
        // A transfer of zero members still produces an archive: a cpio trailer and nothing
        // else. Chunked framing exists for exactly this case — the body has no length to
        // declare, and a sender that fell back to `Content-Length: 0` would be telling the
        // receiver there is no upload at all.
        let mut client = Client::new(Wire::answering(answer(200, Vec::new())), "air");
        let files: [Item; 0] = [];
        client
            .upload(&id(), offered_bytes(&files), |out| {
                out.write_all(b"0707010000000000000000TRAILER")
            })
            .expect("accepted");

        let wire = client.into_inner();
        let lines = wire.head_lines();
        assert_eq!(lines[0], "POST /Upload HTTP/1.1");
        assert_eq!(lines[3], "Content-Type: application/x-dvzip");
        assert_eq!(lines[4], "TransferID: 37373737-3737-4737-B737-373737373737");
        // UP-10: the sum of the sizes offered, which for no members is zero — and zero is
        // a number the header states, not a header it omits.
        assert_eq!(lines[5], "TotalBytes: 0");
        assert_eq!(lines[6], "Transfer-Encoding: chunked");
        assert_eq!(lines[7], "Connection: keep-alive");
        assert!(
            !wire.head().contains("Content-Length"),
            "chunked framing declares its own end"
        );

        let body = wire.body();
        let text = String::from_utf8_lossy(&body).to_string();
        assert!(text.ends_with("0\r\n\r\n"), "{text:?}");
        assert_eq!(
            body,
            [
                b"1d\r\n".as_slice(),
                b"0707010000000000000000TRAILER",
                b"\r\n0\r\n\r\n",
            ]
            .concat()
            .as_slice(),
            "one frame of 29 bytes, then the terminal chunk"
        );
    }

    #[test]
    fn an_archive_larger_than_a_frame_is_cut_in_sixteen_kibibytes_and_ends_properly() {
        let payload = vec![b'k'; UPLOAD_CHUNK * 2 + 5];
        let mut client = Client::new(Wire::answering(answer(200, Vec::new())), "air");
        let files = [Item::new("video.mov").with_size(payload.len() as u64)];
        let expected = payload.clone();
        client
            .upload(&id(), offered_bytes(&files), move |out| {
                out.write_all(&expected)
            })
            .expect("accepted");

        let wire = client.into_inner();
        let body = wire.body();
        // Three frames of 16 KiB, 16 KiB and 5 bytes, then the terminal chunk — the sizes
        // in hexadecimal, because that is what the coding says.
        let mut cursor = 0;
        let mut sent = 0;
        for size in [UPLOAD_CHUNK, UPLOAD_CHUNK, 5] {
            let header = format!("{size:x}\r\n");
            assert_eq!(
                &body[cursor..cursor + header.len()],
                header.as_bytes(),
                "frame {size} declares its own size"
            );
            cursor += header.len();
            assert_eq!(&body[cursor..cursor + size], &payload[sent..sent + size]);
            cursor += size;
            sent += size;
            assert_eq!(&body[cursor..cursor + 2], b"\r\n");
            cursor += 2;
        }
        assert_eq!(sent, payload.len(), "every byte went into a frame");
        assert_eq!(&body[cursor..], b"0\r\n\r\n");
    }

    #[test]
    fn a_connection_the_receiver_closed_is_not_written_to_again() {
        // A Mac that answers `/Discover` with `Connection: close` and means it. Writing the
        // offer anyway fails as a broken pipe somewhere else entirely; naming the answer
        // that closed the connection is the difference between a diagnosis and a symptom.
        let script = closed_answer(200, DiscoverAnswer::new("Air").encode());
        let mut client = Client::new(Wire::answering(script), "air");
        client.discover(&sender()).expect("answers");
        assert!(client.is_closed());

        let error = client
            .ask(&sender(), &id(), &[Item::new("a")], &[])
            .expect_err("refuses");
        match &error {
            Error::Closed { requested } => assert_eq!(*requested, PATH_ASK),
            other => panic!("expected a closed connection, got {other:?}"),
        }
        assert!(error.to_string().contains("/Ask"), "{error}");
        assert!(
            !client.into_inner().written().contains("POST /Ask"),
            "the offer was never written"
        );
    }

    #[test]
    fn an_interim_answer_in_front_of_the_real_one_is_stepped_over() {
        // Nothing in this protocol sends a `1xx` to a sender, but a peer that volunteers one
        // must not be read as having answered: the final status is the answer.
        let mut script = Response::new(100).to_bytes();
        script.extend(answer(200, DiscoverAnswer::new("Air").encode()));
        let mut client = Client::new(Wire::answering(script), "air");
        assert_eq!(client.discover(&sender()).expect("answers").computer_name, "Air");
    }

    #[test]
    fn a_socket_that_ends_before_a_status_line_is_a_framing_error() {
        let mut client = Client::new(Wire::answering(Vec::new()), "air");
        let error = client.discover(&sender()).expect_err("refuses");
        assert!(matches!(error, Error::Http(_)), "{error:?}");
    }

    #[test]
    fn the_offered_total_is_the_sum_of_the_declared_sizes_and_nothing_else() {
        assert_eq!(offered_bytes(&[]), 0);
        assert_eq!(
            offered_bytes(&[
                Item::new("a").with_size(3_000_019),
                Item::new("b").with_size(0),
                // Unknown size: contributes nothing, rather than being guessed at.
                Item::new("c"),
            ]),
            3_000_019
        );
        // A folder's size is not declared, so it is not counted either.
        assert_eq!(offered_bytes(&[Item::new("dir").as_directory()]), 0);
    }
}
