//! The receiving endpoint: routing, status codes, and the order requests are answered in.
//!
//! This module owns everything about a receive-side conversation that does not need a
//! socket: which method and path are acceptable, when `100 Continue` goes out, when the
//! control body is read, what a malformed body costs, and whether the connection may
//! carry another request. TLS, acceptance, threads and timeouts belong to the platform
//! layer, which hands this one a `Read` and a `Write`.
//!
//! # The answer goes out before the body is read
//!
//! Java's endpoint read the body under a 700 ms budget and answered when the budget ran
//! out, so that the answer could honestly promise `Connection: keep-alive` only when the
//! body had actually arrived. Both halves of that trade were measured the hard way — see
//! `AirDropServer.DISCOVER_BODY_MS` and `ASK_BODY_MS` — and the measured cost of getting
//! it wrong was 37.1 s of silence followed by a reset.
//!
//! The trick here is that the budget never had to exist. Neither answer depends on the
//! body: `/Discover` returns our name and capabilities, `/Ask` returns our name and model
//! and the policy is unconditional acceptance. So the answer is written first, while the
//! peer is still sending its body — TCP is full duplex and the Java endpoint already did
//! exactly this on its early path — and the body is then read with no deadline of its
//! own, bounded only by the platform's socket timeout. A peer that never finishes its
//! body costs the answer nothing, because the answer has already left.
//!
//! What this removes is not a setting but a whole function: two budgets, an `answered`
//! flag, five traced exits, and the rule that a connection answered early must close.
//! What it keeps is the one property that mattered, which is that the peer reads a `200`
//! within a millisecond of framing its request no matter what its body does.
//!
//! The consequence to be honest about: a body abandoned mid-flight leaves unread bytes,
//! so the connection cannot carry another request, and [`drain`] swallows what is left
//! before closing so that the close is a FIN rather than a reset.

use std::io::{self, Read, Write};

use opairdrop_http::{plist_response, Request, Response, CONTINUE};

use crate::payload::{Ask, AskAnswer, DiscoverAnswer, Sender};
use crate::{is_archive_type, Error as ProtocolError, PATH_ASK, PATH_DISCOVER, PATH_UPLOAD};

/// Largest control body accepted: room for a plist carrying a file icon, which is the
/// largest thing `/Discover` or `/Ask` ever carries. Java used the same 4 MiB.
pub const DEFAULT_CONTROL_LIMIT: usize = 4 * 1024 * 1024;

/// How much of an abandoned body [`drain`] will swallow before giving up.
///
/// Bounded by the control limit because the only bodies that get here are control bodies;
/// the point is to reach the peer's own end-of-body so the close is orderly, not to read
/// something we have already decided to discard.
pub const DEFAULT_DRAIN_LIMIT: u64 = DEFAULT_CONTROL_LIMIT as u64;

/// The decisions the endpoint does not make.
///
/// Answers, and what happens to an upload body, are the receiver's; everything about
/// method, path, framing and status is not. That split is what lets the whole conversation
/// be tested without a socket, and it is why the trait has no method that returns a status
/// code: a receiver answers, it does not get to invent a status.
pub trait Receiver {
    /// The answer to `/Discover`. Never depends on the request body — our capabilities are
    /// what this device can actually do, and no claim from a peer changes that.
    fn discover(&mut self) -> DiscoverAnswer;

    /// Whether the `/Ask` answer can be produced before the offer has been read.
    ///
    /// `true` lets the endpoint answer first and read the offer afterwards, which is what
    /// makes a stalled body harmless. `false` is for a receiver whose answer depends on
    /// the offer — a prompt the user has to answer, a filter, a disk that is full — and
    /// costs exactly what Java's budgeted read cost: the peer waits for the body.
    fn answers_ask_early(&self) -> bool {
        true
    }

    /// Whether this transfer should be taken at all.
    ///
    /// Consulted after the offer has been read and before [`Receiver::ask`] is called, and
    /// only when [`Receiver::answers_ask_early`] is `false`: a receiver that answers before
    /// reading the offer has not seen what it would be refusing.
    ///
    /// `false` answers `401` and ends the connection. The peer is told in the one place it
    /// is still listening, so a sender can render "declined" rather than "the connection
    /// broke" — which is the difference between a person saying no and a bug. Answering
    /// `200` and then dropping the upload would leave the other phone waiting on a prompt
    /// that was already refused here.
    ///
    /// `401 Unauthorized` is not the spec's example — ASK-2 says "SHOULD respond with a
    /// non-200 status such as `403` and close the connection, so the sender doesn't wait
    /// out its own timeout" — it is what an iPhone actually sends when its owner taps
    /// decline. Both earlier codes were measured against a real one and both failed:
    ///
    /// * `503` reads as *temporarily unavailable*: the phone kept its prompt on
    ///   "waiting" until its own ASK-2a timeout, longer than 15 s.
    /// * `403` reads as *this exchange failed*: the phone reopened a TLS connection
    ///   0.4 s later and asked again, so the person answered twice.
    ///
    /// A sender that treats every non-`200` as a refusal (ASK-1) is unaffected either
    /// way; the difference only shows up on peers that read the code.
    fn accept(&mut self, _offer: &Ask) -> bool {
        true
    }

    /// The answer to `/Ask`, called exactly once per accepted request.
    ///
    /// `offer` is `Some` only when the body had already been read, which happens when
    /// [`Receiver::answers_ask_early`] is `false`. A receiver that answers early gets
    /// `None` and learns the offer through [`Receiver::saw_ask`] instead.
    fn ask(&mut self, offer: Option<&Ask>) -> AskAnswer;

