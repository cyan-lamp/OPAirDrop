//! The frame an AWDL data packet travels in, and the reader that gets it back out.
//!
//! # Why this layer is not optional
//!
//! A frame that leaves the radio with an ordinary LLC/SNAP header reaches the peer's
//! antenna, passes its FCS check, and is even acknowledged — but it never reaches the
//! peer's IP stack, because an AWDL data frame is not an 802.11 data frame with a SNAP
//! header. Between the SNAP header and the packet sits a sixteen-byte AWDL data header:
//!
//! ```text
//!   LLC/SNAP   aa aa 03 00 17 f2 08 00
//!   data head  03 04            little endian 0x0403, AWDL-13's marker
//!   awdl seq   nn nn            little endian, one per MSDU
//!   reserved   00 00
//!   ethertype  86 dd            big endian, the one field in network order
//! ```
//!
//! The SNAP names Apple's OUI and protocol `0x0800`, but that pair is a fixed preamble
//! rather than the real ethertype: the type that matters sits four bytes further on.
//!
//! # The frame control
//!
//! `0x0088` — QoS data — rather than `0x0008`. That is not a style choice. The passthru
//! bridge in the driver accepts a whole frame on the raw path only for probe-request,
//! action, and exactly `0x0088`; any other non-management frame falls through to the
//! ordinary TCL path, where a passthru vif owns no peer for the destination and the frame
//! is dropped without a trace. QoS is also what the driver expects: its A-MSDU and ACK
//! policy rewriting index the first QoS control byte at offset 24.

use crate::mac::Mac;

/// One 802.11 time unit, in microseconds.
pub const TU_US: u64 = 1024;

/// The radiotap header length this crate writes: version, pad, length, one present word,
/// one rate byte.
pub const RADIOTAP_LEN: usize = 9;

/// The frame control word for a QoS data frame. See the module docs for why not `0x0008`.
pub const FC_QOS_DATA: u16 = 0x0088;

/// AWDL-13's marker, little endian, the third 16-bit word a receiver checks.
pub const DATA_HEAD: u16 = 0x0403;

/// The only ethertype an AWDL data frame carries.
pub const ETHERTYPE_IPV6: u16 = 0x86dd;

/// The SNAP pair every AWDL data frame starts its body with.
pub const LLC_SNAP: [u8; 8] = [0xaa, 0xaa, 0x03, 0x00, 0x17, 0xf2, 0x08, 0x00];

/// A radiotap header with a single rate field.
///
/// The rate is load bearing. A header with no rate field leaves the number to the driver,
/// which for a frame arriving through `AF_PACKET` picks its lowest rate and keeps it: the
/// link then runs at 6 Mb/s with no adaptation, and a 1390-byte frame costs 1.85 ms of
/// airtime, which is what caps the transfer rather than the congestion window.
///
/// Radiotap expresses the rate in units of 500 kb/s, so 36 Mb/s is the byte 72 and the one
/// present bit that says "a rate follows" is bit 2.
pub fn radiotap(rate_mbps: u8) -> [u8; RADIOTAP_LEN] {
    let present: u32 = 1 << 2;
    let mut out = [0u8; RADIOTAP_LEN];
    out[2..4].copy_from_slice(&(RADIOTAP_LEN as u16).to_le_bytes());
    out[4..8].copy_from_slice(&present.to_le_bytes());
    out[8] = rate_mbps.saturating_mul(2);
    out
}

/// The rate this crate asks for unless told otherwise.
pub const DEFAULT_RATE_MBPS: u8 = 36;

