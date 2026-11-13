//! Finding peers: the one query a sender multicasts, and the records that come back.
//!
//! # Why the codec stays here
//!
//! A DNS message is name compression, length-prefixed labels and a `u16` at the wrong endian
//! if you blink. The crate that already does this has tests for apex pointers, loops and
//! truncated packets; re-writing it in Java would be a second implementation with none of
//! them. So Java owns the socket — bind, join the group, send, receive — and this owns what
//! the bytes mean.
//!
//! # Why the answer is records and not peers
//!
//! A peer is not in one packet. The PTR that names the service instance, the SRV that gives
//! its port and host, the A/AAAA that gives the host an address and the TXT that names the
//! model arrive across two or three responses, in any order, and a responder may answer the
//! address part only when asked again. Anything that returned "peers" would have to invent
//! a completeness rule and drop whatever arrived in the wrong packet. So this returns the
//! records it saw, one per line, and the caller — which is the side holding the socket and
//! therefore the side that knows what it has collected — joins them.
//!
//! Line shapes, all tab-separated, all values with tabs and newlines replaced by spaces:
//!
//! ```text
//! srv    <instance>  <host>  <port>
//! addr   <host>      <address>
//! txt    <instance>  <key>   <value>
//! ```
//!
//! Only `model`, `flags` and `phash` are reported from TXT: the rest of an AirDrop TXT record
//! describes features the sender does not branch on, and a line per key would be a line per
//! key.

use opairdrop_mdns::{Builder, Message, CLASS_IN, TYPE_A, TYPE_AAAA, TYPE_PTR, TYPE_SRV, TYPE_TXT};

use crate::api::status;
use crate::buffer::{fill, OpairdropBuffer};

/// The service AirDrop peers publish. `_airdrop._tcp` is the type; the `.local` domain is
/// mDNS's own and is part of the name a query has to carry.
pub const SERVICE: &str = "_airdrop._tcp.local";

/// The TXT keys worth reporting.
const TEXT_KEYS: &[&str] = &["model", "flags", "phash"];

/// Builds the PTR query a sender multicasts to find peers.
///
/// One question, `PTR _airdrop._tcp.local`, class `IN`. The id is zero because mDNS answers
/// ignore it and a responder that echoes a non-zero id is not a responder anyone should
/// trust. `report` receives the packet, or a message on failure.
///
/// # Safety
///
/// `report` must point at a zeroed [`OpairdropBuffer`] that outlives the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_mdns_query(report: *mut OpairdropBuffer) -> i32 {
    if report.is_null() {
        return status::NULL_ARGUMENT;
    }
    match Builder::new(0, 0).question(SERVICE, TYPE_PTR, CLASS_IN) {
        Ok(builder) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, builder.build()) };
            status::OK
        }
        Err(error) => {
            // SAFETY: as above.
            unsafe { fill(report, format!("the query could not be built: {error}").into_bytes()) };
            status::TRANSPORT
        }
    }
}

/// Reads one response packet into record lines.
///
/// A packet that is not a DNS message at all — which is what a stray datagram on port 5353
/// from an unrelated service can be — returns [`status::TRANSPORT`] with the parser's
/// reason. A packet that parses but holds nothing relevant returns an empty success, which
/// is the common case and not an error.
///
/// # Safety
///
/// `packet` must point at `packet_len` readable bytes; `report` must point at a zeroed
/// [`OpairdropBuffer`] that outlives the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_mdns_records(
    packet: *const u8,
    packet_len: usize,
    report: *mut OpairdropBuffer,
) -> i32 {
    if report.is_null() || packet.is_null() {
        return status::NULL_ARGUMENT;
    }

    // SAFETY: the caller guarantees the region for the length given.
    let bytes = unsafe { std::slice::from_raw_parts(packet, packet_len) };
    let message = match Message::parse(bytes) {
        Ok(message) => message,
        Err(error) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, format!("not a DNS message: {error}").into_bytes()) };
            return status::TRANSPORT;
        }
    };

    let mut lines = String::new();
    for record in message.records() {
        match record.record_type {
            TYPE_SRV => {
                // A record whose rdata does not parse is skipped rather than failing the
                // packet: one malformed answer must not hide the readable ones beside it.
                let (Ok(host), Ok(port)) = (record.target(), record.port()) else {
                    continue;
                };
                push(&mut lines, &["srv", &record.name, &host, &port.to_string()]);
            }
            TYPE_A => {
                let Ok(address) = record.address_v4() else {
                    continue;
                };
                push(
                    &mut lines,
                    &["addr", &record.name, &format!("{}.{}.{}.{}", address[0], address[1], address[2], address[3])],
                );
            }
            TYPE_AAAA => {
                let Ok(address) = record.address_v6() else {
                    continue;
                };
                push(&mut lines, &["addr", &record.name, &address]);
            }
            TYPE_TXT => {
                let Ok(text) = record.txt() else {
                    continue;
                };
                for key in TEXT_KEYS {
                    let value = text.get(key);
                    if value.is_empty() {
                        continue;
                    }
                    push(&mut lines, &["txt", &record.name, key, value]);
                }
            }
            // PTR is the packet's own index of instances and is not joined to anything: the
            // SRV record that follows carries the name a peer is displayed under, and a PTR
            // for an instance whose SRV never arrives names nothing that can be connected to.
            _ => {}
        }
    }

    // SAFETY: `report` is non-null and zeroed, per the contract.
    unsafe { fill(report, lines.into_bytes()) };
    status::OK
}

