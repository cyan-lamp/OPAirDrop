#![no_main]
//! HTTP 头解析：请求走私与失同步的旧伤（P1-5/P1-6 的形状从这里盯回来）。
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let mut input = data;
    let _ = opairdrop_http::Request::read(&mut input);
});
