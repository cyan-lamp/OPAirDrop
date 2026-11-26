//! The endpoint: one connection, from the first request byte to the last member on disk.
//!
//! # Why this is the export that makes the rest matter
//!
//! Everything below the socket was already here and already tested — the request framing in
//! [`opairdrop_http`], the answers in [`opairdrop_airdrop::payload`], the rules that turn an
//! untrusted archive into files in [`opairdrop_airdrop::inbox`] — but the only thing Android
//! could reach was [`crate::api::opairdrop_receive_archive`], which starts *after* the
//! request has been parsed. So the app had no way to answer a peer, and every setting that
//! describes how a peer is answered answered nothing: `device.name` was stored, validated,
//! drawn on screen, and never said to anyone. One export closes that gap.
//!
//! # Why one connection per call
//!
//! The caller owns the listening socket, because on Android that is where TLS and the
//! certificate live, and both are the caller's business rather than the protocol's. What
//! crosses is one accepted stream in each direction, and this returns when the peer closes
//! — or when the endpoint closes on purpose, which it does after an upload. A caller that
//! wants a second conversation accepts a second connection, exactly as the protocol says:
//! an archive is the last thing a sender has to say.
//!
//! # Why the answers come from the settings and not from the caller
//!
//! The name in the `/Discover` and `/Ask` answers is `device.name`; blank falls back to what the
//! operating system calls this phone (`system_name`), and a caller with nothing to say about that
//! falls back to the model, which is the chain the table promises. The model in the `/Ask` answer
//! is `device.model`, falling back in turn to what the caller passes. Passing either as another
//! argument would mean two answers to one question — the row on the settings screen, and whatever
//! the caller decided to pass — and the screen would be the one that lost. `system_name` is not
//! that argument: it is not a setting and cannot be, because it is the phone's name in the
//! system's own settings, and nothing in this library can read it.
//!
//! The model used to be only an argument, on the grounds that it is the one thing here the
//! operating system knows and a row for it would invite a person to rename their phone. It is
//! still the one thing the operating system knows; what changed is that renaming it turns out
//! to be a reasonable thing to want. `ReceiverModelName` is display-only on both sides of the
//! protocol — no parser reads it, no sender branches on it — so what a person enters here
//! travels verbatim, and blank means the caller's own value, which is what keeps an untouched
//! row invisible. What must *not* follow it is anything a peer acts on: the capability blob in
//! the `/Discover` answer is a constant, and the certificate and the mDNS record are built by
//! the caller from the same name, not from this string.
//!
//! # Why a failed upload still leaves its files
//!
//! An inbox refusal (over budget, a name that resolves outside, a truncated archive) ends
//! the connection with a `500`, and the report comes back as that failure's message rather
//! than as the list of what landed. That is not a detail worth a second out-parameter: the
//! members are on disk either way — each is published whole or not at all — and a caller
//! that wants to show them has the directory. What it cannot recover from the disk is *why*
//! the transfer stopped, which is what the message carries.

use std::ffi::c_void;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use opairdrop_airdrop::inbox::{self, Error as InboxError, Limits};
use opairdrop_airdrop::payload::{Ask, Item};
use opairdrop_airdrop::server::{Error as EndpointError, Receiver, Server, Upload};
use opairdrop_airdrop::{AskAnswer, DiscoverAnswer};

use crate::api::{
    self, status, CallbackReader, CallbackWriter, ReadCallback, Report, WriteCallback,
};
use crate::buffer::{fill, OpairdropBuffer};
use crate::settings;

/// Asks a person whether a transfer should be taken.
///
/// Called with a one-line summary (`sender<TAB>files<TAB>bytes<TAB>links<TAB>names<TAB>urls`,
/// UTF-8) after the offer has been read and before `/Ask` is answered. A positive return accepts;
/// zero refuses; a negative value is the caller's own error code and is treated as a refusal,
/// because the only safe reading of "the prompt broke" is "do not take what nobody saw".
pub type DecideCallback =
    unsafe extern "C" fn(context: *mut c_void, summary: *const u8, len: usize) -> i32;

/// A [`DecideCallback`] with the context it was built for.
pub(crate) struct Decide {
    callback: DecideCallback,
    context: *mut c_void,
}

impl Decide {
    /// A decider built from a callback and the context it was made for.
    ///
    /// For this crate's own callers — the JNI layer holds the pair already and has no reason
    /// to round-trip it through the C ABI's argument list.
    pub(crate) fn new(callback: DecideCallback, context: *mut c_void) -> Decide {
        Decide { callback, context }
    }

    /// Asks, and reads everything but a positive answer as a refusal.
    fn take(&self, offer: &Ask) -> bool {
        let summary = summary_of(offer);
        // SAFETY: the caller of `opairdrop_serve_decided` promised the callback and its
        // context for the duration of the call, and the bytes outlive the invocation.
        let answer = unsafe { (self.callback)(self.context, summary.as_ptr(), summary.len()) };
        answer > 0
    }
}

/// Told how far a receive has got, so a caller can draw something.
///
/// `done` is payload bytes — the unit an offer's sizes are in — and `total` is what that
/// offer declared. Neither is a promise: a peer may send more or less than it announced,
/// and a `total` of zero means it announced nothing, which is the caller's cue to show
/// motion rather than a fraction.
///
/// Called from inside the write loop of the inbox, so it runs on the thread serving the
/// connection, as often as chunks arrive. It must be cheap and must not block: the transfer
/// is waiting on it.
/// `done`/`total` 是字节；`members` 是已落盘成员数，`member_total` 是发送端报的
/// 成员数（0 = 没报）。实况照片的 /Ask 不给总大小，成员数是卡片上唯一的前进刻度。
pub type LandingCallback = unsafe extern "C" fn(
    context: *mut c_void,
    done: u64,
    total: u64,
    members: u32,
    member_total: u32,
);

/// A [`LandingCallback`] with the context it was built for.
#[derive(Clone, Copy)]
pub(crate) struct Watcher {
    callback: LandingCallback,
    context: *mut c_void,
}

// SAFETY: `context` is opaque here — this layer never dereferences it, only hands it back to
// `callback`, which the caller promised to keep valid for the duration of the call.
//
// The bounds are needed because the hook has to live in an `Arc` to be reachable from
// `Member::write`, which sits behind a `&mut dyn Write` the extractor owns. Threading a
// borrow down there instead would give `Inbox` a lifetime parameter and make every caller
// carry it.
unsafe impl Send for Watcher {}
unsafe impl Sync for Watcher {}

impl Watcher {
    /// A watcher built from a callback and the context it was made for, for this crate's own
    /// callers.
    pub(crate) fn new(callback: LandingCallback, context: *mut c_void) -> Watcher {
        Watcher { callback, context }
    }

