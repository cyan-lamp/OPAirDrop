//! 属性测试（评审 P1-23）：HTTP 头解析吃的是对端的字节流。
//!
//! 不变式：合法头的字段往返，与任意字节不 panic（走私与失同步的旧伤在这里盯着）。

use opairdrop_http::Request;
use proptest::prelude::*;

/// 生成一条合法请求头：方法/路径/版本/若干头。
fn head(
    method: &str,
    path: &str,
    headers: &[(String, String)],
    body_len: usize,
) -> String {
    let mut raw = format!("{method} {path} HTTP/1.1\r\n");
    for (name, value) in headers {
        raw.push_str(&format!("{name}: {value}\r\n"));
    }
    if body_len > 0 {
        raw.push_str(&format!("Content-Length: {body_len}\r\n"));
    }
    raw.push_str("\r\n");
    raw
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(128))]

    #[test]
    fn head_fields_round_trip(
        method in prop_oneof![Just("POST"), Just("GET")],
        path in "[a-zA-Z0-9/]{1,20}",
        header_count in 0usize..4,
        body_len in any::<u16>(),
    ) {
        // 头名从一组固定值里选：RFC 9112 的 token 规则比任意字符串窄得多。
        let names = ["Host", "X-Test", "Transfer-ID", "Expect"];
        let headers: Vec<(String, String)> = (0..header_count)
            .map(|i| (names[i].to_string(), format!("value-{i}")))
            .collect();
        let body = vec![b'x'; body_len as usize];
        let raw = head(method, &path, &headers, body.len()) + std::str::from_utf8(&body).unwrap();

        let mut input = raw.as_bytes();
        let request = Request::read(&mut input)
            .expect("a well-formed head must parse")
            .expect("the head is complete");
        prop_assert_eq!(request.method(), method);
        prop_assert_eq!(request.path(), path);
        let expected = if header_count > 0 { Some("value-0".to_string()) } else { None };
        prop_assert_eq!(request.header("host").map(|h| h.to_string()), expected);
        let got = request.read_body(&mut input, 1 << 20).expect("body reads");
        prop_assert_eq!(got, body);
    }

    /// 任意字节进来：Ok(None) / Ok(Some) / Err，绝不允许 panic。
    #[test]
    fn never_panics_on_arbitrary_bytes(data in any::<Vec<u8>>()) {
        let mut input = &data[..];
        let _ = Request::read(&mut input);
    }
}
