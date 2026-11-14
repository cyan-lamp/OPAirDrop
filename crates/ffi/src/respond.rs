//! Answering: the records this device publishes, and the reply to whoever asks for them.
//!
//! [`crate::browse`] is the asking half — one PTR question out, record lines back. This is
//! the other half, and it is what makes an iPhone list this device at all: a browsing iPhone
//! multicasts a PTR question and then waits for somebody to answer it. Until something here
//! answers, the phone is invisible however healthy the socket and the link are.
//!
//! # Why the records are rebuilt on every send
//!
//! Nothing is cached between sends. The certificate hash in TXT changes when the identity is
//! rotated, the address list changes when the tunnel is rebuilt, and the port changes when
//! the listener is restarted — a cached announcement would keep publishing whatever was true
//! when it was cached, and a peer would connect to a port that is not listening. Rebuilding
//! costs one allocation per reply, on a path that runs a few times a second at most.
//!
//! # The contract, and where it comes from
//!
//! Four records, in this order, with these lifetimes:
//!
//! ```text
//! PTR    _airdrop._tcp.local            -> <instance>._airdrop._tcp.local   4500
//! SRV    <instance>._airdrop._tcp.local -> host:port (priority 0, weight 0)  120
//! TXT    <instance>._airdrop._tcp.local -> h, fl, flags                      4500
//! AAAA   host                           -> every link-local address         120
//! ```
//!
//! This is not an interpretation of the specification. It is what the legacy implementation
//! sends — `AirDropPeer.announce` — and it is the only version known to appear in an iPhone's
//! share sheet. Both the lifetimes and the order are load-bearing: an iPhone that answers a
//! PTR question with our SRV and TXT and no address has a name to show and nothing to
//! connect to.
//!
//! # Why `h`, `fl` and `flags`
//!
//! `h` is the SHA-256 of our certificate, uppercase hex, and it is what the sender on the
//! other side pins before it will hand over a file. `fl` and `flags` carry the same number
//! twice: iOS reads `flags`, the drafts this code grew up on read `fl`, and a device that
//! publishes only one of them is a device whose capabilities read as zero.

use std::net::Ipv6Addr;

use opairdrop_mdns::{
    same_name, write_name, Builder, Error, Message, FLAG_AUTHORITATIVE, FLAG_RESPONSE, TYPE_AAAA,
    TYPE_ANY, TYPE_PTR, TYPE_SRV, TYPE_TXT,
};

use crate::api::status;
use crate::browse::SERVICE;
use crate::buffer::{fill, OpairdropBuffer};

/// Bit for the PTR record in an announcement's `types` mask.
pub const RECORD_PTR: u32 = 1;
/// Bit for the SRV record.
pub const RECORD_SRV: u32 = 2;
/// Bit for the TXT record.
pub const RECORD_TXT: u32 = 4;
/// Bit for the AAAA records.
pub const RECORD_ADDRESS: u32 = 8;
/// Every record kind, which is what an announcement of a live instance carries.
pub const RECORD_ALL: u32 = RECORD_PTR | RECORD_SRV | RECORD_TXT | RECORD_ADDRESS;

/// The PTR lifetime: seventy-five minutes, which is the cost of not withdrawing.
pub const TTL_PTR: u32 = 4500;
/// The SRV lifetime.
pub const TTL_SRV: u32 = 120;
/// The TXT lifetime.
pub const TTL_TXT: u32 = 4500;
/// The AAAA lifetime.
pub const TTL_ADDRESS: u32 = 120;

/// "Leave each record the lifetime it was written with."
///
/// Negative rather than zero because zero is already spoken for: a record with a zero TTL is
/// the withdrawal of RFC 6762 §10.1, and it has to reach the wire as zero. A responder that
/// merely stops announcing is remembered for as long as it last said it would be.
pub const TTL_AS_PUBLISHED: i32 = -1;

/// The full name a peer asks for: the escaped instance label under the service.
pub fn service_name(instance: &str) -> String {
    format!("{}.{}", escape_label(instance), SERVICE)
}

