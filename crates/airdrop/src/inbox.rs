//! The receive side of an `/Upload`: a directory, plus the rules for turning an
//! untrusted cpio stream into files on disk.
//!
//! The archive comes from another device, so every member is treated as hostile until
//! proven otherwise: names are re-checked, symlinks are dropped rather than created,
//! nothing may resolve outside the directory, collisions get a ` (n)` sibling, and a
//! running byte budget stops a sender from filling the volume.
//!
//! The one rule a person can turn off is the sibling: [`Limits::overwrite`] replaces the
//! file instead. It is off by default because it is the only setting here that lets a peer
//! destroy something already on the phone, and it does not loosen anything else — the
//! containment check, the symlink refusal and the budget all still apply.
//!
//! # A member appears whole or not at all
//!
//! Each member is written to a dot-prefixed temporary in its own directory and renamed
//! into place only once its last byte has arrived. The predecessor wrote straight to the
//! final name and deleted it if the budget was exceeded, which meant that any other
//! failure — a full disk, a peer that vanished mid-member — left a truncated file under
//! the real name. The sender cannot tell the difference between that and a complete
//! file, and on a phone the truncated version is the one the gallery indexes.
//!
//! The temporary name starts with a dot so the media scanner skips it, and it is a
//! sibling of the target rather than a staging area elsewhere so the rename stays inside
//! one filesystem.
//!
//! # One refused member does not cost the others
//!
//! The sink has no way to report an error to [`opairdrop_cpio::extract`] — `open` either
//! yields a writer or drops the member. So a refusal (budget, escape, unopenable path) is
//! latched here and reported after the archive has been drained. The receiver keeps
//! reading for two reasons: the peer's upload should finish and the connection stay
//! usable, and a single unwritable name should not discard the members after it. What
//! landed is whatever [`Inbox::names`] says; the first refusal is the error.
//!
//! # What this layer does not detect
//!
//! A stream that ends early *between* members is not treated as a failure: every member
//! that did arrive is intact, and the trailer carries no data. A connection that drops
//! mid-transfer therefore has to be caught by the framing above — `Content-Length` or the
//! chunked terminator in [`opairdrop_http`] — which is the only layer that knows how many
//! bytes were promised. Detecting it here would mean declaring a complete archive broken
//! because its sender chose not to write a trailer.

use std::collections::HashSet;
use std::fmt;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use opairdrop_cpio::{self, Sink};

/// Upper bound on one transfer, matching the predecessor's value: 8 GiB.
///
/// Larger uploads fail rather than filling the volume. It is a limit on payload bytes,
/// not on the archive, so header overhead does not count against it.
pub const DEFAULT_BYTE_LIMIT: u64 = 8 * 1024 * 1024 * 1024;

/// What an inbox allows, as opposed to where it writes.
///
/// Grouped into one value because the two travel together: every caller that has an opinion
/// about the byte budget has one about collisions too, and two positional arguments of
/// different types is how a call site ends up passing them in the wrong order.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// Most payload bytes one transfer may write.
    pub byte_limit: u64,
    /// Whether a member may replace a file that is already there.
    pub overwrite: bool,
}

impl Default for Limits {
    fn default() -> Limits {
        Limits {
            byte_limit: DEFAULT_BYTE_LIMIT,
            overwrite: false,
        }
    }
}

/// Suffix of the temporary a member is written to before it is published.
const PARTIAL: &str = ".opairdrop-part";

/// How many ` (n)` variants to try before giving up on a name.
const NAME_ATTEMPTS: u32 = 10_000;

/// What went wrong while receiving.
#[derive(Debug)]
pub enum Error {
    /// The archive itself could not be read.
    Archive(opairdrop_cpio::Error),
    /// The inbox directory, or one member, could not be written.
    Io(io::Error),
    /// The transfer would have exceeded the byte budget.
    OverBudget { limit: u64 },
    /// Every ` (n)` variant of a member's name was already taken.
    NoFreeName { name: String },
    /// A member resolved outside the inbox, so it was refused.
    Outside { name: String },
}

