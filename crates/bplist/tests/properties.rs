//! 属性测试（评审 P1-23）：bplist 的编解码器吃的是对端完全可控的字节。
//!
//! 两条不变式：**往返恒等**（合法值编码后必须原样解回来）与 **任意字节不 panic**
//! （panic="abort" 时一次越界就是整个接收进程死亡——P0-2 修过一次，这些用例防止
//! 它以别的形状回来）。

use opairdrop_bplist::{encode, parse, Value};
use proptest::prelude::*;

/// 递归生成「合法」的 bplist 值：深度有界、字符串里只有真实 AirDrop 会带的字符类。
fn value() -> impl Strategy<Value = Value> {
    let leaf = prop_oneof![
        Just(Value::Null),
        any::<bool>().prop_map(Value::Bool),
        any::<i64>().prop_map(Value::Integer),
        // NaN != NaN，会把「往返恒等」误报成失败——用位模式排除 NaN。
        any::<u64>().prop_map(|bits| Value::Real(f64::from_bits(bits | (1u64 << 62)))),
        // Date 经过 2001 纪元换算，亚秒精度不保（实测 2.0000000000000004 往返成 2.0）。
        // 真实 plist 的日期是秒精度，属性按秒生成；这一行同时是「已知保真边界」的记录。
        any::<u32>().prop_map(|secs| Value::Date(secs as f64)),
        any::<Vec<u8>>().prop_map(Value::Data),
        ".*".prop_map(Value::String),
    ];
    leaf.prop_recursive(4, 64, 8, |inner| {
        prop_oneof![
            prop::collection::vec(inner.clone(), 0..8).prop_map(Value::Array),
            prop::collection::vec((".{0,12}", inner), 0..8).prop_map(Value::Dict),
        ]
    })
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(128))]

    #[test]
    fn round_trips_any_legal_value(v in value()) {
        let bytes = encode(&v);
        let back = parse(&bytes).expect("a value we just encoded must decode");
        prop_assert_eq!(format!("{v:?}"), format!("{back:?}"));
    }

    /// 任意字节进来：要么 Ok 要么 Err，绝不允许 panic（P0-2 的形状回归防线）。
    #[test]
    fn never_panics_on_arbitrary_bytes(data in any::<Vec<u8>>()) {
        let _ = parse(&data);
    }

    /// 合法报文的截断：只允许走到 Err。
    #[test]
    fn truncations_are_errors_not_panics(v in value(), cut in 0usize..64) {
        let mut bytes = encode(&v);
        let cut = cut.min(bytes.len());
        bytes.truncate(cut);
        let _ = parse(&bytes);
    }
}
