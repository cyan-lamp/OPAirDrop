//! The one advertisement that makes a receiver answer.
//!
//! # Discovery does not start at mDNS
//!
//! An iPhone listens for this advertisement on Bluetooth, and only after hearing it brings
//! AWDL's AirDrop service up. Before that it is present on the air and deaf to us in a way
//! no packet trace explains: its MIFs arrive, our data frames reach it, a TCP SYN draws a
//! RST, and nothing on port 8770 is listening. A query can therefore be well formed, go out
//! on the wireless every two seconds, have every byte right, and be answered by nobody.
//!
//! That is why this is not an optimisation. It is the layer the rest of the stack stands on,
//! and it carries no data at all — it is a knock, not a delivery.
//!
//! # What is here, and what is Java's
//!
//! Here: the twenty bytes, which are Apple's format rather than a choice; the four settings,
//! which are Apple's too, since the receiver being woken is what answers a connectable
//! advertiser at low-latency intervals; and the state a caller reports.
//!
//! Not here: `BluetoothLeAdvertiser`. The radio calls are Java's, in `BleRadio`, which hands
//! these bytes to the stack and reports the stack's verdict back without deciding anything —
//! the same division as everywhere else on this boundary, where Java owns the socket and
//! Rust owns the bytes. There is no C export for the same reason: the JNI shim is the only
//! caller and lives in this same library.

use std::sync::Mutex;

/// Apple's company identifier, which is what makes the payload parseable on the other side.
pub const COMPANY_ID: u16 = 0x004c;

/// The AirDrop type in Apple's manufacturer data.
const TLV_TYPE: u8 = 0x05;
/// Payload length after the type and this length byte: 0x12 = 18. With the two of them the
/// whole payload is 20 bytes, which still fits an advertisement of 31.
const PAYLOAD_LEN: u8 = 0x12;
/// Always 0x40 in version 3. Apple's own bytes, not a flag this code sets for a reason.
const RESERVED_HEAD: u8 = 0x40;
/// The version that is answered. Version 1 is an older, shorter format.
const VERSION: u8 = 0x03;

/// Bytes in [`manufacturer_data`].
pub const PAYLOAD_BYTES: usize = 2 + PAYLOAD_LEN as usize;

/// `AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY`.
///
/// The interval is not in the API: this is a hint, and every production stack measured
/// sends at 100 ms for it. That is the rate the receiver being woken expects.
pub const MODE_LOW_LATENCY: i32 = 2;

/// `AdvertiseSettings.ADVERTISE_TX_POWER_HIGH`. A knock nobody hears is not a knock.
pub const POWER_HIGH: i32 = 3;

/// Whether the advertiser accepts a connection.
///
/// True because the receiver answers a connectable advertiser: it is the same radio it will
/// use for AWDL, and a receiver that cannot connect back treats the advertisement as noise.
pub const CONNECTABLE: bool = true;

/// `AdvertiseSettings.setTimeout(0)`: advertise until told to stop.
///
/// A timeout would have to be longer than the person takes to pick a device, which is not a
/// number this library can know. Stopping is the page's job, on the way out.
pub const TIMEOUT_MS: i32 = 0;

/// What `BleRadio` answers when it cannot hand the advertisement over.
///
/// The numbers are a contract with `BleRadio.java`, which declares the same constants. They
/// are negative to leave room for the stack's own `ADVERTISE_FAILED_*` codes, which arrive
/// positive through the same channel.
pub mod refusal {
    /// The advertisement was handed over; the stack's verdict follows.
    pub const NONE: i32 = 0;
    /// `BLUETOOTH_ADVERTISE` (or `BLUETOOTH_CONNECT`, which guards the adapter queries on
    /// API 31+) was not granted. The remedy is a permission dialog, not a retry.
    pub const NO_PERMISSION: i32 = -1;
    /// The device has no Bluetooth adapter at all.
    pub const NO_ADAPTER: i32 = -2;
    /// Bluetooth is switched off. Turning it on needs a person: the system permission and,
    /// on recent versions, a user gesture.
    pub const DISABLED: i32 = -3;
    /// The adapter exists but does not do LE advertising — the chip is the limit.
    pub const UNSUPPORTED: i32 = -4;
    /// `getBluetoothLeAdvertiser()` answered null: the stack refused to make an advertiser.
    pub const NO_ADVERTISER: i32 = -5;
}