impl fmt::Display for Error {
    fn fmt(&self, out: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Archive(error) => write!(out, "the archive could not be read: {error}"),
            Error::Io(error) => write!(out, "the inbox could not be written: {error}"),
            Error::OverBudget { limit } => {
                write!(out, "the transfer exceeds the inbox budget of {limit} bytes")
            }
            Error::NoFreeName { name } => write!(out, "no free name for {name}"),
            Error::Outside { name } => write!(out, "refusing {name}: it resolves outside the inbox"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Archive(error) => Some(error),
            Error::Io(error) => Some(error),
            _ => None,
        }
    }
}

/// What one archive left behind.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Received {
    /// Names as stored, relative to the inbox — what the notification should show.
    ///
    /// Directories are included; members that were dropped (unsafe name, symlink) are
    /// not, because there is nothing on disk behind them.
    pub names: Vec<String>,
    /// Payload bytes written. Header overhead and dropped members do not count.
    pub bytes: u64,
}

/// What became of one member once its bytes ran out.
enum Settlement {
    Published { name: String, bytes: u64 },
    Refused(Error),
    Unwritten(io::Error),
}

/// Told how many payload bytes have landed, as they land.
///
/// The count is the whole transfer's, not one member's: a person watching a receive thinks
/// in "how much of this has arrived", and a number that restarts at every file is not that
/// number. It also counts only payload — header overhead and members that were dropped are
/// excluded — so it is directly comparable with the sizes the sender declared in its offer,
/// which is the only total available before the archive is drained.
///
/// Takes a [`u64`] rather than a fraction because the caller is the one holding the total,
/// and it may be wrong: an offer names sizes, and a peer is free to send something else.
///
/// `Arc` rather than a borrowed `&mut dyn FnMut` on purpose. The hook is reached from
/// [`Member::write`], which runs behind a `&mut dyn Write` the extractor holds for the
/// length of a member — there is nowhere to thread a borrow through that without giving
/// [`Inbox`] a lifetime parameter and making every caller carry it. A shared, `'static`
/// handle costs one atomic per chunk and keeps the type where it is.
pub type Progress = Arc<dyn Fn(u64) + Send + Sync>;

/// A member that is currently being written.
struct Member {
    name: String,
    target: PathBuf,
    temp: PathBuf,
    file: Option<File>,
    written: u64,
    /// Bytes this member may still write, which is the budget minus everything already
    /// published. Only one member is open at a time, so this cannot drift.
    allowed: u64,
    limit: u64,
    failure: Option<Error>,
    /// Where to say how far the transfer has got, and what to add to this member's own
    /// count to say it: the bytes earlier members already published.
    progress: Option<(Progress, u64)>,
}

impl Member {
    fn open(
        root: &Path,
        name: String,
        allowed: u64,
        limit: u64,
        progress: Option<(Progress, u64)>,
    ) -> Result<Member, Error> {
        let target = root.join(&name);
        let parent = target.parent().unwrap_or(root).to_path_buf();
        fs::create_dir_all(&parent).map_err(Error::Io)?;

        let base = target
            .file_name()
            .map(|base| base.to_string_lossy().into_owned())
            .unwrap_or_default();
        let temp = parent.join(format!(".{base}{PARTIAL}"));
        let file = File::create(&temp).map_err(Error::Io)?;

        Ok(Member {
            name,
            target,
            temp,
            file: Some(file),
            written: 0,
            allowed,
            limit,
            failure: None,
            progress,
        })
    }

    /// Closes the file and renames it onto its final name.
    fn publish(&mut self) -> io::Result<()> {
        self.file.take();
        fs::rename(&self.temp, &self.target)
    }

    fn remove_temp(&mut self) {
        self.file.take();
        let _ = fs::remove_file(&self.temp);
    }

    /// Settles a member whose bytes have all arrived.
    fn settle(mut self) -> Settlement {
        if let Some(failure) = self.failure.take() {
            self.remove_temp();
            return Settlement::Refused(failure);
        }
        match self.publish() {
            Ok(()) => Settlement::Published {
                name: self.name,
                bytes: self.written,
            },
            Err(error) => {
                self.remove_temp();
                Settlement::Unwritten(error)
            }
        }
    }