    /// The parsed offer, called exactly once per `/Ask` whose body arrived and parsed.
    ///
    /// Separated from [`Receiver::ask`] because the two happen in a fixed order that
    /// depends on the answer policy, whereas a receiver that wants the offer wants it in
    /// both policies. Called after the answer has been written when the answer went early,
    /// so nothing here can delay the peer's `200`.
    fn saw_ask(&mut self, offer: &Ask);

    /// The raw `/Ask` body, handed over before it is parsed.
    ///
    /// Exists because "the offer parsed as nothing" and "the sender offered nothing" look
    /// identical from every other angle: both produce an empty file list and a prompt with
    /// no content, and the peer's bytes are the only thing that can tell them apart. After
    /// a parse that produced no files, the body is the whole evidence trail — and it is
    /// gone the moment the connection ends, because it arrived inside a TLS session.
    ///
    /// Called on the serving thread, before [`Receiver::saw_ask`], for every `/Ask` whose
    /// body arrived. A receiver that keeps it must bound what it keeps: the body is a
    /// peer's file names, and a share sheet can name a hundred of them.
    fn saw_ask_body(&mut self, _body: &[u8]) {}

    /// The name a peer gave for itself in its `/Discover` body.
    ///
    /// Not a courtesy. It is the only place an Apple device states a name a person would
    /// recognise: the `_airdrop._tcp` instance name is a rotated hash and the hostname a
    /// fresh UUID, so a device list titled from mDNS can only ever show hex. The body of
    /// `/Discover` carries `SenderComputerName`, and a phone with its share sheet open
    /// posts one every few seconds — which makes this the one name source that needs no
    /// connection of our own, on a link where our own connections are what keep failing.
    fn saw_sender(&mut self, _sender: &Sender) {}

    /// Consumes an `/Upload` body — the de-chunked archive, still framed by gzip or DVZip
    /// blocks, because sorting that out belongs to the archive reader rather than here.
    ///
    /// Called only after `100 Continue` has been written when the peer asked for it.
    ///
    /// The error type is [`io::Error`] on purpose: this is a byte stream, and everything
    /// that can go wrong reading one — a truncated archive, a bad magic, a full disk — is
    /// an I/O failure with an `InvalidData` or `Other` kind. A sender that broke the
    /// archive gets a `500`, which is the honest verdict: we accepted the transfer and
    /// then failed to store it, and no status code can un-send the `200` the answer to
    /// `/Ask` already promised.
    fn upload(
        &mut self,
        body: &mut dyn Read,
        upload: &Upload<'_>,
    ) -> io::Result<()>;
}

/// What the head of an `/Upload` says before its body is touched.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Upload<'a> {
    /// The `Content-Type` the peer declared, verbatim.
    pub content_type: &'a str,
    /// The `TransferID` header, when the peer sent one.
    pub transfer_id: Option<&'a str>,
    /// Whether that header equals the `TransferID` the preceding `/Ask` promised, or
    /// `None` when there is nothing to compare — no header, or no offer on this
    /// connection.
    ///
    /// A mismatch is reported rather than enforced. The promise is the sender's, and the
    /// bytes behind it are either a cpio archive or they are not; refusing a transfer that
    /// would have unpacked because an identifier drifted costs a file to punish a
    /// bookkeeping error.
    pub matches_offer: Option<bool>,
}

/// A receiver that accepts everything and stores nothing.
///
/// The reference behaviour of the Java endpoint — "unconditional acceptance, and the trace
/// says so out loud because it is a policy rather than a mechanism" — with the archive
/// discarded. Useful as the real endpoint for a device that only wants the prompt to
/// appear, and as the base for a receiver that does store.
#[derive(Clone, Debug)]
pub struct AcceptAll {
    /// `ReceiverComputerName` in both answers.
    pub computer_name: String,
    /// `ReceiverModelName` in the `/Ask` answer.
    pub model_name: String,
}

impl AcceptAll {
    pub fn new(computer_name: impl Into<String>, model_name: impl Into<String>) -> AcceptAll {
        AcceptAll {
            computer_name: computer_name.into(),
            model_name: model_name.into(),
        }
    }
}

impl Receiver for AcceptAll {
    fn discover(&mut self) -> DiscoverAnswer {
        DiscoverAnswer::new(self.computer_name.clone())
    }

    fn ask(&mut self, _offer: Option<&Ask>) -> AskAnswer {
        AskAnswer::new(self.computer_name.clone(), self.model_name.clone())
    }

    fn saw_ask(&mut self, _offer: &Ask) {}

    fn upload(&mut self, body: &mut dyn Read, _upload: &Upload<'_>) -> io::Result<()> {
        io::copy(body, &mut io::sink()).map(|_| ())
    }
}

/// A receiving endpoint over a pair of streams.
///
/// One instance serves one connection at a time, which is the right granularity: the only
/// state a conversation carries is the offer whose `TransferID` the upload has to repeat.
pub struct Server<R> {
    receiver: R,
    control_limit: usize,
    drain_limit: u64,
}

impl<R: Receiver> Server<R> {
    pub fn new(receiver: R) -> Server<R> {
        Server {
            receiver,
            control_limit: DEFAULT_CONTROL_LIMIT,
            drain_limit: DEFAULT_DRAIN_LIMIT,
        }
    }

    pub fn with_limits(mut self, control_limit: usize, drain_limit: u64) -> Server<R> {
        self.control_limit = control_limit;
        self.drain_limit = drain_limit;
        self
    }

    pub fn receiver(&self) -> &R {
        &self.receiver
    }

    pub fn receiver_mut(&mut self) -> &mut R {
        &mut self.receiver
    }