    fn say(&self, done: u64, total: u64, members: u32, member_total: u32) {
        // SAFETY: as `Decide::take`: the caller promised this pair for the whole call.
        unsafe { (self.callback)(self.context, done, total, members, member_total) }
    }
}

/// The line a person gets to decide on: who, how many, how much, and what each one is
/// called.
///
/// Tab-separated rather than pretty-printed, because the caller renders it and knows the
/// language: four numbers and a name do not deserve a sentence assembled down here.
///
/// The fourth field is the link count, and it is not decoration. A share from Safari is a
/// links offer — `Files` empty, `Items` full — so a three-field summary describes it as
/// "0 files", which reads as "there is nothing to take" on the one prompt where the person
/// is deciding. A real peer asked exactly that way; the count alone could not say so.
///
/// The fifth field is the per-file list, and it exists for the same reason the fourth does:
/// a count and a total are not what a person recognizes. "2 files, 2.7 MB" could be two
/// photos or one document and one archive; the names decide whether the prompt is worth
/// reading. Without them the card falls back to saying the file details are unknown, which
/// is what it said for every invitation until this field existed.
///
/// The sixth field is the links themselves, and it is the one that makes a links offer
/// usable rather than merely visible. The fourth field can say "1 link"; only this one says
/// *which* link, and "which" is the whole point of a link — there is no file to open
/// afterwards, so the URL is the entire thing being sent. Field five's discipline applies
/// unchanged: `\x1e` between entries, the line-breaking characters replaced.
fn summary_of(offer: &Ask) -> String {
    let bytes: u64 = offer.files.iter().filter_map(|file| file.size).sum();
    format!(
        "{}\t{}\t{}\t{}\t{}\t{}",
        offer.display_name(),
        offer.files.len(),
        bytes,
        offer.links.len(),
        names_of(&offer.files),
        links_of(&offer.links)
    )
}

/// Separator between one file's name and its size, and between files. See [`names_of`].
const BETWEEN_NAME_AND_SIZE: char = '\u{1f}';

/// Separator between files. See [`names_of`].
const BETWEEN_FILES: char = '\u{1e}';

/// The fifth field: `name<US>size<RS>name<US>size…`, sizes empty when undeclared.
///
/// Two control characters rather than two more tabs, because a file name is arbitrary text
/// a person typed and may contain a tab — splitting the fifth field on tabs again would
/// turn one name into a name and a size. `\x1f` and `\x1e` are not something a share sheet
/// produces, and the only characters that could break the line are replaced, so the field
/// survives any name unchanged in meaning:
///
/// * `\x1f` and `\x1e` are the field's own structure, and a name carrying one would read as
///   two entries.
/// * A tab is the separator *between* fields, so a name carrying one would split the fifth
///   field in half and truncate every name after it.
/// * A newline would end the line the caller treats as one line.
///
/// Sizes are the sender's own declaration, the same numbers the third field sums. A folder
/// entry usually has none, and an empty size means "unknown", not zero — the caller must not
/// draw a total from a list where any entry is blank.
fn names_of(files: &[Item]) -> String {
    let mut text = String::new();
    for file in files {
        if !text.is_empty() {
            text.push(BETWEEN_FILES);
        }
        for ch in file.name.chars() {
            let ch = if ch == BETWEEN_NAME_AND_SIZE
                || ch == BETWEEN_FILES
                || ch == '\t'
                || ch == '\n'
                || ch == '\r'
            {
                ' '
            } else {
                ch
            };
            text.push(ch);
        }
        text.push(BETWEEN_NAME_AND_SIZE);
        if let Some(size) = file.size {
            text.push_str(&size.to_string());
        }
    }
    text
}

/// The sixth field: the links themselves, `\x1e` between them.
///
/// Same character discipline as [`names_of`], with one difference that is worth stating:
/// a URL has no second component, so `\x1f` is not structure here — it is just another
/// character that must not reach the caller, because the field is split on `\x1e` and a
/// stray `\x1f` would ride along inside a link.
///
/// Control characters in a URL are not a real possibility — a sender that means one
/// percent-encodes it — which is exactly why replacing them costs nothing and guessing
/// does. What arrives here is whatever the peer wrote in `Items`, and it is about to be
/// handed to an intent that opens a browser.
fn links_of(links: &[String]) -> String {
    let mut text = String::new();
    for link in links {
        if !text.is_empty() {
            text.push(BETWEEN_FILES);
        }
        for ch in link.chars() {
            let ch = if ch == BETWEEN_NAME_AND_SIZE
                || ch == BETWEEN_FILES
                || ch == '\t'
                || ch == '\n'
                || ch == '\r'
            {
                ' '
            } else {
                ch
            };
            text.push(ch);
        }
    }
    text
}

/// A receiver that answers from the settings and stores what arrives.
///
/// The trait's other three methods have defaults, and this type is the reason two of them
/// exist: `saw_ask` and `saw_sender` are for a caller that wants to *watch* a conversation
/// — a log, a notification, a progress bar. Nothing here watches yet: the offer it already
/// 把 `/Upload` 读过的字节留一份（上限 `cap`）。
///
/// <p>只有一个用途：一次接收失败之后，「对端到底发完了没有」这个问题必须能回答——落盘
/// 字节数只说明我们**写了**多少，说明不了流的末尾长什么样。把原始字节留最后一份，就能
/// 离线看出它是断在 CPIO trailer 之前、断在 trailer 中间，还是早就发完了、只是我们没认出
/// 结尾（2026-11-26：单张实况每次都停在 7–9 MiB、16–21 秒，三种可能指三个完全不同的改法）。
struct Tap<'a> {
    inner: &'a mut dyn std::io::Read,
    kept: std::rc::Rc<std::cell::RefCell<Vec<u8>>>,
    cap: usize,
}

impl std::io::Read for Tap<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        let mut kept = self.kept.borrow_mut();
        let room = self.cap.saturating_sub(kept.len());
        if room > 0 {
            kept.extend_from_slice(&buf[..n.min(room)]);
        }
        Ok(n)
    }
}

/// 一小段字节的十六进制，给诊断文件用（只进日志与文件，不上屏幕）。
fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// accepted is in the body it is about to read, and the sender's name is cosmetic.
pub(crate) struct Store {
    /// What both answers call this device.
    name: String,
    /// What the `/Ask` answer calls the hardware. Not the same string as `name`: a person
    /// may rename the phone, and the peer shows the model beside the name it was given.
    model: String,
    /// Where members are written, already created by the inbox on the first upload.
    directory: PathBuf,
    /// Who to ask before taking a transfer, or `None` to take every one.
    decide: Option<Decide>,
    /// Who to tell how far a receive has got, or `None` to stay quiet.
    watcher: Option<Watcher>,
    /// What the offer in hand says the whole transfer weighs. The hook's denominator, and
    /// the reason `accept` reads the sizes: by the time bytes are landing the offer has
    /// been consumed.
    declared: u64,
    /// 发送端报的成员数（/Ask 的 Files 数），实况没有总大小时的进度刻度。
    members_total: u32,
    /// /Ask 里每一份都带了大小吗。实况（.pvt 目录）与混合场景下不是——那时 `declared`
    /// 只是部分之和，当分母会画出「100% 还在传」，只能退回 /Upload 的 Content-Length。
    fully_sized: bool,
    /// The budget and the collision rule, both from the settings.
    limits: Limits,
    /// What the `/Discover` answer tells the peer it can store untouched.
    ///
    /// Held rather than read in `discover` because the settings are consumed before the
    /// connection is served: `discover` runs inside the endpoint, where `Values` no longer
    /// exists. The bytes are the row's value or [`opairdrop_airdrop::MEDIA_CAPABILITIES`],
    /// already decided by `settings::media_capabilities`.
    capabilities: Vec<u8>,
    /// What the connection has stored so far, in the frame a caller reads.
    landed: Report,
}