/// Escapes the two characters that would otherwise be read as structure.
///
/// A dot would split the label; a backslash would start an escape. Everything else —
/// spaces included — travels verbatim, which is what mDNS puts on the wire and what the
/// legacy implementation does.
fn escape_label(label: &str) -> String {
    let mut out = String::with_capacity(label.len() + 4);
    for character in label.chars() {
        if character == '\\' || character == '.' {
            out.push('\\');
        }
        out.push(character);
    }
    out
}

/// Which of our record kinds one question asks for; zero means none.
///
/// A question is not a yes/no. Asking for `PTR _airdrop._tcp.local` is asking for the whole
/// instance — a PTR alone names something that cannot be connected to — while asking for
/// `TXT <instance>` comes from a peer that already holds the rest and wants only that. The
/// mask is what carries the difference back to [`announce`].
pub fn types_for(name: &str, question_type: u16, instance: &str, host: &str) -> u32 {
    let any = question_type == TYPE_ANY;

    if same_name(name, SERVICE) {
        if !any && question_type != TYPE_PTR {
            return 0;
        }
        // Apple answers a PTR question with the instance records as additionals, which is
        // what a sender needs before it can open a connection.
        return RECORD_ALL;
    }

    if same_name(name, &service_name(instance)) {
        if any {
            return RECORD_SRV | RECORD_TXT | RECORD_ADDRESS;
        }
        // An SRV answer is useless without the TXT — it carries the certificate hash — and
        // the address, so those ride along. TXT and AAAA questions come from a peer that
        // already holds the rest, and get exactly what they asked for.
        return match question_type {
            TYPE_SRV => RECORD_SRV | RECORD_TXT | RECORD_ADDRESS,
            TYPE_TXT => RECORD_TXT,
            TYPE_AAAA => RECORD_ADDRESS,
            _ => 0,
        };
    }

    if same_name(name, host) {
        if !any && question_type != TYPE_AAAA {
            return 0;
        }
        return RECORD_ADDRESS;
    }

    0
}

/// A reply to one query, and how it has to be sent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reply {
    /// The DNS message.
    pub packet: Vec<u8>,
    /// True when the querier asked from a port other than 5353 and RFC 6762 §6.7 therefore
    /// requires the answer to go back to it directly rather than to the group.
    pub unicast: bool,
}

/// Builds one announcement of `types`, as a DNS response.
///
/// `id` is zero for a multicast announcement and the query's own id for a unicast reply.
/// `txt` is the ordered key/value list — order is preserved because a peer that logs it
/// expects to see what it sent — and `addresses` is whatever textual IPv6 addresses the
/// caller found on the interface. An address that does not parse is skipped rather than
/// failing the announcement: one unreadable string must not make the device invisible.
///
/// `ttl_override` is [`TTL_AS_PUBLISHED`] for the normal lifetimes, or a value to force —
/// zero being the withdrawal.
pub fn announce(
    instance: &str,
    host: &str,
    port: u16,
    txt: &[(String, String)],
    addresses: &[String],
    id: u16,
    types: u32,
    ttl_override: i32,
) -> Result<Vec<u8>, Error> {
    let service_name = service_name(instance);
    let mut builder = Builder::new(id, FLAG_RESPONSE | FLAG_AUTHORITATIVE);

    if types & RECORD_PTR != 0 {
        let mut rdata = Vec::new();
        write_name(&mut rdata, &service_name)?;
        builder = builder.answer(SERVICE, TYPE_PTR, ttl(TTL_PTR, ttl_override), &rdata)?;
    }

    if types & RECORD_SRV != 0 {
        let rdata = Builder::srv_rdata(0, 0, port, host)?;
        builder = builder.answer(&service_name, TYPE_SRV, ttl(TTL_SRV, ttl_override), &rdata)?;
    }

    if types & RECORD_TXT != 0 {
        // Every key this device publishes carries a value, so the crate's "bare key" case
        // for an empty one never arises and does not change a byte of the wire format.
        let pairs: Vec<(&str, &str)> = txt
            .iter()
            .map(|(key, value)| (key.as_str(), value.as_str()))
            .collect();
        let rdata = Builder::txt_rdata(&pairs)?;
        builder = builder.answer(&service_name, TYPE_TXT, ttl(TTL_TXT, ttl_override), &rdata)?;
    }

    if types & RECORD_ADDRESS != 0 {
        for address in addresses {
            let Some(raw) = ipv6_bytes(address) else {
                continue;
            };
            builder = builder.answer(host, TYPE_AAAA, ttl(TTL_ADDRESS, ttl_override), &raw)?;
        }
    }

    Ok(builder.build())
}

