//! Where in the availability-window sequence a node is, and when its next boundary is.
//!
//! # The clock
//!
//! AWDL is time division multiplexed over a repeating sequence. One **availability
//! window** (AW) is 16 time units; because each node is only on the channel for part of
//! each one, the code that runs it expresses presence as a multiple: this node is present
//! for four AWs out of four, so a **slot** — the unit the schedule is written in — is four
//! AWs, 64 time units, 65.5 ms.
//!
//! Sixteen slots make a sequence, about 1.05 s. A node announces, in every frame, which
//! channel each of those sixteen slots is spent on, and every node that hears it is
//! expected to obey the same map.
//!
//! # Anchoring
//!
//! The map is meaningless without a shared origin, and there is no beacon to take one
//! from: the origin is adopted from a peer's frame (`next_tu` and the AW counter in the
//! Synchronization Parameters TLV), or set when no peer is present. [`Schedule::anchored`]
//! sets it, [`Schedule::follow`] adopts a peer's.
//!
//! This node anchors itself and announces a single-channel map — all sixteen slots on the
//! social channel. That is what an isolated node does, and it is also the whole of the
//! minimal implementation: every slot is ours, so a packet never waits for a window.

pub use crate::frame::TU_US;

/// One availability window, in time units.
pub const AW_PERIOD_TU: u8 = 16;

/// How many AWs a node is present for, out of the same number. Four is "always on".
pub const PRESENCE_MODE: u8 = 4;

/// One slot, in time units: `AW_PERIOD_TU * PRESENCE_MODE`.
pub const SLOT_TU: u8 = AW_PERIOD_TU * PRESENCE_MODE;

/// Slots in a sequence.
pub const SLOTS: usize = 16;

/// One slot, in microseconds.
pub const SLOT_US: u64 = SLOT_TU as u64 * TU_US;

/// Identical announcements needed before a peer's map is believed.
pub const SEQ_CONFIRM: u8 = 3;

/// Shortest interval between two applied peer maps; a whole sequence is 1.05 s.
pub const SEQ_HOLD_US: u64 = 1_100_000;

/// What came of one of the peer's announcements.
///
/// The status line prints these, because "the map did not change" has four different
/// causes and only one of them is a broken link.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapVerdict {
    /// Every slot it named is somewhere this radio does not go.
    Refused,
    /// A shape not seen before, or seen fewer than [`SEQ_CONFIRM`] times in a row.
    Learning(u8),
    /// Agreed with the last [`SEQ_CONFIRM`] frames but too soon after the last change.
    Held,
    /// The map this node is already obeying.
    Believed,
    /// A new map, now obeyed.
    Adopted,
}

/// What a peer says about where it is, kept apart from what this node announces.
///
/// One array cannot answer both questions, and the legacy implementation paid for
/// learning it: it copied an iPhone's map into its own announcement and thereby told the
/// phone it was reachable in five slots of sixteen, which is where the phone then sent.
/// The map this node announces is a claim about its own radio, which never leaves
/// `channel`; the map it believes about a peer is a prediction about the peer's receiver
/// and is good for exactly one decision — whether a frame sent now would be heard.
#[derive(Debug, Clone)]
struct PeerMap {
    /// The last shape offered, slot for slot.
    offered: [u8; SLOTS],
    /// Consecutive announcements carrying exactly `offered`.
    streak: u8,
    /// The shape being obeyed, once `streak` reached [`SEQ_CONFIRM`].
    believed: Option<[u8; SLOTS]>,
    /// When `believed` was applied, for [`SEQ_HOLD_US`].
    applied_us: Option<u64>,
}

impl PeerMap {
    const EMPTY: PeerMap = PeerMap {
        offered: [0; SLOTS],
        streak: 0,
        believed: None,
        applied_us: None,
    };
}

/// How often a node must send a presence frame, in time units.
pub const PSF_INTERVAL_TU: u16 = 110;

/// Where a node is in the sequence.
#[derive(Debug, Clone)]
pub struct Schedule {
    /// The instant of a slot boundary, in microseconds on any monotonic clock.
    base_us: u64,
    /// The AW counter at that boundary, which is what a peer's frame reports.
    base_aw: u16,
    /// The channel this node is tuned to.
    channel: u8,
    /// The channel of each of the sixteen slots, as this node announces them.
    slots: [u8; SLOTS],
    /// What the peer is believed to be doing with the same sixteen slots.
    peer: PeerMap,
}

