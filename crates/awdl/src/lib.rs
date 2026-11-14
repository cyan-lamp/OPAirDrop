//! The link layer: 802.11 frames, AWDL's data header, and the availability-window
//! schedule.
//!
//! # Where this crate sits
//!
//! The other crates start at IP and go up: `http` parses a request, `bplist` reads a
//! body, `cpio` builds an archive. This one starts at IP and goes **down**. It is the
//! layer whose absence made every earlier attempt fail the same way — the radio
//! transmitted, the peer heard, and nothing arrived, because the frame that reached the
//! antenna was not a frame the peer's IP stack would accept.
//!
//! ```text
//!   airdrop / http / bplist / cpio      the protocol
//!   --------------------------------    a TCP socket on awdltun0
//!   awdl                                this crate
//!   --------------------------------
//!   802.11 on channel 149               wonder.ko, wondertap0
//! ```
//!
//! # Why a rewrite and not the predecessor
//!
//! The legacy data plane is about 8 000 lines of Java across `AwdlData`, `AwdlFrame` and
//! `AwdlTx`. Most of it is throughput work: sixteen-frame windows, five layers of
//! aggregation, MCS and bandwidth adaptation, retransmission storms, MSS clamping. That
//! work took a link from 80 KB/s to 1 MB/s, and §5.3 of the task already says throughput
//! is not the goal.
//!
//! What is not optional is small and is all here:
//!
//! * [`frame`] — the sixteen-byte AWDL data header between SNAP and the packet. Without
//!   it a frame is heard and never delivered.
//! * [`action`] — the presence frame. Without it a node is not part of the network and no
//!   peer resolves it.
//! * [`schedule`] — where in the sequence a node is, which every frame reports.
//! * [`link`] — the two directions, and the three tests that keep a monitor interface's
//!   traffic out of the kernel.
//!
//! [`linux`] holds the two descriptors those run on. Everything above it is testable
//! without a radio, which is the reason the split exists.
//!
//! # The device half
//!
//! [`linux`] needs `CAP_NET_RAW` and `/dev/tun`, so the binary built from this crate runs
//! as root, started by the app rather than inside it. The app keeps the protocol: it
//! listens on `8770` at the far end of the tunnel, where it does not have to know that a
//! radio is involved.

#![deny(unsafe_op_in_unsafe_fn)]
#![deny(missing_docs)]

pub mod action;
pub mod frame;
pub mod link;
pub mod mac;
pub mod schedule;

// Android is Linux, but `target_os` is "android" there, and the phone is the half that
// matters. Both are gated on the same module: nothing in it is Linux-specific beyond
// `AF_PACKET` and `/dev/tun`, which Android has.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod linux;

pub use action::NodeInfo;
pub use frame::{data_frame, parse as parse_frame, Inbound};
pub use link::{Link, Radio, Stats, Tunnel};
pub use mac::Mac;
pub use schedule::Schedule;

/// The channel this crate is built for: UNII-3, the one `wonder.ko` pins the radio to.
///
/// `pas_rx_fixed_channel()` in the patched driver returns `-EOPNOTSUPP` for anything but
/// 5745 MHz, so a different number here produces a link that comes up and carries
/// nothing.
pub const SOCIAL_CHANNEL: u8 = 149;

/// The frequency of [`SOCIAL_CHANNEL`], in MHz.
pub const SOCIAL_FREQ_MHZ: u32 = 5745;
