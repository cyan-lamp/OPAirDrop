//! What moves between the tunnel and the radio, and when.
//!
//! # The two directions
//!
//! * **Up:** a packet the kernel wrote to the tunnel is wrapped in an AWDL data frame and
//!   handed to the radio.
//! * **Down:** a frame the radio received is unwrapped and written to the tunnel, where
//!   the kernel's IPv6 stack — and above it this project's own TCP listener — does the
//!   rest.
//!
//! Nothing in between is protocol-aware. The link carries IPv6 and does not look past its
//! header, which is the property that lets [`crate::frame`] be the only module that knows
//! what an AWDL data frame is.
//!
//! # Filtering
//!
//! A monitor interface hands up every frame on the channel, including frames between two
//! other nodes and echoes of this node's own transmissions. Three tests, in this order:
//!
//! 1. **Echo** — address 2 is this node. A monitor tap that reports what it sent would
//!    otherwise put every packet back into the kernel it came from.
//! 2. **Not ours** — address 1 is neither this node nor a group. Someone else's
//!    conversation, and delivering it would answer for a node that is not us.
//! 3. **Not IPv6** — a management or action frame, counted and dropped.
//!
//! # Timing
//!
//! Two clocks, and getting either wrong costs the link silently.
//!
//! **Presence.** A PSF goes out every [`PSF_INTERVAL_TU`] time units and a MIF at the
//! middle of every slot, for as long as the link is up. The two are not interchangeable:
//! a PSF carries the clock and nothing else, and AWDL-17 has a peer refuse to treat a
//! sender as a node at all until it has a name for it. A run that announces the name once
//! at the start works only for peers that were already listening.
//!
//! **Data.** A slot is [`SLOT_TU`] time units wide and a peer is not in its receive window
//! for all of it. A frame sent at a phase of the slot the peer is asleep for is simply not
//! heard, and the cost is a whole slot before the same data is tried again — measured here
//! at three replies out of twenty before the queue below existed. So data frames are
//! queued and released only inside the window, never sent straight from the tunnel path.
//!
//! Which part of the slot that window is in is not a constant: a phase offset swept across
//! seven values found no peak at all, so a small packet goes into all four availability
//! windows of the slot, one AW apart. Four chances against one is the difference between a
//! link that delivers one ping in six and one that carries a handshake; the predecessor
//! measured exactly that, and [`AW_BURST`] records it.

use core::fmt;
use std::collections::VecDeque;
use std::io;

use crate::action::{self, NodeInfo};
use crate::frame::{self, Inbound};
use crate::mac::Mac;
use crate::schedule::{MapVerdict, Schedule, AW_PERIOD_TU, PSF_INTERVAL_TU, SLOT_TU, TU_US};

/// The radio, as this layer needs it: raw 802.11 frames in and out.
pub trait Radio {
    /// One frame, or `None` when nothing is waiting.
    fn recv(&mut self, buf: &mut [u8]) -> io::Result<Option<usize>>;

    /// Hand one frame to the driver. A frame that is refused is not an error worth
    /// stopping for: the next presence frame will be along in 110 time units.
    fn send(&mut self, frame: &[u8]) -> io::Result<()>;
}

/// The tunnel, as this layer needs it: bare IPv6 packets in and out.
pub trait Tunnel {
    /// One packet, or `None` when nothing is waiting.
    fn recv(&mut self, buf: &mut [u8]) -> io::Result<Option<usize>>;

    /// Hand one packet to the kernel.
    fn send(&mut self, packet: &[u8]) -> io::Result<()>;
}

/// What has crossed the link, in totals a status line can print.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    /// IPv6 packets wrapped and transmitted.
    pub tx_packets: u64,
    /// Bytes of IPv6 payload transmitted, before framing.
    pub tx_bytes: u64,
    /// Data frames handed to the radio.
    ///
    /// Apart from [`Stats::tx_packets`] because the two answer different questions: a
    /// packet is one packet however many copies of it go out, and a counter that cannot
    /// show the copies cannot show that the repetition across the slot ran at all.
    pub tx_frames: u64,
    /// Data frames accepted from the radio and written to the tunnel.
    pub rx_packets: u64,
    /// Bytes of IPv6 payload received.
    pub rx_bytes: u64,
    /// Frames from this node, dropped as echoes.
    pub rx_echo: u64,
    /// Frames between other nodes, dropped as not ours.
    pub rx_not_ours: u64,
    /// Well-formed frames that were not IPv6 data.
    pub rx_other: u64,
    /// Frames that did not parse at all.
    pub rx_malformed: u64,
    /// Presence frames sent.
    pub presence: u64,
    /// Of those, the ones that named the host: the mid-slot MIFs.
    ///
    /// Counted apart from [`Stats::presence`] because the two are not interchangeable to a
    /// peer. A PSF tells it where our clock is; only a MIF tells it who we are, and a run
    /// whose `psf` climbs while `mif` stays at its opening value is a run that is
    /// announcing itself to nobody.
    pub mif: u64,
    /// Presence frames from the peer that the clock was adopted from.
    pub anchored: u64,
    /// Anchors abandoned because the device they named went silent.
    ///
    /// The counter that would have made this defect visible at 03:40 rather than at 09:00.
    /// A run whose `anchored` has stopped climbing while `rx_other` keeps rising is hearing
    /// peers and refusing every one of them, and this number is what tells that apart from
    /// the peers having genuinely left: it only rises when the release rule ran.
    pub anchor_released: u64,
    /// Frames the radio refused.
    pub tx_errors: u64,
    /// Packets dropped for want of queue space while waiting for a window.
    ///
    /// Separate from [`Stats::tx_errors`] because the two say opposite things: an error
    /// is the radio rejecting a frame it was handed, a drop is a frame never built
    /// because the offered load outran the slot.
    pub tx_dropped: u64,
    /// Packets the kernel refused.
    pub rx_errors: u64,
    /// Peer maps accepted and now gating transmission.
    pub maps_adopted: u64,
    /// Peer maps that agreed but arrived inside the hold interval.
    pub maps_held: u64,
    /// Peer maps naming no slot on this channel, refused.
    ///
    /// Not a cosmetic counter: a hop between channels shows up here first, and a run
    /// whose `held` climbs while `adopted` stays at one is a peer whose map is flapping
    /// between the shapes it announces.
    pub maps_refused: u64,
    /// Total microseconds between a packet entering the queue and its first copy going
    /// out, summed over packets.
    ///
    /// **This is the whole of this node's share of a round trip.** The kernel writes an
    /// acknowledgement the instant a segment lands in the tunnel; the peer cannot send
    /// again until that acknowledgement is on the air. Whatever happens between those two
    /// instants — waiting for a window this node owns, queueing behind a copy that is not
    /// due yet — is read by the peer as its own latency, and there is no way for it to
    /// tell the difference.
    ///
    /// Measured 2026-11-15 rather than assumed: a run at 102 KB/s spent 24.4 ms per two
    /// segments, and the question this answers is whether that is ours or the phone's.
    pub tx_wait_sum_us: u64,
    /// The longest single wait, which is what a stalled window looks like.
    pub tx_wait_max_us: u64,
    /// How many waits are in the sum.
    pub tx_wait_n: u64,
    /// Those waits, bucketed by [`WAIT_BUCKETS_US`]; the last bucket is "over the top".
    pub tx_wait_buckets: [u64; 7],
}

impl fmt::Display for Stats {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "tx={}p/{}B/{}f rx={}p/{}B echo={} foreign={} other={} bad={} psf={} mif={} anchored={} released={} txerr={} drop={} rxerr={} maps={}/{}/{}",
            self.tx_packets,
            self.tx_bytes,
            self.tx_frames,
            self.rx_packets,
            self.rx_bytes,
            self.rx_echo,
            self.rx_not_ours,
            self.rx_other,
            self.rx_malformed,
            self.presence,
            self.mif,
            self.anchored,
            self.anchor_released,
            self.tx_errors,
            self.tx_dropped,
            self.rx_errors,
            self.maps_adopted,
            self.maps_held,
            self.maps_refused
        )?;
        // Silent until something has actually been sent: a link that has never carried a
        // packet should not print a row of zeroes that reads like a measurement.
        if self.tx_wait_n > 0 {
            let avg = self.tx_wait_sum_us / self.tx_wait_n;
            let buckets: Vec<_> = self.tx_wait_buckets.iter().map(u64::to_string).collect();
            write!(
                f,
                " wait={}us/{}us x{} [{}]",
                avg,
                self.tx_wait_max_us,
                self.tx_wait_n,
                buckets.join(",")
            )?;
        }
        Ok(())
    }
}

impl Stats {
    /// File one packet's wait under the bucket it belongs to.
    fn record_wait(&mut self, wait_us: u64) {
        self.tx_wait_sum_us += wait_us;
        self.tx_wait_n += 1;
        self.tx_wait_max_us = self.tx_wait_max_us.max(wait_us);
        let slot = WAIT_BUCKETS_US
            .iter()
            .position(|&edge| wait_us < edge)
            .unwrap_or(WAIT_BUCKETS_US.len());
        self.tx_wait_buckets[slot] += 1;
    }
}

/// How many consecutive whole-slot phase errors to refuse before one is believed.
///
/// A whole slot of error says the frame was read a slot after it arrived — this loop's
/// own queue, not the peer's clock. Adopting that reading aims the next frame a slot off
/// the peer's window and holds it there until the following sample undoes it, which is
/// exactly the stall a transfer shows as a pause. Refusing forever is the other failure:
/// a clock that can never move again is a link that drifts out of phase and stays there,
/// so the refusal is bounded rather than permanent.
const ANCHOR_REJECT_LIMIT: u8 = 8;

/// How long the node whose clock is kept may stay silent before the anchor is released.
///
/// A live peer sends a presence frame every [`PSF_INTERVAL_TU`](crate::schedule::PSF_INTERVAL_TU)
/// — 110 time units, about 113 ms — so two seconds is eighteen missed frames. That is not a
/// scheduling hiccup, it is a device that has gone; and it is short enough that a phone
/// whose AWDL address was re-randomised while this daemon was up is followed again before
/// the share sheet that opened on it finishes scanning.
///
/// Without a release the anchor is a one-way door. `peer` is set from the first action
/// frame the process ever hears and, before this constant existed, was never cleared: a
/// run that started at 03:35 and locked onto a phone one minute later was still naming
/// that phone at 09:00, having dropped every one of the ~12 presence frames per second
/// arriving from the device actually in the room. The visible symptom is not silence but
/// a link that looks healthy — `err` frozen at its last good value, `phy` correct,
/// `txerr=0`, a rising frame count — while every frame it permits goes out in one of the
/// two slots of sixteen that the departed peer's map left open, at whatever phase drift
/// has put there by now.
const PEER_SILENCE_US: u64 = 2_000_000;

/// AWDL-16: how far clear of the slot edge at its head a unicast frame must stay.
///
/// The number is the protocol's, not a tuning knob. A frame that starts before it lands
/// while the peer is still closing out the previous slot.
const UNICAST_GUARD_TU: u8 = 3;