impl Schedule {
    /// Start a sequence at `now_us`, with every slot on `channel`.
    ///
    /// A single-channel map is the honest description of a node that never leaves the
    /// social channel, and it is what makes the minimal data plane possible: there is no
    /// slot in which a transmission would be lost because nothing is listening.
    pub fn anchored(now_us: u64, channel: u8) -> Self {
        Schedule {
            base_us: now_us,
            base_aw: 0,
            channel,
            slots: [channel; SLOTS],
            peer: PeerMap::EMPTY,
        }
    }

    /// Adopt a peer's origin, as reported by its Synchronization Parameters TLV.
    ///
    /// `next_tu` is the peer's distance to its own next slot boundary, so the boundary it
    /// just passed was `SLOT_TU - next_tu` TU ago. Only ever called for the peer being
    /// followed: adopting a stranger's phase puts this node on a schedule its own master
    /// is not on.
    pub fn follow(&mut self, now_us: u64, next_tu: u8, aw_counter: u16) {
        let next = u64::from(next_tu.min(SLOT_TU));
        self.base_us = now_us.saturating_sub((SLOT_TU as u64 - next) * TU_US);
        self.base_aw = aw_counter & !(u16::from(PRESENCE_MODE) - 1);
    }

    /// The channel this node is tuned to.
    pub fn channel(&self) -> u8 {
        self.channel
    }

    /// The map, one channel per slot.
    pub fn slots(&self) -> &[u8; SLOTS] {
        &self.slots
    }

    /// Replace the map this node announces.
    ///
    /// Only for a node that changes channel, and then with a single-channel map: what a
    /// peer announces is never written here. That is the whole point of the split — see
    /// [`PeerMap`] and [`Schedule::offer_map`].
    pub fn announce(&mut self, slots: [u8; SLOTS]) {
        self.slots = slots;
    }

    /// Weigh a peer's announcement and adopt whatever survives three rules.
    ///
    /// The rules are the ones the legacy implementation arrived at, each for a reason it
    /// wrote down:
    ///
    /// * **A map with no slot on this channel is refused.** Every slot it names is
    ///   somewhere this radio does not go, so obeying it is a permanent silence that reads
    ///   exactly like a peer that went away.
    /// * **A shape is believed after [`SEQ_CONFIRM`] consecutive agreeing frames.** The
    ///   phone's map is a live thing — one channel-6 slot moves through the sixteen, and
    ///   it alternates between a six-slot and a two-slot shape on the social channel —
    ///   so adopting each reading as it arrives would rewrite the schedule at frame rate.
    /// * **No two belief changes land closer than [`SEQ_HOLD_US`].** A whole sequence is
    ///   1.05 s, and a change inside that interval is a change made twice on one look.
    ///
    /// What it does *not* do is copy the map. Only the gate reads it, and only through
    /// [`Schedule::owns_now`].
    pub fn offer_map(&mut self, theirs: [u8; SLOTS], now_us: u64) -> MapVerdict {
        if !theirs.contains(&self.channel) {
            self.peer.offered = theirs;
            self.peer.streak = 0;
            return MapVerdict::Refused;
        }
        if theirs != self.peer.offered {
            self.peer.offered = theirs;
            self.peer.streak = 1;
            return MapVerdict::Learning(1);
        }
        self.peer.streak = self.peer.streak.saturating_add(1);
        if self.peer.believed == Some(theirs) {
            // Already obeying it. Asking the hold rule here would report "held" for a map
            // that is in force, which reads as a refusal in a status line.
            return MapVerdict::Believed;
        }
        if self.peer.streak < SEQ_CONFIRM {
            return MapVerdict::Learning(self.peer.streak);
        }
        if let Some(applied) = self.peer.applied_us {
            if now_us.saturating_sub(applied) < SEQ_HOLD_US {
                return MapVerdict::Held;
            }
        }
        self.peer.believed = Some(theirs);
        self.peer.applied_us = Some(now_us);
        MapVerdict::Adopted
    }

    /// The map believed about the peer, if one has survived the confirmation rules.
    pub fn peer_map(&self) -> Option<&[u8; SLOTS]> {
        self.peer.believed.as_ref()
    }