/// Answers one query packet, or says there is nothing to answer.
///
/// `Ok(None)` covers the three ordinary cases: the packet is itself a response (a peer
/// announcing, which this device only listens to), it asks about names that are not ours,
/// or it asks for a record type we do not publish. None of them is an error, and none
/// should cost the caller a datagram.
///
/// `Err` means the datagram is not a DNS message at all, which on port 5353 does happen —
/// other services share it.
pub fn reply(
    packet: &[u8],
    instance: &str,
    host: &str,
    port: u16,
    txt: &[(String, String)],
    addresses: &[String],
) -> Result<Option<Reply>, Error> {
    let message = Message::parse(packet)?;
    if message.is_response() {
        return Ok(None);
    }

    let mut types = 0;
    let mut unicast = false;
    for question in message.questions() {
        let wanted = types_for(&question.name, question.record_type, instance, host);
        if wanted == 0 {
            continue;
        }
        types |= wanted;
        unicast |= question.unicast;
    }
    if types == 0 {
        return Ok(None);
    }

    // mDNS keeps id zero on multicast responses; a unicast answer echoes the query id so the
    // querier can match it against the socket it used.
    let id = if unicast { message.id() } else { 0 };
    let packet = announce(
        instance,
        host,
        port,
        txt,
        addresses,
        id,
        types,
        TTL_AS_PUBLISHED,
    )?;
    Ok(Some(Reply { packet, unicast }))
}

/// `override` when a caller forced a lifetime, the record's own value otherwise.
fn ttl(published: u32, ttl_override: i32) -> u32 {
    if ttl_override == TTL_AS_PUBLISHED {
        published
    } else {
        // A negative value other than the sentinel is nonsense; zero, the withdrawal, is
        // the only thing below the sentinel's meaning that a caller could want.
        ttl_override.max(0) as u32
    }
}

/// The sixteen bytes of a textual IPv6 address, or nothing when it is not one.
///
/// The `%interface` suffix is dropped first: the address is published as a plain link-local,
/// and the interface a peer reaches us on is decided by the packet that carries it, not by a
/// scope written into rdata.
fn ipv6_bytes(address: &str) -> Option<[u8; 16]> {
    let bare = address.split('%').next()?;
    bare.parse::<Ipv6Addr>()
        .ok()
        .map(|address| address.octets())
}

/// Reads a UTF-8 region the caller promised, or says which argument was unreadable.
///
/// # Safety
///
/// `pointer` must be null or point at `length` readable bytes for the duration of the call.
unsafe fn text<'a>(pointer: *const u8, length: usize, what: &str) -> Result<&'a str, String> {
    if length == 0 {
        return Ok("");
    }
    if pointer.is_null() {
        return Err(format!("{what} is null but its length is {length}"));
    }
    // SAFETY: the caller guarantees the region for the length given.
    let bytes = unsafe { std::slice::from_raw_parts(pointer, length) };
    std::str::from_utf8(bytes).map_err(|_| format!("{what} is not valid UTF-8"))
}

/// Splits one region into its non-empty lines, dropping a trailing carriage return.
fn lines(blob: &str) -> impl Iterator<Item = &str> {
    blob.split('\n')
        .map(|line| line.strip_suffix('\r').unwrap_or(line))
        .filter(|line| !line.is_empty())
}