    /// Runs one connection to its end.
    ///
    /// `Ok(())` covers every ordinary end: the peer closed, sent a partial head and left,
    /// or was told to close by a response. `Err` means something that a caller may want to
    /// see in a log — the transport failed, a head could not be framed, or a body was not
    /// the plist it claimed to be (which is answered `400` before the error is returned).
    /// None of these are exceptional enough to be worth a stack trace: a phone hanging up
    /// between requests is the normal way this function ends.
    pub fn serve<In: Read, Out: Write>(
        &mut self,
        input: &mut In,
        output: &mut Out,
    ) -> Result<(), Error> {
        // The offer carried over from `/Ask`, for the `TransferID` promise the upload has
        // to keep. One slot, because one conversation carries one offer before one upload.
        let mut offer: Option<Ask> = None;
        let mut accepted = false;
        loop {
            let request = match Request::read(input) {
                Ok(Some(request)) => request,
                // Clean end of stream between requests.
                Ok(None) => return Ok(()),
                // A head that stopped mid-line: the peer gave up, or the transfer died. Not
                // an error, and not answerable either — there is no request to answer.
                Err(opairdrop_http::Error::TruncatedHead) => return Ok(()),
                // Nothing can be written in answer to a head we could not frame, because we
                // do not know what it was a request for.
                Err(error) => return Err(Error::Http(error)),
            };
            // 评审 P1-5：重复/畸形的 Content-Length 意味着分帧不可信。继续按空 body
            // 读会把 body 留在流里、被下一个请求头吃掉（请求走私/失同步）。
            // 400 + close：宁可少收，不误收。
            if request.method() == "POST" && request.content_length_conflicting() {
                send(output, &Response::new(400).with_keep_alive(false))?;
                return Ok(());
            }
            let keep = if request.method() != "POST" {
                // `405` and close: the body of a request we refuse is still in the stream,
                // and framing it as the next head is how a refusal becomes a parse error.
                // The head says `close` because that is what happens next — a refusal that
                // declares `keep-alive` invites the peer to wait for an answer on a
                // connection this loop is about to drop.
                send(output, &Response::new(405).with_keep_alive(false))?;
                false
            } else {
                match request.path() {
                    PATH_DISCOVER => self.discover(&request, input, output)?,
                    PATH_ASK => self.ask(&request, input, output, &mut offer, &mut accepted)?,
                    PATH_UPLOAD => self.upload(&request, input, output, offer.as_ref(), accepted)?,
                    _ => {
                        send(output, &Response::new(404).with_keep_alive(false))?;
                        false
                    }
                }
            };

            if !keep {
                return Ok(());
            }
        }
    }

    /// Answers `/Discover` and files the name the peer gave for itself.
    fn discover<In: Read, Out: Write>(
        &mut self,
        request: &Request,
        input: &mut In,
        output: &mut Out,
    ) -> Result<bool, Error> {
        let answer = self.receiver.discover();
        let keep = request.keep_alive();
        if request.expects_continue() {
            send_continue(output)?;
        }
        // Before the body, for the reason in the module docs.
        send(output, &plist_response(answer.encode(), keep))?;
        let (body, complete) = self.read_control(request, input);
        if complete {
            // A half-read plist is not a plist. Parsing a truncated dictionary would file
            // whatever survived under the peer's address, where it would outlive the packet
            // that carried it.
            if let Ok(sender) = Sender::parse(&body) {
                self.receiver.saw_sender(&sender);
            }
        } else {
            drain(input, self.drain_limit);
        }
        Ok(keep && complete)
    }