/// The frequency the driver says a received frame arrived on, in MHz.
///
/// # Why this exists
///
/// `--channel` is a label. It goes into what this node announces and into the arithmetic
/// that decides which slots it may transmit in, but nothing in this crate ever moved the
/// radio: the vif is left wherever the driver put it, and on a single-radio device that is
/// wherever the station interface currently is. So the schedule can describe a radio that
/// is not there, and every window in it is then paid for in full and buys nothing.
///
/// The one witness that cannot lie is the driver's own radiotap header: it stamps the
/// frequency at the instant of reception. Reading it turns "which channel are we actually
/// on" from an assumption into a measurement, and a measurement that disagrees with
/// `--channel` is the whole diagnosis.
///
/// The channel field is `struct ieee80211_radiotap_channel { u16 freq; u16 flags; }` —
/// **frequency first**. Reading the pair the other way round yields 320 MHz for channel
/// 149 (`0x1671` read as flags, `0x0140` as frequency), which is not a channel any radio
/// has, so the mistake announces itself once there is a number to sanity-check.
pub fn phy_mhz(frame: &[u8]) -> Option<u16> {
    if frame.len() < 8 {
        return None;
    }
    let rt_len = u16::from_le_bytes([frame[2], frame[3]]) as usize;
    if rt_len < 8 || rt_len > frame.len() {
        return None;
    }
    // One present word, or several chained by the extended bit (31) in the last one.
    let mut at = 4;
    let mut first = None;
    loop {
        if at + 4 > rt_len {
            return None;
        }
        let word = u32::from_le_bytes([frame[at], frame[at + 1], frame[at + 2], frame[at + 3]]);
        first.get_or_insert(word);
        at += 4;
        if word & (1 << 31) == 0 {
            break;
        }
    }
    let first = first?;
    // IEEE80211_RADIOTAP_CHANNEL is bit 3.
    if first & (1 << 3) == 0 {
        return None;
    }
    // Walk the three fields that precede it, aligning each to its own boundary relative
    // to the start of the header, which is what "natural alignment" means in radiotap.
    for bit in 0..4u32 {
        if first & (1 << bit) == 0 {
            continue;
        }
        let (size, align) = match bit {
            0 => (8usize, 8usize), // TSFT
            1 => (1, 1),           // FLAGS
            2 => (1, 1),           // RATE
            _ => (4, 2),           // CHANNEL: u16 freq, u16 flags
        };
        at = (at + align - 1) & !(align - 1);
        if bit == 3 {
            if at + 4 > rt_len {
                return None;
            }
            let freq = u16::from_le_bytes([frame[at], frame[at + 1]]);
            return (freq > 0).then_some(freq);
        }
        at += size;
    }
    None
}

/// The channel number a frequency belongs to, or `None` for a band this crate does not
/// announce in.
///
/// 5 GHz is a straight line from 5000 MHz in steps of 5; 2.4 GHz starts at 2412 for
/// channel 1 and puts 2484 (channel 14) off the end of that line.
pub fn channel_of_mhz(mhz: u16) -> Option<u8> {
    match mhz {
        2484 => Some(14),
        2412..=2472 => Some(((mhz - 2407) / 5) as u8),
        5000..=5925 => Some(((mhz - 5000) / 5) as u8),
        _ => None,
    }
}

/// What a received frame turned out to be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Inbound<'a> {
    /// An AWDL data frame carrying an IPv6 packet.
    Ipv6 {
        /// Address 2, the transmitter.
        src: Mac,
        /// Address 1, which for a group destination is a group address.
        dst: Mac,
        /// The bare IPv6 packet: no Ethernet header, no padding.
        packet: &'a [u8],
    },
    /// A well-formed frame this layer recognises but does not forward.
    Other {
        /// Address 2, the transmitter.
        src: Mac,
        /// The frame control's type field.
        kind: u8,
        /// The frame control's subtype field.
        subtype: u8,
    },
}

