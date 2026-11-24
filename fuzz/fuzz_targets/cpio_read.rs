#![no_main]
//! cpio 档案流：成员头的每个字段都来自对端。
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = opairdrop_cpio::read(data);
});