    /// Answers `/Ask`, in whichever order its receiver allows.
    fn ask<In: Read, Out: Write>(
        &mut self,
        request: &Request,
        input: &mut In,
        output: &mut Out,
        offer: &mut Option<Ask>,
        accepted: &mut bool,
    ) -> Result<bool, Error> {
        *offer = None;
        let keep = request.keep_alive();

        if self.receiver.answers_ask_early() {
            if request.expects_continue() {
                send_continue(output)?;
            }
            let answer = self.receiver.ask(None);
            // 早答式接收器按契约无条件接受（它不提示人），接受即此刻成立。
            *accepted = true;
            send(output, &plist_response(answer.encode(), keep))?;
            let (body, complete) = self.read_control(request, input);
            if !complete {
                drain(input, self.drain_limit);
                return Ok(false);
            }
            // The body is worth parsing even though the answer is already written: it is
            // the offer, which is the only thing on this endpoint that names the sender
            // and says what is about to arrive. A body that does not parse costs nothing
            // now, because the peer has its `200`.
            self.receiver.saw_ask_body(&body);
            let mut nothing_to_upload = false;
            if let Ok(parsed) = Ask::parse(&body) {
                self.receiver.saw_ask(&parsed);
                nothing_to_upload = parsed.files.is_empty();
                *offer = Some(parsed);
            }
            return Ok(keep && !nothing_to_upload);
        }

        if request.expects_continue() {
            send_continue(output)?;
        }
        let (body, complete) = self.read_control(request, input);
        if !complete {
            drain(input, self.drain_limit);
            return Ok(false);
        }
        // Before the parse, not after: a body that fails to parse is exactly the case whose
        // bytes nobody can reconstruct later.
        self.receiver.saw_ask_body(&body);
        let parsed = match Ask::parse(&body) {
            Ok(parsed) => parsed,
            Err(error) => {
                // A `400` here is our own parser refusing a body the peer considers valid,
                // and the peer is still waiting for an answer, so it can still be told.
                // The error below ends the connection, so the head says `close`.
                send(output, &Response::new(400).with_keep_alive(false))?;
                return Err(Error::Protocol(error));
            }
        };
        self.receiver.saw_ask(&parsed);
        // The offer is read; this is the only point where a person's answer is both
        // possible and useful. `ask` is skipped rather than called and ignored, so a
        // receiver that prompts cannot be made to prompt twice.
        if !self.receiver.accept(&parsed) {
            // `401`, because that is what an iPhone sends when a person declines.
            //
            // Not a guess and not from the spec: 2026-11-18, an iPhone was asked for a
            // file from this phone and its owner tapped decline, and this client recorded
            // the answer it gave —
            //
            // ```text
            // /Ask answered 401 [Connection: keep-alive, Content-Length: 0]: (no body)
            // ```
            //
            // That is the whole decline: status, two headers, no body. Apple's receiver
            // says "not this time" with `401 Unauthorized`, which reads like an auth
            // failure and is not one — it is the person's answer, expressed in the one
            // vocabulary the protocol has for answers.
            //
            // What the two earlier codes cost, both measured on the same phone:
            //
            // * `503 Service Unavailable` — chosen as "what OpenDrop answers". It carries
            //   "temporarily, try again later", and the sender did exactly that: its share
            //   sheet stayed on *waiting* until its own ASK-2a timeout expired (> 15 s)
            //   instead of showing the refusal that had already happened.
            // * `403 Forbidden` — the spec's example for a decline (ASK-2), and still
            //   wrong for an Apple sender: the phone treated it as a failed exchange and
            //   **retried**, opening a fresh TLS connection 0.4 s later and sending the
            //   same offer again, so the person was asked twice and the first phone kept
            //   spinning. `401` is the difference between "your request is not allowed"
            //   and "the person said no".
            //
            // `close` in the head, although the iPhone's own decline says `keep-alive`:
            // this loop is about to end, and a head that declares the opposite of what
            // happens next invites the sender to wait on a socket that is already gone.
            // Nothing follows a decline, so closing is the honest framing of a finished
            // conversation.
            send(output, &Response::new(401).with_keep_alive(false))?;
            // 回完不能就这么 return。对端可能还在发它的请求体，这时关连接是 RST，而不是
            // FIN —— 而 RST 会把对端还没读走的字节一起丢掉，包括刚写出去的这个 401。
            // 屏幕上的表现就是「一直等待」，然后自己超时变失败，看起来像我们从没回答过。
            //
            // 人手动点拒绝时这条不太显眼（他本来就盯着屏幕，而且请求体多半早就发完了）；
            // 超时那条一定会撞上：那 30 秒里对端可能还在重发同一个 /Ask。drain 把剩下的
            // body 读完，close 就成了普通结束。
            drain(input, self.drain_limit);
            return Ok(false);
        }
        // 走到这里 = 人说了收。评审 P0-1 的闸门状态：本条连接上的 /Upload 只在这之后放行。
        *accepted = true;
        let answer = self.receiver.ask(Some(&parsed));
        // An offer that names no files is over the moment it is accepted. `Files` empty
        // means no `/Upload` follows, so there is no next request on this connection — only
        // a read that will never return. Waiting for it anyway is what this loop used to
        // do, and the wait is the caller's read timeout: a minute on the phone. Then a
        // transfer that had already succeeded was reported as `Read timed out`, and the
        // card that should have said "tap to open the link" said "接收失败".
        //
        // Measured, 2026-11-18 23:34, iPhone → this phone, one link shared:
        //
        // ```text
        // offer from … 0 0 1  https://apps.apple.com/app/id6459478672
        // live card: 正在接收 · 准备中 · -1%
        // receive from … failed: Read timed out            (exactly 60 s later)
        // ```
        //
        // The peer had not hung up because it had nothing left to say and no reason to be
        // the one to close. Nothing is lost by closing first: the offer — which for a links
        // share *is* the whole transfer — was parsed above.
        let nothing_to_upload = parsed.files.is_empty();
        *offer = Some(parsed);
        send(output, &plist_response(answer.encode(), keep))?;
        Ok(keep && !nothing_to_upload)
    }

    /// Accepts `/Upload`, or refuses it before a byte of archive is read.
    ///
    /// 评审 P0-1：`/Upload` 必须经过这条连接上 `/Ask` 的用户接受。TLS 不认证客户端
    /// （`Listener` 是 `setNeedClientAuth(false)`），「问人」是唯一的通行控制；旧代码
    /// 对没有 `/Ask` 的连接照单全收，等于同链路任何人都能静默写收件箱。
    fn upload<In: Read, Out: Write>(
        &mut self,
        request: &Request,
        input: &mut In,
        output: &mut Out,
        offer: Option<&Ask>,
        accepted: bool,
    ) -> Result<bool, Error> {
        // 没有 offer、或者这条连接上没人说过"收"：401 拒绝，一个字节的 body 都不读。
        // `close`，因为 body 还在流里，这条连接到此为止。
        if !accepted {
            send(output, &Response::new(401).with_keep_alive(false))?;
            return Ok(false);
        }
        let content_type = request.header("content-type").unwrap_or("");
        if !is_archive_type(content_type) {
            // `406`, before `100 Continue`: saying "carry on" and then refusing the body
            // makes the peer send an archive nobody will read. `close`, because the body is
            // still in the stream and this loop returns.
            send(output, &Response::new(406).with_keep_alive(false))?;
            return Ok(false);
        }
        if request.expects_continue() {
            send_continue(output)?;
        }

        let transfer_id = request.header("transferid").filter(|id| !id.is_empty());
        let matches_offer = match (offer.and_then(|ask| ask.transfer_id.as_deref()), transfer_id) {
            (Some(promised), Some(sent)) => Some(promised == sent),
            _ => None,
        };
        // 账对不上就别传：TransferID 是 offer 对这次传输的点名，对不上说明这条
        // /Upload 不是 offer 描述的那一笔。旧代码只统计不强制（评审 P0-1 建议 2）。
        if matches_offer == Some(false) {
            send(output, &Response::new(409).with_keep_alive(false))?;
            return Ok(false);
        }
        let upload = Upload {
            content_type,
            transfer_id,
            matches_offer,
        };

        // Both framings are accepted. A sender is required to chunk this body, but a
        // receiver that *insists* on chunking turns a working `Content-Length` sender into
        // a `400`, and there is nothing in the archive that needs to know which framing
        // carried it.
        let mut body = request.body(input);
        let outcome = self.receiver.upload(&mut body, &upload);
        match outcome {
            Ok(()) => {
                send(output, &Response::new(200))?;
                // Always a close: the archive is the last thing a sender has to say, and a
                // sender that wants to send another opens another connection.
                Ok(false)
            }
            Err(error) => {
                send(output, &Response::new(500))?;
                Err(Error::Io(error))
            }
        }
    }

