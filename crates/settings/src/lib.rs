//! Every tunable the protocol stack has, declared once.
//!
//! # Why one table
//!
//! A tunable normally exists three times: as a constant in the code that reads it, as a
//! field in the settings screen, and as a line in whatever the config is stored in. The
//! three agree on the day they are written and drift on the first edit — the screen keeps
//! offering 15 s while the code has been waiting 20 s, and the number on screen is a
//! believable lie. Here the constant, the range, and the wording are one row. The settings
//! screen is drawn *from* the table, so a setting the screen does not know about cannot
//! exist, and a setting that exists cannot be missing from the screen.
//!
//! # Why the range is in the row
//!
//! A number without a range is a number that can be set to zero. `server.read_timeout_ms`
//! at zero is not a fast timeout, it is a connection that fails before the peer has said
//! anything; `receive.byte_limit` at zero refuses everything. Every [`Kind::Number`] carries
//! the range that keeps the value meaningful, and [`Values::set`] refuses anything outside
//! it. The refusal names the range, because a message that says only "invalid" leaves the
//! person guessing at a limit they cannot see.
//!
//! # The two text forms
//!
//! [`catalog_text`] is for the screen: one line per setting, tab-separated, with a version
//! head. [`Values::to_text`] is for storage: `key=value`, one per line, so a person can read
//! the file and fix it by hand.
//!
//! The heads and separators are load-bearing: a title may contain a space and a summary may
//! contain a colon, so neither is a separator here. Tab and newline are, which is why
//! [`Kind::Text`] refuses both — a name with a newline in it would end its own line in the
//! config file and start a new, unparseable one.
//!
//! # Why these strings are Chinese and the errors are not
//!
//! The titles and summaries are the screen itself; the app has one language and this is it,
//! so translating them into English here would only move the Chinese copy into the UI layer
//! and give it two places to drift between. The error messages read like the rest of the
//! stack — English diagnostics — because they describe a contract, not a control: a caller
//! that wants Chinese for the person holding the phone has the key and the range in the
//! [`Error`] and can write it.

#![deny(missing_docs)]

use std::fmt;

/// What a value's number means, so the screen can put a unit after it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Unit {
    /// A bare count.
    Plain,
    /// Thousandths of a second.
    Milliseconds,
    /// Eight-bit bytes.
    Bytes,
}

impl Unit {
    /// The unit as it appears in the catalog line, and as a suffix on screen.
    pub const fn name(self) -> &'static str {
        match self {
            Unit::Plain => "plain",
            Unit::Milliseconds => "ms",
            Unit::Bytes => "bytes",
        }
    }
}

/// The shape of a value: what the screen should offer, and what is allowed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// On or off.
    Switch,
    /// A whole number within `min..=max`.
    Number {
        /// Smallest meaningful value.
        min: i64,
        /// Largest meaningful value.
        max: i64,
        /// What the number counts.
        unit: Unit,
    },
    /// One of a fixed set, spelled exactly as one of the options.
    Choice(&'static [&'static str]),
    /// Free text of at most `max_len` characters, with no tab, newline or carriage return.
    Text {
        /// Longest accepted value, in characters.
        max_len: usize,
    },
}

impl Kind {
    /// The kind as it appears in the catalog line.
    ///
    /// `switch`, `number:<min>:<max>:<unit>`, `choice:<a>|<b>`, or `text:<max>`. No option
    /// or unit may contain any of those separators, which is checked by a test rather than
    /// assumed.
    pub fn spec(self) -> String {
        match self {
            Kind::Switch => "switch".to_string(),
            Kind::Number { min, max, unit } => {
                format!("number:{min}:{max}:{}", unit.name())
            }
            Kind::Choice(options) => format!("choice:{}", options.join("|")),
            Kind::Text { max_len } => format!("text:{max_len}"),
        }
    }
}

/// One row of the table: a key, the shape of its value, its default, and its wording.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Setting {
    /// Dotted name, `group.name`, unique across the table.
    pub key: &'static str,
    /// What is allowed.
    pub kind: Kind,
    /// Value before anyone changes it, spelled as it would be stored.
    pub default: &'static str,
    /// One line on screen.
    pub title: &'static str,
    /// One sentence under the title, saying what changing it does.
    pub summary: &'static str,
}