/// The meaning of one verdict, in words a log can carry.
///
/// Positive codes are `AdvertiseCallback.ADVERTISE_FAILED_*`. They are written out rather
/// than passed through because `error 4` says nothing to whoever reads the log at the
/// moment the other phone stays silent.
pub fn explained(code: i32) -> String {
    let text = match code {
        refusal::NONE => "advertising",
        refusal::NO_PERMISSION => {
            "BLUETOOTH_ADVERTISE not granted (adb shell pm grant com.oshare.airdrop \
             android.permission.BLUETOOTH_ADVERTISE)"
        }
        refusal::NO_ADAPTER => "no Bluetooth adapter",
        refusal::DISABLED => "Bluetooth is off",
        refusal::UNSUPPORTED => "adapter does not support LE advertising",
        refusal::NO_ADVERTISER => "getBluetoothLeAdvertiser returned null",
        1 => "data too large for one advertisement",
        2 => "too many advertisers already registered",
        3 => "already started",
        4 => "internal error in the Bluetooth stack",
        5 => "feature unsupported by this stack",
        _ => return format!("unknown verdict {code}"),
    };
    text.to_string()
}

/// Where the advertisement has got to.
///
/// Three states rather than two because handing something to a radio is asynchronous: a
/// caller that reported "advertising" the moment `startAdvertising` returned would be
/// describing an intention, and one that reported "failed" for a stack that is about to say
/// yes would be worse.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum State {
    /// Nothing is registered with the stack.
    Idle,
    /// Handed over; the stack has not said whether it took it.
    Starting,
    /// The stack is transmitting it.
    Advertising,
    /// It could not be started, for the stated reason.
    Failed(String),
}

impl State {
    /// True while something is registered, whatever the stack has said about it.
    ///
    /// A second registration while one is live fails with `ALREADY_STARTED`, so a caller
    /// asks this before trying again rather than learning it from the stack.
    pub fn is_awake(&self) -> bool {
        matches!(self, State::Starting | State::Advertising)
    }

    /// One line for a log, and the only thing that crosses to Java.
    pub fn line(&self) -> String {
        match self {
            State::Idle => "idle".to_string(),
            State::Starting => "starting".to_string(),
            State::Advertising => "advertising".to_string(),
            State::Failed(reason) => format!("failed: {reason}"),
        }
    }
}

/// The live state.
///
/// A `Mutex` and not atomic state because the transitions have to be ordered against the
/// verdict coming back from Java: a verdict that arrived while a newer attempt was already
/// under way would otherwise set the state of the wrong attempt. The lock is never held
/// across a call into Java — the inline path delivers the verdict synchronously, on this
/// same thread, and would deadlock against itself.
static STATE: Mutex<State> = Mutex::new(State::Idle);

/// Locks the state, ignoring poisoning.
///
/// Poisoning cannot happen here: the release profile is `panic = "abort"`, so a panic while
/// holding this lock ends the process rather than leaving the state half-written.
fn state() -> std::sync::MutexGuard<'static, State> {
    match STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

/// What the advertisement is doing now.
pub fn current() -> State {
    state().clone()
}

/// The current state as one line.
pub fn line() -> String {
    state().line()
}

/// Records that the advertisement is being handed over, and answers the line to report.
pub fn starting() -> String {
    let mut state = state();
    *state = State::Starting;
    state.line()
}

/// Records the stack's answer and answers the line to report.
///
/// A verdict that arrives while nothing is starting is a stale callback — the stack was
/// told to stop after it had already decided — and is dropped rather than allowed to
/// overwrite the state of a later attempt.
pub fn verdict(code: i32) -> String {
    let mut state = state();
    match code {
        refusal::NONE => {
            if matches!(*state, State::Starting) {
                *state = State::Advertising;
            }
        }
        code => {
            if matches!(*state, State::Starting | State::Advertising) {
                *state = State::Failed(explained(code));
            }
        }
    }
    state.line()
}

/// Records that the advertisement was taken down.
pub fn stopped() -> String {
    let mut state = state();
    *state = State::Idle;
    state.line()
}

/// Records a failure that happened before anything reached the stack.
///
/// For the failures only this side can name — the Java radio class missing from the class
/// path, say — rather than for a verdict, which has a code and goes through [`verdict`].
pub fn given_up(reason: &str) -> String {
    let mut state = state();
    *state = State::Failed(reason.to_string());
    state.line()
}

/// The twenty bytes of manufacturer data for `tag`.
///
/// The layout, as Apple writes it:
///
/// ```text
/// 05 12                       AirDrop, 18 bytes after this
/// 40                          reserved, 0x40 in version 3
/// tt tt tt                    tag, three bytes, random per run
/// 00 00 00 00                 reserved
/// 03                          version 3
/// hh hh x4                    four contact hashes, two bytes each
/// 00                          reserved
/// ```
///
/// The tag identifies one advertising session, so two devices in the same room do not look
/// identical; nothing on the receiving side is expected to remember it between sessions.
///
/// The four hashes are zero, and that is the honest limit of this implementation: a hash is
/// of an Apple ID, and there is none here to hash. A receiver set to "everyone" wakes on the
/// advertisement itself and never reads them; one set to "contacts only" does not wake, and
/// no value invented in this function would change that.
pub fn manufacturer_data(tag: [u8; 3]) -> [u8; PAYLOAD_BYTES] {
    let mut out = [0u8; PAYLOAD_BYTES];
    out[0] = TLV_TYPE;
    out[1] = PAYLOAD_LEN;
    out[2] = RESERVED_HEAD;
    out[3] = tag[0];
    out[4] = tag[1];
    out[5] = tag[2];
    // out[6..10]: reserved, left zero.
    out[10] = VERSION;
    // out[11..19]: the four contact hashes, left zero; see above.
    // out[19]: reserved, left zero.
    out
}