impl Store {
    /// What the connection left behind.
    pub(crate) fn landed(&self) -> &Report {
        &self.landed
    }

    /// 一次投送成功落地后，上一份 `/Ask` 诊断就没有保留了：留着只会让收件箱里
    /// 多一份对端文件名，而它本就只在「解析失败、无从解释」时才有用（P1-10）。
    fn clear_ask_diag(&self) {
        let _ = std::fs::remove_file(self.directory.join(".diag/.ask-last.bin"));
    }
}

impl Receiver for Store {
    fn discover(&mut self) -> DiscoverAnswer {
        // Not `DiscoverAnswer::new`: that answers with the crate constant, and the row is
        // what makes a wrong guess undoable without a rebuild. See
        // `settings::media_capabilities`.
        DiscoverAnswer::with_capabilities(self.name.clone(), self.capabilities.clone())
    }

    /// Late exactly when there is somebody to ask: an early answer is written before the
    /// offer is read, and a prompt that has not seen the offer cannot describe it.
    fn answers_ask_early(&self) -> bool {
        self.decide.is_none()
    }

    fn accept(&mut self, offer: &Ask) -> bool {
        // Kept for the progress hook. A sender that declares no sizes leaves this at zero,
        // and a zero total is a caller's cue to show motion rather than a fraction.
        self.declared = offer.files.iter().filter_map(|file| file.size).sum();
        self.members_total = offer.files.len() as u32;
        self.fully_sized = !offer.files.is_empty() && offer.files.iter().all(|file| file.size.is_some());
        match &self.decide {
            None => true,
            Some(decide) => decide.take(offer),
        }
    }

    fn ask(&mut self, _offer: Option<&Ask>) -> AskAnswer {
        AskAnswer::new(self.name.clone(), self.model.clone())
    }

    fn saw_ask(&mut self, _offer: &Ask) {}

    /// Keeps the last `/Ask` body next to what it offered, so an invitation that parsed as
    /// nothing can be explained afterwards.
    ///
    /// <p>The failure this exists for is the one with no other evidence: the connection is
    /// TLS, the body lives inside it, and both "we could not read the offer" and "the sender
    /// offered nothing" end in the same empty prompt. One file, overwritten every time and
    /// capped, because what it holds is a peer's file names — and because a diagnostic that
    /// grows is a disk-full bug waiting for a busy day.
    fn saw_ask_body(&mut self, body: &[u8]) {
        const KEEP: usize = 64 * 1024;
        if body.is_empty() {
            return;
        }
        // 评审 P1-10：诊断字节不落在收件箱根上（那是给人看文件的地方），也不在
        // accept 之前假设目录已存在。独立子目录 + create_dir_all，写不了也照样收——
        // 诊断失败不是拒收的理由。成功落盘后由 upload() 删除（见下）。
        let diag = self.directory.join(".diag");
        if std::fs::create_dir_all(&diag).is_err() {
            return;
        }
        let _ = std::fs::write(diag.join(".ask-last.bin"), &body[..body.len().min(KEEP)]);
    }

    fn upload(&mut self, body: &mut dyn std::io::Read, upload: &Upload<'_>) -> io::Result<()> {
        // The body is de-chunked but still wrapped — gzip or DVZip blocks — and the inbox
        // sniffs and unwraps it. Doing this here would mean two places that know what an
        // upload looks like.
        //
        // 分母（2026-11-26 修）：`/Ask` 每一份都报了 size 时才用 `declared`；否则用
        // `/Upload` 的 Content-Length。实况（.pvt 目录报 0）与混合场景下 `declared` 只是
        // **部分之和**——5 张混合报 7.8 MB、真实 30 MB+，拿它当分母就是「进度 100% 还在
        // 传」。Content-Length 是线上字节总数，单位与落盘字节不同（压缩率 10% 上下），
        // 但方向对，且它总在。
        let total = if let Some(total) = upload.total_bytes {
            // 对端自己说的整次传输字节数，最准。
            total
        } else if self.fully_sized && self.declared > 0 {
            self.declared
        } else {
            upload.content_length.unwrap_or(self.declared)
        };
        let content_length = upload.content_length;
        let tap = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let started = std::time::Instant::now();
        let mut tapped = Tap {
            inner: body,
            kept: tap.clone(),
            cap: 48 << 20,
        };
        let outcome = match self.watcher {
            None => inbox::receive_with(&self.directory, self.limits, &mut tapped),
            Some(watcher) => {
                let members_total = self.members_total;
                let hook: inbox::Progress =
                    Arc::new(move |done, members| watcher.say(done, total, members, members_total));
                inbox::receive_watched(&self.directory, self.limits, &mut tapped, hook)
            }
        };
        // 决定性的一问：解码器提前放弃之后，流里**还有没有数据**。
        //
        // 这一个数字分得开两件完全相反的事（2026-11-26 卡在这一步）：
        //   - 还能读出几兆 → 流是完整的，是我们自己的分帧/解码错位（app 的 bug）
        //   - 读出 0      → 对端就发了这么多，剩下的从来没到过
        // 上限 1 MiB：够判断"有没有"，又不会在一条已经坏掉的连接上读八百兆。
        let leftover = if outcome.is_err() {
            let mut sink = [0u8; 8192];
            let mut total = 0usize;
            for _ in 0..128 {
                match body.read(&mut sink) {
                    Ok(0) => break,
                    Ok(n) => total += n,
                    Err(_) => break,
                }
            }
            total
        } else {
            0
        };
        // 现场的最后一笔：成败都留一份小的，失败时连原始字节一起留（见 `Tap`）。
        leave_receive_evidence(
            &self.directory,
            &tap.borrow(),
            content_length,
            started,
            &outcome,
            &upload.head,
            leftover,
            upload.total_bytes,
            total,
        );
        let received = outcome.map_err(io::Error::other)?;
        self.landed.bytes += received.bytes;
        self.landed.names.extend(received.names);
        self.clear_ask_diag();
        Ok(())
    }


}
/// 把这一次 `/Upload` 的形状写到 inbox 旁边：对端声明多少、我们读了多少、花了多久、
/// 末尾几十个字节是什么。失败时另存一份原始字节。
///
/// <p>写不了就算了——诊断失败不是接收失败的理由。
fn leave_receive_evidence(
    directory: &Path,
    kept: &[u8],
    content_length: Option<u64>,
    started: std::time::Instant,
    outcome: &Result<inbox::Received, inbox::Error>,
    head: &str,
    leftover: usize,
    total_bytes: Option<u64>,
    total_used: u64,
) {
    let Some(parent) = directory.parent() else {
        return;
    };
    let mut text = format!(
        "content_length={content_length:?}\ntotal_bytes={total_bytes:?}\ntotal_used={total_used}\nread={}\nelapsed_ms={}\nleftover_after_refusal={leftover}\nrequest_head={head}\n",
        kept.len(),
        started.elapsed().as_millis()
    );
    match outcome {
        Ok(received) => text.push_str(&format!(
            "outcome=ok names={} bytes={}\n",
            received.names.len(),
            received.bytes
        )),
        Err(error) => text.push_str(&format!("outcome=err {error}\n")),
    }
    text.push_str(&format!(
        "head={}\ntail={}\n",
        hex(&kept[..kept.len().min(64)]),
        hex(&kept[kept.len().saturating_sub(96)..])
    ));
    let _ = std::fs::write(parent.join("recv-debug.txt"), text);
    if outcome.is_err() {
        let _ = std::fs::write(parent.join("recv-failed.bin"), kept);
    }
}