/// The table.
///
/// Every entry is a number or a choice that was hardcoded somewhere in the Java stack, with
/// the same default it had there unless the value was load-bearing for a peer's behaviour.
pub const CATALOG: &[Setting] = &[
    Setting {
        key: "device.name",
        kind: Kind::Text { max_len: 200 },
        default: "",
        title: "AirDrop 名称",
        summary: "对端在列表里看到的名字。留空则使用手机型号。",
    },
    Setting {
        key: "receive.directory",
        kind: Kind::Text { max_len: 512 },
        default: "",
        title: "接收文件保存位置",
        summary: "留空按文件类型分目录保存；填写后全部放进该目录。",
    },
    Setting {
        key: "receive.byte_limit",
        kind: Kind::Number {
            min: 64 * 1024 * 1024,
            max: 1024 * 1024 * 1024 * 1024,
            unit: Unit::Bytes,
        },
        default: "8589934592",
        title: "单次接收上限",
        summary: "一次投送最多写入多少字节，超出后拒绝该成员并继续读完这一次投送。",
    },
    Setting {
        key: "receive.probe_bytes",
        kind: Kind::Number {
            min: 0,
            max: 512 * 1024 * 1024,
            unit: Unit::Bytes,
        },
        default: "50331648",
        title: "上传预览上限",
        summary: "边收边判断内容类型时最多缓冲多少字节。调大更准，但占内存。",
    },
    Setting {
        key: "receive.overwrite",
        kind: Kind::Switch,
        default: "false",
        title: "同名时覆盖",
        summary: "接收目录里已有同名文件时直接覆盖，而不是加编号另存。",
    },
    Setting {
        key: "receive.follow_oshare",
        kind: Kind::Switch,
        default: "true",
        title: "跟随一加互传接收范围",
        summary: "互传开着「所有人」时跟着接收；关掉此项改由本页按钮控制。",
    },
    Setting {
        key: "discover.window_ms",
        kind: Kind::Number {
            min: 300,
            max: 30_000,
            unit: Unit::Milliseconds,
        },
        default: "2500",
        title: "搜索时长",
        summary: "打开分享面板后等对端出现的时间。太短会漏掉慢的设备。",
    },
    Setting {
        key: "server.read_timeout_ms",
        kind: Kind::Number {
            min: 1_000,
            max: 600_000,
            unit: Unit::Milliseconds,
        },
        default: "60000",
        title: "接收超时",
        summary: "连接上多久没有任何数据就断开。大文件传输时不宜过短。",
    },
    Setting {
        key: "server.handshake_timeout_ms",
        kind: Kind::Number {
            min: 1_000,
            max: 120_000,
            unit: Unit::Milliseconds,
        },
        default: "12000",
        title: "握手超时",
        summary: "从连接建立起，等对端发出第一行请求的最长时间。",
    },
    Setting {
        key: "client.ask_timeout_ms",
        kind: Kind::Number {
            min: 1_000,
            max: 120_000,
            unit: Unit::Milliseconds,
        },
        default: "15000",
        title: "等待对方同意",
        summary: "发送前等对端回答 /Ask 的时间。对方在锁屏时可能较慢。",
    },
    Setting {
        key: "client.discover_timeout_ms",
        kind: Kind::Number {
            min: 1_000,
            max: 120_000,
            unit: Unit::Milliseconds,
        },
        default: "15000",
        title: "等待搜索应答",
        summary: "发送前等 /Discover 应答的时间。",
    },
    Setting {
        key: "http.response_limit",
        kind: Kind::Number {
            min: 4 * 1024,
            max: 16 * 1024 * 1024,
            unit: Unit::Bytes,
        },
        default: "1048576",
        title: "应答体上限",
        summary: "单个应答最多读取多少字节，防止对端用一段很长的正文撑满内存。",
    },
    Setting {
        key: "discovery.remembered_peers",
        kind: Kind::Number {
            min: 1,
            max: 64,
            unit: Unit::Plain,
        },
        default: "8",
        title: "记住的设备数",
        summary: "为了重连而保留的最近对端数量。",
    },
    Setting {
        key: "log.level",
        kind: Kind::Choice(&["off", "error", "info", "debug"]),
        default: "info",
        title: "日志级别",
        summary: "写进诊断日志的详细程度。debug 会记录报文本身。",
    },
    Setting {
        key: "log.preview_bytes",
        kind: Kind::Number {
            min: 0,
            max: 4 * 1024,
            unit: Unit::Bytes,
        },
        default: "96",
        title: "日志里的报文长度",
        summary: "记录报文时截取多少字节，够辨认即可，太长会拖慢传输。",
    },
];