/// Parses `key=value` per line into an ordered list; a line without `=` is a bare key.
fn txt_pairs(blob: &str) -> Vec<(String, String)> {
    lines(blob)
        .map(|line| match line.split_once('=') {
            Some((key, value)) => (key.to_string(), value.to_string()),
            None => (line.to_string(), String::new()),
        })
        .collect()
}

/// Builds one announcement of `types`, as a DNS response.
///
/// `instance` is the bare instance label — it is escaped here, so a name containing a dot or
/// a backslash is published as one label rather than as several. `host` is the full
/// `.local` name. `txt` is `key=value` per line, in the order the records should carry them.
/// `addresses` is one textual IPv6 address per line.
///
/// `ttl` is [`TTL_AS_PUBLISHED`] for the normal lifetimes, a value to force otherwise, and
/// zero for the withdrawal of RFC 6762 §10.1.
///
/// `report` receives the packet, or a message on failure.
///
/// # Safety
///
/// Every pointer must be null or point at its stated length of readable bytes for the
/// duration; `report` must point at a zeroed [`OpairdropBuffer`] that outlives the call.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn opairdrop_mdns_announce(
    instance: *const u8,
    instance_len: usize,
    host: *const u8,
    host_len: usize,
    port: u16,
    txt: *const u8,
    txt_len: usize,
    addresses: *const u8,
    addresses_len: usize,
    id: u16,
    types: u32,
    ttl_override: i32,
    report: *mut OpairdropBuffer,
) -> i32 {
    if report.is_null() || instance.is_null() || host.is_null() {
        return status::NULL_ARGUMENT;
    }

    // SAFETY: each pointer is promised by the caller for this call.
    let (instance, host, txt, addresses) = unsafe {
        (
            text(instance, instance_len, "the instance name"),
            text(host, host_len, "the host name"),
            text(txt, txt_len, "the TXT records"),
            text(addresses, addresses_len, "the address list"),
        )
    };
    let (instance, host, txt, addresses) = match (instance, host, txt, addresses) {
        (Ok(instance), Ok(host), Ok(txt), Ok(addresses)) => {
            (instance, host, txt_pairs(txt), addresses)
        }
        (Err(message), ..) | (_, Err(message), ..) | (_, _, Err(message), _) | (_, _, _, Err(message)) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, message.into_bytes()) };
            return status::TRANSPORT;
        }
    };
    let addresses: Vec<String> = lines(addresses).map(str::to_string).collect();

    match announce(
        instance,
        host,
        port,
        &txt,
        &addresses,
        id,
        types,
        ttl_override,
    ) {
        Ok(packet) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, packet) };
            status::OK
        }
        Err(error) => {
            // SAFETY: as above.
            unsafe { fill(report, format!("the announcement could not be built: {error}").into_bytes()) };
            status::TRANSPORT
        }
    }
}