/// The same for a frame addressed to a group.
///
/// Wider because every listener's clock differs from ours by a different amount, and a
/// multicast frame has no ack to tell us it landed.
const MULTICAST_GUARD_TU: u8 = 16;

/// The last time unit inside a slot a frame may start in.
///
/// Eight TU short of the edge, so the frame and the ack it draws both finish before the
/// boundary. The predecessor measured its way up from fourteen to fifty-six; the peer stays
/// awake for nearly the whole slot, and the limit is our own airtime, not its window.
const WINDOW_LAST_TU: u8 = 56;

/// How many frames one window will carry before the rest wait for the next.
///
/// Not an airtime bound — [`Link::flush`] stops by itself when the window's airtime runs
/// out, and the frames this limit actually governs are the small ones, which cost almost
/// none. Sixteen was chosen for a link whose traffic was a handshake. A receiving transfer
/// is a stream of acknowledgements instead: about 280 a second, against one window every
/// slot (65.5 ms) — sixteen windows a second, so sixteen frames each is 245 and short of
/// what arrives. Thirty-two leaves the room the acknowledgements need.
const WINDOW_FRAMES: usize = 32;

/// How many availability windows of one slot a small packet is repeated into.
///
/// A slot is four AWs and the guard puts every frame in the first one. Which of the four
/// a peer is listening in is not constant — a constant phase offset was swept across seven
/// values and found no peak at all — so the same packet is sent in all four, one AW apart,
/// and one of them lands. The predecessor's log settled this beyond argument: a relayed SYN
/// left at 38.112 and the peer's SYN-ACK came back at 63.794, twenty-five seconds and eight
/// kernel retransmissions later, while a single UDP datagram sent a second earlier came
/// back as an ICMP port-unreachable within the second. Each frame was heard only if it fell
/// in one of the four windows, and one frame per event meant three were never tried.
const AW_BURST: u8 = 4;

/// The size below which a packet is repeated across the slot.
///
/// Not the header-only line, which was tried first and failed: the 281-byte segment
/// carrying `POST /Discover` was handed over three times four hundred milliseconds apart
/// and never acknowledged. A handshake is not only its SYN — it is a thousand bytes of
/// records in each direction, every one as unrepeated as the SYN was. Four hundred and
/// ninety-six covers the discovery exchange and stops short of the 1.4 KB segments a file
/// transfer uses, where a duplicate really is air spent on bytes the peer already has.
///
/// Public because the daemon prints it beside the configured copy count: a sweep that
/// reads back "copies=4 over=496B" needs both halves to say what a run actually did.
pub const BURST_MAX: usize = 496;

/// The most copies a slot can hold, so the most [`AW_BURST`] may be raised to.
///
/// Four, one per availability window. A fifth copy could only be a copy in the next slot,
/// and the next slot is the one thing the window arithmetic cannot promise: the peer's own
/// map puts five of its sixteen slots on the social channel, and the rest elsewhere. Asking
/// for more than four is asking for a copy whose window nobody can name.
const BURST_LIMIT: u8 = SLOT_TU / AW_PERIOD_TU;

/// How many wrapped frames may wait for their window before new ones are dropped.
///
/// Bounded because the far side of this queue is a TCP stream that will retransmit
/// anything lost: a deeper queue buys delay, not throughput, and on a link that stalls for
/// a slot at a time it buys a stall's worth of stale data.
const QUEUE_LIMIT: usize = 64;

/// The edges of the queue-wait histogram, in microseconds.
///
/// Eight milliseconds is the one that matters: a wait shorter than that is a frame that
/// caught the window already open, and a wait longer than it is a frame that missed one.
/// At the slot length — 65.5 ms, sixteen windows a second — a missed window costs at
/// least a guard's worth of the next slot, so the two populations do not overlap and the
/// split between them says which is happening without anyone having to reason about it.
const WAIT_BUCKETS_US: [u64; 6] = [500, 1_000, 2_000, 4_000, 8_000, 16_000];

/// The guard a frame of this class has to keep from the head of a slot.
fn guard_tu(group: bool) -> u8 {
    if group {
        MULTICAST_GUARD_TU
    } else {
        UNICAST_GUARD_TU
    }
}

/// How many copies a packet of this size gets, when `burst` is the configured count and
/// the link has air to spare. See [`Link::copies_now`] for the state that overrides it.
fn copies_for(packet_len: usize, burst: u8) -> u8 {
    if packet_len <= BURST_MAX {
        burst
    } else {
        1
    }
}


/// A packet waiting for the slot phases that may carry it.
struct Pending {
    /// The IPv6 packet, still unwrapped.
    ///
    /// Held unbuilt because every copy carries a fresh sequence number: three copies of one
    /// number are discarded as duplicates one layer above the radio, which would look
    /// exactly like three windows that were tried and did not work.
    packet: Vec<u8>,
    /// Where it goes, and whether that is a group, which decides how wide the guard is.
    ///
    /// Remembered rather than re-read out of the packet: the address sits behind an IPv6
    /// header whose parsing belongs to [`crate::mac`], and one caller already did it.
    dst: Mac,
    group: bool,
    /// How many copies this packet was given when it was queued.
    ///
    /// Remembered rather than recomputed, because it is a decision taken at one instant
    /// about the state of the queue and read at another: a packet queued while the air
    /// was busy has one copy and must not be mistaken for a burst whose first copy has
    /// already gone.
    copies: u8,
    /// Copies still to be handed over, of that number.
    remaining: u8,
    /// The instant the next copy may start, or zero for "the next window that opens".
    ///
    /// Copies after the first are placed an availability window after the one before, so
    /// this is what keeps them apart once the first has gone.
    due_us: u64,
    /// When this packet was read out of the tunnel, or zero for "not recorded".
    ///
    /// The other end of the wait that [`Stats::tx_wait_sum_us`] measures. Zero rather
    /// than a separate flag because the clock this link runs on is monotonic from boot
    /// and never passes zero, so the two cases cannot be confused; the unit tests, which
    /// do run at zero, therefore leave the statistic alone.
    queued_us: u64,
}

/// The bridge.
pub struct Link<R: Radio, T: Tunnel> {
    radio: R,
    tunnel: T,
    node: NodeInfo,
    schedule: Schedule,
    rate_mbps: u8,
    seq: u16,
    awdl_seq: u16,
    stats: Stats,
    /// When the last PSF went out, and whether any has.
    last_presence_us: Option<u64>,
    /// When the next MIF is due, if one is scheduled.
    next_mif_us: Option<u64>,
    /// The node whose clock this link keeps, once one has been heard.
    ///
    /// One at a time. Adopting every sender's phase in range would put us on a
    /// superframe that no single peer is on, which is indistinguishable from being on
    /// none; the old implementation learned this the expensive way, one address per run.
    ///
    /// Not one *forever*, which is the correction: see [`PEER_SILENCE_US`]. An anchor that
    /// can never be released is an anchor held for a device that has left, and the gate
    /// keeps obeying that device's slot map while the phone in the room sends its presence
    /// frames into the floor.
    peer: Option<Mac>,
    /// When the node named by `peer` was last heard, in microseconds on the schedule's clock.
    ///
    /// Zero until one is chosen. Only meaningful while `peer` is `Some`.
    last_anchor_us: u64,
    /// How far off the peer's boundary this node was, in TU, at the last frame.
    ///
    /// Positive means the peer's boundary is later than ours. The one number that says
    /// whether the clock is converging rather than merely being written.
    phase_err_tu: i32,
    /// The frequency the driver stamped on the last frame that carried one, in MHz.
    ///
    /// Not the channel this node announces: the channel this node's radio was *on* when a
    /// frame arrived. The two are separate because nothing in this crate tunes the radio,
    /// so on a single-radio device the second follows the station interface and can sit
    /// anywhere. A schedule built on a channel the radio is not on is a list of windows
    /// that are all closed, and the loss it produces is indistinguishable from a peer that
    /// went away — which is why it is measured rather than assumed.
    phy_mhz: Option<u16>,
    /// Whole-slot refusals since the last adoption. See [`ANCHOR_REJECT_LIMIT`].
    anchor_rejects: u8,
    /// Wrapped frames waiting for a slot phase the peer is listening in.
    queue: VecDeque<Pending>,
    /// How many copies of a small packet go out, one availability window apart.
    ///
    /// A field rather than a constant so a run can vary it without a rebuild: whether the
    /// peer's window is where the repeat assumes it is, is a question only the air
    /// answers, and the cheapest way to ask is to send more of them.
    burst: u8,
}

impl<R: Radio, T: Tunnel> Link<R, T> {
    /// Assemble a link. `now_us` anchors the schedule, so the caller decides which clock
    /// the sequence runs on — it only has to be monotonic.
    pub fn new(radio: R, tunnel: T, node: NodeInfo, now_us: u64, rate_mbps: u8) -> Self {
        let schedule = Schedule::anchored(now_us, node.channel);
        Link {
            radio,
            tunnel,
            node,
            schedule,
            rate_mbps,
            seq: 0,
            awdl_seq: 0,
            stats: Stats::default(),
            last_presence_us: None,
            next_mif_us: None,
            peer: None,
            last_anchor_us: 0,
            phase_err_tu: 0,
            phy_mhz: None,
            anchor_rejects: 0,
            queue: VecDeque::new(),
            burst: AW_BURST,
        }
    }

    /// Change how many copies a small packet is repeated into.
    ///
    /// Only meaningful before the first packet is queued: what a queued packet has left
    /// is fixed at the count it was admitted with, and lowering this afterwards leaves
    /// those copies to run out on their own schedule.
    pub fn set_burst(&mut self, copies: u8) {
        self.burst = copies.clamp(1, BURST_LIMIT);
    }

    /// How many copies a small packet is repeated into.
    pub fn burst(&self) -> u8 {
        self.burst
    }

    /// The schedule, for a status line.
    pub fn schedule(&self) -> &Schedule {
        &self.schedule
    }

    /// The totals so far.
    pub fn stats(&self) -> Stats {
        self.stats
    }

    /// The node this link announces itself as.
    pub fn node(&self) -> &NodeInfo {
        &self.node
    }

    /// The radio, for a caller that has to poll its descriptor alongside the tunnel's.
    pub fn radio(&self) -> &R {
        &self.radio
    }

    /// The tunnel, likewise.
    pub fn tunnel(&self) -> &T {
        &self.tunnel
    }