    /// Reads a control body, reporting whether it arrived to its last byte.
    ///
    /// `complete == false` covers both a body that stalled and one that was refused for
    /// being too large; either way the stream is no longer positioned at a request
    /// boundary, so the caller's only options are drain-then-close.
    fn read_control<In: Read>(&self, request: &Request, input: &mut In) -> (Vec<u8>, bool) {
        match request.read_body(input, self.control_limit) {
            Ok(body) => (body, true),
            Err(_) => (Vec::new(), false),
        }
    }
}

/// Everything that can stop a conversation.
#[derive(Debug)]
pub enum Error {
    /// The transport failed: the peer reset, or a write could not complete.
    Io(io::Error),
    /// A request head could not be framed at all.
    Http(opairdrop_http::Error),
    /// A control body parsed as something other than the dictionary the protocol requires.
    Protocol(ProtocolError),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Io(error) => write!(f, "transport: {error}"),
            Error::Http(error) => write!(f, "request: {error}"),
            Error::Protocol(error) => write!(f, "protocol: {error}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Io(error) => Some(error),
            Error::Http(error) => Some(error),
            Error::Protocol(error) => Some(error),
        }
    }
}

impl From<io::Error> for Error {
    fn from(error: io::Error) -> Error {
        Error::Io(error)
    }
}

fn send<Out: Write>(output: &mut Out, response: &Response) -> Result<(), Error> {
    output.write_all(&response.to_bytes())?;
    output.flush()?;
    Ok(())
}

fn send_continue<Out: Write>(output: &mut Out) -> Result<(), Error> {
    // Flushed immediately, or the peer waits out its own continue timeout for a line that
    // is already in this process's buffer.
    output.write_all(CONTINUE)?;
    output.flush()?;
    Ok(())
}