/// The row named `key`, if the table has one.
pub fn find(key: &str) -> Option<&'static Setting> {
    CATALOG.iter().find(|setting| setting.key == key)
}

/// Why a value was refused, or a stored form could not be read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// No such row. A typo is a bug, not a value that should be kept for later.
    UnknownKey {
        /// What was asked for.
        key: String,
    },
    /// A number was expected and something else arrived.
    NotANumber {
        /// The row.
        key: &'static str,
        /// What arrived.
        value: String,
    },
    /// A number outside the range the row allows.
    OutOfRange {
        /// The row.
        key: &'static str,
        /// What arrived.
        value: i64,
        /// Smallest allowed.
        min: i64,
        /// Largest allowed.
        max: i64,
    },
    /// A choice that is not one of the row's options.
    NotAnOption {
        /// The row.
        key: &'static str,
        /// What arrived.
        value: String,
        /// What was allowed.
        options: &'static [&'static str],
    },
    /// A switch given something other than `true` or `false`.
    NotASwitch {
        /// The row.
        key: &'static str,
        /// What arrived.
        value: String,
    },
    /// Text longer than the row allows.
    TooLong {
        /// The row.
        key: &'static str,
        /// Longest allowed, in characters.
        max_len: usize,
    },
    /// Text containing a character the storage form cannot carry.
    Unstorable {
        /// The row.
        key: &'static str,
        /// The character, as a name a person can act on.
        character: char,
    },
    /// A line of the stored form with no separator in it.
    Malformed {
        /// Line number, counting from one.
        line: usize,
    },
}

impl fmt::Display for Error {
    fn fmt(&self, out: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::UnknownKey { key } => write!(out, "unknown setting {key}"),
            Error::NotANumber { key, value } => {
                write!(out, "{key} wants a whole number, but {value:?} is not one")
            }
            Error::OutOfRange {
                key,
                value,
                min,
                max,
            } => write!(out, "{key} is {value}, but must be between {min} and {max}"),
            Error::NotAnOption {
                key,
                value,
                options,
            } => write!(out, "{key} is {value:?}, but must be one of {}", options.join(", ")),
            Error::NotASwitch { key, value } => {
                write!(out, "{key} is {value:?}, but must be true or false")
            }
            Error::TooLong { key, max_len } => {
                write!(out, "{key} is longer than {max_len} characters")
            }
            Error::Unstorable { key, character } => write!(
                out,
                "{key} contains {character:?}, which cannot be stored in a line"
            ),
            Error::Malformed { line } => {
                write!(out, "line {line} has no key=value pair in it")
            }
        }
    }
}

impl std::error::Error for Error {}

/// A complete set of values, one per row, in table order.
///
/// Held as strings rather than as typed fields because that is what the config file, the
/// UI and the clipboard all speak; [`Values::number`] and [`Values::switch`] are the way
/// back to a number, and they cannot fail for a value that came through [`Values::set`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Values {
    entries: Vec<(&'static Setting, String)>,
}

impl Default for Values {
    fn default() -> Self {
        Values {
            entries: CATALOG
                .iter()
                .map(|setting| (setting, setting.default.to_string()))
                .collect(),
        }
    }
}

impl Values {
    /// Every setting at its default.
    pub fn new() -> Values {
        Values::default()
    }

    /// The stored form of `key`, if it is in the table.
    pub fn get(&self, key: &str) -> Option<&str> {
        self.entries
            .iter()
            .find(|(setting, _)| setting.key == key)
            .map(|(_, value)| value.as_str())
    }

    /// The number stored under `key`.
    ///
    /// `None` only for a key that is not in the table or a row that is not a number: a
    /// number that came through [`Values::set`] parses, because it was parsed to be checked.
    pub fn number(&self, key: &str) -> Option<i64> {
        self.get(key).and_then(|value| value.parse().ok())
    }