/// Serves one connection, taking every transfer without asking anybody.
///
/// The reference behaviour, and the one to use when there is nobody to ask: an endpoint
/// that answers `/Ask` before reading the offer cannot stall on a person.
///
/// # Safety
///
/// `directory` must point at `directory_len` bytes of UTF-8 readable for the duration;
/// `settings` and `model` must point at their stated lengths of readable bytes, or be null;
/// `read` and `write` must be valid function pointers whose `context` stays valid for the
/// duration; `report` must point at a zeroed [`OpairdropBuffer`] that outlives the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_serve(
    directory: *const u8,
    directory_len: usize,
    settings_text: *const u8,
    settings_len: usize,
    model: *const u8,
    model_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    // SAFETY: every pointer is promised by the caller for this call.
    unsafe {
        serve(
            directory,
            directory_len,
            settings_text,
            settings_len,
            model,
            model_len,
            // 这个入口没有那个参数：它的原型早于「系统设备名」，而加参数会改掉一个已经
            // 有人在用的 C 原型（见 `opairdrop_serve_watched` 为什么另开一个出口）。
            None,
            read,
            write,
            context,
            None,
            None,
            report,
        )
    }
}

/// Serves one connection, asking `decide` before every transfer is taken.
///
/// The offer is read first and then shown to `decide`, because a prompt that names nothing
/// is a prompt nobody can answer: the endpoint reads the offer, hands over a one-line
/// summary, and answers `/Ask` with `200` or `401` according to the reply. A `decide` that
/// returns zero, or a callback that fails, is a refusal — the safe direction, since the
/// alternative is accepting what a person never saw.
///
/// This export exists beside [`opairdrop_serve`] rather than changing its signature, so a
/// caller built against the old one keeps working and keeps its early answers.
///
/// # Safety
///
/// As [`opairdrop_serve`], plus: `decide` must be a valid function pointer whose
/// `decide_context` stays valid for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_serve_decided(
    directory: *const u8,
    directory_len: usize,
    settings_text: *const u8,
    settings_len: usize,
    model: *const u8,
    model_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    decide: DecideCallback,
    decide_context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    // SAFETY: as above, plus the callback pair the caller promises.
    unsafe {
        serve(
            directory,
            directory_len,
            settings_text,
            settings_len,
            model,
            model_len,
            // 同 `opairdrop_serve`：原型不动，参数缺省。
            None,
            read,
            write,
            context,
            Some(Decide {
                callback: decide,
                context: decide_context,
            }),
            None,
            report,
        )
    }
}

/// Serves one connection, asking `decide` before every transfer and reporting how far it
/// has got to `progress`.
///
/// [`opairdrop_serve_decided`] with a watcher. It exists as a third export rather than as
/// two more arguments on the second for the same reason the second exists: a caller built
/// against an older signature keeps working, and `dlsym` failures on a missing symbol are a
/// worse way to learn about an upgrade than an unchanged prototype.
///
/// `progress` is called from inside the write loop, once per landed chunk, with the bytes so
/// far and the total the offer declared. Both are payload bytes, so they are comparable with
/// the sizes a sender names. The transfer waits on the call: it must be cheap and it must
/// not block. It is never allowed to fail the receive — a progress bar is not a reason to
/// drop a transfer.
///
/// # Safety
///
/// As [`opairdrop_serve_decided`], plus: `progress` must be a valid function pointer whose
/// `progress_context` stays valid for the duration of the call.
///
/// `system_name` (and its length) is what the operating system calls this phone — the name the
/// person gave it in Settings, which is also the name Bluetooth advertises — or null for
/// nothing. It is here rather than on the two older exports because those are frozen prototypes
/// with callers already built against them; this one is the entry the app uses.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_serve_watched(
    directory: *const u8,
    directory_len: usize,
    settings_text: *const u8,
    settings_len: usize,
    model: *const u8,
    model_len: usize,
    system_name: *const u8,
    system_name_len: usize,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    decide: DecideCallback,
    decide_context: *mut c_void,
    progress: LandingCallback,
    progress_context: *mut c_void,
    report: *mut OpairdropBuffer,
) -> i32 {
    // SAFETY: `system_name` is null or points at `system_name_len` readable bytes, per the
    // contract. Null is taken before `from_raw_parts`, which requires non-null even for an
    // empty slice.
    let system_name = if system_name.is_null() {
        None
    } else {
        let bytes = unsafe { std::slice::from_raw_parts(system_name, system_name_len) };
        // A name that is not UTF-8 is not a reason to refuse a transfer — the peer would only
        // be shown a replacement-character version of it — so it is dropped instead.
        std::str::from_utf8(bytes).ok()
    };
    // SAFETY: as above, plus the second callback pair the caller promises.
    unsafe {
        serve(
            directory,
            directory_len,
            settings_text,
            settings_len,
            model,
            model_len,
            system_name,
            read,
            write,
            context,
            Some(Decide {
                callback: decide,
                context: decide_context,
            }),
            Some(Watcher {
                callback: progress,
                context: progress_context,
            }),
            report,
        )
    }
}