/// Build the frame that carries one IPv6 packet.
///
/// Address 1 is the peer's AWDL address, or broadcast. An AWDL interface is not part of a
/// distribution system, so a broadcast MSDU is delivered to every node on the air and
/// needs no acknowledgement.
pub fn data_frame(
    src: Mac,
    dst: Mac,
    seq: u16,
    awdl_seq: u16,
    packet: &[u8],
    rate_mbps: u8,
) -> Vec<u8> {
    let mut out = Vec::with_capacity(RADIOTAP_LEN + 36 + packet.len());
    out.extend_from_slice(&radiotap(rate_mbps));

    out.extend_from_slice(&FC_QOS_DATA.to_le_bytes());
    out.extend_from_slice(&[0, 0]); // duration
    out.extend_from_slice(dst.as_slice());
    out.extend_from_slice(src.as_slice());
    out.extend_from_slice(Mac::AWDL_BSSID.as_slice());
    // Sequence control: the low twelve bits are the MSDU counter, the high four the
    // fragment number, which is always zero here.
    out.extend_from_slice(&((seq & 0x0fff) << 4).to_le_bytes());
    // QoS control is part of the MAC header, so the SNAP below lands at offset 26, which
    // is where the driver looks for it. TID 0, A-MSDU bit clear, normal ACK policy.
    out.extend_from_slice(&[0, 0]);

    out.extend_from_slice(&LLC_SNAP);
    out.extend_from_slice(&DATA_HEAD.to_le_bytes());
    out.extend_from_slice(&awdl_seq.to_le_bytes());
    out.extend_from_slice(&[0, 0]); // reserved
    out.extend_from_slice(&ETHERTYPE_IPV6.to_be_bytes());
    out.extend_from_slice(packet);
    out
}

/// The offset of the IPv6 packet inside a received frame, or `None`.
///
/// These are the offsets [`Inbound::Ipv6`] is cut at, repeated rather than inferred from
/// a parse report. A relay that extracted a payload from a substring would eventually
/// hand the kernel something it had only partly validated, and the kernel's own error
/// would then be indistinguishable from the peer saying nothing.
pub fn ipv6_at(frame: &[u8]) -> Option<usize> {
    if frame.len() < 4 {
        return None;
    }
    let radiotap_len = u16::from_le_bytes([frame[2], frame[3]]) as usize;
    if radiotap_len < 4 || radiotap_len > frame.len() || frame.len() - radiotap_len < 24 {
        return None;
    }
    let o = radiotap_len;
    let fc = u16::from_le_bytes([frame[o], frame[o + 1]]);
    // Type 2 is data. Management, control and extension frames are not ours.
    if (fc >> 2) & 0x3 != 2 {
        return None;
    }
    let subtype = (fc >> 4) & 0xf;
    let mut body = o + 24;
    if subtype & 0x8 != 0 {
        // QoS data carries a two-byte control field after the MAC header.
        if frame.len() - body < 2 {
            return None;
        }
        let qos = u16::from_le_bytes([frame[body], frame[body + 1]]);
        // An A-MSDU carries several MSDUs behind one header, so the first SNAP is not at
        // this offset and the framing below does not describe it.
        if qos & 0x0080 != 0 {
            return None;
        }
        body += 2;
    }
    // SNAP, AWDL data header, ethertype, and at least a bare IPv6 header.
    if frame.len() - body < 16 + 40 {
        return None;
    }
    if frame[body] != 0xaa || frame[body + 1] != 0xaa {
        return None;
    }
    // The SNAP's OUI is Apple's, as every AWDL frame's is. A frame carrying another
    // vendor's SNAP is not a data frame this layer understands, whatever its ethertype
    // claims, and handing it to the tunnel would inject something nothing validated.
    if frame[body + 3..body + 6] != LLC_SNAP[3..6] {
        return None;
    }
    if u16::from_le_bytes([frame[body + 8], frame[body + 9]]) != DATA_HEAD {
        return None;
    }
    if u16::from_be_bytes([frame[body + 14], frame[body + 15]]) != ETHERTYPE_IPV6 {
        return None;
    }
    let ip = body + 16;
    if frame[ip] >> 4 != 6 {
        return None;
    }
    Some(ip)
}

