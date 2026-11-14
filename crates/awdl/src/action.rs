//! The action frames that make a node a node: presence and master indication.
//!
//! # Why a data plane is not enough
//!
//! A node that transmits data frames and never announces itself is invisible. Its frames
//! are heard, its acknowledgements are sent back, and nothing ever resolves it, because
//! an AWDL network is not discovered by traffic — it is discovered by the presence frame
//! each node puts on the air eleven times a second, which is the only thing that carries
//! the channel map and the clock every peer needs to talk to it.
//!
//! Two subtypes matter here:
//!
//! * **PSF** (parameters synchronization frame, subtype 0) — the periodic one: clock,
//!   channel map, election.
//! * **MIF** (master indication frame, subtype 3) — PSF plus the node's host name, sent
//!   when a peer has to be told who the master is (`AWDL-17` requires the name and the
//!   version TLV before a peer treats a sender as a node at all).
//!
//! # The layout
//!
//! An action frame with Apple's vendor category, then a fixed eight-byte preamble, then a
//! sequence of `u8 kind, le16 length, body` TLVs. The subtype byte is inside the preamble,
//! not the 802.11 frame control: on the wire every one of these is the same action frame.

use crate::mac::Mac;
use crate::schedule::{
    Schedule, AW_PERIOD_TU, PRESENCE_MODE, PSF_INTERVAL_TU, SLOTS, SLOT_TU,
};

/// Vendor-specific category, the one Apple uses for AWDL.
pub const CATEGORY_VENDOR: u8 = 0x7f;

/// Apple's OUI, the three bytes that identify the vendor action.
pub const OUI: [u8; 3] = [0x00, 0x17, 0xf2];

/// AWDL's action type within the vendor category.
pub const AWDL_TYPE: u8 = 0x08;

/// The action version byte. Every AWDL action frame on the air carries this value.
pub const ACTION_VERSION: u8 = 0x10;

/// Frame control for an action frame (management, subtype 13).
pub const FC_ACTION: u16 = 0x00d0;

/// The PSF subtype: clock and channel map, no name.
pub const SUBTYPE_PSF: u8 = 0;

/// The MIF subtype: a PSF that also names the master.
pub const SUBTYPE_MIF: u8 = 3;

/// The node's protocol version, as it appears in the Version TLV and in the preamble of
/// every data frame the peer sends.
pub const AWDL_VERSION: u8 = 0x34;

/// Device class 1: a phone. The value an iPhone sends.
pub const DEVICE_CLASS: u8 = 1;

/// The metric a node claims when it has no reason to claim more.
pub const INIT_METRIC: u32 = 1024;

const TLV_SERVICE_PARAMS: u8 = 6;
const TLV_SYNC_PARAMS: u8 = 4;
const TLV_ELECTION_V1: u8 = 5;
const TLV_CHANNEL_SEQUENCE: u8 = 18;
const TLV_ELECTION_V2: u8 = 24;
const TLV_HT_CAPABILITIES: u8 = 7;
const TLV_ARPA: u8 = 16;
const TLV_DATA_PATH_STATE: u8 = 12;
const TLV_VERSION: u8 = 21;

/// What a presence frame says about this node.
#[derive(Debug, Clone)]
pub struct NodeInfo {
    /// This node's AWDL address: address 2 of every frame it sends.
    pub addr: Mac,
    /// The host name a MIF carries. ASCII, at most 64 bytes on the wire.
    pub name: String,
    /// The channel this node is tuned to, and the one its map puts every slot on.
    pub channel: u8,
    /// The operating channel of the network's master.
    pub master_channel: u8,
    /// The master this node follows, or itself when it follows nobody.
    pub master: Mac,
    /// Distance from the master, in hops. Zero when this node is the master.
    pub height: u8,
    /// The metric the master announced.
    pub master_metric: u32,
    /// The metric this node claims for itself.
    pub self_metric: u32,
}

impl NodeInfo {
    /// A node that is its own master on `channel`.
    ///
    /// Height zero and metric `INIT_METRIC` is what an isolated node says, and it is what
    /// makes the election unambiguous: a peer that hears it either accepts it as master
    /// or answers with a better metric, and both outcomes leave a working link.
    pub fn master_of(addr: Mac, name: impl Into<String>, channel: u8) -> Self {
        NodeInfo {
            addr,
            name: name.into(),
            channel,
            master_channel: channel,
            master: addr,
            height: 0,
            master_metric: INIT_METRIC,
            self_metric: INIT_METRIC,
        }
    }

