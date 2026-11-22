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
/// Every entry is a number or a choice that is read somewhere in the Java or Rust stack, and
/// its default is what that reader used before the row existed. A row that no reader consults
/// is a knob the screen promises and nothing honours, so rows are added when the reading code
/// lands and removed when it does not exist — see [`Values::retired`] for what removal does to
/// a file that already has the old row in it.
pub const CATALOG: &[Setting] = &[
    Setting {
        key: "device.name",
        kind: Kind::Text { max_len: 200 },
        default: "",
        title: "AirDrop 名称",
        // 留空落到「系统设备名」——设置里那个设备名称，蓝牙广播的也是它。以前落到机型上，
        // 于是对端看到的是一台叫「PJZ110」的机器，而本人手机上这台明明叫「书桌旁的手机」。
        summary: "对端在列表里看到的名字。留空则使用系统设备名（蓝牙名称）。",
    },
    Setting {
        key: "device.model",
        kind: Kind::Text { max_len: 200 },
        default: "",
        title: "AirDrop 机型",
        summary: "对端在探测应答里读到的机型。留空则使用系统报告的型号。",
    },
    Setting {
        key: "receive.media_capabilities",
        kind: Kind::Text { max_len: 4096 },
        default: "",
        title: "接收能力声明",
        summary:
            "探测应答里告诉对端「我能原样存下什么」。留空用内置值（声明 HEVC 与 MP4 容器）。\
             这一行会覆盖内置值：只要填了内容，对端看到的就是这里的内容，而不是内置的那份——\
             填入一份不含 public.mpeg-4 的声明（例如照抄真机 iPhone 的原文），对端仍会转码。",
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
        key: "receive.decision_timeout_ms",
        kind: Kind::Number {
            min: 5_000,
            max: 300_000,
            unit: Unit::Milliseconds,
        },
        default: "60000",
        title: "等人应答时长",
        summary: "邀请弹出后多久没人应答就当作拒绝。卡片和通知用同一个数。",
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
        key: "client.connect_timeout_ms",
        kind: Kind::Number {
            min: 1_000,
            max: 60_000,
            unit: Unit::Milliseconds,
        },
        default: "15000",
        title: "连接超时",
        summary: "点了一个设备之后，最多等多久让它接受连接。",
    },
    Setting {
        key: "client.read_timeout_ms",
        kind: Kind::Number {
            min: 5_000,
            max: 600_000,
            unit: Unit::Milliseconds,
        },
        default: "15000",
        title: "请求超时",
        summary: "发出一条请求之后，多久收不到任何回应就放弃。问一句 HTTP 本就是毫秒级的，等对方的人做决定是下面那一行。",
    },
    Setting {
        key: "client.upload_read_timeout_ms",
        kind: Kind::Number {
            min: 5_000,
            max: 600_000,
            unit: Unit::Milliseconds,
        },
        default: "90000",
        title: "等待接受超时",
        summary: "档案已经在发，等的是对面手机上那个人点「接受」。他犹豫多久都合法，所以它比上面那行长得多。",
    },
    // 这里原来有一行 `client.streams`（并行连接数，1–6，默认 1）。2026-11-20 删掉：
    // 两条流合计吞吐与单流相同（3.02–3.25 vs 2.97–3.14，Plan.md §3.3.1），而代价是 iPhone
    // 上多弹一次确认框、晚点第二框就白丢三分之一时间窗。一个不提速、只在对面多问一句的
    // 旋钮，留在设置页里就是给人一个坏选择。多流那条路本身没删（`SendActivity.sendLanes`
    // 仍是 N 条流的形状），复测时把这里加回来即可；磁盘上旧值会被 `retired()` 读过去并在
    // 下一次保存时清掉。
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
        title: "记住的旧实例名",
        summary: "换过名字之后还保留几个发布过的旧实例名，让仍记着它们的设备能找到本机。",
    },
    Setting {
        key: "log.level",
        kind: Kind::Choice(&["off", "error", "info", "debug"]),
        // 默认 debug（2026-11-18 由 info 改来）：这一版还在追流体云与对端发现的时序，出问题时
        // 手上第一份东西就是日志，默认 info 会让最要紧的那几行过程记录根本不落盘。代价是文件大
        // 一些——日志按天分文件，且 log.level 就摆在设置页最下面，嫌吵的人自己调回 info 即可。
        // 设置页那颗【还原】回到的就是这一档。
        default: "debug",
        title: "日志级别",
        // 原文是「debug 会记录报文本身」，摘掉 log.preview_bytes 时一并改掉：报文收发全在
        // crates/http 与 crates/airdrop 里，两边都不往这份日志里写一个字，写了日志的只有
        // Java 侧的 Diagnostics。承诺「记报文」兑现不了，人翻不到只会以为日志坏了。
        summary: "写进诊断日志的详细程度。debug 连过程中的细节一起记。",
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
#[derive(Debug, Clone)]
pub struct Values {
    entries: Vec<(&'static Setting, String)>,
    /// Keys met in a file that the table no longer has. Not part of the values: a set that
    /// compares unequal to itself for having read a retired row would make every equality in
    /// this crate mean something other than "same settings".
    retired: Vec<String>,
}

impl PartialEq for Values {
    fn eq(&self, other: &Values) -> bool {
        self.entries == other.entries
    }
}

impl Eq for Values {}

impl Default for Values {
    fn default() -> Self {
        Values {
            entries: CATALOG
                .iter()
                .map(|setting| (setting, setting.default.to_string()))
                .collect(),
            retired: Vec::new(),
        }
    }
}

impl Values {
    /// Every setting at its default.
    pub fn new() -> Values {
        Values::default()
    }

    /// Keys this value set was read past because the table no longer lists them.
    ///
    /// Empty for anything built by [`Values::new`], and empty again after a save, because
    /// [`Values::to_text`] writes the table and nothing else. Non-empty means the file on disk
    /// is older than the table — not an error, and not something to keep.
    pub fn retired(&self) -> &[String] {
        &self.retired
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
    /// notes survive a round trip. Every other line must be `key=value`; a line whose key is
    /// not in the table is **skipped and named** in [`Values::retired`] rather than refused.
    ///
    /// Refusing it was the first rule here and it was wrong, for a reason that only shows up
    /// when a row is deleted: the same bytes that feed this function also feed the send path,
    /// where an unreadable table is a send that fails with a settings error. Deleting a row —
    /// the right end for a knob no reader consults — would then break every install that had
    /// the old file until somebody happened to open the settings screen and save. Skipping
    /// keeps the file readable and still leaves the stale key visible: it is dropped on the
    /// next [`Values::to_text`], so it lives one round trip, not forever.
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
            match find(key) {
                Some(_) => values.set(key, value)?,
                None => values.retired.push(key.to_string()),
            }
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
        let error = values.set("client.read_timeout_ms", "2.5s").unwrap_err();
        assert_eq!(
            error,
            Error::NotANumber {
                key: "client.read_timeout_ms",
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
    fn the_model_row_is_blank_by_default_and_blank_means_use_the_system() {
        // Blank is not "no model", it is the instruction to fall back to whatever the
        // operating system reports — which is why the default is empty and why an empty value
        // has to be legal rather than something the table refuses.
        let mut values = Values::new();
        assert_eq!(values.get("device.model"), Some(""));
        values.set("device.model", "iPhone 18").unwrap();
        assert_eq!(values.get("device.model"), Some("iPhone 18"));
        // The space is the interesting character: it is what makes the SRV target differ from
        // the name, so the table must not be the layer that refuses it.
        assert!(values.set("device.model", "iPhone 18 Pro").is_ok());
        // A tab still breaks the storage line, here as everywhere.
        assert!(matches!(
            values.set("device.model", "iPhone\t18").unwrap_err(),
            Error::Unstorable { .. }
        ));
        // And the refusal left the previous value alone.
        assert_eq!(values.get("device.model"), Some("iPhone 18 Pro"));
    }

    #[test]
    fn an_unknown_key_is_refused_rather_than_kept_for_later() {
        // About `set`, which is what the screen calls when a row is edited: a key nobody has a
        // row for has no default to fall back to and no range to check, so writing one is a bug
        // at the call site. Reading a file is the other direction and is tolerant — see below.
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
    fn a_key_that_left_the_table_is_skipped_and_named_rather_than_refusing_the_file() {
        // The file on a device that has been installed since before a row was deleted still
        // carries that row. Refusing the whole file would be a send that fails with a settings
        // error until somebody opened the screen and saved; dropping the line keeps the install
        // working and still reports what it dropped.
        let text = format!("{HEAD}\ndiscover.window_ms=2500\ndevice.name=青\n");
        let values = Values::from_text(&text).expect("one retired row is not a broken file");
        assert_eq!(values.get("device.name"), Some("青"));
        assert_eq!(values.retired(), ["discover.window_ms".to_string()]);

        // And the next write drops it: the file catches up with the table on its own.
        assert!(!values.to_text().contains("discover.window_ms"));

        // A key that is still in the table is still checked, retired neighbours or not.
        let bad = format!("{HEAD}\ndiscover.window_ms=2500\nreceive.byte_limit=1\n");
        assert!(matches!(
            Values::from_text(&bad).unwrap_err(),
            Error::OutOfRange { .. }
        ));
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
        assert_eq!(values.number("discovery.remembered_peers"), Some(8));
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
