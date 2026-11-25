//! Print one presence frame as hex, so it can be diffed against a capture.
//!
//! `cargo run -p opairdrop-awdl --example dump_psf -- 149 OPairDrop [mif]`
//!
//! The frame a real iPhone sends is the only oracle for this format: the capture that
//! produced `action.rs` came from one, and every field below is checked against it. When
//! a peer stops answering, the first question is always "did the frame change shape", and
//! this prints the bytes to compare.

use opairdrop_awdl::action::{self, NodeInfo};
use opairdrop_awdl::mac::Mac;
use opairdrop_awdl::schedule::Schedule;

fn main() {
    let mut args = std::env::args().skip(1);
    let channel: u8 = args.next().unwrap_or_else(|| "149".into()).parse().unwrap();
    let name = args.next().unwrap_or_else(|| "OPAirDrop".into());
    let mif = args.next().is_some();

    let addr = Mac::from_slice(&[0x02, 0x11, 0x22, 0x33, 0x44, 0x55]).unwrap();
    let node = NodeInfo::master_of(addr, name, channel);
    let schedule = Schedule::anchored(0, channel);

    let frame = action::presence(&node, &schedule, 0, 1, mif, 36);
    // Radiotap is transport, not wire format: skip it so the hex starts at the MAC header.
    // Its own length field is authoritative — the real device emits one with more fields
    // than we build, so a capture and this dump only line up past it.
    let rt = u16::from_le_bytes([frame[2], frame[3]]) as usize;
    let body = &frame[rt..];
    println!("len={} (after {}-byte radiotap)", body.len(), rt);
    for (i, chunk) in body.chunks(16).enumerate() {
        let hex: Vec<String> = chunk.iter().map(|b| format!("{b:02x}")).collect();
        println!("{:04x}  {:47}  {}", i * 16, hex.join(" "), printable(chunk));
    }
}

fn printable(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|&b| {
            if (32..127).contains(&b) {
                b as char
            } else {
                '.'
            }
        })
        .collect()
}