    /// The switch stored under `key`.
    pub fn switch(&self, key: &str) -> Option<bool> {
        match self.get(key) {
            Some("true") => Some(true),
            Some("false") => Some(false),
            _ => None,
        }
    }

    /// Replaces the value of `key`, refusing anything the row does not allow.
    pub fn set(&mut self, key: &str, value: &str) -> Result<(), Error> {
        let setting = find(key).ok_or_else(|| Error::UnknownKey {
            key: key.to_string(),
        })?;
        check(setting, value)?;
        let slot = self
            .entries
            .iter_mut()
            .find(|(entry, _)| entry.key == setting.key)
            .expect("the table and the values are built from the same rows");
        slot.1 = value.to_string();
        Ok(())
    }

    /// Puts `key` back to its default.
    pub fn reset(&mut self, key: &str) {
        if let Some((setting, value)) = self.entries.iter_mut().find(|(row, _)| row.key == key) {
            *value = setting.default.to_string();
        }
    }

    /// Puts every row back to its default.
    pub fn reset_all(&mut self) {
        *self = Values::new();
    }

    /// True when `key` still holds what the table declared.
    pub fn is_default(&self, key: &str) -> bool {
        match (find(key), self.get(key)) {
            (Some(setting), Some(value)) => value == setting.default,
            _ => false,
        }
    }

    /// The stored form: `key=value` per line, in table order, with a version head.
    pub fn to_text(&self) -> String {
        let mut text = String::with_capacity(512);
        text.push_str(HEAD);
        text.push('\n');
        for (setting, value) in &self.entries {
            text.push_str(setting.key);
            text.push('=');
            text.push_str(value);
            text.push('\n');
        }
        text
    }

    /// Reads the stored form back.
    ///
    /// Blank lines and lines starting with `#` are skipped, so the head and a person's own
    /// notes survive a round trip. Every other line must be `key=value` with a key from the
    /// table; a key that is not there is refused rather than ignored, because a file that
    /// still loads after a rename is a file that silently keeps a value nobody reads.
    pub fn from_text(text: &str) -> Result<Values, Error> {
        let mut values = Values::new();
        for (index, line) in text.lines().enumerate() {
            let line = line.trim_end_matches('\r');
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let Some((key, value)) = line.split_once('=') else {
                return Err(Error::Malformed { line: index + 1 });
            };
            values.set(key, value)?;
        }
        Ok(values)
    }
}

/// The head of both text forms, so a file from an older layout is not read as this one.
pub const HEAD: &str = "#opairdrop-settings:1";

/// The catalog as the settings screen reads it.
///
/// One head line, then one line per setting: `key`, the [`Kind::spec`], the default, the
/// title, and the summary, tab-separated. The screen draws what it is given, so a row added
/// to [`CATALOG`] appears there without a second edit — and a row removed cannot linger.
pub fn catalog_text() -> String {
    let mut text = String::with_capacity(2048);
    text.push_str(HEAD);
    text.push('\n');
    for setting in CATALOG {
        text.push_str(setting.key);
        text.push('\t');
        text.push_str(&setting.kind.spec());
        text.push('\t');
        text.push_str(setting.default);
        text.push('\t');
        text.push_str(setting.title);
        text.push('\t');
        text.push_str(setting.summary);
        text.push('\n');
    }
    text
}

