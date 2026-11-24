#![no_main]
//! 评审 P1-23：bplist 解码器吃对端可控字节（P0-2 的越界形状从这里盯回来）。
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = opairdrop_bplist::parse(data);
});