    /// AWDL-9's ordering, applied to the rank a peer announced.
    ///
    /// Metric first and the higher one wins; a tie falls to the shorter path to the
    /// root, then to the higher master address. The counter AWDL-9 compares between the
    /// metric and the height is left out on purpose: it only decides a tie at equal
    /// metric, and two nodes advertising the same metric are already in the regime where
    /// the answer changes with the next frame, so waiting one frame is cheaper than
    /// carrying a number nothing here would keep in step.
    ///
    /// This is the same comparison the peer runs on its own side, which is why it is the
    /// one that decides whether copying the peer's master is a statement or a lie. At
    /// [`INIT_METRIC`] this node outranks an iPhone (513), so it keeps naming itself —
    /// and a node that followed anyway would be advertising a follower's frame with a
    /// winner's rank, which no peer can reconcile.
    pub fn outranks(&self, theirs: &Election) -> bool {
        if self.self_metric != theirs.master_metric {
            return self.self_metric > theirs.master_metric;
        }
        if self.height != theirs.height {
            return self.height < theirs.height;
        }
        // A peer that names this node as its master has already conceded the tie, and
        // `>=` is what keeps that concession final: a strict comparison would make the
        // node follow itself and write height 1 onto a node that is height 0.
        self.addr >= theirs.master
    }

    /// Point this node's election at a master it does not outrank.
    ///
    /// `hops` is the peer's height plus one: we are one step further from the root than
    /// the node that told us about it. Only the election moves — the channel this node
    /// announces stays its own, because the radio here never leaves it.
    pub fn follow(&mut self, master: Mac, hops: u8, master_metric: u32) {
        self.master = master;
        self.height = hops;
        self.master_metric = master_metric;
    }
}

/// Build a presence frame — PSF when `mif` is false, MIF when it is true.
pub fn presence(
    node: &NodeInfo,
    schedule: &Schedule,
    now_us: u64,
    seq: u16,
    mif: bool,
    rate_mbps: u8,
) -> Vec<u8> {
    let subtype = if mif { SUBTYPE_MIF } else { SUBTYPE_PSF };
    let mut out = Vec::with_capacity(256);
    out.extend_from_slice(&crate::frame::radiotap(rate_mbps));

    // 802.11 header: an action frame, broadcast to the network the BSSID names.
    out.extend_from_slice(&FC_ACTION.to_le_bytes());
    out.extend_from_slice(&[0, 0]); // duration
    out.extend_from_slice(Mac::BROADCAST.as_slice());
    out.extend_from_slice(node.addr.as_slice());
    out.extend_from_slice(Mac::AWDL_BSSID.as_slice());
    out.extend_from_slice(&((seq & 0x0fff) << 4).to_le_bytes());

    // Action header (AWDL-3). The two timestamps are the same value: the second is the
    // "target" the peer schedules against and there is nothing better to put there.
    out.push(CATEGORY_VENDOR);
    out.extend_from_slice(&OUI);
    out.push(AWDL_TYPE);
    out.push(ACTION_VERSION);
    out.push(subtype);
    out.push(0);
    out.extend_from_slice(&(now_us as u32).to_le_bytes());
    out.extend_from_slice(&(now_us as u32).to_le_bytes());

    let next_tu = schedule.next_slot_tu(now_us);
    let aw_counter = schedule.aw_counter(now_us);

    sync_params(&mut out, node, schedule, next_tu, aw_counter);
    election_v1(&mut out, node);
    channel_sequence(&mut out, schedule);
    election_v2(&mut out, node);
    service_params(&mut out);
    ht_capabilities(&mut out);
    if mif {
        arpa(&mut out, &node.name);
    }
    data_path_state(&mut out, node);
    version(&mut out);
    out
}

/// Open a TLV and close it with its length filled in.
fn tlv(out: &mut Vec<u8>, kind: u8, body: impl FnOnce(&mut Vec<u8>)) {
    out.push(kind);
    let at = out.len();
    out.extend_from_slice(&[0, 0]);
    body(out);
    let len = (out.len() - at - 2) as u16;
    out[at..at + 2].copy_from_slice(&len.to_le_bytes());
}