/// A fresh tag.
///
/// Drawn from `/dev/urandom`, which every Android release has. A failure falls back to the
/// clock: the tag only has to differ from the device next to this one, and there is no
/// security property resting on it — a guessable tag wakes the same phones a random one does.
pub fn random_tag() -> [u8; 3] {
    use std::io::Read;

    let mut tag = [0u8; 3];
    if let Ok(mut source) = std::fs::File::open("/dev/urandom") {
        if source.read_exact(&mut tag).is_ok() {
            return tag;
        }
    }
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.subsec_nanos())
        .unwrap_or(0);
    let bytes = now.to_le_bytes();
    tag.copy_from_slice(&bytes[..3]);
    tag
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The state is one static for the process, and the harness runs tests in parallel:
    /// without this, every test below would be racing the others through it.
    static IN_TURN: Mutex<()> = Mutex::new(());

    fn in_turn() -> std::sync::MutexGuard<'static, ()> {
        match IN_TURN.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    #[test]
    fn the_payload_is_twenty_bytes_in_apples_order() {
        let payload = manufacturer_data([0xa1, 0xb2, 0xc3]);
        assert_eq!(payload.len(), 20);
        assert_eq!(&payload[..3], &[0x05, 0x12, 0x40]);
        assert_eq!(&payload[3..6], &[0xa1, 0xb2, 0xc3]);
        assert_eq!(&payload[6..10], &[0, 0, 0, 0]);
        assert_eq!(payload[10], 0x03);
    }

    #[test]
    fn the_contact_hashes_are_zero_because_there_is_no_apple_id_to_hash() {
        let payload = manufacturer_data([1, 2, 3]);
        assert_eq!(&payload[11..], &[0u8; 9]);
    }

    #[test]
    fn the_payload_fits_a_legacy_advertisement_once_apple_is_added() {
        // Two bytes of company identifier in front, three of advertisement header behind:
        // 20 + 2 + 3 is 25, and a legacy advertisement is 31.
        let payload = manufacturer_data([9, 9, 9]);
        assert!(payload.len() + 2 + 3 <= 31);
    }

    #[test]
    fn a_fresh_tag_differs_between_draws() {
        // Three random bytes match once in 16 million draws, so a collision here would mean
        // the source is not read at all.
        assert_ne!(random_tag(), random_tag());
    }

    #[test]
    fn giving_up_clears_starting_so_it_cannot_get_stuck() {
        let _turn = in_turn();
        stopped();
        assert_eq!(starting(), "starting");
        assert!(current().is_awake());
        // 评审 P1-20：new_bytes 抛异常 / BleRadio.start 抛异常时，stack 永远不会回
        // verdict。如果这条路径不回滚，is_awake() 对 Starting 为真，状态就永久
        // 停在「叫醒中」，之后再点接收也不会重发广播。
        let line = given_up("BleRadio.start threw before the stack answered");
        assert!(line.starts_with("failed: "), "{line}");
        assert!(!current().is_awake());
        assert_eq!(
            current(),
            State::Failed("BleRadio.start threw before the stack answered".into())
        );
    }

    #[test]
    fn a_handed_over_advertisement_is_only_advertising_once_the_stack_says_so() {
        let _turn = in_turn();
        stopped();
        assert_eq!(starting(), "starting");
        assert!(current().is_awake());
        assert_eq!(verdict(refusal::NONE), "advertising");
    }

    #[test]
    fn a_refusal_is_reported_with_its_reason() {
        let _turn = in_turn();
        stopped();
        starting();
        let line = verdict(refusal::NO_PERMISSION);
        assert!(
            line.starts_with("failed: BLUETOOTH_ADVERTISE not granted"),
            "{line}"
        );
        assert!(!current().is_awake());
    }

    #[test]
    fn a_stack_failure_is_written_out_rather_than_numbered() {
        let _turn = in_turn();
        stopped();
        starting();
        assert_eq!(
            verdict(3),
            "failed: already started",
            "error 3 alone says nothing in a log"
        );
    }

    #[test]
    fn a_stale_verdict_does_not_land_on_a_later_attempt() {
        let _turn = in_turn();
        stopped();
        // A callback left over from an attempt that was already stopped.
        assert_eq!(verdict(refusal::NONE), "idle");
        assert_eq!(current(), State::Idle);
    }

    #[test]
    fn stopping_clears_a_failure() {
        let _turn = in_turn();
        given_up("BleRadio is not on the class path");
        assert!(!current().is_awake());
        assert_eq!(stopped(), "idle");
    }
}