    /// Drops a member that never finished, and yields whatever it was refused for.
    ///
    /// A truncated member is never published: unlike a resumable download, a prefix of
    /// one file in an archive of many is not a useful artifact.
    fn revoke(mut self) -> Option<Error> {
        let failure = self.failure.take();
        self.remove_temp();
        failure
    }
}

impl Write for Member {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if let Some(failure) = &self.failure {
            return Err(io::Error::other(failure.to_string()));
        }
        if self.written.saturating_add(bytes.len() as u64) > self.allowed {
            let failure = Error::OverBudget { limit: self.limit };
            let message = failure.to_string();
            self.failure = Some(failure);
            return Err(io::Error::other(message));
        }
        let file = self
            .file
            .as_mut()
            .ok_or_else(|| io::Error::other("this member has already been published"))?;
        // `write_all` rather than `write`: a short write would hand cpio a partial-copy
        // loop at a frame boundary it never agreed to, and the accounting below counts
        // what was actually stored.
        file.write_all(bytes)?;
        self.written += bytes.len() as u64;
        // After the write, not before: a chunk that failed did not arrive, and a bar that
        // counts what a full disk refused is worse than no bar.
        if let Some((say, earlier)) = &self.progress {
            say(earlier.saturating_add(self.written));
        }
        Ok(bytes.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        match self.file.as_mut() {
            Some(file) => file.flush(),
            None => Ok(()),
        }
    }
}

/// A directory that received uploads are unpacked into.
pub struct Inbox {
    root: PathBuf,
    /// `root` with symlinks resolved. Every member's parent is checked against it, which
    /// is what stops a symlinked subdirectory from redirecting a write out of the inbox.
    resolved: PathBuf,
    limit: u64,
    /// Whether a member may replace a file that is already there.
    overwrite: bool,
    total: u64,
    names: Vec<String>,
    /// Names this transfer has handed out, so two members cannot collide with each other
    /// before either exists on disk.
    taken: HashSet<String>,
    member: Option<Member>,
    /// Stands in for the writer of a directory member, whose bytes have nowhere to go.
    discard: io::Sink,
    /// First refusal, reported once the archive has been drained.
    failure: Option<Error>,
    /// Where to report landing bytes, if anybody is watching.
    progress: Option<Progress>,
}

impl Inbox {
    /// Opens an inbox at `directory`, creating it if needed.
    pub fn new(directory: impl AsRef<Path>) -> Result<Inbox, Error> {
        Inbox::with_limits(directory, Limits::default())
    }

    /// Opens an inbox that writes under `limits`. A zero byte limit means
    /// [`DEFAULT_BYTE_LIMIT`], because a budget of zero is a receiver that refuses every
    /// member and is never what a caller meant to ask for.
    pub fn with_limits(directory: impl AsRef<Path>, limits: Limits) -> Result<Inbox, Error> {
        let root = directory.as_ref().to_path_buf();
        fs::create_dir_all(&root).map_err(Error::Io)?;
        let resolved = fs::canonicalize(&root).map_err(Error::Io)?;
        Ok(Inbox {
            root,
            resolved,
            limit: if limits.byte_limit == 0 {
                DEFAULT_BYTE_LIMIT
            } else {
                limits.byte_limit
            },
            overwrite: limits.overwrite,
            total: 0,
            names: Vec::new(),
            taken: HashSet::new(),
            member: None,
            discard: io::sink(),
            failure: None,
            progress: None,
        })
    }

    /// Reports landing bytes to `progress` as they are written.
    ///
    /// A builder method rather than an argument to [`Inbox::with_limits`]: watching is a
    /// property of this one receive, and the two callers that do not watch would otherwise
    /// pass `None` to say nothing.
    pub fn watched(mut self, progress: Progress) -> Inbox {
        self.progress = Some(progress);
        self
    }

    /// The directory members are unpacked into.
    pub fn directory(&self) -> &Path {
        &self.root
    }

    /// What has landed so far, in archive order.
    pub fn names(&self) -> &[String] {
        &self.names
    }

    /// Payload bytes published so far.
    pub fn bytes(&self) -> u64 {
        self.total
    }