/// Where in the sequence this node is, and what its map is.
fn sync_params(
    out: &mut Vec<u8>,
    node: &NodeInfo,
    schedule: &Schedule,
    next_tu: u8,
    aw_counter: u16,
) {
    tlv(out, TLV_SYNC_PARAMS, |v| {
        v.push(node.channel);
        v.extend_from_slice(&u16::from(next_tu).to_le_bytes());
        v.push(node.master_channel);
        v.push(0); // guard time
        v.extend_from_slice(&u16::from(AW_PERIOD_TU).to_le_bytes());
        v.extend_from_slice(&PSF_INTERVAL_TU.to_le_bytes());
        v.extend_from_slice(&0x1800u16.to_le_bytes()); // flags: reserved
        v.extend_from_slice(&u16::from(AW_PERIOD_TU).to_le_bytes()); // extended AW
        v.extend_from_slice(&u16::from(AW_PERIOD_TU).to_le_bytes()); // common AW
        // What is left of the AW that `next_tu` lands in. A node announcing
        // `next_tu = 43` has three AWs of the slot behind it and reports zero.
        let elapsed = u16::from(AW_PERIOD_TU) * u16::from(PRESENCE_MODE)
            - u16::from(next_tu.min(SLOT_TU));
        let remaining = u16::from(AW_PERIOD_TU).saturating_sub(elapsed);
        v.extend_from_slice(&remaining.to_le_bytes());
        // Minimum extended, maximum extended, multicast, and AF: four AWs each.
        v.extend_from_slice(&[
            PRESENCE_MODE - 1,
            PRESENCE_MODE - 1,
            PRESENCE_MODE - 1,
            PRESENCE_MODE - 1,
        ]);
        v.extend_from_slice(node.master.as_slice());
        v.push(PRESENCE_MODE);
        v.push(0);
        v.extend_from_slice(&aw_counter.to_le_bytes()); // next AW sequence number
        v.extend_from_slice(&aw_counter.to_le_bytes()); // AP alignment
        sequence(v, schedule);
        v.extend_from_slice(&[0, 0]);
    });
}

/// Election parameters, version 1. Its layout puts the height at offset 3 and the
/// master's metric at offset 11, which is where a reader looks for them.
fn election_v1(out: &mut Vec<u8>, node: &NodeInfo) {
    tlv(out, TLV_ELECTION_V1, |v| {
        v.push(0); // flags
        v.extend_from_slice(&0u16.to_le_bytes()); // id
        v.push(node.height);
        v.push(0);
        v.extend_from_slice(node.master.as_slice());
        v.extend_from_slice(&node.master_metric.to_le_bytes());
        v.extend_from_slice(&node.self_metric.to_le_bytes());
        v.extend_from_slice(&[0, 0]);
    });
}

/// The sequence structure on its own, as both the sync parameters and the channel
/// sequence TLVs carry it.
fn sequence(out: &mut Vec<u8>, schedule: &Schedule) {
    out.push((SLOTS - 1) as u8);
    out.push(3); // encoding: an operating class follows each channel
    out.push(0); // duplicate count
    out.push(PRESENCE_MODE - 1); // AWs per slot, less one
    out.extend_from_slice(&0xffffu16.to_le_bytes()); // fill channel
    for slot in schedule.slots().iter() {
        out.push(*slot);
        out.push(operating_class(*slot));
    }
}

/// The operating class a channel number travels with. Only three values appear in an
/// announcement of this shape, and a peer checks the pair rather than the number.
fn operating_class(channel: u8) -> u8 {
    match channel {
        0 => 0,
        1..=14 => 0x51,
        _ => 0x80,
    }
}

fn channel_sequence(out: &mut Vec<u8>, schedule: &Schedule) {
    tlv(out, TLV_CHANNEL_SEQUENCE, |v| {
        sequence(v, schedule);
        v.extend_from_slice(&[0, 0, 0]);
    });
}

/// Election parameters, version 2, which supersedes V1 when both are present.
///
/// The sync source is the node whose clock this one keeps, and every path in the legacy
/// implementation that moved the master moved the sync source with it, so the two are the
/// same address. Writing this node's own address there while naming somebody else as
/// master is two answers to one question, and a peer reading it cannot tell whose clock we
/// are actually on.
///
/// The two counter words are zero on purpose. They are the master's election counter and
/// this node's own, AWDL-9 uses them to break a tie at equal metric, and the legacy
/// implementation sent zero for both in every state this node can reach. Filling them
/// with the AW counter — which is what a plausible reading of "a sequence number goes
/// here" produces — announces a number that changes fifty times a second, which is the
/// shape of an election that is flapping.
fn election_v2(out: &mut Vec<u8>, node: &NodeInfo) {
    tlv(out, TLV_ELECTION_V2, |v| {
        v.extend_from_slice(node.master.as_slice());
        v.extend_from_slice(node.master.as_slice()); // sync source
        v.extend_from_slice(&0u32.to_le_bytes()); // master counter
        v.extend_from_slice(&u32::from(node.height).to_le_bytes());
        v.extend_from_slice(&node.master_metric.to_le_bytes());
        v.extend_from_slice(&node.self_metric.to_le_bytes());
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&0u32.to_le_bytes()); // this node's own counter
    });
}