/// Appends one tab-separated line, replacing the characters that would break the framing.
///
/// A peer's name is chosen by whoever owns that phone and may contain anything, including a
/// tab or a newline. Escaping would need an unescape on the other side; replacing costs a
/// character in a name that is only shown.
fn push(lines: &mut String, fields: &[&str]) {
    for (index, field) in fields.iter().enumerate() {
        if index > 0 {
            lines.push('\t');
        }
        for character in field.chars() {
            lines.push(if matches!(character, '\t' | '\n' | '\r') {
                ' '
            } else {
                character
            });
        }
    }
    lines.push('\n');
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::buffer::opairdrop_buffer_free;

    fn records(packet: &[u8]) -> String {
        let mut report = OpairdropBuffer::EMPTY;
        // SAFETY: the packet slice outlives the call and `report` is a zeroed buffer.
        let outcome = unsafe { opairdrop_mdns_records(packet.as_ptr(), packet.len(), &mut report) };
        assert_eq!(outcome, status::OK);
        // SAFETY: the call filled `report` and nothing has released it.
        let text = unsafe { String::from_utf8_lossy(report.as_slice()).into_owned() };
        // SAFETY: as above, released once.
        unsafe { opairdrop_buffer_free(&mut report) };
        text
    }

    #[test]
    fn the_query_is_one_ptr_question_for_the_airdrop_service() {
        let mut report = OpairdropBuffer::EMPTY;
        // SAFETY: `report` is a zeroed buffer on this frame.
        let outcome = unsafe { opairdrop_mdns_query(&mut report) };
        assert_eq!(outcome, status::OK);
        // SAFETY: filled by the call above.
        let bytes = unsafe { report.as_slice() }.to_vec();
        // SAFETY: released once.
        unsafe { opairdrop_buffer_free(&mut report) };

        let message = Message::parse(&bytes).unwrap();
        assert!(!message.is_response());
        assert_eq!(message.questions().len(), 1);
        assert!(message.questions()[0].matches(SERVICE, TYPE_PTR));
    }

    #[test]
    fn a_response_becomes_three_kinds_of_line() {
        let srv = Builder::srv_rdata(0, 0, 8770, "adas-iphone.local").unwrap();
        let txt = Builder::txt_rdata(&[("model", "iPhone14,3"), ("flags", "2")]).unwrap();
        let packet = Builder::response()
            .answer("Ada._airdrop._tcp.local", TYPE_SRV, 120, &srv)
            .unwrap()
            .answer("adas-iphone.local", TYPE_A, 4500, &[192, 168, 1, 5])
            .unwrap()
            .answer("Ada._airdrop._tcp.local", TYPE_TXT, 120, &txt)
            .unwrap()
            .build();

        let text = records(&packet);
        assert!(text.contains("srv\tAda._airdrop._tcp.local\tadas-iphone.local\t8770\n"), "{text}");
        assert!(text.contains("addr\tadas-iphone.local\t192.168.1.5\n"), "{text}");
        assert!(text.contains("txt\tAda._airdrop._tcp.local\tmodel\tiPhone14,3\n"), "{text}");
    }

    #[test]
    fn a_packet_that_is_not_dns_is_refused_rather_than_parsed_into_nothing() {
        let mut report = OpairdropBuffer::EMPTY;
        let junk = [0u8, 1, 2, 3];
        // SAFETY: the slice outlives the call and `report` is zeroed.
        let outcome = unsafe { opairdrop_mdns_records(junk.as_ptr(), junk.len(), &mut report) };
        assert_eq!(outcome, status::TRANSPORT);
        // SAFETY: filled by the call above.
        unsafe { opairdrop_buffer_free(&mut report) };
    }
}