    /// Wrap one packet from the tunnel and queue it for its slot phase.
    ///
    /// The destination address comes from the packet's own IPv6 header: a unicast
    /// link-local address carries a modified EUI-64, and a multicast group maps onto
    /// `33:33`. A packet whose destination implies no MAC — a privacy address, or a
    /// global one — is dropped, because the AWDL link carries neither.
    ///
    /// Nothing is transmitted here. See [`Link::flush`] for why.
    ///
    /// `now_us` is the instant the packet was read, on the schedule's clock. It is the
    /// other end of the wait the status line reports, and it is taken here rather than
    /// inside because the caller has already read the clock for the drain — see the
    /// matching argument on [`Link::down`].
    pub fn up(&mut self, buf: &mut [u8], now_us: u64) -> io::Result<bool> {
        let Some(len) = self.tunnel.recv(buf)? else {
            return Ok(false);
        };
        let packet = &buf[..len];
        if packet.len() < 40 || packet[0] >> 4 != 6 {
            // The tunnel is configured for IPv6 only; anything else is a routing mistake
            // above this layer and there is no frame to build for it.
            return Ok(false);
        }
        let mut dst_addr = [0u8; 16];
        dst_addr.copy_from_slice(&packet[24..40]);
        let Some(dst) = Mac::for_ipv6(&dst_addr) else {
            return Ok(false);
        };
        if self.queue.len() >= QUEUE_LIMIT {
            // Counted, not silently dropped: a queue that is full while `flush` is running
            // means the window is narrower than the offered load, and the number is the
            // only way to tell that apart from a peer that stopped answering.
            self.stats.tx_dropped += 1;
            return Ok(true);
        }

        let copies = self.copies_now(len);
        self.queue.push_back(Pending {
            packet: packet.to_vec(),
            dst,
            group: dst.is_group(),
            copies,
            remaining: copies,
            due_us: 0,
            queued_us: now_us,
        });
        Ok(true)
    }

    /// How many copies this packet is worth, given what is already waiting.
    ///
    /// The repetition across a slot is bought with airtime on the one channel the peer is
    /// also using, and it is worth that air only while there is some to spare. The four
    /// copies were measured on handshake traffic, which is sparse and small. A TCP stream
    /// is neither: a 162 MB transfer offers about 280 acknowledgements a second, every one
    /// of them under [`BURST_MAX`], so four copies each is a thousand frames a second
    /// aimed at a window that releases [`WINDOW_FRAMES`] of them. Measured on the phone
    /// 2026-11-15: `tx=13318p/53017f` against `drop=164899` — ninety-two per cent of
    /// everything the tunnel offered was dropped for want of queue space, and what got
    /// dropped was chosen by arrival order, so the SYN-ACK and the reply that ends a
    /// transfer queued behind sixty-four acknowledgements and were discarded with them.
    /// The peer then waited an RTO it did not have to wait, and the transfer that had
    /// already finished on this side stayed "正在发送" on the other.
    ///
    /// So the copies are spent only when nothing is waiting. A queue already holding a
    /// window's worth says the airtime is spoken for, and one copy is the honest number
    /// there: a lost acknowledgement costs a retransmit the peer was going to make
    /// anyway, while a dropped SYN-ACK costs a round trip on a link whose timer is
    /// seconds long.
    fn copies_now(&self, len: usize) -> u8 {
        if self.queue.len() >= WINDOW_FRAMES {
            1
        } else {
            copies_for(len, self.burst)
        }
    }

    /// How long before a queued frame may go out: zero when one may go now.
    ///
    /// Zero with an empty queue too, because for this question "nothing is waiting" and
    /// "wait no longer" are the same number. **That is exactly why this is not the function
    /// a sleeping loop should call**: the loop needs the earlier of this and the presence
    /// timer, and a `min` would read the empty-queue zero as "wake immediately". Ask
    /// [`wake_in`] instead — it is the same two timers with the empty queue resolved.
    ///
    /// [`wake_in`]: Self::wake_in
    pub fn send_due_in(&self, now_us: u64) -> u64 {
        let Some(head) = self.queue.front() else {
            return 0;
        };
        if head.due_us > now_us {
            // A later copy of something already going out. Its own spacing is the whole
            // answer, and it was placed inside a window when it was scheduled.
            return head.due_us - now_us;
        }
        if self.window_open(now_us, head.group) {
            return 0;
        }
        // Outside the window, aim at the next slot this node may use, plus the guard.
        // Aiming rather than permitting is the whole point: "may send now" is true for
        // most of a slot, and a frame sent at a random phase of it is heard about a
        // quarter of the time. `next_owned_us` answers zero when the current slot is
        // already ours — and also when the map names no slot on this channel at all, in
        // which case the ordinary next-boundary wait is the right fallback.
        let owned = self.schedule.next_owned_us(now_us);
        let to_slot = if owned == 0 {
            self.schedule.next_slot_us(now_us)
        } else {
            owned
        };
        to_slot + u64::from(guard_tu(head.group)) * TU_US
    }

    /// Release whatever the slot phase allows, oldest first; returns how many frames.
    ///
    /// A peer is in its receive window for part of a slot, not all of it, and a frame that
    /// starts outside it is not heard — it is not acked either, so the loss is discovered
    /// a slot later. That is why data goes through here rather than straight from
    /// [`Link::up`]: the tunnel path has no idea what phase of the slot it is in, and the
    /// kernel writes whenever it has a segment ready.
    ///
    /// A packet is released once per call at most when it is small, because its remaining
    /// copies are [placed an availability window apart](AW_BURST) and are therefore not due
    /// yet. A large packet has one copy and bursts up to [`WINDOW_FRAMES`] into a single
    /// window, advancing the clock by each frame's own airtime rather than waiting for the
    /// next boundary: a frame that starts inside the window is worth more than the tick
    /// spent waiting for the boundary behind it.
    ///
    /// **The first copy of a packet is never held for the sake of the rest.** Three variants
    /// of this were built and measured, and the least clever won:
    ///
    /// | what `flush` did with a burst that did not fit | ping | average RTT |
    /// | --- | --- | --- |
    /// | sent the first copy now, copies behind it waited their turn | **12/20** | **88 ms** |
    /// | held the packet for a window this node owns with room for all four | 10/20 | 175 ms |
    /// | sent only the copies that still fitted | 9/20 | 244 ms |
    ///
    /// The reason waiting loses is the shape of the peer's own map, read off the air rather
    /// than inferred. A phone announces sixteen slots in two alternating shapes, and the
    /// channel this node happens to sit on is not always the crowded one:
    ///
    /// ```text
    /// 44 44 149  0  0  0  0 44  6 44 149 44  0  0  0 44    six on 44, two on 149
    /// 44  0 149  0  0  0  0  0  6  0 149  0  0  0  0  0    one on 44, two on 149
    /// ```
    ///
    /// A copy that waits for the next slot this node owns may therefore wait for a slot the
    /// peer is on some other channel for — the wait is paid in full and buys nothing. The
    /// first copy costs one availability window and can be heard in the slot that is open
    /// right now; the three behind it are cheap to lose and expensive to delay. Cutting the
    /// burst short is worse still: it gives up the repetition that turned 1/20 into 12/20.
    ///
    /// The other half of this is that the channel matters more than the slot. Same binary,
    /// same phone, only `--channel` changed: 149 gave 7/20 at 491 ms, 44 gave 15/20 at
    /// 410 ms with a 6.5 ms minimum and 46 packets received against 8.
    pub fn flush(&mut self, now_us: u64) -> usize {
        let mut frames = 0;
        let mut at = now_us;
        // How many heads in a row have been stepped over while looking for one that may go.
        //
        // A packet small enough to be repeated is released once per availability window, so
        // most of the time the head is a copy that is not due for another sixteen
        // milliseconds. Stopping there is what this used to do, and it stopped the whole
        // queue with it: `send_due_in` reads the head too, so the loop slept for that
        // window while ready packets sat behind it. The cost is not the airtime, it is the
        // round trip — the peer measures its own against when the acknowledgement for a
        // segment comes back, and 48 ms of queueing (four copies, one window apart) is a
        // round trip it cannot grow a window over. Measured 2026-11-15 on a 45 MB photo
        // transfer: `tx=23171p/76575f` in 5 s, i.e. 386 frames and 96 packets a second at a
        // round trip the phone read as one slot, and `rx` that never passed 200 KB/s.
        //
        // Bounded by the queue length: a queue in which nothing is due has no head worth
        // looking for, and walking it forever would be a spinner.
        let mut passed = 0usize;
        while frames < WINDOW_FRAMES {
            let (due_us, group, copies, remaining, queued_us) = match self.queue.front() {
                Some(head) => (
                    head.due_us,
                    head.group,
                    head.copies,
                    head.remaining,
                    head.queued_us,
                ),
                None => break,
            };
            if !self.window_open(at, group) {
                break;
            }
            if due_us > at {
                if passed >= self.queue.len() {
                    break;
                }
                self.queue.rotate_left(1);
                passed += 1;
                continue;
            }
            // Read out of the head before taking it mutably: `window_open` and the frame
            // builder both want a borrow of `self`, and the queue entry cannot hold one
            // across them.
            //
            // First of its copies, which is when the packet counts as sent rather than the
            // last, so a counter read between two copies still says the packet left.
            let first = remaining == copies;
            let Some(head) = self.queue.front_mut() else {
                break;
            };
            let frame = frame::data_frame(
                self.node.addr,
                head.dst,
                self.seq,
                self.awdl_seq,
                &head.packet,
                self.rate_mbps,
            );
            self.seq = self.seq.wrapping_add(1);
            self.awdl_seq = self.awdl_seq.wrapping_add(1);

            match self.radio.send(&frame) {
                Ok(()) => {
                    frames += 1;
                    passed = 0;
                    if first {
                        self.stats.tx_packets += 1;
                        self.stats.tx_bytes += head.packet.len() as u64;
                        // The wait this node imposed on the peer, closed here because
                        // this is the first instant any of the packet could be heard.
                        if queued_us != 0 {
                            self.stats.record_wait(at.saturating_sub(queued_us));
                        }
                    }
                    self.stats.tx_frames += 1;
                    let start = at;
                    at += Self::air_time_us(self.rate_mbps, frame.len());
                    head.remaining -= 1;
                    if head.remaining == 0 {
                        self.queue.pop_front();
                    } else {
                        // The next copy goes into the next availability window, which is
                        // the one a peer that missed this one may be listening in. Placed
                        // from the instant this frame *started*, so the spacing is the AW
                        // and not the AW plus however long this frame held the air.
                        head.due_us = start + u64::from(AW_PERIOD_TU) * TU_US;
                        // And out of the head, for the reason `passed` gives above: this
                        // packet now has sixteen milliseconds to wait, and the packets
                        // behind it have none. Order is not owed to anyone here — a copy of
                        // a frame already handed over carries the same sequence number as
                        // its predecessor, and the IPv6 packets underneath are reassembled
                        // by the peer's own stack.
                        self.queue.rotate_left(1);
                    }
                }
                Err(_) => {
                    self.stats.tx_errors += 1;
                    // The frame was refused rather than heard, so the copy is spent either
                    // way; dropping it is what keeps a broken descriptor from spinning.
                    self.queue.pop_front();
                }
            }
        }
        frames
    }