/// How many bytes of the packet to hand the kernel.
///
/// 802.11 pads a short frame out to its own minimum, so the frame's length is not the
/// packet's length and anything read from the tail would be padding. When the header's
/// own length is longer than what arrived the frame was truncated, and the part that did
/// arrive is passed on: the kernel's checksum rejects it more precisely than a length test
/// here could.
pub fn ipv6_total(frame: &[u8], ip: usize) -> usize {
    if frame.len() - ip < 40 {
        return 0;
    }
    let payload_len = u16::from_be_bytes([frame[ip + 4], frame[ip + 5]]) as usize;
    let total = 40 + payload_len;
    total.min(frame.len() - ip)
}

/// Read a received frame, or `None` when it is not something this layer forwards.
pub fn parse(frame: &[u8]) -> Option<Inbound<'_>> {
    if frame.len() < 4 {
        return None;
    }
    let radiotap_len = u16::from_le_bytes([frame[2], frame[3]]) as usize;
    if radiotap_len < 4 || radiotap_len > frame.len() || frame.len() - radiotap_len < 24 {
        return None;
    }
    let o = radiotap_len;
    let fc = u16::from_le_bytes([frame[o], frame[o + 1]]);
    let kind = ((fc >> 2) & 0x3) as u8;
    let subtype = ((fc >> 4) & 0xf) as u8;
    let src = Mac::from_slice(&frame[o + 10..])?;
    let dst = Mac::from_slice(&frame[o + 4..])?;

    match ipv6_at(frame) {
        Some(ip) => {
            let total = ipv6_total(frame, ip);
            if total < 40 {
                return None;
            }
            Some(Inbound::Ipv6 {
                src,
                dst,
                packet: &frame[ip..ip + total],
            })
        }
        None => Some(Inbound::Other { src, kind, subtype }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mac(text: &str) -> Mac {
        Mac::parse(text).expect("valid")
    }

    fn ipv6_stub(len: usize) -> Vec<u8> {
        let mut packet = vec![0u8; len.max(40)];
        packet[0] = 0x60;
        // Payload length covers everything after the fixed header.
        let payload = (packet.len() - 40) as u16;
        packet[4..6].copy_from_slice(&payload.to_be_bytes());
        packet[6] = 17; // UDP
        packet[7] = 64; // hop limit
        packet
    }

    #[test]
    fn radiotap_carries_the_rate() {
        let rt = radiotap(36);
        assert_eq!(rt.len(), RADIOTAP_LEN);
        assert_eq!(rt[0], 0, "version");
        assert_eq!(rt[1], 0, "pad");
        assert_eq!(u16::from_le_bytes([rt[2], rt[3]]), 9, "length");
        assert_eq!(u32::from_le_bytes([rt[4], rt[5], rt[6], rt[7]]), 4, "rate present");
        assert_eq!(rt[8], 72, "36 Mb/s in 500 kb/s units");
    }

    #[test]
    fn frame_layout_is_the_one_the_peer_expects() {
        let src = mac("ee:aa:d3:d0:cb:f1");
        let dst = mac("2a:d8:dc:5d:8c:5a");
        let packet = ipv6_stub(48);
        let frame = data_frame(src, dst, 7, 21, &packet, 36);

        assert_eq!(&frame[..9], &radiotap(36));
        assert_eq!(u16::from_le_bytes([frame[9], frame[10]]), 0x0088, "QoS data");
        assert_eq!(&frame[13..19], dst.as_slice(), "address 1 is the receiver");
        assert_eq!(&frame[19..25], src.as_slice(), "address 2 is us");
        assert_eq!(&frame[25..31], Mac::AWDL_BSSID.as_slice(), "address 3");
        assert_eq!(
            u16::from_le_bytes([frame[31], frame[32]]),
            7 << 4,
            "sequence control"
        );
        assert_eq!(&frame[33..35], &[0, 0], "QoS control");
        assert_eq!(&frame[35..43], &LLC_SNAP);
        assert_eq!(u16::from_le_bytes([frame[43], frame[44]]), 0x0403, "data head");
        assert_eq!(u16::from_le_bytes([frame[45], frame[46]]), 21, "awdl seq");
        assert_eq!(&frame[47..49], &[0, 0], "reserved");
        assert_eq!(&frame[49..51], &[0x86, 0xdd], "ethertype, big endian");
        assert_eq!(&frame[51..], &packet[..]);
    }

    #[test]
    fn round_trips_a_packet() {
        let src = mac("ee:aa:d3:d0:cb:f1");
        let dst = mac("2a:d8:dc:5d:8c:5a");
        let packet = ipv6_stub(96);
        let frame = data_frame(src, dst, 1, 2, &packet, 36);
        match parse(&frame) {
            Some(Inbound::Ipv6 { src: got_src, dst: got_dst, packet: got }) => {
                assert_eq!(got_src, src);
                assert_eq!(got_dst, dst);
                assert_eq!(got, &packet[..]);
            }
            other => panic!("expected IPv6, got {other:?}"),
        }
    }

    #[test]
    fn drops_trailing_padding() {
        let src = mac("ee:aa:d3:d0:cb:f1");
        let packet = ipv6_stub(48);
        let mut frame = data_frame(src, Mac::BROADCAST, 1, 2, &packet, 36);
        // 802.11 pads a short frame to its minimum; the padding must not reach the kernel.
        frame.extend_from_slice(&[0u8; 12]);
        match parse(&frame) {
            Some(Inbound::Ipv6 { packet: got, .. }) => assert_eq!(got.len(), 48),
            other => panic!("expected IPv6, got {other:?}"),
        }
    }

    #[test]
    fn passes_on_a_truncated_packet() {
        let src = mac("ee:aa:d3:d0:cb:f1");
        let packet = ipv6_stub(200);
        let mut frame = data_frame(src, Mac::BROADCAST, 1, 2, &packet, 36);
        frame.truncate(frame.len() - 100);
        match parse(&frame) {
            Some(Inbound::Ipv6 { packet: got, .. }) => {
                // The header claims 200 bytes; only 100 arrived. What arrived is passed
                // on, and the kernel's checksum is the authority on the rest.
                assert_eq!(got.len(), 100);
            }
            other => panic!("expected a truncated IPv6 packet, got {other:?}"),
        }
    }

    #[test]
    fn rejects_a_frame_without_the_data_head() {
        let src = mac("ee:aa:d3:d0:cb:f1");
        let mut frame = data_frame(src, Mac::BROADCAST, 1, 2, &ipv6_stub(48), 36);
        frame[43] = 0;
        frame[44] = 0;
        assert!(matches!(parse(&frame), Some(Inbound::Other { .. })));
    }

    #[test]
    fn rejects_a_snap_without_apple_oui() {
        let src = mac("ee:aa:d3:d0:cb:f1");
        let mut frame = data_frame(src, Mac::BROADCAST, 1, 2, &ipv6_stub(48), 36);
        frame[37] = 0x00; // break 00:17:f2
        frame[38] = 0x50;
        assert!(matches!(parse(&frame), Some(Inbound::Other { .. })));
    }

    #[test]
    fn reports_management_frames_as_other() {
        // A beacon: type 0, subtype 8.
        let mut frame = radiotap(6).to_vec();
        frame.extend_from_slice(&[0x80, 0x00]);
        frame.extend_from_slice(&[0, 0]);
        frame.extend_from_slice(Mac::BROADCAST.as_slice());
        frame.extend_from_slice(mac("2a:d8:dc:5d:8c:5a").as_slice());
        frame.extend_from_slice(Mac::AWDL_BSSID.as_slice());
        frame.extend_from_slice(&[0, 0]);
        frame.extend_from_slice(&[0u8; 32]);
        match parse(&frame) {
            Some(Inbound::Other { kind, subtype, src }) => {
                assert_eq!(kind, 0);
                assert_eq!(subtype, 8);
                assert_eq!(src, mac("2a:d8:dc:5d:8c:5a"));
            }
            other => panic!("expected Other, got {other:?}"),
        }
    }

    #[test]
    fn rejects_a_frame_with_a_bad_radiotap_length() {
        let mut frame = data_frame(mac("ee:aa:d3:d0:cb:f1"), Mac::BROADCAST, 1, 2, &ipv6_stub(48), 36);
        frame[2] = 0xff;
        frame[3] = 0xff;
        assert!(parse(&frame).is_none());
    }

    #[test]
    fn accepts_a_frame_with_a_longer_radiotap() {
        // The driver may hand back a radiotap header with fields this crate does not
        // write, as long as it says how long it is.
        let src = mac("ee:aa:d3:d0:cb:f1");
        let packet = ipv6_stub(64);
        let plain = data_frame(src, Mac::BROADCAST, 1, 2, &packet, 36);
        let mut frame = plain[..9].to_vec();
        frame.extend_from_slice(&[0u8; 12]); // extra radiotap fields
        let new_len = (frame.len()) as u16;
        frame[2..4].copy_from_slice(&new_len.to_le_bytes());
        frame.extend_from_slice(&plain[9..]);
        match parse(&frame) {
            Some(Inbound::Ipv6 { packet: got, .. }) => assert_eq!(got, &packet[..]),
            other => panic!("expected IPv6, got {other:?}"),
        }
    }

    #[test]
    fn rejects_a_buffer_too_short_to_hold_a_header() {
        assert!(parse(&[]).is_none());
        assert!(parse(&[0, 0, 4, 0]).is_none());
    }

    /// The header the driver writes on a frame that came off the air: TSFT, FLAGS, RATE,
    /// CHANNEL, then two fields this crate has no business reading. Built byte for byte
    /// from a capture, because the alignment padding between RATE and CHANNEL is the
    /// part that a hand-rolled parser gets wrong.
    fn driver_header(freq: u16) -> Vec<u8> {
        let mut h = Vec::new();
        h.extend_from_slice(&[0, 0]); // version, pad
        h.extend_from_slice(&48u16.to_le_bytes()); // length
        h.extend_from_slice(&0x4000_086fu32.to_le_bytes()); // TSFT FLAGS RATE CHANNEL ...
        h.extend_from_slice(&0x0000_007cu64.to_le_bytes()); // TSFT
        h.push(0x00); // FLAGS
        h.push(0x18); // RATE, 12 Mb/s
        h.extend_from_slice(&freq.to_le_bytes()); // CHANNEL: frequency first
        h.extend_from_slice(&0x0140u16.to_le_bytes()); // CHANNEL: flags, OFDM + 20 MHz
        h.push(0xea); // DBM_ANTSIGNAL
        h.push(0x28); // DBM_ANTNOISE
        h.push(0x02); // ANTENNA
        h.extend_from_slice(&[0u8; 23]); // RX_FLAGS and the rest, out to the declared 48
        assert_eq!(h.len(), 48);
        h
    }

    #[test]
    fn reads_the_frequency_the_driver_stamped() {
        let mut frame = driver_header(5745);
        frame.extend_from_slice(&[0u8; 40]);
        assert_eq!(phy_mhz(&frame), Some(5745));
        assert_eq!(channel_of_mhz(5745), Some(149));
        assert_eq!(channel_of_mhz(5220), Some(44));
        assert_eq!(channel_of_mhz(2412), Some(1));
        assert_eq!(channel_of_mhz(2484), Some(14));
    }

    #[test]
    fn does_not_read_the_channel_pair_backwards() {
        // 0x1671 is 5745 as a frequency and a nonsense channel flag set as flags; 0x0140
        // is 320 as a frequency, which is not a channel at all. A parser that swapped the
        // two would report 320 here and look plausible enough not to be questioned.
        let mut frame = driver_header(5745);
        frame.extend_from_slice(&[0u8; 40]);
        assert_ne!(phy_mhz(&frame), Some(320));
    }

    #[test]
    fn a_header_with_no_channel_field_reports_no_frequency() {
        // The header this crate writes: rate only. Nothing to read, and inventing the
        // configured channel here would defeat the point of the measurement.
        let frame = radiotap(36);
        assert_eq!(phy_mhz(&frame), None);
    }
}