    /// Unpacks one whole archive and returns how many members landed.
    ///
    /// On error, [`names`](Inbox::names) still describes what did land.
    pub fn receive<R: Read>(&mut self, archive: R) -> Result<u64, Error> {
        let outcome = opairdrop_cpio::extract(archive, self);

        // A member's own refusal explains the failure better than the I/O error the
        // extractor wrapped it in, so it is reported first.
        if let Some(failure) = self.abandon() {
            return Err(failure);
        }
        if let Some(failure) = self.failure.take() {
            return Err(failure);
        }
        outcome.map_err(Error::Archive)?;
        Ok(self.names.len() as u64)
    }

    /// Drops a member that was mid-write when the archive stopped.
    fn abandon(&mut self) -> Option<Error> {
        self.member.take()?.revoke()
    }

    /// True when neither this transfer nor the disk has `name` yet.
    fn free(&self, name: &str) -> bool {
        !self.taken.contains(name) && !self.root.join(name).exists()
    }

    /// First free variant of a name, honouring both the disk and this transfer.
    fn unique(&self, name: &str) -> Result<String, Error> {
        if self.free(name) {
            return Ok(name.to_owned());
        }
        let slash = name.rfind('/').map_or(0, |at| at + 1);
        let split = match name.rfind('.') {
            Some(at) if at > slash => at,
            _ => name.len(),
        };
        let (base, extension) = name.split_at(split);
        for attempt in 1..NAME_ATTEMPTS {
            let candidate = format!("{base} ({attempt}){extension}");
            if self.free(&candidate) {
                return Ok(candidate);
            }
        }
        Err(Error::NoFreeName {
            name: name.to_owned(),
        })
    }

    /// Refuses a name whose parent directory resolves outside the inbox.
    ///
    /// The name itself has already been normalized by the extractor, so this is not about
    /// `..`; it is about a symlink inside the inbox pointing elsewhere, which is how a
    /// hostile archive would otherwise reach out through a directory it did not create.
    fn contain(&self, name: &str) -> Result<(), Error> {
        let target = self.root.join(name);
        let parent = target.parent().unwrap_or(&self.root);
        let resolved = match fs::canonicalize(parent) {
            Ok(resolved) => resolved,
            // The parent does not exist yet, so nothing can redirect through it.
            Err(_) => return Ok(()),
        };
        if resolved == self.resolved || resolved.starts_with(&self.resolved) {
            Ok(())
        } else {
            Err(Error::Outside {
                name: name.to_owned(),
            })
        }
    }
}

impl Sink for Inbox {
    fn open(&mut self, name: &str, _size: u64, mode: u32) -> Option<&mut dyn Write> {
        // A member still open here was never closed, which means the extractor gave up on
        // it; it must not be published just because another member arrived.
        if let Some(stale) = self.member.take() {
            if let Some(failure) = stale.revoke() {
                self.failure.get_or_insert(failure);
            }
        }

        let kind = opairdrop_cpio::type_of(mode);
        if kind == opairdrop_cpio::TYPE_SYMLINK {
            // Never create links from a peer: the link target is chosen by the sender and
            // a later member could then be written through it.
            return None;
        }
        if let Err(failure) = self.contain(name) {
            self.failure.get_or_insert(failure);
            return None;
        }

        if kind == opairdrop_cpio::TYPE_DIRECTORY {
            return match fs::create_dir_all(self.root.join(name)) {
                Ok(()) => {
                    self.taken.insert(name.to_owned());
                    self.names.push(name.to_owned());
                    Some(&mut self.discard as &mut dyn Write)
                }
                Err(error) => {
                    self.failure.get_or_insert(Error::Io(error));
                    None
                }
            };
        }

        let name = if self.overwrite {
            // The sender's name is used as written. A member that lands on a file already
            // there replaces it, which is the whole point of the switch; two members with
            // one name land in archive order, the later one winning.
            name.to_owned()
        } else {
            match self.unique(name) {
                Ok(name) => name,
                Err(failure) => {
                    self.failure.get_or_insert(failure);
                    return None;
                }
            }
        };
        let allowed = self.limit.saturating_sub(self.total);
        // `total` is what earlier members published, which is the offset this member's own
        // count has to be added to before it means anything to a watcher.
        let progress = self
            .progress
            .as_ref()
            .map(|say| (Arc::clone(say), self.total));
        match Member::open(&self.root, name.clone(), allowed, self.limit, progress) {
            Ok(member) => {
                self.taken.insert(name);
                self.member = Some(member);
                self.member.as_mut().map(|member| member as &mut dyn Write)
            }
            Err(failure) => {
                self.failure.get_or_insert(failure);
                None
            }
        }
    }