/// Serves one connection: reads requests from `read`, writes answers to `write`.
///
/// `directory` is where an upload's members are written; it is created if it does not
/// exist. `settings` is the stored settings text — `key=value` per line — or null for the
/// table's defaults. `model` is what the operating system calls this hardware, or null for
/// nothing. `system_name` is what the operating system calls *this phone* — the name its owner
/// gave it, which is also what Bluetooth advertises — or `None` when the caller has nothing to
/// say, in which case a blank `device.name` falls through to the model as it always did.
///
/// `report` receives a [`Report`] frame on success, or the failure's message as UTF-8 on
/// failure — the status says which. It must be zero-initialized and released with
/// [`crate::buffer::opairdrop_buffer_free`] once read.
///
/// # Safety
///
/// `directory` must point at `directory_len` bytes of UTF-8 readable for the duration;
/// `settings` and `model` must point at their stated lengths of readable bytes, or be null;
/// `read` and `write` must be valid function pointers whose `context` stays valid for the
/// duration; `report` must point at a zeroed [`OpairdropBuffer`] that outlives the call.
///
/// `pub(crate)` rather than private so the JNI layer can hand over the callbacks it already
/// holds as [`Decide`] and [`Watcher`] pairs, instead of re-deriving them from the raw
/// argument lists the two outer exports take.
#[allow(clippy::too_many_arguments)]
pub(crate) unsafe fn serve(
    directory: *const u8,
    directory_len: usize,
    settings_text: *const u8,
    settings_len: usize,
    model: *const u8,
    model_len: usize,
    system_name: Option<&str>,
    read: ReadCallback,
    write: WriteCallback,
    context: *mut c_void,
    decide: Option<Decide>,
    watcher: Option<Watcher>,
    report: *mut OpairdropBuffer,
) -> i32 {
    if report.is_null() || directory.is_null() {
        return status::NULL_ARGUMENT;
    }

    // SAFETY: the caller guarantees the region is readable for the length given.
    let directory = unsafe { std::slice::from_raw_parts(directory, directory_len) };
    let Ok(directory) = std::str::from_utf8(directory) else {
        // SAFETY: `report` is non-null and zeroed, per the contract.
        unsafe { fill(report, b"the inbox path is not valid UTF-8".to_vec()) };
        return status::BAD_PATH;
    };
    if directory.is_empty() {
        // SAFETY: as above.
        unsafe { fill(report, b"the inbox path is empty".to_vec()) };
        return status::BAD_PATH;
    }

    // SAFETY: `settings_text` is null or points at `settings_len` readable bytes, per the
    // contract. The null case is taken before `from_raw_parts`, which requires a non-null
    // pointer even for an empty slice.
    let settings_text = if settings_text.is_null() {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(settings_text, settings_len) }
    };
    let values = match settings::values_from(settings_text) {
        Ok(values) => values,
        Err(message) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, message.into_bytes()) };
            return status::SETTINGS;
        }
    };

    // SAFETY: as above, with `model` in place of the settings.
    let model = if model.is_null() {
        ""
    } else {
        let bytes = unsafe { std::slice::from_raw_parts(model, model_len) };
        match std::str::from_utf8(bytes) {
            Ok(text) => text,
            Err(_) => {
                // SAFETY: as above.
                unsafe { fill(report, b"the model name is not valid UTF-8".to_vec()) };
                return status::BAD_PATH;
            }
        }
    };

    let mut server = Server::new(Store {
        name: settings::name(&values, system_name.unwrap_or_default(), model),
        model: settings::model(&values, model),
        directory: PathBuf::from(directory),
        limits: settings::limits(&values),
        capabilities: settings::media_capabilities(&values),
        landed: Report::default(),
        decide,
        watcher,
        declared: 0,
        members_total: 0,
        fully_sized: false,
    });

    // SAFETY: the caller guarantees `read`, `write` and `context` for the duration of this
    // call, which is the contract both adapters state.
    let mut reader = CallbackReader::new(read, context);
    let mut writer = CallbackWriter::new(write, context);

    let outcome = server.serve(&mut reader, &mut writer);
    match outcome {
        Ok(()) => {
            let frame = server.receiver().landed().encode();
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, frame) };
            status::OK
        }
        Err(failure) => {
            let code = classify(&failure);
            // SAFETY: as above.
            unsafe { fill(report, failure.to_string().into_bytes()) };
            code
        }
    }
}

/// The status that best describes why a connection ended badly.
///
/// An inbox refusal keeps its own code, because a caller can do something with it — say the
/// transfer was too large rather than that the network failed — while the three transport
/// failures share one, because their only remedy is to accept the next connection. The
/// downcast is how the inbox's refusal survives being wrapped in an [`io::Error`] by the
/// endpoint: the variant is the same value that was wrapped, not a copy of its text.
fn classify(failure: &EndpointError) -> i32 {
    match failure {
        EndpointError::Io(error) => match error
            .get_ref()
            .and_then(|inner| inner.downcast_ref::<InboxError>())
        {
            Some(refusal) => api::classify(refusal),
            None => status::TRANSPORT,
        },
        EndpointError::Http(_) | EndpointError::Protocol(_) => status::TRANSPORT,
    }
}

#[cfg(test)]
mod tests {
    use std::io::{Cursor, Read as _};

    use super::*;
    use opairdrop_airdrop::{ask_request, Sender};

    /// A connection held in memory: requests go in, answers come out.
    ///
    /// The callbacks are the real ones the C ABI takes, so a bug in the adapters shows up
    /// here rather than on a phone.
    struct Wire {
        requests: Cursor<Vec<u8>>,
        answers: Vec<u8>,
        /// What to answer on the write side instead of storing: a negative number, for the
        /// tests that need a caller to give up.
        refuse: Option<isize>,
    }

    impl Wire {
        fn new(requests: Vec<u8>) -> Wire {
            Wire {
                requests: Cursor::new(requests),
                answers: Vec::new(),
                refuse: None,
            }
        }
    }

    unsafe extern "C" fn read_wire(context: *mut c_void, buf: *mut u8, len: usize) -> isize {
        // SAFETY: every test passes the `Wire` it created as the context.
        let wire = unsafe { &mut *(context as *mut Wire) };
        let target = unsafe { std::slice::from_raw_parts_mut(buf, len) };
        match wire.requests.read(target) {
            Ok(got) => got as isize,
            Err(_) => -1,
        }
    }

    unsafe extern "C" fn write_wire(context: *mut c_void, buf: *const u8, len: usize) -> isize {
        // SAFETY: as above.
        let wire = unsafe { &mut *(context as *mut Wire) };
        if let Some(code) = wire.refuse {
            return code;
        }
        let source = unsafe { std::slice::from_raw_parts(buf, len) };
        wire.answers.extend_from_slice(source);
        len as isize
    }