    /// Slots in which the peer says it is somewhere other than this node's channel.
    ///
    /// The number to watch while on the wrong channel: it is how much of the sequence is
    /// spent transmitting at a receiver that is elsewhere.
    pub fn divergence(&self) -> usize {
        match self.peer.believed {
            Some(map) => map.iter().filter(|c| **c != self.channel).count(),
            None => 0,
        }
    }

    /// The believed peer map in the comma form a status line uses, or `none`.
    pub fn describe_peer(&self) -> String {
        match self.peer.believed {
            Some(map) => map
                .iter()
                .map(u8::to_string)
                .collect::<Vec<_>>()
                .join(","),
            None => "none".to_string(),
        }
    }

    /// Whether the peer is on this channel in slot `index`, according to what is believed.
    ///
    /// `true` when nothing is believed yet: a node with no map is not a reason to stop
    /// transmitting, and every slot is worth a try until the peer says otherwise.
    fn peer_on(&self, index: usize) -> bool {
        match self.peer.believed {
            Some(map) => map[index] == self.channel,
            None => true,
        }
    }

    fn since_us(&self, now_us: u64) -> u64 {
        now_us.saturating_sub(self.base_us)
    }

    /// Time units to the next slot boundary, in `1..=64`.
    ///
    /// Never zero: a frame that announces `next_tu = 0` tells the peer its boundary is
    /// now, which is a claim no clock can keep.
    pub fn next_slot_tu(&self, now_us: u64) -> u8 {
        let tu = (self.since_us(now_us) / TU_US) % u64::from(SLOT_TU);
        SLOT_TU - tu as u8
    }

    /// Microseconds to the next slot boundary.
    pub fn next_slot_us(&self, now_us: u64) -> u64 {
        SLOT_US - self.since_us(now_us) % SLOT_US
    }

    /// The AW counter now: four of them to a slot, wrapping at sixteen bits.
    pub fn aw_counter(&self, now_us: u64) -> u16 {
        let tu = self.since_us(now_us) / TU_US;
        let within = (tu % u64::from(SLOT_TU)) / u64::from(AW_PERIOD_TU);
        let slots = tu / u64::from(SLOT_TU);
        let aw = u64::from(self.base_aw)
            + within
            + u64::from(PRESENCE_MODE) * slots;
        (aw & 0xffff) as u16
    }

    /// Which of the sixteen slots the clock is inside now.
    pub fn slot_index(&self, now_us: u64) -> usize {
        (usize::from(self.aw_counter(now_us)) / usize::from(PRESENCE_MODE)) % SLOTS
    }

    /// The channel the current slot sits on.
    pub fn slot_channel(&self, now_us: u64) -> u8 {
        self.slots[self.slot_index(now_us)]
    }

    /// Whether a frame sent now would be heard: the slot has to be one this node owns and
    /// one the peer spends on this channel too.
    ///
    /// The second condition is the one that matters, and it is not a refinement of the
    /// first. This node's own map is all its channel by construction, so on its own it
    /// would permit every slot — which is a node transmitting eleven sixths of the time at
    /// a receiver that is somewhere else.
    pub fn owns_now(&self, now_us: u64) -> bool {
        let here = self.slot_index(now_us);
        self.slots[here] == self.channel && self.peer_on(here)
    }

    /// Microseconds until a slot this node may transmit in begins, or zero if the one it
    /// is inside already is.
    ///
    /// Zero is also what a map with no slot on this channel returns — except that such a
    /// map never gets this far: [`Schedule::offer_map`] refuses it at the door, because a
    /// node waiting for a slot that never arrives is a silence with no diagnostic. Zero
    /// remains the answer a caller repeats unchanged, which is the honest fallback.
    ///
    /// The slot *index* is derived from the AW counter, which [`Schedule::follow`] has
    /// already aligned with the peer's, so the index this walks forward from is the peer's
    /// index too — that is what makes the answer mean "a slot the peer is on this channel
    /// for" and not merely "a slot this map happens to name".
    pub fn next_owned_us(&self, now_us: u64) -> u64 {
        let here = self.slot_index(now_us);
        let usable = |i: usize| self.slots[i] == self.channel && self.peer_on(i);
        if usable(here) {
            return 0;
        }
        let to_next = self.next_slot_us(now_us);
        for ahead in 1..=SLOTS {
            if usable((here + ahead) % SLOTS) {
                return to_next + (ahead as u64 - 1) * SLOT_US;
            }
        }
        0
    }