    fn close(&mut self) {
        let Some(member) = self.member.take() else {
            return;
        };
        match member.settle() {
            Settlement::Published { name, bytes } => {
                self.total += bytes;
                // With overwrite on, a second member of the same name replaced the first,
                // so the name is already in the report and listing it twice would describe
                // one file as two. Without it, every name here is distinct by construction
                // and the check is skipped rather than paid for.
                if !self.overwrite || !self.names.contains(&name) {
                    self.names.push(name);
                }
            }
            Settlement::Refused(failure) => {
                self.failure.get_or_insert(failure);
            }
            Settlement::Unwritten(error) => {
                self.failure.get_or_insert(Error::Io(error));
            }
        }
    }
}

/// Receives one archive into `directory`, creating it if needed.
///
/// The convenience shape of [`Inbox`] for callers that have the archive in hand and only
/// want to know what landed. `archive` is anything readable, so a byte slice and a socket
/// take the same path.
pub fn receive<R: Read>(directory: impl AsRef<Path>, archive: R) -> Result<Received, Error> {
    receive_with(directory, Limits::default(), archive)
}

/// [`receive`] with explicit limits.
pub fn receive_with<R: Read>(
    directory: impl AsRef<Path>,
    limits: Limits,
    archive: R,
) -> Result<Received, Error> {
    let mut inbox = Inbox::with_limits(directory, limits)?;
    drain(&mut inbox, archive)
}

/// [`receive_with`], saying how many bytes have landed as they land.
///
/// The hook sees payload bytes only, which is what makes it comparable with the sizes an
/// offer declared. It is not allowed to fail: whatever it does with the number, the
/// receive goes on — a progress bar is not a reason to drop a transfer.
pub fn receive_watched<R: Read>(
    directory: impl AsRef<Path>,
    limits: Limits,
    archive: R,
    progress: Progress,
) -> Result<Received, Error> {
    let mut inbox = Inbox::with_limits(directory, limits)?.watched(progress);
    drain(&mut inbox, archive)
}