    /// Whether this instant sits inside the part of a slot a frame may start in.
    ///
    /// Two conditions, and the second one is not a refinement of the first: the slot has
    /// to exist on this channel before it matters how far into it the clock is. A slot the
    /// peer spends elsewhere is a slot in which its receiver is elsewhere, and four copies
    /// inside it share that one slot's fate.
    fn window_open(&self, now_us: u64, group: bool) -> bool {
        if !self.schedule.owns_now(now_us) {
            return false;
        }
        let pos = self.slot_position_tu(now_us);
        pos >= guard_tu(group) && pos < WINDOW_LAST_TU
    }

    /// How far into the slot this instant sits, in time units from the boundary.
    ///
    /// The inverse of `next_slot_tu`, and the coordinate the window is defined in: the
    /// guard is measured from zero and [`WINDOW_LAST_TU`] from the same place.
    fn slot_position_tu(&self, now_us: u64) -> u8 {
        SLOT_TU.saturating_sub(self.schedule.next_slot_tu(now_us))
    }

    /// How long a frame of this size holds the air, at the configured rate.
    ///
    /// Bits divided by rate, plus a fixed allowance for SIFS, the ack the peer will send
    /// in reply, and the backoff before the next frame can start. Without the allowance a
    /// burst back-to-back at line rate would overrun its own window.
    ///
    /// Takes the rate rather than `self` because the one caller is in the middle of
    /// holding the head of the queue, and a method on all of `self` cannot be reached from
    /// there.
    fn air_time_us(rate_mbps: u8, frame_len: usize) -> u64 {
        (frame_len as u64 * 8) / u64::from(rate_mbps.max(1)) + 120
    }

    /// Move one frame from the air to the tunnel.
    ///
    /// `now_us` is the instant the frame was read, on the same clock the schedule runs
    /// on. It is passed in rather than sampled here because the caller already reads it
    /// once per drain and a frame stamped twice would be compared against a boundary it
    /// did not arrive at.
    pub fn down(&mut self, buf: &mut [u8], now_us: u64) -> io::Result<bool> {
        let Some(len) = self.radio.recv(buf)? else {
            return Ok(false);
        };
        let frame = &buf[..len];
        // Before any parsing that might reject the frame: where it arrived is a fact about
        // the radio, not about the frame's contents, and a malformed frame is still
        // evidence of which channel the radio was listening on.
        if let Some(mhz) = frame::phy_mhz(frame) {
            self.phy_mhz = Some(mhz);
        }
        match frame::parse(frame) {
            None => {
                self.stats.rx_malformed += 1;
                Ok(false)
            }
            Some(Inbound::Other { src, .. }) => {
                if src == self.node.addr {
                    self.stats.rx_echo += 1;
                } else {
                    self.stats.rx_other += 1;
                    // Every action frame on the channel is a peer saying where it is in
                    // time. Nothing above this layer reads that, and a link that ignores
                    // it broadcasts into a receiver whose window is somewhere else.
                    self.listen(src, frame, now_us);
                }
                Ok(false)
            }
            Some(Inbound::Ipv6 { src, dst, packet }) => {
                if src == self.node.addr {
                    self.stats.rx_echo += 1;
                    return Ok(false);
                }
                if dst != self.node.addr && !dst.is_group() {
                    self.stats.rx_not_ours += 1;
                    return Ok(false);
                }
                match self.tunnel.send(packet) {
                    Ok(()) => {
                        self.stats.rx_packets += 1;
                        self.stats.rx_bytes += packet.len() as u64;
                        Ok(true)
                    }
                    Err(_) => {
                        self.stats.rx_errors += 1;
                        Ok(false)
                    }
                }
            }
        }
    }

    /// Read what a peer's presence frame says and act on the two separate halves of it.
    ///
    /// A presence frame carries an election and a clock, and this link needs both for
    /// different reasons:
    ///
    /// * **The clock** is adopted unconditionally. A node receives in the same window it
    ///   transmits in, so the phase of the peer's frame is the slot map of the only place
    ///   worth aiming at. Without this we are a node nobody can hear: the frames are
    ///   well formed, leave the antenna, and land while the peer's receiver is elsewhere
    ///   in the superframe.
    /// * **The election** is a comparison, not a reflex. Copying the peer's master
    ///   unconditionally is what kept this node a follower of a peer it outranks, which
    ///   is a follower's frame carrying a winner's rank — a combination no peer can
    ///   reconcile and every peer resolves by ignoring us.
    /// * **The map** is adopted, because the clock alone says when a slot begins and not
    ///   whether the peer is on this channel while it lasts. See
    ///   [`action::map_of`] for what that cost while it was ignored.
    ///
    /// The order matters for one reason only: the phase error has to be measured against
    /// the clock that is about to be replaced, so it is read before [`Schedule::follow`]
    /// and not after.
    fn listen(&mut self, src: Mac, frame: &[u8], now_us: u64) {
        let Some((next_tu, aw_counter)) = action::clock_of(frame) else {
            return;
        };
        // Before the identity test, because the whole point is that the identity may be
        // stale: a device that has been silent for [`PEER_SILENCE_US`] has left, and the
        // frame in hand is evidence of who is actually there. Released and re-locked in
        // one step rather than dropped and picked up later, so the frame that proves the
        // old anchor dead is also the frame the new one is taken from.
        if self.peer.is_some() && now_us.saturating_sub(self.last_anchor_us) > PEER_SILENCE_US {
            self.peer = None;
            self.schedule.forget_peer();
            self.anchor_rejects = 0;
            self.stats.anchor_released += 1;
        }
        if self.peer.is_none() {
            self.peer = Some(src);
        }
        if self.peer != Some(src) {
            // Not the node whose clock we keep. Its phase describes a superframe we are
            // not on, and following it would be following two schedules at once.
            return;
        }
        // Reachable at all is proof of life, and it is refreshed before the clock is used
        // rather than after: a frame from this node that this crate cannot read a clock
        // out of is still a reason not to hand the anchor to somebody else.
        self.last_anchor_us = now_us;

        if let Some(theirs) = action::election_of(frame) {
            if !self.node.outranks(&theirs) {
                self.node
                    .follow(theirs.master, theirs.height.saturating_add(1), theirs.master_metric);
            }
        }

        // The map, if the frame names one this node can use. Belief, not adoption: it is
        // recorded so the gate knows when the peer is on this channel, and it is never
        // written into what this node announces. Announcing a peer's map back at it says
        // "I am reachable in these five slots" about a radio that never leaves its
        // channel, and the peer then sends only in those five — which is measurable, and
        // was measured: while this node echoed the phone's map it received 8 packets on
        // the channel the phone had two slots of, and 46 on the one it had six.
        //
        // The peer test is already done above: only the node whose clock is kept is worth
        // believing, because a stranger's map describes a superframe this node is not on.
        // The legacy implementation also refused a peer's map while it was master; that
        // guard protected its own announcement, which cannot be overwritten here, and
        // copying it would put the gate back to asking nothing — which is the state that
        // transmitted into fourteen vacant slots out of sixteen.
        if let Some(theirs) = action::map_of(frame) {
            match self.schedule.offer_map(theirs, now_us) {
                MapVerdict::Refused => self.stats.maps_refused += 1,
                MapVerdict::Held => self.stats.maps_held += 1,
                MapVerdict::Adopted => self.stats.maps_adopted += 1,
                MapVerdict::Believed | MapVerdict::Learning(_) => {}
            }
        }

        // Both halves of the announcement are needed: the counter says how many slots
        // apart the two grids are and `next_tu` where inside one. Passing only `next_tu`
        // caps the reading at half a slot and makes the refusal below unreachable.
        let err = self.schedule.error_tu(now_us, next_tu, aw_counter);
        self.phase_err_tu = err;
        let whole_slot = err.unsigned_abs() >= u32::from(SLOT_TU / 2);
        if whole_slot && self.anchor_rejects < ANCHOR_REJECT_LIMIT {
            self.anchor_rejects += 1;
            return;
        }
        self.anchor_rejects = 0;
        self.schedule.follow(now_us, next_tu, aw_counter);
        self.stats.anchored += 1;
    }

    /// The node whose clock this link keeps, once one has been heard.
    pub fn peer(&self) -> Option<Mac> {
        self.peer
    }

    /// How far off the peer's slot boundary this node was at the last frame, in TU.
    pub fn phase_err_tu(&self) -> i32 {
        self.phase_err_tu
    }

    /// The frequency the radio was actually receiving on, as the driver stamped it.
    ///
    /// `None` until a frame arrives that carries a channel field — frames this crate
    /// injects have a nine-byte header with no such field, so the first evidence comes
    /// from a peer. That is the point: a self-report would only repeat what `--channel`
    /// already said.
    pub fn phy_mhz(&self) -> Option<u16> {
        self.phy_mhz
    }

    /// Whether the radio is receiving somewhere other than the channel this node
    /// announces, once there is evidence either way.
    ///
    /// The most expensive single mismatch in this crate: the announcement names slots on
    /// one channel, the receiver sits on another, and every frame aimed at those slots
    /// lands in a window that never opens. Nothing upstream can see it, because the link
    /// still reports a peer, a phase error and a rising transmit count.
    pub fn off_channel(&self) -> bool {
        match self.phy_channel() {
            Some(ch) => ch != self.node.channel,
            None => false,
        }
    }

    /// The channel the radio is receiving on, once a frame has said.
    pub fn phy_channel(&self) -> Option<u8> {
        self.phy_mhz.and_then(frame::channel_of_mhz)
    }

    /// Send whatever presence frame is due: a PSF on its period, a MIF at the mid-slot.
    ///
    /// Returns whether anything went out, so a caller driving a timer knows to reset it.
    ///
    /// # Two frames, because they answer two questions
    ///
    /// A PSF carries the clock and the channel map; a MIF carries all of that *and* the
    /// host name. AWDL-17 has a peer refuse to treat a sender as a node at all until it
    /// has a name for it, so a node that sends only PSFs is one whose frames are heard
    /// and whose address never enters any peer's table: no mDNS, no answer, nothing to
    /// address. This implementation used to send three MIFs in its first third of a
    /// second and then fall back to PSF alone for the rest of its life — long enough to
    /// look correct in a log that counts presence frames, and never long enough to be
    /// heard by a peer that joined the channel afterwards.
    ///
    /// The cadence is the legacy one: a MIF every slot, half a slot after the boundary
    /// the most recent PSF announced. Deriving it from the announcement rather than from
    /// the wall clock is what makes the two frames describe the same superframe; a MIF
    /// scheduled from `now` alone would drift with the loop's own latency and eventually
    /// land in a window the PSF never claimed.
    pub fn announce(&mut self, now_us: u64) -> io::Result<bool> {
        let mut sent = false;
        // Read before the PSF block below rewrites it: a MIF that is already due is not
        // cancelled by a PSF going out, and dropping it is the difference between naming
        // this node once a period and naming it once a slot.
        let pending_mif = self.next_mif_us;

        let psf_due = match self.last_presence_us {
            None => true,
            Some(last) => now_us.saturating_sub(last) >= u64::from(PSF_INTERVAL_TU) * TU_US,
        };
        if psf_due {
            sent |= self.send_presence(now_us, false);
            self.last_presence_us = Some(now_us);
            // Half a slot past the boundary this PSF just named.
            let to_boundary = u64::from(SLOT_TU - self.schedule.next_slot_tu(now_us));
            let scheduled =
                now_us + to_boundary * TU_US + u64::from(SLOT_TU) * TU_US / 2;
            self.next_mif_us = Some(match pending_mif {
                Some(due) if due < scheduled => due,
                _ => scheduled,
            });
        }

        if let Some(due) = self.next_mif_us {
            if now_us >= due {
                sent |= self.send_presence(now_us, true);
                // Mid-slot to mid-slot is exactly one slot, so the phase tracks itself
                // and a late wakeup costs one frame's timing rather than the offset.
                self.next_mif_us = Some(now_us + u64::from(SLOT_TU) * TU_US);
            }
        }

        Ok(sent)
    }