    /// How far off a peer's origin is, in time units, before [`Schedule::follow`].
    ///
    /// Two terms, and both are needed:
    ///
    /// * **The AW counters** say how many whole slots apart the two grids are. They are
    ///   sixteen bits and four AWs make a slot, so the difference wraps at 2^14 slots and
    ///   is taken the short way round.
    /// * **The two `next_tu` values** say where inside that slot each side is.
    ///
    /// Comparing `next_tu` alone — the obvious simplification — leaves an error that is
    /// never more than half a slot, because half a slot is exactly where that comparison
    /// has to wrap. It reads as "nearly aligned" wherever in the sequence the peer
    /// actually is, and it silently disables the whole-slot refusal in [`crate::link`]:
    /// the one reading that check exists to catch is the one the arithmetic folds away.
    /// On a cold start the two counters are unrelated and the first sample is tens of
    /// slots wide, which is precisely what a bounded refusal is for.
    ///
    /// Sign: zero means the two grids coincide, and only the magnitude is load-bearing —
    /// [`Schedule::follow`] snaps to the announced boundary rather than nudging by `err`,
    /// and the refusal tests `|err|`. The direction is kept exactly as the legacy
    /// implementation produced it, so a new status line can be read against the old logs:
    /// the coarse term wins on a cold start, where a peer whose counter is ahead reads
    /// positive by whole slots, and inside a slot the fine term is subtracted, so a peer
    /// boundary a few TU after ours reads negative by that much.
    pub fn error_tu(&self, now_us: u64, next_tu: u8, aw_counter: u16) -> i32 {
        const WRAP: i32 = 1 << 14;
        let theirs = i32::from(aw_counter) / i32::from(PRESENCE_MODE);
        let ours = i32::from(self.aw_counter(now_us)) / i32::from(PRESENCE_MODE);
        let slots = (theirs - ours + WRAP / 2).rem_euclid(WRAP) - WRAP / 2;
        slots * i32::from(SLOT_TU)
            - (i32::from(next_tu) - i32::from(self.next_slot_tu(now_us)))
    }