/// Unpacks into an open inbox and reports what landed, refusal first.
fn drain<R: Read>(inbox: &mut Inbox, archive: R) -> Result<Received, Error> {
    let landed = inbox.receive(archive);
    let received = Received {
        names: inbox.names().to_vec(),
        bytes: inbox.bytes(),
    };
    match landed {
        Ok(_) => Ok(received),
        Err(failure) => Err(failure),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opairdrop_cpio::{Entry, Format, MODE_SYMLINK};

    fn archive(entries: &[Entry]) -> Vec<u8> {
        opairdrop_cpio::to_vec(entries, Format::Newc).expect("the fixture archive builds")
    }

    fn scratch(name: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!("opairdrop-inbox-{name}"));
        let _ = fs::remove_dir_all(&path);
        path
    }

    fn read(path: &Path) -> Vec<u8> {
        fs::read(path).unwrap_or_else(|error| panic!("{} is unreadable: {error}", path.display()))
    }

    /// Every file under `root`, so a stray temporary shows up as a difference.
    fn listing(root: &Path) -> Vec<String> {
        let mut found = Vec::new();
        let mut pending = vec![root.to_path_buf()];
        while let Some(directory) = pending.pop() {
            for item in fs::read_dir(&directory).expect("the inbox is readable") {
                let item = item.expect("a directory entry");
                let path = item.path();
                if path.is_dir() {
                    pending.push(path.clone());
                }
                found.push(
                    path.strip_prefix(root)
                        .expect("everything is under the root")
                        .to_string_lossy()
                        .replace('\\', "/"),
                );
            }
        }
        found.sort();
        found
    }

    #[test]
    fn a_plain_archive_lands_under_its_own_names() {
        let root = scratch("plain");
        let payload = b"the quick brown fox".to_vec();
        let bytes = archive(&[
            Entry::file("note.txt", payload.clone()),
            Entry::file("photos/one.jpg", b"jpeg".to_vec()),
        ]);

        let received = receive(&root, bytes.as_slice()).expect("the archive is accepted");

        assert_eq!(received.names, ["note.txt", "photos/one.jpg"]);
        assert_eq!(received.bytes, payload.len() as u64 + 4);
        assert_eq!(read(&root.join("note.txt")), payload);
        assert_eq!(read(&root.join("photos/one.jpg")), b"jpeg");
        assert_eq!(
            listing(&root),
            ["note.txt", "photos", "photos/one.jpg"],
            "no temporary is left behind"
        );
    }

    #[test]
    fn a_watched_receive_counts_the_whole_transfer_rather_than_each_member() {
        let root = scratch("watched");
        let bytes = archive(&[
            Entry::file("note.txt", b"the quick brown fox".to_vec()),
            Entry::file("photos/one.jpg", b"jpeg".to_vec()),
        ]);
        let seen = Arc::new(std::sync::Mutex::new(Vec::new()));
        let log = Arc::clone(&seen);

        let received = receive_watched(
            &root,
            Limits::default(),
            bytes.as_slice(),
            Arc::new(move |done| {
                log.lock().expect("the log is not poisoned").push(done);
            }),
        )
        .expect("the archive is accepted");

        let reported = seen.lock().expect("the log is not poisoned").clone();
        assert!(
            reported.windows(2).all(|pair| pair[0] <= pair[1]),
            "a bar that goes backwards is worse than none: {reported:?}"
        );
        assert_eq!(
            reported.last().copied(),
            Some(received.bytes),
            "the last number a watcher sees is what actually landed"
        );
        // 19 is the first member's size. A hook that reported each member's own count would
        // stop there and start again at 4, so anything past it is the second member being
        // added to the first rather than counted alone.
        assert!(
            reported.iter().any(|&done| done > 19),
            "the second member must count on from the first: {reported:?}"
        );
    }

    #[test]
    fn a_zero_byte_member_becomes_an_empty_file() {
        let root = scratch("empty");
        let bytes = archive(&[Entry::file("empty.txt", Vec::new())]);

        let received = receive(&root, bytes.as_slice()).expect("an empty member is legal");

        assert_eq!(received.names, ["empty.txt"]);
        assert_eq!(received.bytes, 0);
        assert!(root.join("empty.txt").is_file());
        assert_eq!(read(&root.join("empty.txt")), b"");
    }

    #[test]
    fn a_member_that_walks_out_is_dropped_without_costing_the_others() {
        let root = scratch("escape");
        let bytes = archive(&[
            Entry::file("../escaped.txt", b"no".to_vec()),
            Entry::file("kept.txt", b"yes".to_vec()),
        ]);

        let received = receive(&root, bytes.as_slice()).expect("the rest of the archive still lands");

        assert_eq!(received.names, ["kept.txt"]);
        assert!(!root.join("..").join("escaped.txt").exists());
        assert_eq!(read(&root.join("kept.txt")), b"yes");
    }

    #[test]
    fn an_absolute_member_name_is_placed_inside_instead() {
        let root = scratch("absolute");
        let bytes = archive(&[Entry::file("/etc/passwd", b"not really".to_vec())]);

        let received = receive(&root, bytes.as_slice()).expect("the member is placed, not refused");

        assert_eq!(received.names, ["etc/passwd"]);
        assert_eq!(read(&root.join("etc/passwd")), b"not really");
    }

    #[test]
    fn a_symlink_member_is_never_created() {
        let root = scratch("symlink");
        let mut link = Entry::file("passwd", b"/etc/passwd".to_vec());
        link.mode = MODE_SYMLINK;

        let received = receive(&root, archive(&[link]).as_slice())
            .expect("dropping a link is not an error");

        assert!(received.names.is_empty());
        assert!(
            fs::symlink_metadata(root.join("passwd")).is_err(),
            "a peer must not be able to plant a link"
        );
    }

    #[test]
    fn a_name_that_is_already_on_disk_gets_a_numbered_sibling() {
        let root = scratch("collision");
        fs::create_dir_all(&root).expect("the inbox is creatable");
        fs::write(root.join("note.txt"), b"older").expect("the fixture is writable");

        let received = receive(
            &root,
            archive(&[Entry::file("note.txt", b"newer".to_vec())]).as_slice(),
        )
        .expect("the archive is accepted");

        assert_eq!(received.names, ["note (1).txt"]);
        assert_eq!(read(&root.join("note.txt")), b"older");
        assert_eq!(read(&root.join("note (1).txt")), b"newer");
    }

    #[test]
    fn two_members_with_one_name_do_not_overwrite_each_other() {
        let root = scratch("same-name");
        let bytes = archive(&[
            Entry::file("clip.mov", b"first".to_vec()),
            Entry::file("clip.mov", b"second".to_vec()),
        ]);

        let received = receive(&root, bytes.as_slice()).expect("both members are stored");

        assert_eq!(received.names, ["clip.mov", "clip (1).mov"]);
        assert_eq!(read(&root.join("clip.mov")), b"first");
        assert_eq!(read(&root.join("clip (1).mov")), b"second");
    }

    #[test]
    fn an_overwriting_inbox_replaces_a_file_that_is_already_there() {
        let root = scratch("overwrite");
        fs::create_dir_all(&root).expect("the inbox is creatable");
        fs::write(root.join("note.txt"), b"older").expect("the fixture is writable");

        let received = receive_with(
            &root,
            Limits {
                byte_limit: DEFAULT_BYTE_LIMIT,
                overwrite: true,
            },
            archive(&[Entry::file("note.txt", b"newer".to_vec())]).as_slice(),
        )
        .expect("the archive is accepted");

        assert_eq!(received.names, ["note.txt"]);
        assert_eq!(read(&root.join("note.txt")), b"newer");
        assert_eq!(listing(&root), ["note.txt"], "no sibling is made");
    }

    #[test]
    fn an_overwriting_inbox_lists_a_repeated_name_once() {
        let root = scratch("overwrite-twice");
        let bytes = archive(&[
            Entry::file("clip.mov", b"first".to_vec()),
            Entry::file("clip.mov", b"second".to_vec()),
        ]);

        let received = receive_with(
            &root,
            Limits {
                byte_limit: DEFAULT_BYTE_LIMIT,
                overwrite: true,
            },
            bytes.as_slice(),
        )
        .expect("both members are stored");

        assert_eq!(received.names, ["clip.mov"]);
        assert_eq!(received.bytes, 11, "both members were written");
        assert_eq!(read(&root.join("clip.mov")), b"second");
    }

    #[test]
    fn overwriting_does_not_relax_the_budget() {
        let root = scratch("overwrite-budget");
        let mut inbox = Inbox::with_limits(
            &root,
            Limits {
                byte_limit: 10,
                overwrite: true,
            },
        )
        .expect("the inbox opens");

        let failure = inbox
            .receive(archive(&[Entry::file("big.bin", vec![7u8; 64])]).as_slice())
            .expect_err("64 bytes do not fit in a 10 byte budget");

        assert!(matches!(failure, Error::OverBudget { limit: 10 }), "{failure:?}");
        assert_eq!(listing(&root), Vec::<String>::new(), "nothing is published");
    }

    #[test]
    fn the_suffix_goes_before_the_last_extension_only() {
        let root = scratch("extension");
        fs::create_dir_all(&root).expect("the inbox is creatable");
        fs::write(root.join("my.holiday.jpg"), b"older").expect("the fixture is writable");

        let received = receive(
            &root,
            archive(&[Entry::file("my.holiday.jpg", b"newer".to_vec())]).as_slice(),
        )
        .expect("the archive is accepted");

        assert_eq!(received.names, ["my.holiday (1).jpg"]);
    }

    #[test]
    fn a_transfer_that_outgrows_the_budget_leaves_no_partial_file() {
        let root = scratch("budget");
        let mut inbox = Inbox::with_limits(&root, Limits { byte_limit: 10, overwrite: false }).expect("the inbox opens");
        let bytes = archive(&[Entry::file("big.bin", vec![7u8; 64])]);

        let failure = inbox
            .receive(bytes.as_slice())
            .expect_err("64 bytes do not fit in a 10 byte budget");

        assert!(matches!(failure, Error::OverBudget { limit: 10 }), "{failure:?}");
        assert!(inbox.names().is_empty());
        assert_eq!(listing(&root), Vec::<String>::new(), "no truncated file, no temporary");
    }

    #[test]
    fn the_budget_is_spent_across_members_not_per_member() {
        let root = scratch("budget-across");
        let mut inbox = Inbox::with_limits(&root, Limits { byte_limit: 10, overwrite: false }).expect("the inbox opens");
        let bytes = archive(&[
            Entry::file("first.bin", vec![1u8; 8]),
            Entry::file("second.bin", vec![2u8; 8]),
        ]);

        let failure = inbox.receive(bytes.as_slice()).expect_err("16 bytes exceed a 10 byte budget");

        assert!(matches!(failure, Error::OverBudget { .. }), "{failure:?}");
        assert_eq!(inbox.names(), ["first.bin"], "the member that fit is kept");
        assert_eq!(inbox.bytes(), 8);
        assert_eq!(read(&root.join("first.bin")), vec![1u8; 8]);
        assert!(!root.join("second.bin").exists());
    }

    #[test]
    fn a_directory_member_is_created_and_listed_but_costs_no_bytes() {
        let root = scratch("directory");
        let bytes = archive(&[
            Entry::directory("holiday"),
            Entry::file("holiday/a.txt", b"a".to_vec()),
        ]);

        let received = receive(&root, bytes.as_slice()).expect("the archive is accepted");

        assert_eq!(received.names, ["holiday", "holiday/a.txt"]);
        assert_eq!(received.bytes, 1, "only payload counts against the budget");
        assert!(root.join("holiday").is_dir());
    }

    #[test]
    fn the_archive_may_arrive_wrapped_in_dvzip() {
        let root = scratch("dvzip");
        let payload = vec![0x5au8; 200 * 1024];
        let plain = archive(&[Entry::file("video.mov", payload.clone())]);
        let mut wrapped = Vec::new();
        {
            let mut encoder = opairdrop_cpio::dvzip::Encoder::new(&mut wrapped);
            encoder
                .write_all(&plain)
                .expect("the archive fits through the encoder");
            encoder.finish().expect("the blocks are flushed");
        }
        assert!(opairdrop_cpio::dvzip::looks_like_block(&wrapped[..4]));

        let received = receive(&root, wrapped.as_slice())
            .expect("the wrapper is unwrapped on the way in");

        assert_eq!(received.names, ["video.mov"]);
        assert_eq!(read(&root.join("video.mov")), payload);
    }

    #[test]
    fn a_member_cut_short_reports_the_archive_rather_than_a_member() {
        let root = scratch("broken");
        let mut bytes = archive(&[Entry::file("a.txt", vec![0x41u8; 256])]);
        // Into the payload, not off the end: a newc header plus the six byte name and its
        // padding is 118 bytes, and the trailer that follows the payload is 120, so 150
        // leaves 32 of the 256 bytes the header promised.
        bytes.truncate(150);

        let failure = receive(&root, bytes.as_slice())
            .expect_err("a cut-short member is not accepted");

        assert!(matches!(failure, Error::Archive(_)), "{failure:?}");
        assert!(root.join("a.txt").symlink_metadata().is_err(), "half a member is not published");
    }

    #[test]
    fn opening_the_inbox_creates_a_missing_directory() {
        let root = scratch("create").join("nested").join("inbox");

        let inbox = Inbox::new(&root).expect("the directory is created on demand");

        assert!(root.is_dir());
        assert_eq!(inbox.directory(), root.as_path());
    }

    #[test]
    fn the_same_inbox_can_take_a_second_transfer() {
        let root = scratch("twice");
        let mut inbox = Inbox::new(&root).expect("the inbox opens");

        inbox
            .receive(archive(&[Entry::file("one.txt", b"1".to_vec())]).as_slice())
            .expect("the first transfer lands");
        inbox
            .receive(archive(&[Entry::file("one.txt", b"2".to_vec())]).as_slice())
            .expect("the second transfer lands");

        assert_eq!(inbox.names(), ["one.txt", "one (1).txt"]);
        assert_eq!(inbox.bytes(), 2);
    }
}