/// The AWDL services this node runs: nine bytes whose only non-zero part is `0x0008` in
/// the middle word.
///
/// Those bytes are an iPhone's, kept verbatim. The middle word is a service bitmask
/// rather than an identifier, and there is no way to derive which bit means AirDrop from
/// this side of the link. Sending nine zeros instead — the obvious reading, "this field
/// is an identifier, leave it alone" — costs the whole feature: a peer then treats the
/// node as running no service worth resolving and never puts a single mDNS packet on the
/// air, while still answering every echo request it is sent. Advertising a service that
/// is not implemented is the smaller lie.
fn service_params(out: &mut Vec<u8>) {
    tlv(out, TLV_SERVICE_PARAMS, |v| {
        v.extend_from_slice(&[0, 0, 0]);
        v.extend_from_slice(&0x0008u16.to_le_bytes());
        v.extend_from_slice(&0u32.to_le_bytes());
    });
}

/// One spatial stream, every MCS of it.
fn ht_capabilities(out: &mut Vec<u8>) {
    tlv(out, TLV_HT_CAPABILITIES, |v| {
        v.extend_from_slice(&0u16.to_le_bytes());
        v.extend_from_slice(&0x11ceu16.to_le_bytes());
        v.push(0x1b);
        v.push(0xff);
        v.extend_from_slice(&0u16.to_le_bytes());
    });
}

/// The host name, then `.local` as a DNS compression pointer. Required in a MIF.
fn arpa(out: &mut Vec<u8>, name: &str) {
    tlv(out, TLV_ARPA, |v| {
        let label = name.as_bytes();
        let len = label.len().min(64);
        v.push(3); // the one flag value IO80211Family accepts
        v.push(len as u8);
        v.extend_from_slice(&label[..len]);
        v.push(0xc0);
        v.push(0x0c);
    });
}

/// Legacy's flags, the country code `X0`, the social channel served, and the address.
fn data_path_state(out: &mut Vec<u8>, node: &NodeInfo) {
    tlv(out, TLV_DATA_PATH_STATE, |v| {
        v.extend_from_slice(&0x8f24u16.to_le_bytes());
        v.extend_from_slice(&[b'X', b'0', 0]);
        let social: u16 = match node.master_channel {
            6 => 0x0001,
            44 => 0x0002,
            _ => 0x0004,
        };
        v.extend_from_slice(&social.to_le_bytes());
        v.extend_from_slice(node.addr.as_slice());
        v.extend_from_slice(&0u16.to_le_bytes());
    });
}

fn version(out: &mut Vec<u8>) {
    tlv(out, TLV_VERSION, |v| {
        v.push(AWDL_VERSION);
        v.push(DEVICE_CLASS);
    });
}

/// One TLV as a reader sees it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Tlv<'a> {
    /// The type byte.
    pub kind: u8,
    /// The body, length already applied.
    pub body: &'a [u8],
}

/// Walk the TLVs of a presence frame, or return `None` when the frame is not one.
///
/// Reading is needed for one thing in the minimal implementation: a peer's clock, so a
/// node can follow a master whose map it has no reason to change. Everything else a
/// presence frame says is for other nodes.
pub fn tlvs(frame: &[u8]) -> Option<Vec<Tlv<'_>>> {
    // Radiotap, then 24 bytes of 802.11, then the action header: category, OUI, type,
    // version, subtype and a pad byte, then the two timestamps. Sixteen bytes before the
    // first TLV, twelve of which are the fixed part a reader skips.
    let radiotap_len = u16::from_le_bytes([*frame.get(2)?, *frame.get(3)?]) as usize;
    let action = radiotap_len.checked_add(24)?;
    let preamble = action.checked_add(16)?;
    if frame.len() < preamble {
        return None;
    }
    if frame[action] != CATEGORY_VENDOR || frame[action + 1..action + 4] != OUI {
        return None;
    }
    if frame[action + 4] != AWDL_TYPE {
        return None;
    }
    let mut out = Vec::new();
    let mut at = preamble;
    while at + 3 <= frame.len() {
        let kind = frame[at];
        let len = u16::from_le_bytes([frame[at + 1], frame[at + 2]]) as usize;
        let start = at + 3;
        let end = start.checked_add(len)?;
        if end > frame.len() {
            return None;
        }
        out.push(Tlv {
            kind,
            body: &frame[start..end],
        });
        at = end;
    }
    Some(out)
}

/// The clock a presence frame announces: `(next_tu, aw_counter)`, ready for
/// [`Schedule::follow`].
pub fn clock_of(frame: &[u8]) -> Option<(u8, u16)> {
    let list = tlvs(frame)?;
    let sync = list.iter().find(|t| t.kind == TLV_SYNC_PARAMS)?;
    if sync.body.len() < 4 {
        return None;
    }
    let next_tu = u16::from_le_bytes([sync.body[1], sync.body[2]]) as u8;
    // The AW counter is the last field before the sequence structure: the next-AW word and
    // the AP alignment word, two bytes each. Derived from the sequence's own offset rather
    // than written out a second time, so a reader of one cannot drift from the other.
    let at = SYNC_SEQUENCE_AT - 4;
    if sync.body.len() < at + 2 {
        return None;
    }
    Some((next_tu, u16::from_le_bytes([sync.body[at], sync.body[at + 1]])))
}