/// Refuses `value` unless `setting` allows it.
fn check(setting: &'static Setting, value: &str) -> Result<(), Error> {
    match setting.kind {
        Kind::Switch => match value {
            "true" | "false" => Ok(()),
            _ => Err(Error::NotASwitch {
                key: setting.key,
                value: value.to_string(),
            }),
        },
        Kind::Number { min, max, .. } => {
            let number: i64 = value.parse().map_err(|_| Error::NotANumber {
                key: setting.key,
                value: value.to_string(),
            })?;
            if number < min || number > max {
                return Err(Error::OutOfRange {
                    key: setting.key,
                    value: number,
                    min,
                    max,
                });
            }
            Ok(())
        }
        Kind::Choice(options) => {
            if options.contains(&value) {
                Ok(())
            } else {
                Err(Error::NotAnOption {
                    key: setting.key,
                    value: value.to_string(),
                    options,
                })
            }
        }
        Kind::Text { max_len } => {
            for character in value.chars() {
                if matches!(character, '\t' | '\n' | '\r') {
                    return Err(Error::Unstorable {
                        key: setting.key,
                        character,
                    });
                }
            }
            if value.chars().count() > max_len {
                return Err(Error::TooLong {
                    key: setting.key,
                    max_len,
                });
            }
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_key_is_unique_and_namespaced() {
        let mut seen = Vec::new();
        for setting in CATALOG {
            assert!(
                !seen.contains(&setting.key),
                "{} is declared twice",
                setting.key
            );
            seen.push(setting.key);
            assert!(
                setting.key.contains('.'),
                "{} is not grouped under a prefix",
                setting.key
            );
        }
    }

    #[test]
    fn every_default_passes_its_own_row() {
        let mut values = Values::new();
        for setting in CATALOG {
            assert_eq!(values.get(setting.key), Some(setting.default));
            values
                .set(setting.key, setting.default)
                .unwrap_or_else(|error| panic!("{}'s default is refused: {error}", setting.key));
        }
    }

    #[test]
    fn a_number_below_its_floor_is_refused_with_the_range_in_the_message() {
        let mut values = Values::new();
        let error = values.set("server.read_timeout_ms", "900").unwrap_err();
        assert_eq!(
            error,
            Error::OutOfRange {
                key: "server.read_timeout_ms",
                value: 900,
                min: 1000,
                max: 600_000,
            }
        );
        let message = error.to_string();
        assert!(message.contains("1000"), "{message}");
        assert!(message.contains("600000"), "{message}");
        // the refusal left the old value in place
        assert_eq!(values.number("server.read_timeout_ms"), Some(60_000));
    }

    #[test]
    fn a_number_that_is_not_a_number_says_so() {
        let mut values = Values::new();
        let error = values.set("discover.window_ms", "2.5s").unwrap_err();
        assert_eq!(
            error,
            Error::NotANumber {
                key: "discover.window_ms",
                value: "2.5s".to_string(),
            }
        );
    }

    #[test]
    fn a_choice_outside_the_list_names_the_list() {
        let mut values = Values::new();
        let error = values.set("log.level", "verbose").unwrap_err();
        assert_eq!(
            error,
            Error::NotAnOption {
                key: "log.level",
                value: "verbose".to_string(),
                options: &["off", "error", "info", "debug"],
            }
        );
        assert!(error.to_string().contains("off, error, info, debug"));
    }

    #[test]
    fn a_switch_takes_only_the_two_spellings_the_screen_writes() {
        let mut values = Values::new();
        assert_eq!(values.switch("receive.overwrite"), Some(false));
        values.set("receive.overwrite", "true").unwrap();
        assert_eq!(values.switch("receive.overwrite"), Some(true));
        for bad in ["on", "1", "yes", "True"] {
            assert_eq!(
                values.set("receive.overwrite", bad).unwrap_err(),
                Error::NotASwitch {
                    key: "receive.overwrite",
                    value: bad.to_string(),
                }
            );
        }
        assert_eq!(values.switch("receive.overwrite"), Some(true));
    }

    #[test]
    fn text_longer_than_its_row_allows_is_refused() {
        let mut values = Values::new();
        let long = "名".repeat(201);
        assert_eq!(
            values.set("device.name", &long).unwrap_err(),
            Error::TooLong {
                key: "device.name",
                max_len: 200,
            }
        );
        // characters, not bytes: 200 two-byte characters are still 200
        assert!(values.set("device.name", &"名".repeat(200)).is_ok());
    }

    #[test]
    fn text_that_would_break_the_storage_line_is_refused() {
        let mut values = Values::new();
        for bad in ["a\tb", "a\nb", "a\rb"] {
            let error = values.set("receive.directory", bad).unwrap_err();
            assert!(matches!(error, Error::Unstorable { .. }), "{bad:?} gave {error:?}");
        }
        assert!(values.set("receive.directory", "Download/Air").is_ok());
    }

    #[test]
    fn an_unknown_key_is_refused_rather_than_kept_for_later() {
        let mut values = Values::new();
        let error = values.set("server.timeout_ms", "5000").unwrap_err();
        assert_eq!(
            error,
            Error::UnknownKey {
                key: "server.timeout_ms".to_string(),
            }
        );
    }

    #[test]
    fn values_survive_a_round_trip_through_the_storage_form() {
        let mut values = Values::new();
        values.set("device.name", "书桌旁的手机").unwrap();
        values.set("receive.byte_limit", "17179869184").unwrap();
        values.set("log.level", "debug").unwrap();
        values.set("receive.directory", "Download/收件箱").unwrap();

        let text = values.to_text();
        let read_back = Values::from_text(&text).expect("what was written can be read");
        assert_eq!(read_back, values);
        assert_eq!(read_back.get("receive.directory"), Some("Download/收件箱"));
        assert_eq!(read_back.number("receive.byte_limit"), Some(17_179_869_184));
        assert!(text.starts_with(HEAD));
    }

    #[test]
    fn the_storage_form_skips_comments_and_blank_lines() {
        let text = format!("{HEAD}\n\n# 手写的说明\nlog.level=debug\n\n");
        let values = Values::from_text(&text).expect("comments are not content");
        assert_eq!(values.get("log.level"), Some("debug"));
        assert_eq!(values.number("discover.window_ms"), Some(2500));
    }

    #[test]
    fn a_storage_line_without_a_separator_names_its_line_number() {
        let text = format!("{HEAD}\nlog.level=debug\nnonsense\n");
        assert_eq!(
            Values::from_text(&text).unwrap_err(),
            Error::Malformed { line: 3 }
        );
    }

    #[test]
    fn a_stored_value_outside_its_range_is_refused_on_load() {
        let text = format!("{HEAD}\nreceive.byte_limit=1\n");
        assert_eq!(
            Values::from_text(&text).unwrap_err(),
            Error::OutOfRange {
                key: "receive.byte_limit",
                value: 1,
                min: 64 * 1024 * 1024,
                max: 1024 * 1024 * 1024 * 1024,
            }
        );
    }

    #[test]
    fn the_catalog_has_one_line_per_setting_and_a_version_head() {
        let text = catalog_text();
        let mut lines = text.lines();
        assert_eq!(lines.next(), Some(HEAD));
        let rows: Vec<&str> = lines.collect();
        assert_eq!(rows.len(), CATALOG.len());
        for (row, setting) in rows.iter().zip(CATALOG) {
            let columns: Vec<&str> = row.split('\t').collect();
            assert_eq!(columns.len(), 5, "{row:?} is not five columns");
            assert_eq!(columns[0], setting.key);
            assert_eq!(columns[1], setting.kind.spec());
            assert_eq!(columns[2], setting.default);
        }
    }

    #[test]
    fn no_wording_or_option_can_break_a_catalog_line() {
        for setting in CATALOG {
            for field in [setting.title, setting.summary, setting.key] {
                assert!(
                    !field.contains('\t') && !field.contains('\n'),
                    "{} carries a separator: {field:?}",
                    setting.key
                );
            }
            if let Kind::Choice(options) = setting.kind {
                for option in options {
                    assert!(
                        !option.contains('|') && !option.contains(':'),
                        "{}'s option {option:?} carries a separator",
                        setting.key
                    );
                }
            }
        }
    }

    #[test]
    fn a_kind_says_what_the_screen_needs_to_draw_it() {
        assert_eq!(Kind::Switch.spec(), "switch");
        assert_eq!(
            Kind::Number {
                min: 1,
                max: 64,
                unit: Unit::Plain
            }
            .spec(),
            "number:1:64:plain"
        );
        assert_eq!(
            Kind::Number {
                min: 300,
                max: 30_000,
                unit: Unit::Milliseconds
            }
            .spec(),
            "number:300:30000:ms"
        );
        assert_eq!(Kind::Choice(&["a", "b"]).spec(), "choice:a|b");
        assert_eq!(Kind::Text { max_len: 200 }.spec(), "text:200");
    }

    #[test]
    fn resetting_one_row_leaves_the_others_alone() {
        let mut values = Values::new();
        values.set("log.level", "debug").unwrap();
        values.set("device.name", "x").unwrap();
        values.reset("log.level");
        assert!(values.is_default("log.level"));
        assert!(!values.is_default("device.name"));
        assert_eq!(values.get("device.name"), Some("x"));
        values.reset_all();
        assert!(values.is_default("device.name"));
    }
}
