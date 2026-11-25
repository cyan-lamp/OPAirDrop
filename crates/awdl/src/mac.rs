//! 802.11 addresses, and the two mappings between them and IPv6.
//!
//! # Why the address is derived and not read
//!
//! An AWDL node does not put its own MAC in address 2 and expect the peer to answer to a
//! name. It addresses a frame to the MAC that the IPv6 destination implies: a unicast
//! link-local address carries a modified EUI-64 in its interface identifier, so the
//! receiver is recoverable from the packet, and a multicast group maps onto the `33:33`
//! range the way IPv4 maps onto `01:00:5e`.
//!
//! That direction is worth trusting because both ends apply it independently. Reading the
//! address off an inbound frame instead would mean guessing which of the three address
//! fields the peer meant, on a link where they arrive shifted and where the middle one is
//! sometimes a copy of the BSSID.

use core::fmt;

/// A 48-bit 802.11 address.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Mac([u8; 6]);

impl Mac {
    /// The address a frame to every node on the air carries.
    pub const BROADCAST: Mac = Mac([0xff; 6]);

    /// The fixed BSSID every AWDL frame carries (AWDL-1). Not a node: it names the
    /// network, and mac80211 drops Apple frames whose address 3 is anything else.
    pub const AWDL_BSSID: Mac = Mac([0x00, 0x25, 0x00, 0xff, 0x94, 0x73]);

    /// `33:33:00:00:00:fb`, the one group this node joins.
    pub const MDNS_V6: Mac = Mac([0x33, 0x33, 0x00, 0x00, 0x00, 0xfb]);

    /// From six bytes, in wire order.
    pub const fn new(bytes: [u8; 6]) -> Self {
        Mac(bytes)
    }

    /// From the first six bytes of `bytes`, or `None` when there are fewer.
    pub fn from_slice(bytes: &[u8]) -> Option<Mac> {
        if bytes.len() < 6 {
            return None;
        }
        let mut out = [0u8; 6];
        out.copy_from_slice(&bytes[..6]);
        Some(Mac(out))
    }

    /// From `aa:bb:cc:dd:ee:ff`, case-insensitively.
    pub fn parse(text: &str) -> Option<Mac> {
        let mut out = [0u8; 6];
        let mut parts = text.split(':');
        for slot in out.iter_mut() {
            let part = parts.next()?;
            if part.len() != 2 {
                return None;
            }
            *slot = u8::from_str_radix(part, 16).ok()?;
        }
        if parts.next().is_some() {
            return None;
        }
        Some(Mac(out))
    }

    /// The six bytes, in wire order.
    pub fn bytes(&self) -> [u8; 6] {
        self.0
    }

    /// The bytes as a slice, for a frame writer.
    pub fn as_slice(&self) -> &[u8] {
        &self.0
    }

    /// Whether this is a group address: the I/G bit, the low bit of the first octet.
    pub fn is_group(&self) -> bool {
        self.0[0] & 0x01 != 0
    }

    /// The link-local address this MAC's modified EUI-64 implies.
    ///
    /// `fe80::` followed by the MAC with `0xff 0xfe` inserted in the middle and the
    /// universal/local bit flipped. The same construction a kernel performs when it gives
    /// an interface an address with no other input, which is why the two ends of a frame
    /// agree without an exchange.
    pub fn link_local(&self) -> [u8; 16] {
        let m = self.0;
        let mut out = [0u8; 16];
        out[0] = 0xfe;
        out[1] = 0x80;
        out[8] = m[0] ^ 0x02;
        out[9] = m[1];
        out[10] = m[2];
        out[11] = 0xff;
        out[12] = 0xfe;
        out[13] = m[3];
        out[14] = m[4];
        out[15] = m[5];
        out
    }

    /// The address a frame carrying a packet to `dst` must name, or `None` when `dst`
    /// implies none.
    ///
    /// * `ff02::/16` becomes `33:33` plus the group's low four bytes.
    /// * A `fe80::/64` address whose interface identifier is a modified EUI-64 becomes
    ///   that EUI-64's MAC. One that is not — a privacy address — implies nothing, and
    ///   `None` is the honest answer: those are the addresses a peer generates when it
    ///   does not want to be found by them.
    pub fn for_ipv6(dst: &[u8; 16]) -> Option<Mac> {
        if dst[0] == 0xff && dst[1] == 0x02 {
            return Some(Mac([0x33, 0x33, dst[12], dst[13], dst[14], dst[15]]));
        }
        if dst[0] != 0xfe || dst[1] != 0x80 {
            return None;
        }
        if dst[11] != 0xff || dst[12] != 0xfe {
            return None;
        }
        Some(Mac([
            dst[8] ^ 0x02,
            dst[9],
            dst[10],
            dst[13],
            dst[14],
            dst[15],
        ]))
    }
}