    /// Build, count and transmit one presence frame.
    fn send_presence(&mut self, now_us: u64, mif: bool) -> bool {
        let frame = action::presence(
            &self.node,
            &self.schedule,
            now_us,
            self.seq,
            mif,
            self.rate_mbps,
        );
        self.seq = self.seq.wrapping_add(1);
        self.stats.presence += 1;
        if mif {
            self.stats.mif += 1;
        }
        match self.radio.send(&frame) {
            Ok(()) => true,
            Err(_) => {
                self.stats.tx_errors += 1;
                false
            }
        }
    }

    /// How long until the next presence frame of either kind is due, in microseconds.
    pub fn presence_due_in(&self, now_us: u64) -> u64 {
        let psf = match self.last_presence_us {
            None => 0,
            Some(last) => {
                let interval = u64::from(PSF_INTERVAL_TU) * TU_US;
                interval.saturating_sub(now_us.saturating_sub(last))
            }
        };
        let mif = match self.next_mif_us {
            None => 0,
            Some(due) => due.saturating_sub(now_us),
        };
        psf.min(mif)
    }

    /// How long the loop may sleep before it has to come back, in microseconds.
    ///
    /// The question the daemon actually asks, and the only correct place to ask it: the
    /// answer is the earlier of the two timers, and taking the minimum of them outside
    /// this function is what made the daemon burn a core. [`send_due_in`] answers zero
    /// both for "a queued frame may go now" and for "nothing is queued" — honest for its
    /// own question, fatal through a `min`, because the second zero then becomes a zero
    /// timeout on `poll` and the loop never blocks: an idle link spun at ~92 % of one
    /// core on the device, and an idle link is what a link is most of the time.
    ///
    /// So the empty queue is handled here, where both timers are visible, and it means
    /// "the presence timer is the only thing left to wait for".
    ///
    /// [`send_due_in`]: Self::send_due_in
    pub fn wake_in(&self, now_us: u64) -> u64 {
        let presence = self.presence_due_in(now_us);
        if self.queue.is_empty() {
            return presence;
        }
        presence.min(self.send_due_in(now_us))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schedule::{SLOTS, SLOT_US};

    /// A radio and a tunnel that keep their frames in a queue.
    struct Mock {
        inbound: Vec<Vec<u8>>,
        outbound: Vec<Vec<u8>>,
    }

    impl Mock {
        fn new() -> Self {
            Mock {
                inbound: Vec::new(),
                outbound: Vec::new(),
            }
        }
    }

    impl Radio for Mock {
        fn recv(&mut self, buf: &mut [u8]) -> io::Result<Option<usize>> {
            if self.inbound.is_empty() {
                return Ok(None);
            }
            let frame = self.inbound.remove(0);
            let n = frame.len().min(buf.len());
            buf[..n].copy_from_slice(&frame[..n]);
            Ok(Some(n))
        }
        fn send(&mut self, frame: &[u8]) -> io::Result<()> {
            self.outbound.push(frame.to_vec());
            Ok(())
        }
    }

    impl Tunnel for Mock {
        fn recv(&mut self, buf: &mut [u8]) -> io::Result<Option<usize>> {
            if self.inbound.is_empty() {
                return Ok(None);
            }
            let packet = self.inbound.remove(0);
            let n = packet.len().min(buf.len());
            buf[..n].copy_from_slice(&packet[..n]);
            Ok(Some(n))
        }
        fn send(&mut self, packet: &[u8]) -> io::Result<()> {
            self.outbound.push(packet.to_vec());
            Ok(())
        }
    }

    fn node() -> NodeInfo {
        NodeInfo::master_of(
            Mac::parse("ee:aa:d3:d0:cb:f1").expect("valid"),
            "PJZ110N",
            149,
        )
    }

    fn peer() -> Mac {
        Mac::parse("2a:d8:dc:5d:8c:5a").expect("valid")
    }

    /// The peer as it appears in a frame it sent: same address, its own name.
    fn peer_node() -> NodeInfo {
        NodeInfo::master_of(peer(), "iPhone", 149)
    }

    fn ipv6_to(dst: &[u8; 16], payload: usize) -> Vec<u8> {
        let mut out = vec![0u8; 40 + payload];
        out[0] = 0x60;
        out[4..6].copy_from_slice(&(payload as u16).to_be_bytes());
        out[6] = 17;
        out[7] = 64;
        out[24..40].copy_from_slice(dst);
        out
    }

    /// Where the schedule is anchored. Every timestamp below is relative to it.
    const ANCHOR_US: u64 = 1_000_000;

    fn link() -> Link<Mock, Mock> {
        Link::new(Mock::new(), Mock::new(), node(), ANCHOR_US, 36)
    }

    /// A link that sends each packet once.
    ///
    /// Used by the tests about framing, the guard and the channel gate, where the subject
    /// is one frame and its timing rather than the repetition across the slot. A repeated
    /// packet also has to fit four copies into the window, and those tests start a third of
    /// the way in — a burst there is the very thing [`Link::flush`] now holds back.
    fn single_link() -> Link<Mock, Mock> {
        let mut l = link();
        l.set_burst(1);
        l
    }

    /// One time unit into the slot: inside the guard, so nothing may start here.
    fn guarded_us() -> u64 {
        ANCHOR_US + TU_US
    }

    /// Thirty time units into the slot: past either guard, inside the window.
    fn open_us() -> u64 {
        ANCHOR_US + 30 * TU_US
    }

    /// The first instant past the unicast guard, which is where a repeated packet has to
    /// start: four copies sixteen TU apart have to finish before the window closes.
    fn guard_edge_us() -> u64 {
        ANCHOR_US + u64::from(UNICAST_GUARD_TU) * TU_US
    }

    #[test]
    fn a_frame_waits_for_a_slot_the_peer_spends_on_this_channel() {
        let mut l = single_link();
        // The peer's sequence: slots 0, 1 and 8 on the social channel, with a six-slot gap
        // spent where this radio does not go. That shape is why four copies inside one slot
        // are one attempt and not four — the copies share the slot's fate, not each other's
        // independence.
        let mut map = [6u8; SLOTS];
        map[0] = 149;
        map[1] = 149;
        map[8] = 149;
        l.schedule.offer_map(map, ANCHOR_US);
        l.schedule.offer_map(map, ANCHOR_US);
        l.schedule.offer_map(map, ANCHOR_US);

        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));

        // Slot 4 is inside the gap: nothing may start there however far into it the clock
        // is, and the wait crosses the gap to slot 8 rather than to the next boundary.
        let in_the_gap = ANCHOR_US + 4 * SLOT_US + 30 * TU_US;
        assert!(!l.schedule().owns_now(in_the_gap));
        assert_eq!(l.flush(in_the_gap), 0);
        assert!(l.radio.outbound.is_empty(), "nothing leaves into a vacant slot");
        let wait = l.send_due_in(in_the_gap);
        // Across the gap to slot 8's boundary, plus the guard the frame has to keep from
        // it: three slots and a guard, not one boundary.
        assert_eq!(wait, SLOT_US - 30 * TU_US + 3 * SLOT_US + 3 * TU_US);
        assert!(l.schedule().owns_now(in_the_gap + wait), "the wait ends in slot 8");

