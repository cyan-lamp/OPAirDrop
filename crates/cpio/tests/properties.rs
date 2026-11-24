//! 属性测试（评审 P1-23）：cpio 档案的往返恒等与任意字节不 panic。

use opairdrop_cpio::{read, to_vec, Entry, Format};
use proptest::prelude::*;

/// cpio 的成员名先过 safe_name 再落盘，这里只生成能原样往返的名字。
fn name() -> impl Strategy<Value = String> {
    // 首字符非空白：trim 后可能变空名，空成员在归档里不存在。
    "[a-zA-Z0-9_\\u{4e00}-\\u{9fff}][a-zA-Z0-9_\\-. \\u{4e00}-\\u{9fff}]{0,23}"
        .prop_map(|s| s.trim_end().to_string())
}

fn entries() -> impl Strategy<Value = Vec<Entry>> {
    prop::collection::vec(
        prop_oneof![
            (name(), any::<Vec<u8>>()).prop_map(|(n, d)| Entry::file(n, d)),
            name().prop_map(Entry::directory),
        ],
        0..8,
    )
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(64))]

    #[test]
    fn round_trips_newc(entries in entries()) {
        let bytes = to_vec(&entries, Format::Newc)?;
        let back = read(&bytes)?;
        prop_assert_eq!(entries.len(), back.len());
        for (a, b) in entries.iter().zip(back.iter()) {
            prop_assert_eq!(&a.name, &b.name);
            prop_assert_eq!(a.is_directory(), b.is_directory());
            prop_assert_eq!(&a.data, &b.data);
        }
    }

    /// 任意字节：Err 或 Ok，不允许 panic。
    #[test]
    fn never_panics_on_arbitrary_bytes(data in any::<Vec<u8>>()) {
        let _ = read(&data);
    }
}