/// Swallows what is left of an abandoned body so that closing is a FIN, not a reset.
///
/// A reset discards data the peer has not read yet — including the answer this endpoint
/// just wrote — and a sender renders that as a declined transfer. Reading to the peer's
/// own end of body costs one buffer and turns the close into the ordinary end it was
/// always meant to be. Every failure ends the drain: there is nothing left to protect.
fn drain<In: Read>(input: &mut In, limit: u64) {
    let mut scratch = [0u8; 4096];
    let mut left = limit;
    while left > 0 {
        let want = scratch.len().min(left as usize);
        match input.read(&mut scratch[..want]) {
            Ok(0) => return,
            Ok(read) => left -= read as u64,
            Err(_) => return,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::payload::{discover_request, Item};
    use crate::TransferId;
    use opairdrop_bplist::Value;

    /// One upload as the receiver saw it.
    #[derive(Debug, PartialEq, Eq)]
    struct Seen {
        content_type: String,
        transfer_id: Option<String>,
        matches_offer: Option<bool>,
        bytes: Vec<u8>,
    }

    /// A receiver that records what it was told and nothing else.
    #[derive(Default)]
    struct Recorder {
        /// Whether the answer to `/Ask` needs the offer first.
        late_answer: bool,
        senders: Vec<String>,
        offers: Vec<Ask>,
        uploads: Vec<Seen>,
        /// When set, an upload fails as if the archive were truncated.
        refuse_upload: bool,
        /// When set, the offer is declined before `/Ask` is answered.
        declines: bool,
        /// How many times the answer to `/Ask` was asked for.
        asks: usize,
    }

    impl Receiver for Recorder {
        fn discover(&mut self) -> DiscoverAnswer {
            DiscoverAnswer::new("Air")
        }

        fn answers_ask_early(&self) -> bool {
            !self.late_answer
        }

        fn accept(&mut self, _offer: &Ask) -> bool {
            !self.declines
        }

        fn ask(&mut self, offer: Option<&Ask>) -> AskAnswer {
            self.asks += 1;
            // The late answer says how much was offered, which is the only way a test can
            // see that this ran after the body rather than before it.
            let model = match offer {
                Some(offer) => format!("{} items", offer.files.len()),
                None => "early".to_string(),
            };
            AskAnswer::new("Air", model)
        }

        fn saw_sender(&mut self, sender: &Sender) {
            self.senders.push(sender.computer_name.clone());
        }

        fn saw_ask(&mut self, offer: &Ask) {
            self.offers.push(offer.clone());
        }

        fn upload(&mut self, body: &mut dyn Read, upload: &Upload<'_>) -> io::Result<()> {
            let mut bytes = Vec::new();
            body.read_to_end(&mut bytes)?;
            self.uploads.push(Seen {
                content_type: upload.content_type.to_string(),
                transfer_id: upload.transfer_id.map(str::to_string),
                matches_offer: upload.matches_offer,
                bytes,
            });
            if self.refuse_upload {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated cpio"));
            }
            Ok(())
        }
    }

    #[test]
    fn a_declined_offer_is_answered_401_close_and_never_answered_200() {
        let files = [Item::new("a.pdf").with_size(7)];
        let body = crate::ask_request(&Sender::new("Mac", "MacBookPro18,3", "id"), &id(), &files, &[]);
        let (receiver, output) = run(
            Recorder {
                late_answer: true,
                declines: true,
                ..Recorder::default()
            },
            &post(PATH_ASK, "", &body),
        );

        assert_eq!(statuses(&output), [401]);
        // The head has to agree with what happens next: this refusal is the last thing on
        // the connection, so it may not declare `keep-alive`. A sender that believes the
        // header waits for a second answer on a socket that is already gone.
        let head = String::from_utf8_lossy(&output);
        assert!(
            head.contains("Connection: close"),
            "a refusing answer closes: {head}"
        );
        assert!(!head.contains("keep-alive"), "and says so: {head}");
        // The offer was still read: refusing is something done to a known transfer, and a
        // receiver that refuses without looking cannot say what it refused.
        assert_eq!(receiver.offers.len(), 1);
        // And the prompt was never raised: `ask` is what a receiver uses to ask a person.
        assert_eq!(receiver.asks, 0, "a declined offer must not raise the prompt");
    }

    #[test]
    fn an_accepted_offer_is_answered_200_and_the_prompt_runs_once() {
        let files = [Item::new("a.pdf").with_size(7)];
        let body = crate::ask_request(&Sender::new("Mac", "MacBookPro18,3", "id"), &id(), &files, &[]);
        let (receiver, output) = run(
            Recorder {
                late_answer: true,
                ..Recorder::default()
            },
            &post(PATH_ASK, "", &body),
        );

        assert_eq!(statuses(&output), [200]);
        assert_eq!(receiver.asks, 1);
    }

    fn head(path: &str, extra: &str, length: usize) -> String {
        format!("POST {path} HTTP/1.1\r\nHost: air\r\n{extra}Content-Length: {length}\r\n\r\n")
    }

    /// One request, head and body, as the bytes a peer would send.
    fn post(path: &str, extra: &str, body: &[u8]) -> Vec<u8> {
        let mut raw = head(path, extra, body.len()).into_bytes();
        raw.extend_from_slice(body);
        raw
    }

    /// The status code of every response in `output`, in the order they were written.
    fn statuses(output: &[u8]) -> Vec<u16> {
        String::from_utf8_lossy(output)
            .split("HTTP/1.1 ")
            .skip(1)
            .filter_map(|rest| rest[..3].parse().ok())
            .collect()
    }

    fn body_of(output: &[u8]) -> &[u8] {
        let split = output
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .expect("a head terminator");
        &output[split + 4..]
    }

    fn run(recorder: Recorder, raw: &[u8]) -> (Recorder, Vec<u8>) {
        let mut server = Server::new(recorder);
        let mut input = raw;
        let mut output = Vec::new();
        server.serve(&mut input, &mut output).expect("serves");
        (server.receiver, output)
    }

    #[test]
    fn discover_is_answered_before_its_body_has_arrived() {
        // A head that promises 400 bytes of body, and not one of them sent. This is the
        // shape that cost Java 37.1 s of silence on /Ask and cost the whole exchange on a
        // /Discover it waited on.
        let raw = head(PATH_DISCOVER, "", 400);
        let (receiver, output) = run(Recorder::default(), raw.as_bytes());

        assert_eq!(statuses(&output), [200], "the answer went out anyway");
        let answer = DiscoverAnswer::parse(body_of(&output)).expect("a plist answer");
        assert_eq!(answer.airdropable, Some(true));
        assert_eq!(answer.computer_name, "Air");
        // Nothing to name: the body never arrived, and a truncated plist is not parsed.
        assert!(receiver.senders.is_empty());
    }

    #[test]
    fn discover_files_the_name_from_a_body_that_did_arrive() {
        let body = discover_request(&Sender::new("iPhone von Ada", "iPhone", "id"));
        let (receiver, output) = run(Recorder::default(), &post(PATH_DISCOVER, "", &body));
        assert_eq!(statuses(&output), [200]);
        assert_eq!(receiver.senders, ["iPhone von Ada"]);
    }

    #[test]
    fn a_discover_body_that_is_not_a_plist_costs_nothing() {
        // The answer is already on the wire, so the only casualty is the name.
        let (receiver, output) = run(Recorder::default(), &post(PATH_DISCOVER, "", b"not a plist"));
        assert_eq!(statuses(&output), [200]);
        assert!(receiver.senders.is_empty());
    }

    #[test]
    fn ask_is_answered_early_and_the_offer_is_still_collected() {
        let files = [Item::new("a.pdf").with_size(7), Item::new("b.pdf")];
        let body = crate::ask_request(&Sender::new("Mac", "MacBookPro18,3", "id"), &id(), &files, &[]);
        let (receiver, output) = run(Recorder::default(), &post(PATH_ASK, "", &body));

        assert_eq!(statuses(&output), [200]);
        let answer = AskAnswer::parse(body_of(&output)).expect("a plist answer");
        assert_eq!(answer.model_name, "early", "answered before the offer was read");
        assert_eq!(receiver.offers.len(), 1);
        assert_eq!(receiver.offers[0].files.len(), 2);
        assert_eq!(receiver.offers[0].display_name(), "Mac");
    }

    #[test]
    fn a_late_ask_answer_sees_the_offer() {
        let recorder = Recorder {
            late_answer: true,
            ..Recorder::default()
        };
        let body = crate::ask_request(&Sender::new("Mac", "MacBookPro18,3", "id"), &id(), &[Item::new("a")], &[]);
        let (receiver, output) = run(recorder, &post(PATH_ASK, "", &body));

        assert_eq!(statuses(&output), [200]);
        let answer = AskAnswer::parse(body_of(&output)).expect("a plist answer");
        assert_eq!(answer.model_name, "1 items");
        assert_eq!(receiver.offers.len(), 1);
    }

    #[test]
    fn an_offer_with_no_files_ends_the_conversation_instead_of_waiting_for_an_upload() {
        // A links share: `Files` empty, `Items` full. The queue below deliberately carries a
        // second request behind the `/Ask`, because that is the only way a test can see the
        // difference — a loop that went back for one more request would read it, and this
        // receiver would end up holding an upload it should never have been offered.
        let links = ["https://example.com/a".to_string()];
        let body =
            crate::ask_request(&Sender::new("iPhone", "iPhone15,2", "id"), &id(), &[], &links);
        let mut raw = post(PATH_ASK, "", &body);
        raw.extend_from_slice(&post(
            PATH_UPLOAD,
            "Content-Type: application/x-cpio\r\n",
            b"nothing",
        ));

        let (receiver, output) = run(Recorder::default(), &raw);

        // Accepted, and the `200` is the last word on the connection: no upload was waited
        // for, so no read sat there until the caller's timeout turned success into failure.
        assert_eq!(statuses(&output), [200]);
        assert_eq!(receiver.offers.len(), 1);
        assert_eq!(receiver.offers[0].links, ["https://example.com/a"]);
        assert!(receiver.uploads.is_empty(), "nothing was read after the offer");
    }

    #[test]
    fn an_offer_with_files_still_waits_for_its_upload() {
        // The other half of the rule above, and the reason it is `files.is_empty()` rather
        // than "a links share": a normal offer must still be served to its archive.
        let files = [Item::new("a.pdf").with_size(7)];
        let body = crate::ask_request(&Sender::new("Mac", "MacBookPro18,3", "id"), &id(), &files, &[]);
        let mut raw = post(PATH_ASK, "", &body);
        raw.extend_from_slice(&post(
            PATH_UPLOAD,
            "Content-Type: application/x-cpio\r\n",
            b"07",
        ));

        let (receiver, output) = run(Recorder::default(), &raw);

        assert_eq!(statuses(&output), [200, 200]);
        assert_eq!(receiver.uploads.len(), 1);
        assert_eq!(receiver.uploads[0].bytes, b"07");
    }

    #[test]
    fn a_late_ask_whose_body_never_arrives_is_never_answered() {
        // The cost of an answer that depends on the offer: there is nothing to say until
        // the offer is there, so a peer that withholds it gets nothing.
        let recorder = Recorder {
            late_answer: true,
            ..Recorder::default()
        };
        let raw = head(PATH_ASK, "", 200);
        let mut server = Server::new(recorder);
        let mut input = raw.as_bytes();
        let mut output = Vec::new();
        server.serve(&mut input, &mut output).expect("serves");
        assert!(output.is_empty(), "{:?}", String::from_utf8_lossy(&output));
    }

    #[test]
    fn two_requests_share_one_connection() {
        let discover = discover_request(&Sender::new("Mac", "MacBookPro18,3", "id"));
        let ask = crate::ask_request(&Sender::new("Mac", "MacBookPro18,3", "id"), &id(), &[Item::new("a")], &[]);
        let mut raw = post(PATH_DISCOVER, "Connection: keep-alive\r\n", &discover);
        raw.extend_from_slice(&post(PATH_ASK, "", &ask));

        let (receiver, output) = run(Recorder::default(), &raw);
        assert_eq!(statuses(&output), [200, 200]);
        assert_eq!(receiver.senders, ["Mac"]);
        assert_eq!(receiver.offers.len(), 1);
    }

    #[test]
    fn a_connection_close_request_ends_the_conversation() {
        let body = discover_request(&Sender::new("Mac", "MacBook", "id"));
        let (_, output) = run(Recorder::default(), &post(PATH_DISCOVER, "Connection: close\r\n", &body));
        assert_eq!(statuses(&output), [200]);
        assert!(
            String::from_utf8_lossy(&output).contains("Connection: close"),
            "a peer that asked to close is told so"
        );
    }

    #[test]
    fn a_method_other_than_post_is_refused_and_closes() {
        let raw = "GET /Discover HTTP/1.1\r\nHost: air\r\n\r\n";
        let (_, output) = run(Recorder::default(), raw.as_bytes());
        assert_eq!(statuses(&output), [405]);
    }

    #[test]
    fn an_unknown_path_is_not_found() {
        let (_, output) = run(Recorder::default(), &post("/Notes", "", b""));
        assert_eq!(statuses(&output), [404]);
    }

    #[test]
    fn upload_without_an_archive_type_is_refused_before_a_byte_is_read() {
        let ask = crate::ask_request(&Sender::new("Mac", "Mac", "id"), &id(), &[Item::new("a")], &[]);
        let mut raw = post(PATH_ASK, "", &ask);
        raw.extend_from_slice(&post(
            PATH_UPLOAD,
            "Content-Type: text/plain\r\nExpect: 100-continue\r\n",
            b"not an archive",
        ));
        let (receiver, output) = run(Recorder::default(), &raw);
        assert_eq!(statuses(&output), [200, 406], "no 100-continue before a refusal");
        assert!(receiver.uploads.is_empty());
    }

    #[test]
    fn continue_precedes_the_upload_answer_and_the_bytes_reach_the_receiver() {
        let archive = b"070701 trailer";
        let ask = crate::ask_request(&Sender::new("Mac", "Mac", "id"), &id(), &[Item::new("a")], &[]);
        let mut raw = post(PATH_ASK, "", &ask);
        raw.extend_from_slice(&post(
            PATH_UPLOAD,
            "Content-Type: application/x-cpio\r\nExpect: 100-continue\r\n",
            archive,
        ));
        let (receiver, output) = run(Recorder::default(), &raw);

        assert_eq!(statuses(&output), [200, 100, 200]);
        let continue_at = output.windows(CONTINUE.len()).position(|w| w == CONTINUE)
            .expect("the continue has to be there");
        let upload_answer_at = output.windows(b"HTTP/1.1 200".len())
            .rposition(|w| w == b"HTTP/1.1 200")
            .expect("the upload answer has to be there");
        assert!(
            continue_at < upload_answer_at,
            "the continue has to precede the upload answer"
        );
        assert_eq!(receiver.uploads.len(), 1);
        assert_eq!(receiver.uploads[0].content_type, "application/x-cpio");
        assert_eq!(receiver.uploads[0].bytes, archive);
    }

    #[test]
    fn an_upload_repeats_the_transfer_id_the_offer_promised() {
        let ask = crate::ask_request(&Sender::new("Mac", "Mac", "id"), &id(), &[Item::new("a")], &[]);
        let mut raw = post(PATH_ASK, "", &ask);
        raw.extend_from_slice(&post(
            PATH_UPLOAD,
            &format!("Content-Type: application/x-dvzip\r\nTransferID: {}\r\n", id()),
            b"archive",
        ));

        let (receiver, output) = run(Recorder::default(), &raw);
        assert_eq!(statuses(&output), [200, 200]);
        assert_eq!(receiver.uploads[0].transfer_id.as_deref(), Some(id().as_str()));
        assert_eq!(
            receiver.uploads[0].matches_offer,
            Some(true),
            "the upload kept the promise the offer made"
        );
    }

    #[test]
    fn an_upload_that_breaks_the_promise_is_refused() {
        // 评审 P0-1：TransferID 对不上 offer 的点名，这笔 /Upload 不是被统计、是被拒。
        let ask = crate::ask_request(&Sender::new("Mac", "Mac", "id"), &id(), &[Item::new("a")], &[]);
        let mut raw = post(PATH_ASK, "", &ask);
        raw.extend_from_slice(&post(
            PATH_UPLOAD,
            "Content-Type: application/x-dvzip\r\nTransferID: 00000000-0000-4000-8000-000000000000\r\n",
            b"archive",
        ));

        let (receiver, output) = run(Recorder::default(), &raw);
        assert_eq!(statuses(&output), [200, 409]);
        assert!(receiver.uploads.is_empty(), "nothing is stored");
    }

    #[test]
    fn an_upload_without_an_accepted_offer_is_refused() {
        // 评审 P0-1：跳过 /Ask 直接 /Upload 的新连接，必须在读 body 之前被 401 拒绝。
        // 旧代码对这种连接照单全收（同链路任何人可静默写收件箱），这条测试把漏洞
        // 的旧预期反过来钉死。
        let raw = post(
            PATH_UPLOAD,
            "Content-Type: application/x-cpio\r\nTransferID: ABC\r\n",
            b"archive",
        );
        let (receiver, output) = run(Recorder::default(), &raw);
        assert_eq!(statuses(&output), [401]);
        assert!(receiver.uploads.is_empty(), "nothing is stored without asking");
    }

    #[test]
    fn an_archive_that_will_not_read_is_answered_500() {
        let recorder = Recorder {
            refuse_upload: true,
            ..Recorder::default()
        };
        // 先 /Ask 拿到人的接受（评审 P0-1 之后裸 /Upload 会被 401 拒掉），
        // 再送一个读不出来的 archive，看 500 的诚实判决。
        let ask = crate::ask_request(&Sender::new("Mac", "Mac", "id"), &id(), &[Item::new("a")], &[]);
        let mut raw = post(PATH_ASK, "", &ask);
        raw.extend_from_slice(&post(
            PATH_UPLOAD,
            "Content-Type: application/x-cpio\r\n",
            b"garbage",
        ));
        let mut server = Server::new(recorder);
        let mut input: &[u8] = &raw;
        let mut output = Vec::new();
        let error = server.serve(&mut input, &mut output).unwrap_err();
        assert!(matches!(error, Error::Io(_)), "{error}");
        assert_eq!(statuses(&output), [200, 500]);
    }

    #[test]
    fn an_oversized_control_body_is_abandoned_rather_than_buffered() {
        let mut server = Server::new(Recorder::default()).with_limits(16, 64);
        let body = vec![0u8; 100];
        let raw = post(PATH_DISCOVER, "", &body);
        let mut input: &[u8] = &raw;
        let mut output = Vec::new();
        server.serve(&mut input, &mut output).expect("serves");

        // Answered, then abandoned: the answer does not depend on the body, so an oversized
        // one costs a connection rather than a response.
        assert_eq!(statuses(&output), [200]);
        // `drain` swallowed the remainder up to its own limit, so whatever is left is what
        // the limit refused -- and the point is that nothing was buffered to find that out.
        assert!(input.len() <= 100 - 16 - 64, "{} bytes left", input.len());
    }

    #[test]
    fn an_unparsable_ask_body_is_answered_400_when_the_peer_is_still_waiting() {
        let recorder = Recorder {
            late_answer: true,
            ..Recorder::default()
        };
        let mut server = Server::new(recorder);
        let raw = post(PATH_ASK, "", &opairdrop_bplist::encode(&Value::String("nope".into())));
        let mut input: &[u8] = &raw;
        let mut output = Vec::new();
        let error = server.serve(&mut input, &mut output).unwrap_err();
        assert!(matches!(error, Error::Protocol(_)), "{error}");
        assert_eq!(statuses(&output), [400]);
    }

    #[test]
    fn a_head_that_stops_mid_line_is_the_end_of_the_connection_not_an_error() {
        let mut server = Server::new(Recorder::default());
        let mut input: &[u8] = b"POST /Discover HTT";
        let mut output = Vec::new();
        server.serve(&mut input, &mut output).expect("not an error");
        assert!(output.is_empty());
    }

    fn id() -> TransferId {
        TransferId::from_bytes16(&[0x2a; 16])
    }
}