/// Answers one query packet, writing the packet into `report`.
///
/// A packet that asks nothing of ours is not an error: `report` comes back empty and the
/// status is [`status::OK`], because the caller's next move — send nothing — is the same as
/// it would be after a failure, without a message to log for a datagram that arrives every
/// few seconds.
///
/// When there is an answer, `unicast` receives one for "send this back to where the query
/// came from" and zero for "multicast it"; it is left untouched when there is no answer.
///
/// # Safety
///
/// `packet` must point at `packet_len` readable bytes; every other pointer must be null or
/// point at its stated length of readable bytes; `report` must point at a zeroed
/// [`OpairdropBuffer`] that outlives the call; `unicast` must be null or valid for writes.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn opairdrop_mdns_reply(
    packet: *const u8,
    packet_len: usize,
    instance: *const u8,
    instance_len: usize,
    host: *const u8,
    host_len: usize,
    port: u16,
    txt: *const u8,
    txt_len: usize,
    addresses: *const u8,
    addresses_len: usize,
    report: *mut OpairdropBuffer,
    unicast: *mut u8,
) -> i32 {
    if report.is_null() || packet.is_null() || instance.is_null() || host.is_null() {
        return status::NULL_ARGUMENT;
    }

    // SAFETY: each pointer is promised by the caller for this call.
    let (packet, instance, host, txt, addresses) = unsafe {
        (
            std::slice::from_raw_parts(packet, packet_len),
            text(instance, instance_len, "the instance name"),
            text(host, host_len, "the host name"),
            text(txt, txt_len, "the TXT records"),
            text(addresses, addresses_len, "the address list"),
        )
    };
    let (instance, host, txt, addresses) = match (instance, host, txt, addresses) {
        (Ok(instance), Ok(host), Ok(txt), Ok(addresses)) => {
            (instance, host, txt_pairs(txt), addresses)
        }
        (Err(message), ..) | (_, Err(message), ..) | (_, _, Err(message), _) | (_, _, _, Err(message)) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, message.into_bytes()) };
            return status::TRANSPORT;
        }
    };
    let addresses: Vec<String> = lines(addresses).map(str::to_string).collect();

    match reply(packet, instance, host, port, &txt, &addresses) {
        Ok(Some(answer)) => {
            if !unicast.is_null() {
                // SAFETY: the caller promises this is valid for writes.
                unsafe { *unicast = u8::from(answer.unicast) };
            }
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, answer.packet) };
            status::OK
        }
        Ok(None) => {
            // SAFETY: `report` is non-null and zeroed, per the contract.
            unsafe { fill(report, Vec::new()) };
            status::OK
        }
        Err(error) => {
            // SAFETY: as above.
            unsafe { fill(report, format!("not a DNS message: {error}").into_bytes()) };
            status::TRANSPORT
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::buffer::opairdrop_buffer_free;
    use opairdrop_mdns::{CLASS_IN, TYPE_A};

    const INSTANCE: &str = "Ada";
    const HOST: &str = "adas-phone.local";
    const PORT: u16 = 8770;

    fn txt() -> Vec<(String, String)> {
        vec![
            ("h".to_string(), "AB12".to_string()),
            ("fl".to_string(), "1078".to_string()),
            ("flags".to_string(), "1078".to_string()),
        ]
    }

    fn addresses() -> Vec<String> {
        vec!["fe80::6410:37ff:fe63:44fb".to_string()]
    }

    fn full() -> Vec<u8> {
        announce(
            INSTANCE,
            HOST,
            PORT,
            &txt(),
            &addresses(),
            0,
            RECORD_ALL,
            TTL_AS_PUBLISHED,
        )
        .unwrap()
    }

    /// The wire form of one name, written by the crate's own encoder.
    fn encoded(name: &str) -> Vec<u8> {
        let mut out = Vec::new();
        write_name(&mut out, name).unwrap();
        out
    }

    #[test]
    fn an_announcement_carries_the_four_records_with_their_published_lifetimes() {
        let packet = full();
        let message = Message::parse(&packet).unwrap();
        assert!(message.is_response());

        let records = message.records();
        let kinds: Vec<(String, u16, u32)> = records
            .iter()
            .map(|record| (record.name.clone(), record.record_type, record.ttl))
            .collect();
        assert_eq!(
            kinds,
            vec![
                (SERVICE.to_string(), TYPE_PTR, 4500),
                ("Ada._airdrop._tcp.local".to_string(), TYPE_SRV, 120),
                ("Ada._airdrop._tcp.local".to_string(), TYPE_TXT, 4500),
                (HOST.to_string(), TYPE_AAAA, 120),
            ]
        );

        // `target()` reads an SRV target, six bytes into the record; a PTR's name starts at
        // its first byte, so the PTR is checked as the name it encodes.
        assert_eq!(records[0].rdata(), encoded("Ada._airdrop._tcp.local"));
        assert_eq!(records[1].target().unwrap(), HOST);
        assert_eq!(records[1].port().unwrap(), PORT);
        assert_eq!(records[1].priority().unwrap(), 0);
        assert_eq!(records[2].txt().unwrap().get("h"), "AB12");
        assert_eq!(records[2].txt().unwrap().get("flags"), "1078");
        assert_eq!(records[3].address_v6().unwrap(), "fe80::6410:37ff:fe63:44fb");
    }

    #[test]
    fn the_types_mask_picks_records_and_keeps_the_order() {
        let packet = announce(
            INSTANCE,
            HOST,
            PORT,
            &txt(),
            &addresses(),
            0,
            RECORD_SRV | RECORD_ADDRESS,
            TTL_AS_PUBLISHED,
        )
        .unwrap();
        let message = Message::parse(&packet).unwrap();
        let kinds: Vec<u16> = message.records().iter().map(|r| r.record_type).collect();
        assert_eq!(kinds, vec![TYPE_SRV, TYPE_AAAA]);
    }

    #[test]
    fn a_dot_in_the_instance_name_stays_inside_one_label() {
        let packet = announce(
            "Ada.Lovelace",
            HOST,
            PORT,
            &txt(),
            &addresses(),
            0,
            RECORD_PTR,
            TTL_AS_PUBLISHED,
        )
        .unwrap();
        let message = Message::parse(&packet).unwrap();
        // The dot stays inside one label, and so does the backslash that marks it: the wire
        // form of `Ada.Lovelace` is one label of thirteen bytes — the eleven characters and
        // the escape — not two labels of three and eight. That is what the legacy encoder
        // writes and what its decoder reads, so the two agree on the name being one thing.
        let rdata = message.records()[0].rdata();
        assert_eq!(rdata[0], 13);
        assert_eq!(&rdata[1..14], b"Ada\\.Lovelace");
        assert_eq!(rdata[14], 8);
        assert_eq!(&rdata[15..23], b"_airdrop");
        // And the escaped name compares equal to the name a question would carry.
        assert!(same_name(
            "Ada\\.Lovelace._airdrop._tcp.local",
            &service_name("Ada.Lovelace")
        ));
    }

    #[test]
    fn zero_ttl_is_the_withdrawal_and_reaches_the_wire_as_zero() {
        let packet = announce(INSTANCE, HOST, PORT, &txt(), &addresses(), 0, RECORD_ALL, 0).unwrap();
        let message = Message::parse(&packet).unwrap();
        for record in message.records() {
            assert_eq!(record.ttl, 0, "{} was not withdrawn", record.name);
        }
    }

    #[test]
    fn an_address_that_does_not_parse_is_skipped_rather_than_fatal() {
        let packet = announce(
            INSTANCE,
            HOST,
            PORT,
            &txt(),
            &["not an address".to_string(), "fe80::1".to_string()],
            0,
            RECORD_ADDRESS,
            TTL_AS_PUBLISHED,
        )
        .unwrap();
        let message = Message::parse(&packet).unwrap();
        assert_eq!(message.records().len(), 1);
        assert_eq!(message.records()[0].address_v6().unwrap(), "fe80::1");
    }

    #[test]
    fn a_scope_suffix_is_dropped_before_parsing() {
        let packet = announce(
            INSTANCE,
            HOST,
            PORT,
            &txt(),
            &["fe80::1%awdltun0".to_string()],
            0,
            RECORD_ADDRESS,
            TTL_AS_PUBLISHED,
        )
        .unwrap();
        let message = Message::parse(&packet).unwrap();
        assert_eq!(message.records()[0].address_v6().unwrap(), "fe80::1");
    }

    #[test]
    fn a_ptr_question_is_answered_with_the_whole_instance() {
        let query = Builder::new(0, 0)
            .question(SERVICE, TYPE_PTR, CLASS_IN)
            .unwrap()
            .build();
        let answer = reply(&query, INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .expect("a PTR question is exactly the question a browsing iPhone asks");

        assert!(!answer.unicast);
        let message = Message::parse(&answer.packet).unwrap();
        assert_eq!(message.id(), 0);
        let kinds: Vec<u16> = message.records().iter().map(|r| r.record_type).collect();
        assert_eq!(kinds, vec![TYPE_PTR, TYPE_SRV, TYPE_TXT, TYPE_AAAA]);
    }

    #[test]
    fn an_srv_question_brings_the_txt_and_the_address_with_it() {
        let query = Builder::new(0, 0)
            .question(&service_name(INSTANCE), TYPE_SRV, CLASS_IN)
            .unwrap()
            .build();
        let answer = reply(&query, INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .unwrap();
        let message = Message::parse(&answer.packet).unwrap();
        let kinds: Vec<u16> = message.records().iter().map(|r| r.record_type).collect();
        assert_eq!(kinds, vec![TYPE_SRV, TYPE_TXT, TYPE_AAAA]);
    }

    #[test]
    fn a_txt_question_gets_exactly_the_txt() {
        let query = Builder::new(0, 0)
            .question(&service_name(INSTANCE), TYPE_TXT, CLASS_IN)
            .unwrap()
            .build();
        let answer = reply(&query, INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .unwrap();
        let message = Message::parse(&answer.packet).unwrap();
        let kinds: Vec<u16> = message.records().iter().map(|r| r.record_type).collect();
        assert_eq!(kinds, vec![TYPE_TXT]);
    }

    #[test]
    fn an_any_question_on_the_instance_gets_srv_txt_and_address() {
        let query = Builder::new(0, 0)
            .question(&service_name(INSTANCE), TYPE_ANY, CLASS_IN)
            .unwrap()
            .build();
        let answer = reply(&query, INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .unwrap();
        let message = Message::parse(&answer.packet).unwrap();
        let kinds: Vec<u16> = message.records().iter().map(|r| r.record_type).collect();
        assert_eq!(kinds, vec![TYPE_SRV, TYPE_TXT, TYPE_AAAA]);
    }

    #[test]
    fn a_question_about_somebody_else_is_left_alone() {
        let query = Builder::new(0, 0)
            .question("Bob._airdrop._tcp.local", TYPE_PTR, CLASS_IN)
            .unwrap()
            .build();
        assert!(reply(&query, INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .is_none());
    }

    #[test]
    fn a_question_for_a_record_we_do_not_publish_is_left_alone() {
        // `A` for our host: this device is IPv6 only over the tunnel, and an address record
        // it cannot honour is better unanswered than answered empty.
        let query = Builder::new(0, 0)
            .question(HOST, TYPE_A, CLASS_IN)
            .unwrap()
            .build();
        assert!(reply(&query, INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .is_none());
    }

    #[test]
    fn a_response_is_not_answered() {
        assert!(reply(&full(), INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .is_none());
    }

    #[test]
    fn a_unicast_question_is_echoed_with_its_id() {
        let query = Builder::new(0x1234, 0)
            .question(SERVICE, TYPE_PTR, CLASS_IN | opairdrop_mdns::CLASS_UNICAST)
            .unwrap()
            .build();
        let answer = reply(&query, INSTANCE, HOST, PORT, &txt(), &addresses())
            .unwrap()
            .unwrap();
        assert!(answer.unicast);
        let message = Message::parse(&answer.packet).unwrap();
        assert_eq!(message.id(), 0x1234);
    }

    #[test]
    fn a_packet_that_is_not_dns_is_refused() {
        assert!(reply(&[0, 1, 2, 3], INSTANCE, HOST, PORT, &txt(), &addresses()).is_err());
    }

    #[test]
    fn the_c_export_writes_the_packet_and_reports_the_send_mode() {
        let query = Builder::new(0x4321, 0)
            .question(SERVICE, TYPE_PTR, CLASS_IN | opairdrop_mdns::CLASS_UNICAST)
            .unwrap()
            .build();
        let txt = b"h=AB12\nfl=1078\nflags=1078";
        let addresses = b"fe80::6410:37ff:fe63:44fb";

        let mut report = OpairdropBuffer::EMPTY;
        let mut unicast = 0u8;
        // SAFETY: every slice outlives the call and `report` is zeroed.
        let outcome = unsafe {
            opairdrop_mdns_reply(
                query.as_ptr(),
                query.len(),
                INSTANCE.as_ptr(),
                INSTANCE.len(),
                HOST.as_ptr(),
                HOST.len(),
                PORT,
                txt.as_ptr(),
                txt.len(),
                addresses.as_ptr(),
                addresses.len(),
                &mut report,
                &mut unicast,
            )
        };
        assert_eq!(outcome, status::OK);
        assert_eq!(unicast, 1);
        // SAFETY: filled by the call above.
        let bytes = unsafe { report.as_slice() }.to_vec();
        // SAFETY: released once.
        unsafe { opairdrop_buffer_free(&mut report) };

        let message = Message::parse(&bytes).unwrap();
        assert_eq!(message.id(), 0x4321);
        assert_eq!(message.records().len(), 4);
        assert_eq!(message.records()[2].txt().unwrap().pairs().len(), 3);
    }

    #[test]
    fn the_c_export_answers_an_empty_buffer_when_there_is_nothing_to_say() {
        let query = Builder::new(0, 0)
            .question("Bob._airdrop._tcp.local", TYPE_PTR, CLASS_IN)
            .unwrap()
            .build();
        let mut report = OpairdropBuffer::EMPTY;
        let mut unicast = 7u8;
        // SAFETY: as above; the TXT and address regions are empty and so may be null.
        let outcome = unsafe {
            opairdrop_mdns_reply(
                query.as_ptr(),
                query.len(),
                INSTANCE.as_ptr(),
                INSTANCE.len(),
                HOST.as_ptr(),
                HOST.len(),
                PORT,
                std::ptr::null(),
                0,
                std::ptr::null(),
                0,
                &mut report,
                &mut unicast,
            )
        };
        assert_eq!(outcome, status::OK);
        // SAFETY: filled (with nothing) by the call above.
        assert!(unsafe { report.as_slice() }.is_empty());
        // SAFETY: released once.
        unsafe { opairdrop_buffer_free(&mut report) };
        // Untouched, because there is no answer to describe.
        assert_eq!(unicast, 7);
    }

    #[test]
    fn the_c_export_reports_an_unreadable_instance_rather_than_building_nothing() {
        let mut report = OpairdropBuffer::EMPTY;
        let broken = [0xffu8, 0xfe];
        // SAFETY: the slices outlive the call and `report` is zeroed.
        let outcome = unsafe {
            opairdrop_mdns_announce(
                broken.as_ptr(),
                broken.len(),
                HOST.as_ptr(),
                HOST.len(),
                PORT,
                std::ptr::null(),
                0,
                std::ptr::null(),
                0,
                0,
                RECORD_ALL,
                TTL_AS_PUBLISHED,
                &mut report,
            )
        };
        assert_eq!(outcome, status::TRANSPORT);
        // SAFETY: filled by the call above.
        let message = String::from_utf8_lossy(unsafe { report.as_slice() }).into_owned();
        assert!(message.contains("instance"), "{message}");
        // SAFETY: released once.
        unsafe { opairdrop_buffer_free(&mut report) };
    }

    #[test]
    fn the_question_class_survives_the_unicast_bit_being_masked_off() {
        // A guard against a later change that stops treating `CLASS_UNICAST` as a flag: the
        // parser is what tells us a querier wants a direct answer, and if it folded the bit
        // into the class, every unicast question would look like an unknown class.
        let query = Builder::new(0, 0)
            .question(SERVICE, TYPE_PTR, CLASS_IN | opairdrop_mdns::CLASS_UNICAST)
            .unwrap()
            .build();
        let message = Message::parse(&query).unwrap();
        assert_eq!(message.questions()[0].class, CLASS_IN);
        assert!(message.questions()[0].unicast);
    }
}
