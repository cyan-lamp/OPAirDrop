//! 属性测试（评审 P1-23）：mDNS 的名字与报文都来自 5353 上的组播，源头不可信。
//!
//! 不变式：名字的写读往返、消息结构的写读往返，以及任意字节不 panic。

use opairdrop_mdns::{write_name, Builder, Message};
use proptest::prelude::*;

/// DNS 名字：点分隔的 label，只含字母数字与连字符（真实 AirDrop 记录的字符类）。
fn dns_name() -> impl Strategy<Value = String> {
    prop::collection::vec("[a-zA-Z0-9][a-zA-Z0-9-]{0,15}", 1..6)
        .prop_map(|labels| labels.join("."))
}

/// SRV 的 rdata 常用构造：优先级、权重、端口、目标。
fn srv_target() -> impl Strategy<Value = String> {
    dns_name()
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(128))]

    #[test]
    fn name_write_read_round_trips(name in dns_name()) {
        let mut wire = Vec::new();
        write_name(&mut wire, &name).expect("benign labels must encode");
        let mut at = 0usize;
        let back = opairdrop_mdns::read_name(&wire, &mut at).expect("must decode");
        prop_assert_eq!(name, back);
    }

    #[test]
    fn message_write_read_round_trips(
        _id in any::<u16>(),
        qname in dns_name(),
        target in srv_target(),
        port in any::<u16>(),
    ) {
        let bytes = Builder::response()
            .question(&qname, 33, 1)
            .and_then(|b| {
                let rdata = Builder::srv_rdata(0, 0, port, &target)?;
                b.answer(&qname, 33, 120, &rdata)
            })
            .map(|b| b.build())
            .expect("benign parts must build");
        let msg = Message::parse(&bytes).expect("a message we just built must parse");
        prop_assert_eq!(msg.questions().len(), 1);
        prop_assert_eq!(msg.records().len(), 1);
    }

    /// 任意字节进来：Err 或 Ok，不允许 panic（17 字节骗 12 MB 预分配的旧伤在这里盯着）。
    #[test]
    fn never_panics_on_arbitrary_bytes(data in any::<Vec<u8>>()) {
        let _ = Message::parse(&data);
    }
}