impl fmt::Debug for Mac {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self)
    }
}

impl fmt::Display for Mac {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for (i, byte) in self.0.iter().enumerate() {
            if i > 0 {
                f.write_str(":")?;
            }
            write!(f, "{byte:02x}")?;
        }
        Ok(())
    }
}

/// `fe80::1:2ff:fe3:405`, the form a log and a router both accept.
pub fn format_ipv6(addr: &[u8; 16]) -> String {
    // The one compressed form worth writing by hand: `::` replaces the longest run of
    // zero groups, which for a link-local address is always the middle.
    let mut groups = [0u16; 8];
    for (i, group) in groups.iter_mut().enumerate() {
        *group = u16::from_be_bytes([addr[i * 2], addr[i * 2 + 1]]);
    }
    let mut best_start = 0;
    let mut best_len = 0;
    let mut run_start = 0;
    let mut run_len = 0;
    for (i, group) in groups.iter().enumerate() {
        if *group == 0 {
            if run_len == 0 {
                run_start = i;
            }
            run_len += 1;
            if run_len > best_len {
                best_len = run_len;
                best_start = run_start;
            }
        } else {
            run_len = 0;
        }
    }
    // A single zero group is longer as `0` than as `::`.
    if best_len < 2 {
        best_len = 0;
    }
    let mut out = String::new();
    let mut i = 0;
    while i < 8 {
        if i == best_start && best_len > 0 {
            out.push_str("::");
            i += best_len;
            continue;
        }
        if !out.is_empty() && !out.ends_with(':') {
            out.push(':');
        }
        out.push_str(&format!("{:x}", groups[i]));
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_prints() {
        let mac = Mac::parse("00:25:00:FF:94:73").expect("valid");
        assert_eq!(mac, Mac::AWDL_BSSID);
        assert_eq!(mac.to_string(), "00:25:00:ff:94:73");
    }

    #[test]
    fn rejects_malformed() {
        assert!(Mac::parse("00:25:00:ff:94").is_none());
        assert!(Mac::parse("00:25:00:ff:94:73:00").is_none());
        assert!(Mac::parse("002500ff9473").is_none());
        assert!(Mac::parse("00:25:00:ff:94:zz").is_none());
    }

    #[test]
    fn link_local_is_modified_eui_64() {
        // The iPhone measured on the air: 2a:d8:dc:5d:8c:5a at fe80::28d8:dcff:fe5d:8c5a.
        let mac = Mac::parse("2a:d8:dc:5d:8c:5a").expect("valid");
        let got = mac.link_local();
        assert_eq!(format_ipv6(&got), "fe80::28d8:dcff:fe5d:8c5a");
    }

    #[test]
    fn link_local_round_trips_through_for_ipv6() {
        for text in [
            "2a:d8:dc:5d:8c:5a",
            "ee:aa:d3:d0:cb:f1",
            "00:11:22:33:44:55",
        ] {
            let mac = Mac::parse(text).expect("valid");
            let back = Mac::for_ipv6(&mac.link_local()).expect("unicast maps back");
            assert_eq!(back, mac, "{text}");
        }
    }

    #[test]
    fn multicast_groups_map_onto_33_33() {
        let mut dst = [0u8; 16];
        dst[0] = 0xff;
        dst[1] = 0x02;
        dst[15] = 0xfb;
        assert_eq!(Mac::for_ipv6(&dst), Some(Mac::MDNS_V6));
    }

    #[test]
    fn privacy_addresses_imply_nothing() {
        // fe80::1:2:3:4 has no 0xff 0xfe in the interface identifier.
        let mut dst = [0u8; 16];
        dst[0] = 0xfe;
        dst[1] = 0x80;
        dst[15] = 0x04;
        assert_eq!(Mac::for_ipv6(&dst), None);
    }

    #[test]
    fn global_unicast_implies_nothing() {
        // 2001:db8::1: the AWDL link carries link-local only.
        let mut dst = [0u8; 16];
        dst[0] = 0x20;
        dst[1] = 0x01;
        dst[15] = 0x01;
        assert_eq!(Mac::for_ipv6(&dst), None);
    }

    #[test]
    fn formats_the_unspecified_address() {
        assert_eq!(format_ipv6(&[0u8; 16]), "::");
    }

    #[test]
    fn formats_without_compression_when_nothing_is_zero() {
        let mut addr = [0u8; 16];
        for (i, byte) in addr.iter_mut().enumerate() {
            *byte = i as u8 + 1;
        }
        assert_eq!(format_ipv6(&addr), "102:304:506:708:90a:b0c:d0e:f10");
    }

    #[test]
    fn group_bit() {
        assert!(Mac::BROADCAST.is_group());
        assert!(Mac::MDNS_V6.is_group());
        assert!(!Mac::AWDL_BSSID.is_group());
    }
}