    /// A directory of this test's own, removed when the test ends.
    fn scratch(name: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!("opairdrop-serve-{name}"));
        let _ = std::fs::remove_dir_all(&path);
        std::fs::create_dir_all(&path).expect("the scratch directory is creatable");
        path
    }

    /// Drives one conversation and returns the status, the buffer's bytes, and the answers.
    fn serve(
        directory: &str,
        settings: &str,
        model: &str,
        requests: Vec<u8>,
    ) -> (i32, Vec<u8>, Vec<u8>) {
        let mut wire = Wire::new(requests);
        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: the strings and the wire outlive the call, and `out` is a zeroed buffer
        // on this frame.
        let outcome = unsafe {
            opairdrop_serve(
                directory.as_ptr(),
                directory.len(),
                settings.as_ptr(),
                settings.len(),
                model.as_ptr(),
                model.len(),
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                &mut out,
            )
        };
        // SAFETY: the call above filled the buffer, one way or the other.
        let bytes = unsafe { out.as_slice() }.to_vec();
        // SAFETY: the buffer came from the call above.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
        (outcome, bytes, wire.answers)
    }

    /// What the test's own decision callback was told, and what it answered.
    struct Verdict {
        accept: bool,
        summary: String,
    }

    unsafe extern "C" fn decide_with(context: *mut c_void, summary: *const u8, len: usize) -> i32 {
        // SAFETY: every test passes the `Verdict` it created as the context.
        let verdict = unsafe { &mut *(context as *mut Verdict) };
        let bytes = unsafe { std::slice::from_raw_parts(summary, len) };
        verdict.summary = String::from_utf8_lossy(bytes).into_owned();
        if verdict.accept {
            1
        } else {
            0
        }
    }

    /// Like [`serve`], but through the export that asks first.
    fn serve_decided(
        directory: &str,
        settings: &str,
        model: &str,
        requests: Vec<u8>,
        accept: bool,
    ) -> (i32, Vec<u8>, String) {
        let mut wire = Wire::new(requests);
        let mut verdict = Verdict {
            accept,
            summary: String::new(),
        };
        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: the strings, the wire and the verdict outlive the call, `out` is zeroed.
        let outcome = unsafe {
            opairdrop_serve_decided(
                directory.as_ptr(),
                directory.len(),
                settings.as_ptr(),
                settings.len(),
                model.as_ptr(),
                model.len(),
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                decide_with,
                &mut verdict as *mut Verdict as *mut c_void,
                &mut out,
            )
        };
        // SAFETY: the call above filled the buffer, one way or the other.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
        (outcome, wire.answers, verdict.summary)
    }

    /// What the test's own progress callback was told: `(done, total)` per call, in order.
    unsafe extern "C" fn record_progress(context: *mut c_void, done: u64, total: u64) {
        // SAFETY: every test passes the `Vec` it created as the context.
        let seen = unsafe { &mut *(context as *mut Vec<(u64, u64)>) };
        seen.push((done, total));
    }

    /// Like [`serve_decided`], but through the export that also reports progress. Returns
    /// the status, the report frame, the answers, and what the hook was told, in order.
    ///
    /// `system_name` is what the phone is called; the empty string is the "caller has nothing
    /// to say" case, which is what every other helper here passes.
    #[allow(clippy::type_complexity)]
    fn serve_watched(
        directory: &str,
        settings: &str,
        model: &str,
        system_name: &str,
        requests: Vec<u8>,
        accept: bool,
    ) -> (i32, Vec<u8>, Vec<u8>, Vec<(u64, u64)>) {
        let mut wire = Wire::new(requests);
        let mut verdict = Verdict {
            accept,
            summary: String::new(),
        };
        let mut seen: Vec<(u64, u64)> = Vec::new();
        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: the strings, the wire, the verdict and the log outlive the call; `out` is
        // a zeroed buffer on this frame.
        let outcome = unsafe {
            opairdrop_serve_watched(
                directory.as_ptr(),
                directory.len(),
                settings.as_ptr(),
                settings.len(),
                model.as_ptr(),
                model.len(),
                system_name.as_ptr(),
                system_name.len(),
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                decide_with,
                &mut verdict as *mut Verdict as *mut c_void,
                record_progress,
                &mut seen as *mut Vec<(u64, u64)> as *mut c_void,
                &mut out,
            )
        };
        // SAFETY: the call above filled the buffer, one way or the other.
        let bytes = unsafe { out.as_slice() }.to_vec();
        // SAFETY: the buffer came from the call above.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
        (outcome, bytes, wire.answers, seen)
    }

    #[test]
    fn a_decided_endpoint_describes_the_offer_and_refuses_when_told_no() {
        let root = scratch("decided-no");
        let files = [opairdrop_airdrop::Item::new("a.pdf").with_size(7)];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("Ada 的 iPhone", "iPhone14,3", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &files,
            &[],
        );

        let (code, answers, summary) = serve_decided(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            false,
        );

        assert_eq!(code, status::OK, "a refusal is an answer, not a failure");
        assert!(
            String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 401"),
            "the peer is told, in the one place it is still listening"
        );
        // And told the truth about the connection: the refusal ends it.
        assert!(
            String::from_utf8_lossy(&answers).contains("Connection: close"),
            "a refusing answer closes"
        );
        assert_eq!(
            summary, "Ada 的 iPhone\t1\t7\t0\ta.pdf\u{1f}7\t",
            "the fifth field names the file, so the prompt can say what it is"
        );
    }

    #[test]
    fn a_decided_endpoint_takes_the_transfer_when_told_yes() {
        let root = scratch("decided-yes");
        let files = [opairdrop_airdrop::Item::new("a.pdf").with_size(7)];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("Mac", "MacBookPro18,3", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &files,
            &[],
        );

        let (code, answers, summary) = serve_decided(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            true,
        );

        assert_eq!(code, status::OK);
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 200"));
        assert_eq!(summary, "Mac\t1\t7\t0\ta.pdf\u{1f}7\t");
    }

    #[test]
    fn a_links_offer_says_links_rather_than_zero_files() {
        // The bug this guards: a Safari share offers no files, only `Items`. A three-field
        // summary counts `files` and reports zero, so the one prompt where the person decides
        // reads "0 files" — which says there is nothing to take. A real peer asked that way.
        let root = scratch("decided-links");
        let links = [
            "https://example.com/a".to_string(),
            "https://example.com/b".to_string(),
        ];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("链接探针", "iPhone18,1", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &[],
            &links,
        );

        let (_, _, summary) = serve_decided(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            false,
        );

        assert_eq!(
            summary, "链接探针\t0\t0\t2\t\thttps://example.com/a\u{1e}https://example.com/b",
            "the link count is the fourth field, and a links offer names no files — \
             but the sixth field is where the links themselves finally cross over, \
             because a link with no URL on this side is a card that cannot be opened"
        );
    }

    #[test]
    fn the_fifth_field_names_every_file_and_survives_a_tab_in_one() {
        // The card draws the first three names above the total, so a name that carries the
        // field separator must not split into a name and a size — that would put a number
        // where a file name goes, on the one line a person reads before deciding.
        let files = [
            opairdrop_airdrop::Item::new("IMG\t0001.HEIC").with_size(2_000_000),
            opairdrop_airdrop::Item::new("合同.pdf").with_size(700_000),
            // A folder entry is declared without a size, and blank means unknown — the
            // caller drops the total rather than reading it as nothing to download.
            opairdrop_airdrop::Item::new("资料").as_directory(),
        ];
        let text = names_of(&files);
        assert_eq!(
            text,
            "IMG 0001.HEIC\u{1f}2000000\u{1e}合同.pdf\u{1f}700000\u{1e}资料\u{1f}"
        );
        assert_eq!(text.split(BETWEEN_FILES).count(), 3, "one entry per file");
        let names: Vec<&str> = text
            .split(BETWEEN_FILES)
            .map(|entry| entry.split(BETWEEN_NAME_AND_SIZE).next().unwrap_or(""))
            .collect();
        assert_eq!(names, ["IMG 0001.HEIC", "合同.pdf", "资料"]);
    }

    #[test]
    fn an_offer_with_no_files_has_an_empty_fifth_field() {
        // Not "0" and not "none": absence is what the caller checks, and a placeholder would
        // make a links offer look like a file called nothing.
        assert_eq!(names_of(&[]), "");
    }

    #[test]
    fn the_model_row_travels_in_the_ask_answer() {
        let root = scratch("model-row");
        let files = [opairdrop_airdrop::Item::new("a.pdf").with_size(7)];
        let body = opairdrop_airdrop::ask_request(
            &opairdrop_airdrop::Sender::new("Ada 的 iPhone", "iPhone14,3", "id"),
            &opairdrop_airdrop::TransferId::from_bytes16(&[7u8; 16]),
            &files,
            &[],
        );
        let settings = format!(
            "{}\ndevice.model=iPhone 18\ndevice.name=书房\n",
            opairdrop_settings::HEAD
        );

        let (code, answers, _) = serve_decided(
            root.to_str().expect("UTF-8"),
            &settings,
            "OPPO PJZ110",
            post("/Ask", "application/x-apple-binary-plist", &body),
            true,
        );

        assert_eq!(code, status::OK);
        let answer = AskAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        // The row wins over the caller's model, and the name is the one the person typed
        // rather than the model standing in for it.
        assert_eq!(answer.model_name, "iPhone 18");
        assert_eq!(answer.computer_name, "书房");
    }

    /// The bytes of one request: a head with its `Content-Length`, then its body.
    fn post(path: &str, content_type: &str, body: &[u8]) -> Vec<u8> {
        let mut request = format!(
            "POST {path} HTTP/1.1\r\nHost: air\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\n\r\n",
            body.len()
        )
        .into_bytes();
        request.extend_from_slice(body);
        request
    }

    /// 一条会被接受的 /Ask（评审 P0-1 之后，/Upload 必须跟在同一条连接的接受之后）。
    fn accepted_ask() -> Vec<u8> {
        let sender = Sender::new("iPhone von Ada", "iPhone", "8A2B:1F:9C");
        ask_request(
            &sender,
            &opairdrop_airdrop::TransferId::from_bytes16(&[0x5c; 16]),
            &[Item::new("note.txt")],
            &[],
        )
    }

    fn archive(name: &str, payload: &[u8]) -> Vec<u8> {
        opairdrop_cpio::to_vec(
            &[opairdrop_cpio::Entry::file(name, payload.to_vec())],
            opairdrop_cpio::Format::Newc,
        )
        .expect("the fixture archive builds")
    }

    /// The body of the one answer in `answers`, past its blank line.
    ///
    /// One request in, one answer out, so there is no need to frame: the end of the buffer
    /// is the end of the body, and a second answer would mean the endpoint had answered a
    /// request nobody sent.
    fn body_of(answers: &[u8]) -> &[u8] {
        let split = answers
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .expect("an answer has a head and a blank line");
        &answers[split + 4..]
    }

    #[test]
    fn a_discovered_receiver_answers_with_the_name_it_was_given() {
        let root = scratch("name");
        let settings = "device.name=书房里的手机\n";

        let (code, _, answers) = serve(
            root.to_str().expect("UTF-8"),
            settings,
            "OPPO PJZ110",
            post("/Discover", "application/x-apple-binary-plist", &[]),
        );

        assert_eq!(code, status::OK);
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 200"));
        // Parsed rather than searched for: a bplist stores a non-ASCII string as UTF-16,
        // so a byte-window search would fail on exactly the case worth testing.
        let answer = DiscoverAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        assert_eq!(answer.computer_name, "书房里的手机");
        assert_eq!(
            answer.airdropable,
            Some(true),
            "a peer must be told this device accepts files"
        );
    }

    #[test]
    fn an_empty_name_falls_back_to_the_model_as_the_table_promises() {
        let root = scratch("fallback");

        let (code, _, answers) = serve(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            post("/Discover", "application/x-apple-binary-plist", &[]),
        );

        assert_eq!(code, status::OK);
        let answer = DiscoverAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        assert_eq!(answer.computer_name, "OPPO PJZ110");
    }

    #[test]
    fn a_blank_name_is_the_phone_s_own_name_and_not_its_model() {
        let root = scratch("system-name");
        // The case this exists for: an untouched installation used to introduce itself to an
        // iPhone as "OPPO PJZ110" while Settings called the same phone "书桌旁的手机". The
        // model row is deliberately unset, so anything reading it here would show the fallback.
        let (code, _, answers, _) = serve_watched(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            "书桌旁的手机",
            post("/Discover", "application/x-apple-binary-plist", &[]),
            true,
        );

        assert_eq!(code, status::OK);
        let answer = DiscoverAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        assert_eq!(answer.computer_name, "书桌旁的手机");

        // …and the row still wins over it, because the row is what the person typed for this.
        let (code, _, answers, _) = serve_watched(
            root.to_str().expect("UTF-8"),
            "device.name=书房里的手机\n",
            "OPPO PJZ110",
            "书桌旁的手机",
            post("/Discover", "application/x-apple-binary-plist", &[]),
            true,
        );
        assert_eq!(code, status::OK);
        let answer = DiscoverAnswer::parse(body_of(&answers)).expect("the answer is a plist");
        assert_eq!(answer.computer_name, "书房里的手机");
    }

    #[test]
    fn an_upload_lands_and_its_names_come_back_in_the_report() {
        let root = scratch("upload");
        let payload = vec![7u8; 4096];
        let body = archive("note.txt", &payload);

        let mut requests = post("/Ask", "application/x-apple-binary-plist", &accepted_ask());
        requests.extend(post("/Upload", "application/x-cpio", &body));
        let (code, report, answers) =
            serve(root.to_str().expect("UTF-8"), "", "OPPO PJZ110", requests);

        assert_eq!(code, status::OK);
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 200"));
        let report = Report::decode(&report).expect("a frame came back");
        assert_eq!(report.names, ["note.txt"]);
        assert_eq!(report.bytes, 4096);
        assert_eq!(
            std::fs::read(root.join("note.txt")).expect("the member is on disk"),
            payload
        );
    }

    #[test]
    fn a_watched_endpoint_falls_back_to_the_head_s_length_for_its_denominator() {
        let root = scratch("watched");
        let payload = vec![7u8; 4096];
        let body = archive("note.txt", &payload);

        let mut requests = post("/Ask", "application/x-apple-binary-plist", &accepted_ask());
        requests.extend(post("/Upload", "application/x-cpio", &body));
        let (code, report, answers, seen) = serve_watched(
            root.to_str().expect("UTF-8"),
            "",
            "OPPO PJZ110",
            "",
            requests,
            true,
        );

        assert_eq!(code, status::OK);
        assert!(String::from_utf8_lossy(&answers).starts_with("HTTP/1.1 200"));
        let report = Report::decode(&report).expect("a frame came back");
        assert!(
            !seen.is_empty(),
            "a watcher on a receive that landed bytes saw none"
        );
        assert!(
            seen.windows(2).all(|pair| pair[0].0 <= pair[1].0),
            "a bar that goes backwards is worse than none: {seen:?}"
        );
        assert_eq!(
            seen.last().map(|&(done, _)| done),
            Some(report.bytes),
            "the last number a watcher sees is what actually landed: {seen:?}"
        );
        // No `/Ask` came first, so the offer this hook would have taken its denominator from
        // never existed. The denominator falls back to the head's `Content-Length`
        // （2026-11-24：iPhone 发实况照片时 offer 不带 size，旧做法全程 -1% 让流体云不画
        // 轨道；线上字节数就是传输总量，误差由调用方 clamp）。每个回调的 total 都相同，
        // 且是这条请求声明过的数——不是编出来的。
        let total = seen[0].1;
        assert!(
            total > 0,
            "the head promised a body, so the fallback total is its length"
        );
        assert!(
            seen.iter().all(|&(_, t)| t == total),
            "the denominator must not drift mid-transfer: {seen:?}"
        );
        assert!(
            seen.iter().all(|&(done, _)| done <= total),
            "payload must never claim more than the promised body: {seen:?}"
        );
    }

    #[test]
    fn an_offer_s_sizes_are_the_denominator_a_watcher_is_given() {
        let root = scratch("declared");
        let ask = Ask {
            files: vec![
                opairdrop_airdrop::Item::new("a.jpg").with_size(7),
                opairdrop_airdrop::Item::new("b.jpg").with_size(11),
                // A sender that declares nothing for one member contributes nothing rather
                // than a zero that would silently make the sum look smaller than the
                // transfer this receiver is about to be shown.
                opairdrop_airdrop::Item::new("c.jpg"),
            ],
            ..Ask::default()
        };
        let mut store = Store {
            name: String::new(),
            model: String::new(),
            directory: root,
            limits: Limits::default(),
            capabilities: opairdrop_airdrop::MEDIA_CAPABILITIES.as_bytes().to_vec(),
            landed: Report::default(),
            decide: None,
            watcher: None,
            declared: 0,
        };

        // Asked with nobody to ask: every transfer is taken, and the sizes are read on the
        // way past because `upload` runs after the offer has been consumed.
        assert!(store.accept(&ask));
        assert_eq!(store.declared, 18);
    }

    #[test]
    fn an_upload_ends_the_connection_so_a_second_one_is_not_read() {
        let root = scratch("twice");
        let mut requests = post("/Ask", "application/x-apple-binary-plist", &accepted_ask());
        requests.extend(post(
            "/Upload",
            "application/x-cpio",
            &archive("a.txt", b"one"),
        ));
        requests.extend(post(
            "/Upload",
            "application/x-cpio",
            &archive("b.txt", b"two"),
        ));

        let (code, report, _) = serve(root.to_str().expect("UTF-8"), "", "", requests);

        // The protocol's own rule, pinned here because it is easy to read the loop in
        // `Server::serve` as "keep going until the peer closes": an archive is the last
        // thing a sender has to say, so the first upload's `200` is followed by a close and
        // the bytes behind it are never parsed. A sender with a second transfer opens a
        // second connection.
        assert_eq!(code, status::OK);
        let report = Report::decode(&report).expect("a frame came back");
        assert_eq!(report.names, ["a.txt"]);
        assert_eq!(report.bytes, 3);
        assert!(root.join("b.txt").symlink_metadata().is_err());
    }

    #[test]
    fn a_member_that_is_not_an_archive_keeps_its_own_status() {
        let root = scratch("refused");

        // Not cpio, not gzip, not DVZip: the inbox refuses it, the endpoint answers `500`,
        // and the refusal has to survive being wrapped in an I/O error.
        let mut requests = post("/Ask", "application/x-apple-binary-plist", &accepted_ask());
        requests.extend(post(
            "/Upload",
            "application/x-cpio",
            b"this is not an archive",
        ));
        let (code, message, answers) = serve(root.to_str().expect("UTF-8"), "", "", requests);

        assert_eq!(
            code,
            status::ARCHIVE,
            "{}",
            String::from_utf8_lossy(&message)
        );
        // 先是 /Ask 的 200（评审 P0-1 之后上传必须跟在接受后面），500 只属于 upload。
        let answers = String::from_utf8_lossy(&answers);
        assert!(answers.starts_with("HTTP/1.1 200"));
        assert!(answers.contains("HTTP/1.1 500"));
    }

    #[test]
    fn a_writer_that_gives_up_is_a_transport_failure_and_is_not_retried() {
        let root = scratch("writer");
        let mut wire = Wire::new(post("/Discover", "application/x-apple-binary-plist", &[]));
        wire.refuse = Some(-9);
        let mut out = OpairdropBuffer::EMPTY;
        let path = root.to_str().expect("UTF-8");

        // SAFETY: as in `serve` above, with a wire that refuses to be written to.
        let code = unsafe {
            opairdrop_serve(
                path.as_ptr(),
                path.len(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                0,
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                &mut out,
            )
        };

        assert_eq!(code, status::TRANSPORT);
        // SAFETY: the call above filled the buffer, one way or the other.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
    }

    #[test]
    fn an_empty_path_is_refused_before_any_stream_is_touched() {
        let mut wire = Wire::new(Vec::new());
        let mut out = OpairdropBuffer::EMPTY;
        let path = "";

        // SAFETY: the pointers are valid for the call; the empty path is what is refused.
        let code = unsafe {
            opairdrop_serve(
                path.as_ptr(),
                path.len(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                0,
                read_wire,
                write_wire,
                &mut wire as *mut Wire as *mut c_void,
                &mut out,
            )
        };

        assert_eq!(code, status::BAD_PATH);
        // SAFETY: the call above filled the buffer, one way or the other.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
    }
}
