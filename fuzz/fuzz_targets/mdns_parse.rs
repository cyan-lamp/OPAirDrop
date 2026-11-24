#![no_main]
//! mDNS 报文：5353 组播上的压缩指针与计数（P1-2 的放大形状从这里盯回来）。
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = opairdrop_mdns::Message::parse(data);
});