        // And a slot the peer is on this channel for takes the frame.
        let in_our_slot = ANCHOR_US + SLOT_US + 30 * TU_US;
        assert!(l.schedule().owns_now(in_our_slot));
        assert_eq!(l.flush(in_our_slot), 1);
        assert_eq!(l.stats.tx_packets, 1);
    }

    #[test]
    fn a_peers_map_gates_transmission_and_never_becomes_our_announcement() {
        // The whole defect in one test. The peer's map has to be believed — it is the only
        // statement about where the peer's receiver is — and it has to stay out of what
        // this node announces, because that would tell the peer to send to us in the same
        // five slots it happens to use.
        let mut l = link();
        let mut map = [6u8; SLOTS];
        map[0] = 149;
        let mut s = Schedule::anchored(ANCHOR_US, 149);
        s.announce(map);
        let frame = action::presence(&peer_node(), &s, ANCHOR_US, 1, false, 36);

        // One announcement is not a map. That is the difference between a peer whose map
        // flaps between two shapes and a schedule rewritten at frame rate.
        l.radio.inbound.push(frame.clone());
        let mut buf = [0u8; 4096];
        let _ = l.down(&mut buf, ANCHOR_US + 30 * TU_US);
        assert_eq!(l.schedule().peer_map(), None, "one frame is a reading, not a map");
        assert!(l.schedule().owns_now(ANCHOR_US + 4 * SLOT_US + 30 * TU_US));

        for step in 1..3u64 {
            l.radio.inbound.push(frame.clone());
            let _ = l.down(&mut buf, ANCHOR_US + step * 200_000 + 30 * TU_US);
        }
        assert_eq!(l.schedule().peer_map(), Some(&map), "the peer's map, slot for slot");
        assert_eq!(l.stats.maps_adopted, 1);
        assert_eq!(
            l.schedule().slots(),
            &[149u8; SLOTS],
            "and all sixteen of ours, because the radio never leaves 149"
        );
        assert!(
            !l.schedule().owns_now(ANCHOR_US + 4 * SLOT_US + 30 * TU_US),
            "slot 4 is one the peer spends elsewhere"
        );
    }

    #[test]
    fn the_frequency_comes_from_the_driver_and_can_disagree_with_the_announcement() {
        // `--channel` never reached the radio, so the number this node advertises and the
        // frequency its receiver actually sits on are two different facts. The link has to
        // report the second one, and has to say when they differ — otherwise a schedule
        // built on the wrong channel reads as a healthy link with a vanished peer.
        use crate::frame as f;

        fn driven(freq: u16) -> Vec<u8> {
            let mut h = Vec::new();
            h.extend_from_slice(&[0, 0]);
            h.extend_from_slice(&48u16.to_le_bytes());
            h.extend_from_slice(&0x4000_086fu32.to_le_bytes());
            h.extend_from_slice(&0u64.to_le_bytes());
            h.extend_from_slice(&[0x00, 0x18]); // FLAGS, RATE
            h.extend_from_slice(&freq.to_le_bytes());
            h.extend_from_slice(&0x0140u16.to_le_bytes());
            h.extend_from_slice(&[0u8; 26]);
            assert_eq!(h.len(), 48);
            h
        }

        let mut l = link();
        assert_eq!(l.phy_mhz(), None, "nothing heard yet, nothing to claim");
        assert!(!l.off_channel(), "and no evidence of a mismatch either");

        // A peer frame that arrived on 5745 while this node announces 149: agreement, and
        // the frequency is still reported.
        let mut frame149 = driven(5745);
        frame149.extend_from_slice(&[0u8; 40]);
        l.radio.inbound.push(frame149);
        let mut buf = [0u8; 4096];
        let _ = l.down(&mut buf, ANCHOR_US);
        assert_eq!(l.phy_mhz(), Some(5745));
        assert_eq!(l.phy_channel(), Some(149));
        assert!(!l.off_channel(), "5745 is channel 149, which is what we announce");

        // The same node, a frame that arrived on 5220: the radio is on 44 and the
        // announcement is a fiction. This is the state the device was actually in for a
        // whole round while the status line showed nothing wrong.
        let mut frame44 = driven(5220);
        frame44.extend_from_slice(&[0u8; 40]);
        l.radio.inbound.push(frame44);
        let _ = l.down(&mut buf, ANCHOR_US + 200_000);
        assert_eq!(l.phy_channel(), Some(44));
        assert!(l.off_channel(), "44 against the 149 this node advertises");

        // Frames this crate injects carry a nine-byte header with no channel field, and
        // hearing one back must not be taken as evidence about the radio.
        let mut echo = f::radiotap(36).to_vec();
        echo.resize(64, 0);
        l.radio.inbound.push(echo);
        let _ = l.down(&mut buf, ANCHOR_US + 400_000);
        assert_eq!(l.phy_channel(), Some(44), "unchanged by a header with no frequency");
    }

    #[test]
    fn a_peer_map_with_no_slot_on_this_channel_is_refused() {
        // Obeying it would be a permanent silence that reads exactly like a peer that went
        // away, so the gate stays as it was.
        let mut l = link();
        let mut s = Schedule::anchored(ANCHOR_US, 149);
        s.announce([6u8; SLOTS]);
        let frame = action::presence(&peer_node(), &s, ANCHOR_US, 1, false, 36);

        for step in 0..4u64 {
            l.radio.inbound.push(frame.clone());
            let mut buf = [0u8; 4096];
            let _ = l.down(&mut buf, ANCHOR_US + step * 200_000 + 30 * TU_US);
        }
        assert_eq!(l.schedule().peer_map(), None, "nothing believed");
        assert_eq!(l.stats.maps_refused, 4, "refused every time, not once");
        assert_eq!(l.schedule().slots(), &[149u8; SLOTS], "still all ours");
        assert!(l.schedule().owns_now(ANCHOR_US + 30 * TU_US));
    }

    #[test]
    fn a_packet_to_a_peer_leaves_as_a_frame_addressed_to_its_mac() {
        let mut l = single_link();
        let packet = ipv6_to(&peer().link_local(), 8);
        l.tunnel.inbound.push(packet.clone());
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));
        // Queued, not sent: `flush` is what releases it into a slot window.
        assert!(l.radio.outbound.is_empty());
        assert_eq!(l.flush(open_us()), 1);

        let sent = &l.radio.outbound[0];
        match frame::parse(sent) {
            Some(Inbound::Ipv6 { src, dst, packet: got }) => {
                assert_eq!(src, node().addr);
                assert_eq!(dst, peer());
                assert_eq!(got, &packet[..]);
            }
            other => panic!("expected a data frame, got {other:?}"),
        }
        assert_eq!(l.stats.tx_packets, 1);
        assert_eq!(l.stats.tx_bytes, packet.len() as u64);
    }

    #[test]
    fn a_multicast_packet_is_addressed_to_the_group_mac() {
        let mut l = single_link();
        let mut group = [0u8; 16];
        group[0] = 0xff;
        group[1] = 0x02;
        group[15] = 0xfb;
        l.tunnel.inbound.push(ipv6_to(&group, 4));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));
        assert_eq!(l.flush(open_us()), 1);
        match frame::parse(&l.radio.outbound[0]) {
            Some(Inbound::Ipv6 { dst, .. }) => assert_eq!(dst, Mac::MDNS_V6),
            other => panic!("expected a data frame, got {other:?}"),
        }
    }

    #[test]
    fn a_queued_frame_waits_for_the_window_and_then_goes_out() {
        let mut l = single_link();
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));

        // Inside the guard a frame that starts here is not heard, so it is not sent —
        // and the wait it reports is the one `edgeWaitUs` computes: the distance to the
        // next boundary plus the guard.
        let wait = l.send_due_in(guarded_us());
        assert_eq!(wait, l.schedule().next_slot_us(guarded_us()) + 3 * TU_US);
        assert_eq!(l.flush(guarded_us()), 0);
        assert!(l.radio.outbound.is_empty(), "nothing leaves early");
        assert_eq!(l.stats.tx_packets, 0);

        // The same frame, one window later.
        assert_eq!(l.send_due_in(open_us()), 0);
        assert_eq!(l.flush(open_us()), 1);
        assert_eq!(l.radio.outbound.len(), 1);
        assert_eq!(l.stats.tx_packets, 1);
    }

    #[test]
    fn the_wait_a_packet_spends_in_the_queue_is_measured() {
        let mut l = link();
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        // Read out of the tunnel inside the guard, where nothing may start. The packet is
        // in this node's hands from this instant, and the peer is waiting on it.
        assert!(l.up(&mut buf, guarded_us()).expect("up"));
        assert_eq!(l.stats.tx_wait_n, 0, "queued is not waited");

        assert_eq!(l.flush(guarded_us()), 0, "the guard holds it");
        assert_eq!(l.stats.tx_wait_n, 0, "and nothing has waited yet");

        // The window opens two time units later. What the peer measures as its own round
        // trip starts here, and it is the distance the packet sat, not the airtime.
        let mut at = guard_edge_us();
        assert_eq!(l.flush(at), 1);
        assert_eq!(l.stats.tx_wait_n, 1);
        assert_eq!(l.stats.tx_wait_sum_us, at - guarded_us());
        assert_eq!(l.stats.tx_wait_max_us, at - guarded_us());
        assert_eq!(
            l.stats.tx_wait_buckets,
            [0, 0, 0, 1, 0, 0, 0],
            "two time units is the 2-4 ms bucket"
        );

        // The copies behind it are the same packet waiting again, and counting them would
        // multiply the wait by the copy count — the one number this is here to divide out.
        let first_at = at;
        for _ in 1..AW_BURST {
            at += u64::from(AW_PERIOD_TU) * TU_US;
            assert_eq!(l.flush(at), 1);
        }
        assert_eq!(l.stats.tx_wait_n, 1, "one packet, one wait");
        assert_eq!(l.stats.tx_wait_sum_us, first_at - guarded_us());
    }

    #[test]
    fn a_small_packet_is_repeated_across_the_slot() {
        let mut l = link();
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));

        // One copy per availability window, four to a slot, each a fresh frame: three
        // copies of one sequence number would be discarded as duplicates one layer above
        // the radio, which would look exactly like three windows that were tried and did
        // not work.
        let mut at = guard_edge_us();
        for _ in 0..AW_BURST {
            assert_eq!(l.flush(at), 1);
            at += u64::from(AW_PERIOD_TU) * TU_US;
        }
        assert_eq!(l.radio.outbound.len(), usize::from(AW_BURST));
        assert!(l.queue.is_empty(), "all four copies are spent");
        assert_eq!(l.stats.tx_packets, 1, "one packet, however many copies");
        assert!(
            l.radio.outbound.windows(2).all(|pair| pair[0] != pair[1]),
            "each copy carries its own sequence number"
        );
        for frame in &l.radio.outbound {
            assert!(matches!(frame::parse(frame), Some(Inbound::Ipv6 { .. })));
        }
    }

    #[test]
    fn a_small_packet_does_not_send_its_second_copy_early() {
        let mut l = link();
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));
        assert_eq!(l.flush(guard_edge_us()), 1);
        // The window is still open a moment later, but the next copy is not due for
        // another availability window and the whole point is that it arrives in a
        // different one.
        let due = l.send_due_in(guard_edge_us() + 1000);
        assert_eq!(due, u64::from(AW_PERIOD_TU) * TU_US - 1000);
        assert_eq!(l.flush(guard_edge_us() + 1000), 0);
    }

    #[test]
    fn a_waiting_copy_does_not_hold_the_packets_behind_it() {
        let mut l = link();
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));
        assert!(l.up(&mut buf, 0).expect("up"));

        // Both first copies leave in the same window. The queue used to be strict
        // first-in-first-out with `flush` and `send_due_in` both reading the head, so the
        // first packet's second copy — an availability window away — held the second
        // packet for sixteen milliseconds and every packet behind it for as long as the
        // queue was deep. On a transfer the queue is nothing but acknowledgements, and the
        // peer measures its round trip by when one comes back: forty-eight milliseconds of
        // that was `tx=23171p/76575f` in five seconds and `rx` that never passed 200 KB/s.
        assert_eq!(l.flush(guard_edge_us()), 2, "both first copies, in one window");
        assert_eq!(l.stats.tx_packets, 2);

        // And the two packets waiting on their second copies are still exactly one window
        // away, so nothing was sent early to get that second frame out.
        assert_eq!(l.send_due_in(guard_edge_us() + 1000), u64::from(AW_PERIOD_TU) * TU_US - 1000);
    }

    #[test]
    fn a_burst_late_in_the_window_sends_its_first_copy_at_once() {
        let mut l = link();
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));

        // Forty TU in, where the four copies do not fit: the second is due at 56 TU, the
        // window's far edge. The first copy goes anyway, and that is the measured
        // difference between 12/20 and 10/20 — a drain that waits for a slot with room for
        // the whole burst spends a window holding a packet whose first copy would have
        // been heard. Nothing is asked about the rest of the burst before the first one.
        let late = ANCHOR_US + 40 * TU_US;
        assert!(l.schedule().owns_now(late));
        assert_eq!(l.flush(late), 1, "the first copy, without asking about the rest");
        assert_eq!(l.radio.outbound.len(), 1);
        assert_eq!(l.stats.tx_packets, 1);

        // The copy behind it is due one availability window later, which lands past the
        // edge, so it does not start outside a window: it waits for the next slot this
        // node owns, the same arithmetic `edgeWaitUs` does. That is the whole of the
        // "spill": one copy spent, not one copy wasted, and no second guess about whether
        // the burst was worth starting.
        let due = late + u64::from(AW_PERIOD_TU) * TU_US;
        assert!(l.slot_position_tu(due) >= WINDOW_LAST_TU);
        assert_eq!(l.flush(due), 0, "56 TU is past the window");
        assert_eq!(
            l.send_due_in(due),
            l.schedule().next_slot_us(due) + 3 * TU_US
        );

        let next = ANCHOR_US + SLOT_US + 3 * TU_US;
        assert_eq!(l.flush(next), 1, "the second copy, in the slot after");
        assert_eq!(l.radio.outbound.len(), 2);
        assert_eq!(l.stats.tx_packets, 1, "still one packet");
    }

    #[test]
    fn a_burst_from_the_guard_edge_lies_inside_the_slot() {
        // The other half of the same rule: a burst that begins when the window opens puts
        // all four copies inside it, at 3, 19, 35 and 51 TU of a 64 TU slot — the last of
        // them still short of `WINDOW_LAST_TU`. This is the path a drain takes when the
        // loop wakes on `send_due_in`, which aims at the guard edge rather than at the
        // boundary, because a frame that starts at the boundary is the one a peer is
        // least likely to be listening for.
        let mut l = link();
        l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));

        let mut at = guard_edge_us();
        assert_eq!(l.flush(at), 1, "the first copy");
        for _ in 1..AW_BURST {
            at += u64::from(AW_PERIOD_TU) * TU_US;
            assert!(
                l.slot_position_tu(at) < WINDOW_LAST_TU,
                "copy at {} TU is past the window",
                l.slot_position_tu(at)
            );
            assert_eq!(l.flush(at), 1);
        }
        assert_eq!(l.radio.outbound.len(), usize::from(AW_BURST));
        assert_eq!(l.stats.tx_packets, 1, "one packet, four copies");
    }

    #[test]
    fn asking_for_more_copies_than_a_slot_holds_is_clamped() {
        // A fifth copy can only be a copy in the next slot, and the next slot is the one
        // thing the window arithmetic cannot promise. The clamp is silent because the value
        // comes from a command line, and a run that asked for eight should behave like one
        // that asked for four rather than refuse to start.
        let mut l = link();
        l.set_burst(8);
        assert_eq!(l.burst(), BURST_LIMIT);
        l.set_burst(0);
        assert_eq!(l.burst(), 1, "never zero: a packet still has to go");
    }

    #[test]
    fn a_large_packet_bursts_into_one_window() {
        let mut l = link();
        for _ in 0..4 {
            l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 1400));
            let mut buf = [0u8; 2048];
            assert!(l.up(&mut buf, 0).expect("up"));
        }
        // Above `BURST_MAX` a second copy is air spent on bytes the peer already
        // has, so a large packet gets one — and the window then carries as many as fit,
        // advancing its own clock by each frame's airtime.
        assert_eq!(l.flush(open_us()), 4);
        assert_eq!(l.stats.tx_packets, 4);
        assert!(l.queue.is_empty());
    }

    #[test]
    fn a_group_frame_waits_for_the_wider_guard() {
        let mut l = single_link();
        let mut group = [0u8; 16];
        group[0] = 0xff;
        group[1] = 0x02;
        group[15] = 0xfb;
        l.tunnel.inbound.push(ipv6_to(&group, 4));
        let mut buf = [0u8; 2048];
        assert!(l.up(&mut buf, 0).expect("up"));
        // Eight time units in: past the unicast guard, still inside the multicast one.
        assert_eq!(l.flush(ANCHOR_US + 8 * TU_US), 0);
        assert!(l.radio.outbound.is_empty());
        assert_eq!(l.flush(open_us()), 1);
    }

    #[test]
    fn a_flood_is_dropped_rather_than_queued_without_end() {
        let mut l = link();
        let mut buf = [0u8; 2048];
        for _ in 0..QUEUE_LIMIT + 5 {
            l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
            assert!(l.up(&mut buf, 0).expect("up"));
        }
        // Counted rather than silent: a rising `drop` is the one sign that the offered
        // load has outrun the slot, and it is invisible in a packet count alone.
        assert_eq!(l.stats.tx_dropped, 5);
        assert_eq!(l.queue.len(), QUEUE_LIMIT);
    }

    #[test]
    fn a_busy_queue_stops_paying_for_four_copies() {
        let mut l = link();
        let mut buf = [0u8; 2048];
        for _ in 0..=WINDOW_FRAMES {
            l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
            assert!(l.up(&mut buf, 0).expect("up"));
        }
        // The queue was empty for a window's worth, so those packets carry the configured
        // four. The first one that finds a window's worth already waiting gets one copy:
        // see [`Link::copies_now`] for the measurement behind it.
        assert_eq!(l.queue.front().expect("a queue").copies, l.burst());
        assert_eq!(l.queue.back().expect("a queue").copies, 1);
    }

    #[test]
    fn a_thinned_packet_is_still_counted_once_when_its_one_copy_goes() {
        let mut l = link();
        let mut buf = [0u8; 2048];
        let queued = WINDOW_FRAMES + 8;
        for _ in 0..queued {
            l.tunnel.inbound.push(ipv6_to(&peer().link_local(), 8));
            assert!(l.up(&mut buf, 0).expect("up"));
        }
        let burst = usize::from(l.burst());
        // A time unit at a time, the way the daemon's own timer wakes it.
        let mut now = open_us();
        for _ in 0..5_000 {
            if l.queue.is_empty() {
                break;
            }
            l.flush(now);
            now += TU_US;
        }
        assert!(l.queue.is_empty(), "the queue drained");
        assert_eq!(l.stats.tx_dropped, 0);
        // Every packet went and was counted once. The copy count is read off the queue
        // entry rather than recomputed from the link's state: a packet thinned to one copy
        // is mid-flight when its first copy leaves, and asking `copies_for` there would
        // answer four and count the packet never.
        assert_eq!(l.stats.tx_packets as usize, queued);
        // The first window's worth paid for four copies each; the rest paid for one.
        assert_eq!(
            l.stats.tx_frames as usize,
            WINDOW_FRAMES * burst + (queued - WINDOW_FRAMES)
        );
    }

    #[test]
    fn a_packet_that_implies_no_mac_is_dropped_silently() {
        let mut l = link();
        // A privacy address: link-local, but not a modified EUI-64.
        let mut privacy = [0u8; 16];
        privacy[0] = 0xfe;
        privacy[1] = 0x80;
        privacy[15] = 0x09;
        l.tunnel.inbound.push(ipv6_to(&privacy, 4));
        let mut buf = [0u8; 2048];
        assert!(!l.up(&mut buf, 0).expect("up"), "nothing to send");
        assert!(l.radio.outbound.is_empty());
        assert_eq!(l.stats.tx_packets, 0);
    }

    #[test]
    fn a_non_ipv6_packet_from_the_tunnel_is_ignored() {
        let mut l = link();
        l.tunnel.inbound.push(vec![0x45; 60]);
        let mut buf = [0u8; 2048];
        assert!(!l.up(&mut buf, 0).expect("up"));
        assert!(l.radio.outbound.is_empty());
    }

    #[test]
    fn a_frame_from_the_peer_reaches_the_tunnel() {
        let mut l = link();
        let packet = ipv6_to(&node().addr.link_local(), 12);
        l.radio.inbound.push(frame::data_frame(
            peer(),
            node().addr,
            1,
            1,
            &packet,
            36,
        ));
        let mut buf = [0u8; 2048];
        assert!(l.down(&mut buf, 2_000_000).expect("down"));
        assert_eq!(l.tunnel.outbound[0], packet);
        assert_eq!(l.stats.rx_packets, 1);
        assert_eq!(l.stats.rx_bytes, packet.len() as u64);
    }

    #[test]
    fn a_multicast_frame_is_accepted() {
        let mut l = link();
        let mut group = [0u8; 16];
        group[0] = 0xff;
        group[1] = 0x02;
        group[15] = 0xfb;
        let packet = ipv6_to(&group, 20);
        l.radio.inbound
            .push(frame::data_frame(peer(), Mac::MDNS_V6, 1, 1, &packet, 36));
        let mut buf = [0u8; 2048];
        assert!(l.down(&mut buf, 2_000_000).expect("down"));
        assert_eq!(l.tunnel.outbound[0], packet);
    }

    #[test]
    fn an_echo_of_our_own_frame_never_returns_to_the_kernel() {
        let mut l = link();
        let packet = ipv6_to(&peer().link_local(), 8);
        l.radio.inbound.push(frame::data_frame(
            node().addr,
            peer(),
            1,
            1,
            &packet,
            36,
        ));
        let mut buf = [0u8; 2048];
        assert!(!l.down(&mut buf, 2_000_000).expect("down"));
        assert!(l.tunnel.outbound.is_empty());
        assert_eq!(l.stats.rx_echo, 1);
    }

    #[test]
    fn a_conversation_between_two_other_nodes_is_dropped() {
        let mut l = link();
        let other = Mac::parse("02:00:00:00:00:01").expect("valid");
        let packet = ipv6_to(&other.link_local(), 8);
        l.radio.inbound
            .push(frame::data_frame(peer(), other, 1, 1, &packet, 36));
        let mut buf = [0u8; 2048];
        assert!(!l.down(&mut buf, 2_000_000).expect("down"));
        assert!(l.tunnel.outbound.is_empty());
        assert_eq!(l.stats.rx_not_ours, 1);
    }

    #[test]
    fn a_malformed_frame_is_counted_not_fatal() {
        let mut l = link();
        l.radio.inbound.push(vec![0u8; 3]);
        let mut buf = [0u8; 2048];
        assert!(!l.down(&mut buf, 2_000_000).expect("down"));
        assert_eq!(l.stats.rx_malformed, 1);
    }

    #[test]
    fn an_empty_radio_is_not_an_error() {
        let mut l = link();
        let mut buf = [0u8; 2048];
        assert!(!l.down(&mut buf, 2_000_000).expect("down"));
        assert!(!l.up(&mut buf, 0).expect("up"));
    }

    /// A presence frame from a node that is not this one, plus the boundary it announces.
    ///
    /// `anchored_us` is the peer's own slot origin, so passing a value a little in the
    /// past is what makes its boundary land somewhere other than ours — which is the
    /// whole condition these tests are about.
    fn peer_frame(mac: Mac, anchored_us: u64, at_us: u64) -> (Vec<u8>, u8) {
        let peer = NodeInfo::master_of(mac, "iPhone", 149);
        let schedule = Schedule::anchored(anchored_us, 149);
        let frame = action::presence(&peer, &schedule, at_us, 1, false, 36);
        let next_tu = action::clock_of(&frame).expect("a clock").0;
        (frame, next_tu)
    }

    #[test]
    fn a_peer_presence_frame_is_followed_onto_its_boundary() {
        let at = 3_000_000u64;
        // The peer's boundary is twenty TU before ours, so adopting it is a visible
        // move rather than a coincidence.
        let (frame, next_tu) = peer_frame(peer(), at - 20 * TU_US, at);
        assert_eq!(next_tu, 44);

        let mut l = Link::new(Mock::new(), Mock::new(), node(), at, 36);
        assert_eq!(l.schedule().next_slot_tu(at), 64, "we start anchored here");

        l.radio.inbound.push(frame);
        let mut buf = [0u8; 2048];
        assert!(!l.down(&mut buf, at).expect("down"));

        assert_eq!(l.peer(), Some(peer()));
        assert_eq!(l.stats().anchored, 1);
        assert_eq!(
            l.schedule().next_slot_tu(at),
            next_tu,
            "our frames now land in the peer's window"
        );
    }

    #[test]
    fn an_iphone_rank_does_not_unseat_us() {
        let at = 3_000_000u64;
        let (frame, _) = peer_frame(peer(), at - 20 * TU_US, at);
        let mut l = Link::new(Mock::new(), Mock::new(), node(), at, 36);

        l.radio.inbound.push(frame);
        let mut buf = [0u8; 2048];
        l.down(&mut buf, at).expect("down");

        // 1024 beats the 513 a live iPhone announces, so copying its master would be a
        // follower's frame carrying a winner's rank.
        assert_eq!(l.node().master, node().addr);
        assert_eq!(l.node().height, 0);
        assert_eq!(l.stats().anchored, 1, "the clock is a separate decision");
    }

    #[test]
    fn a_peer_that_outranks_us_becomes_the_master_we_name() {
        let at = 3_000_000u64;
        let root = Mac::parse("02:00:00:00:00:99").expect("valid");
        let mut stronger = NodeInfo::master_of(peer(), "Mac", 149);
        stronger.master = root;
        stronger.master_metric = 1280;
        stronger.self_metric = 1280;
        let schedule = Schedule::anchored(at - 20 * TU_US, 149);
        let frame = action::presence(&stronger, &schedule, at, 1, false, 36);

        let mut l = Link::new(Mock::new(), Mock::new(), node(), at, 36);
        l.radio.inbound.push(frame);
        let mut buf = [0u8; 2048];
        l.down(&mut buf, at).expect("down");

        assert_eq!(l.node().master, root);
        assert_eq!(l.node().height, 1, "one hop further from the root");
        assert_eq!(l.node().master_metric, 1280);
    }

    #[test]
    fn a_stranger_does_not_move_the_clock() {
        let at = 3_000_000u64;
        let (first, _) = peer_frame(peer(), at - 20 * TU_US, at);
        let mut l = Link::new(Mock::new(), Mock::new(), node(), at, 36);
        l.radio.inbound.push(first);
        let mut buf = [0u8; 2048];
        l.down(&mut buf, at).expect("down");
        let boundary = l.schedule().next_slot_tu(at);
        let anchored = l.stats().anchored;

        // A different sender, announcing a boundary a long way from both.
        let stranger = Mac::parse("02:00:00:00:00:77").expect("valid");
        let (second, next_tu) = peer_frame(stranger, at - 50 * TU_US, at);
        assert_eq!(next_tu, 14, "a reading that would visibly move us");
        l.radio.inbound.push(second);
        l.down(&mut buf, at).expect("down");

        assert_eq!(
            l.peer(),
            Some(peer()),
            "the peer is kept for as long as it is heard"
        );
        assert_eq!(l.schedule().next_slot_tu(at), boundary);
        assert_eq!(l.stats().anchored, anchored);
    }

    #[test]
    fn an_anchor_that_went_silent_is_handed_to_the_peer_that_is_still_there() {
        // The defect this pins: `peer` used to be written once and never cleared, so a
        // phone that re-randomised its AWDL address between sessions left this link
        // following a superframe nobody was on, for the life of the daemon. Measured on
        // this phone, a run that anchored at 03:36 was still naming that address at 09:00
        // while dropping every one of the dozen presence frames per second arriving from
        // the device in the room.
        let at = 3_000_000u64;
        let (frame, _) = peer_frame(peer(), at - 20 * TU_US, at);
        let mut l = Link::new(Mock::new(), Mock::new(), node(), at, 36);
        l.radio.inbound.push(frame);
        let mut buf = [0u8; 2048];
        l.down(&mut buf, at).expect("down");
        assert_eq!(l.peer(), Some(peer()));

        // The address the phone re-randomised to, arriving after the old one has been
        // quiet for longer than a presence cadence can explain.
        let later = at + PEER_SILENCE_US + 1;
        let moved = Mac::parse("52:3e:14:c7:8c:ad").expect("valid");
        let before = l.stats().anchored;
        let (frame, _) = peer_frame(moved, at - 20 * TU_US, later);
        l.radio.inbound.push(frame);
        l.down(&mut buf, later).expect("down");

        assert_eq!(l.peer(), Some(moved), "the anchor moved to the live peer");
        assert_eq!(l.stats().anchor_released, 1, "and said so");
        assert_eq!(
            l.stats().anchored,
            before + 1,
            "the new peer's boundary is followed, which the old anchor could never do"
        );
        assert_eq!(
            l.schedule().peer_map(),
            None,
            "the departed peer's slot map is not obeyed through the change"
        );
    }

    #[test]
    fn a_whole_slot_of_error_is_refused() {
        let at = 3_000_000u64;
        // The peer's boundary is one TU away and ours is a whole slot away. A reading
        // that far out is this loop's own queue rather than the peer's clock, and
        // adopting it would aim a slot off the window for as long as the next sample
        // takes to undo it.
        let (frame, next_tu) = peer_frame(peer(), at - 63 * TU_US, at);
        assert_eq!(next_tu, 1);

        let mut l = Link::new(Mock::new(), Mock::new(), node(), at, 36);
        l.radio.inbound.push(frame);
        let mut buf = [0u8; 2048];
        l.down(&mut buf, at).expect("down");

        assert_eq!(l.peer(), Some(peer()), "the peer is still learned");
        assert_eq!(l.phase_err_tu(), 63, "and the reading is a whole slot");
        assert_eq!(l.stats().anchored, 0, "but the clock is not moved onto it");
        assert_eq!(l.schedule().next_slot_tu(at), 64, "the anchor stayed put");
    }

    #[test]
    fn the_refusal_is_bounded_so_a_clock_can_never_freeze() {
        // Eight readings in a row are refused; the ninth is believed. Without the bound
        // a node whose own clock is wrong would refuse forever and never join.
        let at = 3_000_000u64;
        let mut l = Link::new(Mock::new(), Mock::new(), node(), at, 36);
        let mut buf = [0u8; 2048];
        let mut last = 0u8;
        for i in 0..=ANCHOR_REJECT_LIMIT {
            let (frame, next_tu) = peer_frame(peer(), at - 63 * TU_US, at);
            last = next_tu;
            l.radio.inbound.push(frame);
            l.down(&mut buf, at).expect("down");
            let expected = u64::from(i == ANCHOR_REJECT_LIMIT);
            assert_eq!(l.stats().anchored, expected, "after {} frames", i + 1);
        }
        assert_eq!(l.schedule().next_slot_tu(at), last, "believed on the last one");
    }

    #[test]
    fn the_psf_keeps_its_period() {
        let mut l = link();
        let t0 = 1_000_000u64;
        assert!(l.announce(t0).expect("first"));
        assert!(!l.announce(t0 + 1000).expect("too soon"));
        let due = u64::from(PSF_INTERVAL_TU) * TU_US;
        assert!(l.announce(t0 + due).expect("due"));
        assert_eq!(l.stats.mif, 1, "its mid-slot MIF had come due in the same call");
        let psfs = l
            .radio
            .outbound
            .iter()
            .filter(|f| f[9 + 24 + 6] == action::SUBTYPE_PSF)
            .count();
        assert_eq!(psfs, 2, "a PSF per period, and no more");
    }

    #[test]
    fn every_slot_still_names_the_host_a_minute_in() {
        // A PSF is a clock with no name on it, and AWDL-17 has a peer refuse to treat a
        // sender as a node at all until it has a name for it. So the name cannot be an
        // opening burst: a peer that joins the channel a minute after this node did has
        // to hear it too, or this node spends the rest of the run announcing itself to
        // nobody while every counter here says otherwise.
        let mut l = link();
        let t0 = 1_000_000u64;
        l.announce(t0).expect("first");
        assert_eq!(
            l.radio.outbound[0][9 + 24 + 6],
            action::SUBTYPE_PSF,
            "the first frame on the air is the clock"
        );

        let slot = u64::from(SLOT_TU) * TU_US;
        let mut named = 0usize;
        let mut at = t0;
        while at < t0 + 60_000_000 {
            at += slot / 2;
            if l.announce(at).expect("tick") {
                let last = l.radio.outbound.last().expect("a frame");
                if last[9 + 24 + 6] == action::SUBTYPE_MIF {
                    named += 1;
                }
            }
        }
        assert!(named > 800, "a MIF in every slot of a minute, got {named}");
        assert_eq!(l.stats().mif as usize, named);
    }

    #[test]
    fn presence_due_in_returns_the_earlier_of_the_two() {
        let mut l = link();
        let t0 = 1_000_000u64;
        assert_eq!(l.presence_due_in(t0), 0, "nothing sent yet");
        l.announce(t0).expect("first");
        let slot = u64::from(SLOT_TU) * TU_US;
        assert_eq!(l.presence_due_in(t0), slot / 2, "the mid-slot MIF comes first");
        assert_eq!(l.presence_due_in(t0 + 1000), slot / 2 - 1000);
        assert_eq!(l.presence_due_in(t0 + slot * 8), 0, "saturates");
    }

    /// An idle link must still name a wait, because the daemon sleeps on this number.
    ///
    /// The defect this pins: `send_due_in` answers zero to an empty queue, so a `min` of
    /// the two timers was zero for as long as nothing was queued — and on the device that
    /// showed up as `awdld` holding ~92 % of a core with the link perfectly idle. The
    /// assertion on `send_due_in` is there to say the zero is still real and intended;
    /// it is the composition that has to be right.
    #[test]
    fn an_idle_link_still_has_a_wait_to_name() {
        let mut l = link();
        let t0 = 1_000_000u64;
        l.announce(t0).expect("first");
        let slot = u64::from(SLOT_TU) * TU_US;
        let awake = t0 + slot / 2;
        // The MIF just went out, so nothing is pending and nothing is queued: the queue
        // says zero, the link says half a slot.
        l.announce(awake).expect("mif");
        assert_eq!(l.send_due_in(awake), 0, "the queue is empty");
        assert_eq!(
            l.wake_in(awake),
            l.presence_due_in(awake),
            "an empty queue must not shorten the presence wait"
        );
        assert!(l.wake_in(awake) > 0, "an idle link would spin the poll");
    }

    #[test]
    fn a_refused_frame_is_counted_not_returned() {
        struct Failing;
        impl Radio for Failing {
            fn recv(&mut self, _: &mut [u8]) -> io::Result<Option<usize>> {
                Ok(None)
            }
            fn send(&mut self, _: &[u8]) -> io::Result<()> {
                Err(io::Error::from(io::ErrorKind::BrokenPipe))
            }
        }
        impl Tunnel for Failing {
            fn recv(&mut self, _: &mut [u8]) -> io::Result<Option<usize>> {
                Ok(None)
            }
            fn send(&mut self, _: &[u8]) -> io::Result<()> {
                Ok(())
            }
        }
        let mut l = Link::new(Failing, Failing, node(), 0, 36);
        assert!(!l.announce(0).expect("swallowed"), "not an error");
        assert_eq!(l.stats.tx_errors, 1);
    }
}