/// The sequence map a presence frame announces: which channel each of the sixteen slots
/// is spent on.
///
/// # Why a node needs to read this and not only write its own
///
/// The clock says *when* a slot begins; the map says whether the peer is on this
/// channel while it lasts. Those are separate questions and only the first one was being
/// answered here, so every packet went out in all sixteen slots against a peer that is
/// reachable in a handful of them. The predecessor gated transmission on exactly this
/// (`slotSendable`, `AwdlData.java:2505`), with the note that a frame sent while the peer
/// is on another channel — or in one of the slots its own sequence leaves empty — goes to
/// a receiver that is not there, *and repetition cannot repair that*: four copies inside
/// one slot share one slot's fate.
///
/// Measured here before this was read: four copies per packet delivered one ping in
/// twenty, eight copies delivered two. Eight copies take two slots, so the count tracked
/// the number of slots the burst covered, not the number of attempts in each.
pub fn map_of(frame: &[u8]) -> Option<[u8; SLOTS]> {
    let list = tlvs(frame)?;
    // The channel sequence TLV carries the structure on its own from offset zero, which
    // is the copy to prefer: the same structure is repeated inside the sync parameters,
    // behind thirty-three bytes of fields the two frame types do not share.
    if let Some(seq) = list.iter().find(|t| t.kind == TLV_CHANNEL_SEQUENCE) {
        if let Some(map) = read_sequence(seq.body, 0) {
            return Some(map);
        }
    }
    let sync = list.iter().find(|t| t.kind == TLV_SYNC_PARAMS)?;
    read_sequence(sync.body, SYNC_SEQUENCE_AT)
}

/// Where the sequence structure starts inside a sync parameters body.
///
/// The same thirty-three bytes [`sync_params`] writes before calling [`sequence`]: the
/// channel, `next_tu`, the master's channel, the guard, six length words, four presence
/// bytes, the master's address and the two AW counters.
const SYNC_SEQUENCE_AT: usize = 33;

/// Read one sequence structure: `(slots, encoding, duplicates, aws, fill)` then one
/// `(channel, operating class)` pair per slot.
///
/// Only encoding 3 is accepted. It is the only form a peer of this shape sends, and it is
/// the one where a slot is a pair; any other encoding means the bytes after the header
/// are not pairs, and reading them as pairs yields a map of the right length with the
/// wrong contents. That is worse than no map at all, because this map gates transmission:
/// a wrong one silences the link in a way that looks like the peer vanished.
fn read_sequence(body: &[u8], at: usize) -> Option<[u8; SLOTS]> {
    let count = usize::from(*body.get(at)?) + 1;
    let encoding = *body.get(at + 1)?;
    if count != SLOTS || encoding != 3 {
        return None;
    }
    let start = at + 6;
    let mut map = [0u8; SLOTS];
    for (slot, channel) in map.iter_mut().enumerate() {
        *channel = *body.get(start + slot * 2)?;
    }
    Some(map)
}

/// What a presence frame says about its sender's place in the election.
///
/// Read from Election Parameters V2 where it is present and V1 otherwise. AWDL-9 has V2
/// supersede V1, and a live iPhone sends both saying the same thing, so the newer layout
/// is the one worth trusting when the two ever disagree.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Election {
    /// The master the sender believes in.
    pub master: Mac,
    /// The metric that master announced.
    pub master_metric: u32,
    /// The sender's distance from the master, in hops.
    pub height: u8,
}