    /// The sequence map in the comma form a status line and a peer's frame both use.
    pub fn describe(&self) -> String {
        let mut out = String::new();
        for (i, slot) in self.slots.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            out.push_str(&slot.to_string());
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_slot_is_sixty_four_time_units() {
        assert_eq!(SLOT_TU, 64);
        assert_eq!(SLOT_US, 65_536);
        assert_eq!(SLOT_US / TU_US, u64::from(SLOT_TU));
    }

    #[test]
    fn next_boundary_counts_down_and_wraps_to_a_full_slot() {
        let s = Schedule::anchored(0, 149);
        assert_eq!(s.next_slot_tu(0), 64, "on the boundary, a whole slot away");
        assert_eq!(s.next_slot_tu(TU_US), 63);
        assert_eq!(s.next_slot_tu(63 * TU_US), 1);
        assert_eq!(s.next_slot_tu(64 * TU_US), 64, "next boundary");
    }

    #[test]
    fn next_boundary_never_reports_zero() {
        let s = Schedule::anchored(0, 149);
        for tu in 0..200u64 {
            let got = s.next_slot_tu(tu * TU_US + 1);
            assert!((1..=SLOT_TU).contains(&got), "tu={tu} got={got}");
        }
    }

    #[test]
    fn the_aw_counter_advances_four_per_slot() {
        let s = Schedule::anchored(0, 149);
        assert_eq!(s.aw_counter(0), 0);
        assert_eq!(s.aw_counter(16 * TU_US), 1);
        assert_eq!(s.aw_counter(32 * TU_US), 2);
        assert_eq!(s.aw_counter(64 * TU_US), 4, "one slot later");
        assert_eq!(s.aw_counter(128 * TU_US), 8);
    }

    #[test]
    fn the_aw_counter_wraps_at_sixteen_bits() {
        let mut s = Schedule::anchored(0, 149);
        s.base_aw = 0xfffe;
        assert_eq!(s.aw_counter(0), 0xfffe);
        assert_eq!(s.aw_counter(16 * TU_US), 0xffff);
        assert_eq!(s.aw_counter(32 * TU_US), 0);
    }

    #[test]
    fn the_slot_index_visits_all_sixteen() {
        let s = Schedule::anchored(0, 149);
        let mut seen = [false; SLOTS];
        for slot in 0..SLOTS {
            // Slot n starts at n * SLOT_US.
            let idx = s.slot_index(slot as u64 * SLOT_US);
            seen[idx] = true;
        }
        assert!(seen.iter().all(|on| *on), "a sequence covers every index");
        // And it repeats: slot 16 is slot 0 again.
        assert_eq!(s.slot_index(16 * SLOT_US), 0);
        assert!(s.owns_now(0));
    }

    #[test]
    fn following_a_peer_moves_the_origin_to_its_boundary() {
        // A peer says: my next boundary is 12 TU away, and my counter is 40.
        let now = 1_000_000u64;
        let mut s = Schedule::anchored(0, 149);
        s.follow(now, 12, 40);
        // Its boundary was 64 - 12 = 52 TU ago, which is three AWs, so its counter reads
        // 43 now. The slot-aligned part is still the peer's: 43 rounded down is 40.
        assert_eq!(s.next_slot_tu(now), 12);
        assert_eq!(s.aw_counter(now), 43);
        assert_eq!(s.aw_counter(now) & !(u16::from(PRESENCE_MODE) - 1), 40);
        assert_eq!(s.error_tu(now, 12, 40), 0, "following reports no error");
    }

    #[test]
    fn error_is_negative_when_the_peers_boundary_is_later() {
        let now = 500_000u64;
        let mut s = Schedule::anchored(0, 149);
        s.follow(now, 32, 8);
        // Our own boundary is 32 TU out. A peer announcing 40 has its boundary 8 TU after
        // ours, which the reading reports as -8; announcing 24 puts it 8 TU before, +8.
        assert_eq!(s.error_tu(now, 40, 8), -8);
        assert_eq!(s.error_tu(now, 24, 8), 8);
        assert_eq!(s.error_tu(now, 32, 8), 0);
    }

    #[test]
    fn a_counter_one_slot_ahead_is_a_whole_slot_of_error() {
        // Inside the slot the two sides agree — same `next_tu` — and only the counters
        // say the peer is a slot away. Reading `next_tu` alone would call this aligned,
        // and that is the reading the whole-slot refusal has to be able to see.
        let now = 500_000u64;
        let mut s = Schedule::anchored(0, 149);
        s.follow(now, 32, 8);
        let next_tu = s.next_slot_tu(now);
        assert_eq!(s.error_tu(now, next_tu, 8), 0, "same counter, aligned");
        assert_eq!(s.error_tu(now, next_tu, 12), SLOT_TU as i32, "one slot ahead");
        assert_eq!(s.error_tu(now, next_tu, 4), -(SLOT_TU as i32), "one slot behind");
    }

    #[test]
    fn an_anchored_single_channel_map_owns_every_slot() {
        let s = Schedule::anchored(0, 149);
        for slot in 0..SLOTS as u64 * 2 {
            assert!(s.owns_now(slot * SLOT_US + 1000));
        }
        assert_eq!(s.describe(), "149,149,149,149,149,149,149,149,149,149,149,149,149,149,149,149");
    }

    #[test]
    fn a_peer_map_with_a_foreign_slot_is_not_owned_then() {
        let mut s = Schedule::anchored(0, 149);
        let mut map = [149u8; SLOTS];
        map[3] = 6;
        assert_eq!(believe(&mut s, map), MapVerdict::Adopted);
        assert!(s.owns_now(0));
        assert!(!s.owns_now(3 * SLOT_US), "slot 3 belongs to the other channel");
        assert!(s.owns_now(4 * SLOT_US));
    }

    #[test]
    fn a_believed_peer_map_is_not_what_this_node_announces() {
        // The defect this test exists for: one array answering both questions. Echoing a
        // phone's map back at it says "reachable in five slots" about a radio that never
        // leaves its channel, and the phone then sends only in those five.
        let mut s = Schedule::anchored(0, 149);
        let mut map = [149u8; SLOTS];
        map[3] = 6;
        believe(&mut s, map);
        assert_eq!(s.peer_map(), Some(&map), "believed, for the gate");
        assert_eq!(s.slots(), &[149u8; SLOTS], "announced, all sixteen ours");
        assert_eq!(s.divergence(), 1, "one slot the peer spends elsewhere");
    }

    #[test]
    fn a_map_is_believed_only_after_three_agreeing_announcements() {
        let mut s = Schedule::anchored(0, 149);
        let mut map = [6u8; SLOTS];
        map[0] = 149;
        assert_eq!(s.offer_map(map, 0), MapVerdict::Learning(1));
        assert_eq!(s.offer_map(map, 65_536), MapVerdict::Learning(2));
        assert_eq!(s.peer_map(), None, "two frames is not a map");
        assert_eq!(s.offer_map(map, 131_072), MapVerdict::Adopted);
        assert_eq!(s.offer_map(map, 196_608), MapVerdict::Believed, "already obeyed");
    }

    #[test]
    fn a_different_map_arriving_too_soon_is_held() {
        // The phone alternates between a six-slot and a two-slot shape on the social
        // channel. Believing both as they arrive rewrites the schedule at frame rate.
        let mut s = Schedule::anchored(0, 149);
        let mut six = [6u8; SLOTS];
        six[..6].fill(149);
        let mut two = [6u8; SLOTS];
        two[2] = 149;
        two[10] = 149;
        believe(&mut s, six);
        believe(&mut s, two);
        assert_eq!(s.peer_map(), Some(&six), "still the first shape");
        assert_eq!(s.offer_map(two, SEQ_HOLD_US - 1), MapVerdict::Held);
        assert_eq!(
            s.offer_map(two, SEQ_HOLD_US + 655_360),
            MapVerdict::Adopted,
            "a hold, not a refusal"
        );
        assert_eq!(s.peer_map(), Some(&two));
    }

    #[test]
    fn a_map_that_restarts_the_streak_is_learnt_again() {
        let mut s = Schedule::anchored(0, 149);
        let mut a = [6u8; SLOTS];
        a[0] = 149;
        let mut b = [6u8; SLOTS];
        b[1] = 149;
        s.offer_map(a, 0);
        s.offer_map(a, 65_536);
        assert_eq!(s.offer_map(b, 131_072), MapVerdict::Learning(1), "different shape");
        assert_eq!(s.peer_map(), None);
    }

    #[test]
    fn the_next_usable_slot_is_found_across_the_gap() {
        // Slots 0..=3 on 149, 4..=7 elsewhere, 8 on 149 again. Standing in slot 5, the
        // next slot this node may transmit in is 8 — three slots away, not one.
        let mut s = Schedule::anchored(0, 149);
        let mut map = [6u8; SLOTS];
        map[..4].fill(149);
        map[8] = 149;
        believe(&mut s, map);

        assert_eq!(s.next_owned_us(SLOT_US + 1000), 0, "inside slot 1");
        // Two slots past slot 5's boundary: slot 5 ends one slot later, then 6 and 7 are
        // spent elsewhere, so slot 8 starts two slots after that boundary — minus the
        // thousand microseconds already spent inside slot 5.
        let inside_five = 5 * SLOT_US + 1000;
        assert!(!s.owns_now(inside_five));
        assert_eq!(s.next_owned_us(inside_five), SLOT_US - 1000 + 2 * SLOT_US);
        assert!(s.owns_now(inside_five + s.next_owned_us(inside_five)));
    }

    #[test]
    fn a_map_with_no_slot_on_this_channel_is_refused_outright() {
        // Waiting for a slot that never comes is a silence with no diagnostic. A refusal
        // leaves the gate exactly as it was, which is what the caller would have done with
        // no map at all.
        let mut s = Schedule::anchored(0, 149);
        assert_eq!(s.offer_map([6u8; SLOTS], 0), MapVerdict::Refused);
        assert_eq!(s.peer_map(), None);
        assert_eq!(s.next_owned_us(0), 0);
        assert!(s.owns_now(0), "a refusal is not a silence");
    }

    /// Believe `map` on the spot: three agreeing announcements, which is what the air
    /// would have had to supply anyway.
    fn believe(s: &mut Schedule, map: [u8; SLOTS]) -> MapVerdict {
        s.offer_map(map, 0);
        s.offer_map(map, 0);
        s.offer_map(map, 0)
    }
}