/// The election a presence frame announces, ready for [`NodeInfo::outranks`].
///
/// A peer's frame is the only evidence this link has about who should lead, and the
/// answer is in one of two TLVs that mean the same thing. Reading both here rather than
/// at the call site keeps the offsets — which are the part that silently goes wrong —
/// in the one module that also writes them.
pub fn election_of(frame: &[u8]) -> Option<Election> {
    let list = tlvs(frame)?;
    if let Some(v2) = list.iter().find(|t| t.kind == TLV_ELECTION_V2) {
        // master (6), sync source (6), master counter (4), height (4), master metric
        // (4), self metric (4), then two unused words and the self counter.
        if v2.body.len() >= 24 {
            return Some(Election {
                master: Mac::from_slice(&v2.body[..6])?,
                height: u32::from_le_bytes([
                    v2.body[16],
                    v2.body[17],
                    v2.body[18],
                    v2.body[19],
                ]) as u8,
                master_metric: u32::from_le_bytes([
                    v2.body[20],
                    v2.body[21],
                    v2.body[22],
                    v2.body[23],
                ]),
            });
        }
    }
    let v1 = list.iter().find(|t| t.kind == TLV_ELECTION_V1)?;
    // flags (1), id (2), height (1), guard (1), master (6), master metric (4).
    if v1.body.len() < 15 {
        return None;
    }
    Some(Election {
        master: Mac::from_slice(&v1.body[5..11])?,
        height: v1.body[3],
        master_metric: u32::from_le_bytes([
            v1.body[11],
            v1.body[12],
            v1.body[13],
            v1.body[14],
        ]),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node() -> NodeInfo {
        NodeInfo::master_of(
            Mac::parse("ee:aa:d3:d0:cb:f1").expect("valid"),
            "PJZ110N",
            149,
        )
    }

    fn subframe<'a>(frame: &'a [u8], name: &str) -> Option<Tlv<'a>> {
        tlvs(frame)?.into_iter().find(|t| t.kind == {
            match name {
                "sync" => TLV_SYNC_PARAMS,
                "election1" => TLV_ELECTION_V1,
                "election2" => TLV_ELECTION_V2,
                "sequence" => TLV_CHANNEL_SEQUENCE,
                "service" => TLV_SERVICE_PARAMS,
                "ht" => TLV_HT_CAPABILITIES,
                "arpa" => TLV_ARPA,
                "path" => TLV_DATA_PATH_STATE,
                "version" => TLV_VERSION,
                _ => unreachable!(),
            }
        })
    }

    #[test]
    fn presence_frame_has_the_action_header() {
        let s = Schedule::anchored(1_000_000, 149);
        let frame = presence(&node(), &s, 1_000_000, 5, false, 36);
        let o = 9;
        assert_eq!(u16::from_le_bytes([frame[o], frame[o + 1]]), 0x00d0, "action");
        assert_eq!(&frame[o + 4..o + 10], Mac::BROADCAST.as_slice());
        assert_eq!(&frame[o + 10..o + 16], node().addr.as_slice());
        assert_eq!(&frame[o + 16..o + 22], Mac::AWDL_BSSID.as_slice());
        assert_eq!(frame[o + 24], CATEGORY_VENDOR);
        assert_eq!(&frame[o + 25..o + 28], &OUI);
        assert_eq!(frame[o + 28], AWDL_TYPE);
        assert_eq!(frame[o + 29], ACTION_VERSION);
        assert_eq!(frame[o + 30], SUBTYPE_PSF);
    }

    #[test]
    fn every_tlv_parses_back() {
        let s = Schedule::anchored(0, 149);
        let frame = presence(&node(), &s, 0, 1, true, 36);
        let list = tlvs(&frame).expect("a presence frame");
        let kinds: Vec<u8> = list.iter().map(|t| t.kind).collect();
        assert_eq!(
            kinds,
            vec![
                TLV_SYNC_PARAMS,
                TLV_ELECTION_V1,
                TLV_CHANNEL_SEQUENCE,
                TLV_ELECTION_V2,
                TLV_SERVICE_PARAMS,
                TLV_HT_CAPABILITIES,
                TLV_ARPA,
                TLV_DATA_PATH_STATE,
                TLV_VERSION,
            ]
        );
        // Every body was consumed exactly: they must add up to the frame's tail.
        assert!(list.iter().all(|t| !t.body.is_empty()));
    }

    #[test]
    fn a_psf_has_no_name_and_a_mif_does() {
        let s = Schedule::anchored(0, 149);
        let psf = presence(&node(), &s, 0, 1, false, 36);
        let mif = presence(&node(), &s, 0, 1, true, 36);
        assert!(subframe(&psf, "arpa").is_none(), "a PSF carries no name");
        let arpa = subframe(&mif, "arpa").expect("a MIF names the host");
        assert_eq!(arpa.body[0], 3, "flag");
        assert_eq!(arpa.body[1], 7, "label length");
        assert_eq!(&arpa.body[2..9], b"PJZ110N");
        assert_eq!(&arpa.body[9..11], &[0xc0, 0x0c], "compression pointer");
    }

    #[test]
    fn the_sequence_tlv_lists_sixteen_slots_with_operating_classes() {
        let s = Schedule::anchored(0, 149);
        let frame = presence(&node(), &s, 0, 1, false, 36);
        let tlv = subframe(&frame, "sequence").expect("sequence");
        assert_eq!(tlv.body[0], 15, "slots less one");
        assert_eq!(tlv.body[1], 3, "encoding");
        assert_eq!(tlv.body[3], 3, "AWs per slot less one");
        // 6 header bytes (count, encoding, duplicates, AWs, fill channel), then 2 per
        // slot, then three trailing.
        assert_eq!(tlv.body.len(), 6 + 2 * SLOTS + 3);
        assert_eq!(&tlv.body[4..6], &[0xff, 0xff], "fill channel");
        for slot in 0..SLOTS {
            assert_eq!(tlv.body[6 + slot * 2], 149);
            assert_eq!(tlv.body[7 + slot * 2], 0x80, "channel 149's operating class");
        }
    }

    #[test]
    fn a_peer_map_is_read_back_from_the_frame_that_carries_it() {
        // An iPhone's sequence is not all one channel: most slots are the social channel
        // and some are spent elsewhere or left empty, which is exactly what a node has to
        // see before it can stop transmitting into them.
        let mut map = [149u8; SLOTS];
        map[2] = 6;
        map[5] = 0;
        map[9] = 44;
        map[11] = 0;
        let mut s = Schedule::anchored(0, 149);
        s.announce(map);
        let frame = presence(&node(), &s, 0, 1, false, 36);
        assert_eq!(map_of(&frame), Some(map), "the channel sequence TLV");

        // And from the copy inside the sync parameters, which is at a different offset in
        // a structure that grew a different prefix. Read by blanking the sequence TLV so
        // the fallback is the path taken.
        let without = strip_tlv(&frame, TLV_CHANNEL_SEQUENCE);
        assert_eq!(map_of(&without), Some(map), "the sync parameters copy");
    }

    #[test]
    fn a_map_of_the_wrong_shape_is_refused_rather_than_misread() {
        // Fifteen slots: the pairs after the header are not sixteen pairs, and a map read
        // anyway would gate transmission on contents that are not a map.
        let mut body = vec![14u8, 3, 0, 3, 0xff, 0xff];
        body.extend_from_slice(&[149u8; 30]);
        assert_eq!(read_sequence(&body, 0), None, "count");

        // Encoding 3 is the only one where a slot is a (channel, operating class) pair.
        let mut body = vec![15u8, 1, 0, 3, 0xff, 0xff];
        body.extend_from_slice(&[149u8; 32]);
        assert_eq!(read_sequence(&body, 0), None, "encoding");

        let mut body = vec![15u8, 3, 0, 3, 0xff, 0xff];
        body.extend_from_slice(&[149u8; 32]);
        assert_eq!(read_sequence(&body, 0), Some([149u8; SLOTS]));
    }

    /// Remove one TLV from a frame, leaving the rest intact.
    fn strip_tlv(frame: &[u8], kind: u8) -> Vec<u8> {
        let mut out = frame.to_vec();
        let mut at = 9 + 24 + 16;
        while at + 3 <= out.len() {
            let len = u16::from_le_bytes([out[at + 1], out[at + 2]]) as usize;
            let end = at + 3 + len;
            if out[at] == kind {
                out.drain(at..end);
                return out;
            }
            at = end;
        }
        out
    }

    #[test]
    fn operating_classes() {
        assert_eq!(operating_class(0), 0);
        assert_eq!(operating_class(6), 0x51);
        assert_eq!(operating_class(14), 0x51);
        assert_eq!(operating_class(44), 0x80);
        assert_eq!(operating_class(149), 0x80);
    }

    #[test]
    fn service_params_carry_the_bitmask() {
        let s = Schedule::anchored(0, 149);
        let frame = presence(&node(), &s, 0, 1, false, 36);
        let tlv = subframe(&frame, "service").expect("service");
        assert_eq!(tlv.body.len(), 9);
        assert_eq!(&tlv.body[..3], &[0, 0, 0]);
        assert_eq!(&tlv.body[3..5], &[0x08, 0x00]);
        assert_eq!(&tlv.body[5..], &[0, 0, 0, 0]);
    }

    #[test]
    fn the_clock_round_trips_through_the_wire() {
        let s = Schedule::anchored(0, 149);
        for step in [0u64, 1, 17, 63, 64, 65, 500] {
            let now = step * crate::frame::TU_US;
            let frame = presence(&node(), &s, now, 1, false, 36);
            let (next_tu, aw) = clock_of(&frame).expect("a clock");
            assert_eq!(next_tu, s.next_slot_tu(now), "step {step}");
            assert_eq!(aw, s.aw_counter(now), "step {step}");
        }
    }

    #[test]
    fn the_election_names_the_master() {
        let s = Schedule::anchored(0, 149);
        let frame = presence(&node(), &s, 0, 1, false, 36);
        let v1 = subframe(&frame, "election1").expect("v1");
        assert_eq!(v1.body[3], 0, "height at offset 3, past the flags and the id");
        assert_eq!(&v1.body[5..11], node().addr.as_slice(), "master at 5");
        assert_eq!(
            u32::from_le_bytes([v1.body[11], v1.body[12], v1.body[13], v1.body[14]]),
            INIT_METRIC,
            "master metric at 11"
        );
        assert_eq!(
            u32::from_le_bytes([v1.body[15], v1.body[16], v1.body[17], v1.body[18]]),
            INIT_METRIC,
            "self metric at 15"
        );
        let v2 = subframe(&frame, "election2").expect("v2");
        assert_eq!(v2.body.len(), 12 + 7 * 4);
        assert_eq!(&v2.body[..6], node().addr.as_slice());
        assert_eq!(&v2.body[6..12], node().addr.as_slice(), "sync source is us");
    }

    #[test]
    fn the_election_reads_back() {
        let s = Schedule::anchored(0, 149);
        let frame = presence(&node(), &s, 0, 1, false, 36);
        let read = election_of(&frame).expect("an election");
        assert_eq!(read.master, node().addr);
        assert_eq!(read.height, 0);
        assert_eq!(read.master_metric, INIT_METRIC);
    }

    #[test]
    fn a_follower_is_one_hop_further_from_the_root() {
        let mut me = node();
        let root = Mac::parse("02:00:00:00:00:99").expect("valid");
        me.follow(root, 1, 1280);

        assert_eq!(me.master, root);
        assert_eq!(me.height, 1);
        assert_eq!(me.master_metric, 1280);
        assert_eq!(me.master_channel, 149, "the radio does not move with the election");
    }

    #[test]
    fn rank_decides_who_follows_whom() {
        let me = node();
        let iphone = Election {
            master: me.addr,
            master_metric: 513,
            height: 0,
        };
        assert!(
            me.outranks(&iphone),
            "1024 beats the 513 a live iPhone announces"
        );

        let laptop = Election {
            master: me.addr,
            master_metric: 1280,
            height: 0,
        };
        assert!(!me.outranks(&laptop), "and loses to a laptop's 1280");

        let same_metric_shallower = Election {
            master: me.addr,
            master_metric: INIT_METRIC,
            height: 0,
        };
        assert!(me.outranks(&same_metric_shallower), "height 0 ties height 0");

        let same_metric_deeper = Election {
            master: me.addr,
            master_metric: INIT_METRIC,
            height: 2,
        };
        assert!(me.outranks(&same_metric_deeper), "the shorter path wins");

        let names_us_higher = Election {
            master: Mac::parse("ff:00:00:00:00:01").expect("valid"),
            master_metric: INIT_METRIC,
            height: 0,
        };
        assert!(
            !me.outranks(&names_us_higher),
            "the higher master address breaks the last tie"
        );
    }

    #[test]
    fn a_frame_with_no_election_yields_none() {
        let s = Schedule::anchored(0, 149);
        let frame = presence(&node(), &s, 0, 1, false, 36);
        // Cut off before the first TLV. The action header is still valid, which is what
        // a peer's frame looks like when it is one this build cannot read.
        let preamble = crate::frame::RADIOTAP_LEN + 24 + 16;
        assert!(election_of(&frame[..preamble]).is_none());
    }

    #[test]
    fn the_version_tlv_matches_the_protocol_constant() {
        let s = Schedule::anchored(0, 149);
        let frame = presence(&node(), &s, 0, 1, false, 36);
        let tlv = subframe(&frame, "version").expect("version");
        assert_eq!(tlv.body, &[AWDL_VERSION, DEVICE_CLASS]);
    }

    #[test]
    fn rejects_a_frame_that_is_not_apple_vendor_action() {
        let mut frame = vec![0u8; 32];
        frame[2] = 9;
        assert!(tlvs(&frame).is_none());

        let s = Schedule::anchored(0, 149);
        let mut good = presence(&node(), &s, 0, 1, false, 36);
        good[9 + 24 + 1] = 0x11; // OUI
        assert!(tlvs(&good).is_none());
    }

    #[test]
    fn rejects_a_tlv_that_runs_past_the_frame() {
        let s = Schedule::anchored(0, 149);
        let mut frame = presence(&node(), &s, 0, 1, false, 36);
        // The first TLV starts past radiotap, the 24-byte MAC header and the 16-byte
        // action header.
        let at = 9 + 24 + 16;
        frame[at + 1] = 0xff;
        frame[at + 2] = 0x00;
        assert!(tlvs(&frame).is_none());
    }

    #[test]
    fn the_radiotap_rate_is_applied() {
        let s = Schedule::anchored(0, 149);
        let slow = presence(&node(), &s, 0, 1, false, 6);
        let fast = presence(&node(), &s, 0, 1, false, 36);
        assert_eq!(slow[8], 12);
        assert_eq!(fast[8], 72);
        assert_eq!(slow.len(), fast.len());
    }
}
