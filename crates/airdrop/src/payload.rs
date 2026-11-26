//! The four plist bodies of the protocol, both directions.
//!
//! Every builder here inserts keys in the order the capture that worked sent them, and
//! every test asserts that order. See the crate docs for why.
//!
//! | body | direction | keys |
//! | --- | --- | --- |
//! | [`discover_request`] | sender → receiver | `SenderComputerName`, `SenderModelName`, `SenderID` |
//! | [`DiscoverAnswer::encode`] | receiver → sender | `ReceiverComputerName`, `ReceiverMediaCapabilities`, `IsAirDropable` |
//! | [`ask_request`] | sender → receiver | nine keys, see below |
//! | [`AskAnswer::encode`] | receiver → sender | `ReceiverComputerName`, `ReceiverModelName` |

use opairdrop_bplist::Value;

use crate::{Error, MEDIA_CAPABILITIES};

/// Who is calling: the three fields that appear in both `/Discover` and `/Ask`.
///
/// One type for both because the receiver's only use for a `/Discover` body is to learn
/// these three, and the sender fills them from one place. Java passed the same three
/// `String`s through both call sites separately, which is how one of them ends up with a
/// different value from the other.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Sender {
    /// `SenderComputerName` — the name the receiver shows on its prompt.
    pub computer_name: String,
    /// `SenderModelName` — e.g. `OPPO PJZ110`, or `iPhone` for a phone that reports one.
    pub model_name: String,
    /// `SenderID` — the sender's own identity string, the same one on every transfer.
    ///
    /// Not to be confused with the transfer id: a receiver groups a sender's activity by
    /// this string, so a value that changes per transfer makes the same device arrive as a
    /// stranger each time. It is derived from something durable (see `Identity::senderId`),
    /// which is why it outlives the transfer that carried it.
    pub id: String,
}

impl Sender {
    pub fn new(
        computer_name: impl Into<String>,
        model_name: impl Into<String>,
        id: impl Into<String>,
    ) -> Sender {
        Sender {
            computer_name: computer_name.into(),
            model_name: model_name.into(),
            id: id.into(),
        }
    }

    /// Reads the three fields out of a `/Discover` body.
    ///
    /// A missing or wrongly typed field becomes an empty string rather than an error:
    /// the answer does not depend on any of them, and refusing a request whose body is
    /// merely incomplete would break discovery for a peer that is otherwise fine.
    pub fn parse(body: &[u8]) -> Result<Sender, Error> {
        let root = opairdrop_bplist::parse_dict(body)?;
        Ok(Sender::from_value(&root))
    }

    fn from_value(root: &Value) -> Sender {
        Sender {
            computer_name: field(root, "SenderComputerName"),
            model_name: field(root, "SenderModelName"),
            id: field(root, "SenderID"),
        }
    }

    fn pairs(&self) -> Vec<(&'static str, Value)> {
        vec![
            (
                "SenderComputerName",
                Value::String(self.computer_name.clone()),
            ),
            ("SenderModelName", Value::String(self.model_name.clone())),
            ("SenderID", Value::String(self.id.clone())),
        ]
    }
}

/// Builds a `/Discover` request body: exactly the three fields a sender fills in.
///
/// §6.3 calls an empty dictionary a valid request, and it is — but it is also a request
/// some receivers ignore. The one this was measured against answers a three-field body
/// in the same millisecond and closes the connection with a TLS alert in under a second
/// when the body is the 42-byte empty dictionary. So the three fields go in even though
/// the certificate already carries all of them: a name is what the answer has to be
/// addressed to.
///
/// Nothing else goes in. `DeviceSupportFlags` is the obvious candidate — a Mac sends
/// one, and `111611` is the value a current iPhone reports about itself — but adding it
/// here made the receiver's answer *shorter* (1142 bytes over two keys instead of 1437
/// over three) while the `/Ask` behind it stayed silent. So it is not the missing piece,
/// and a sender that claims Mac-sized capabilities changes what the receiver expects to
/// be able to do. The body here is the one that was on the wire the first time this
/// phone ever showed a transfer prompt.
pub fn discover_request(sender: &Sender) -> Vec<u8> {
    opairdrop_bplist::encode(&Value::dict(sender.pairs()))
}

/// A parsed `/Discover` answer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DiscoverAnswer {
    /// `ReceiverComputerName`.
    pub computer_name: String,
    /// `ReceiverMediaCapabilities`, the raw JSON blob — `Some` when the peer sent one.
    pub media_capabilities: Option<Vec<u8>>,
    /// `IsAirDropable`: what the peer said about taking a transfer, or `None` when it said
    /// nothing at all.
    ///
    /// Three-valued rather than a `bool`, because the three cases are not two:
    ///
    /// * `Some(true)` — the peer will take one.
    /// * `None` — the peer answered without saying. A current iPhone does this, and the
    ///   reference sender reads it as yes (`flag(caps, "IsAirDropable", true)`) and posts
    ///   `/Ask` regardless. Reading it as no is the difference between a prompt on the
    ///   other phone and no request at all.
    /// * `Some(false)` — the peer said no. That is a statement about this moment, not a
    ///   verdict: the sender goes on to `/Ask`, which is the request that makes the other
    ///   phone show a prompt, and the answer to *that* is the one a person gives.
    pub airdropable: Option<bool>,
}

impl DiscoverAnswer {
    /// The answer this receiver sends: our name, our capability blob, and `IsAirDropable`.
    pub fn new(computer_name: impl Into<String>) -> DiscoverAnswer {
        DiscoverAnswer {
            computer_name: computer_name.into(),
            media_capabilities: Some(MEDIA_CAPABILITIES.as_bytes().to_vec()),
            airdropable: Some(true),
        }
    }

    /// Builds the answer with an explicit capability blob, for a receiver that can
    /// decode more (or less) than the legacy formats.
    pub fn with_capabilities(
        computer_name: impl Into<String>,
        media_capabilities: impl Into<Vec<u8>>,
    ) -> DiscoverAnswer {
        DiscoverAnswer {
            computer_name: computer_name.into(),
            media_capabilities: Some(media_capabilities.into()),
            airdropable: Some(true),
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        opairdrop_bplist::encode(&self.to_value())
    }

    fn to_value(&self) -> Value {
        let mut pairs = vec![(
            "ReceiverComputerName",
            Value::String(self.computer_name.clone()),
        )];
        if let Some(capabilities) = &self.media_capabilities {
            pairs.push((
                "ReceiverMediaCapabilities",
                Value::Data(capabilities.clone()),
            ));
        }
        // Our own answer always says yes — this device is answering because it is
        // accepting — so the silence is not a case this side produces. `unwrap_or(true)`
        // rather than `true` keeps the encoder honest about the type.
        pairs.push((
            "IsAirDropable",
            Value::Bool(self.airdropable.unwrap_or(true)),
        ));
        Value::dict(pairs)
    }

    /// Parses an answer, including the case where `IsAirDropable` is absent.
    ///
    /// `ReceiverModelName` is deliberately *not* read: a current iPhone's own answer
    /// carries no model at all (`model=null`), and a sender that wants one reads it off
    /// the certificate. An earlier version of this answer sent
    /// `ReceiverModelName` in the third slot on the reasoning that it described the
    /// device more usefully. It does, and it broke the whole flow —
    ///
    /// > the sender looks for `IsAirDropable` to decide whether the peer can take a
    /// > transfer at all, finds no boolean, and concludes it cannot. The visible symptom
    /// > was a device that appeared in the share sheet, opened a TLS connection, asked
    /// > `/Discover`, received a `200`, and then vanished without ever posting `/Ask`.
    ///
    /// The absent boolean is now reported as absent and nothing else: whether the peer
    /// takes a transfer is decided by `/Ask`, and a sender that reads a missing key as "no"
    /// reproduces exactly the symptom quoted above one layer down.
    pub fn parse(body: &[u8]) -> Result<DiscoverAnswer, Error> {
        let root = opairdrop_bplist::parse_dict(body)?;
        Ok(DiscoverAnswer {
            computer_name: field(&root, "ReceiverComputerName"),
            media_capabilities: root
                .get_data("ReceiverMediaCapabilities")
                .map(<[u8]>::to_vec),
            airdropable: root.get("IsAirDropable").and_then(Value::as_bool),
        })
    }
}

/// The answer to `/Ask`: who is receiving.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AskAnswer {
    pub computer_name: String,
    pub model_name: String,
}

impl AskAnswer {
    pub fn new(computer_name: impl Into<String>, model_name: impl Into<String>) -> AskAnswer {
        AskAnswer {
            computer_name: computer_name.into(),
            model_name: model_name.into(),
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        opairdrop_bplist::encode(&Value::dict([
            (
                "ReceiverComputerName",
                Value::String(self.computer_name.clone()),
            ),
            ("ReceiverModelName", Value::String(self.model_name.clone())),
        ]))
    }

    pub fn parse(body: &[u8]) -> Result<AskAnswer, Error> {
        let root = opairdrop_bplist::parse_dict(body)?;
        Ok(AskAnswer {
            computer_name: field(&root, "ReceiverComputerName"),
            model_name: field(&root, "ReceiverModelName"),
        })
    }
}

/// One entry of the `Files` array in an `/Ask` request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Item {
    /// `FileName` — what the receiver shows and, usually, what it writes.
    pub name: String,
    /// `FileType` — an Apple UTI, e.g. `public.jpeg`.
    pub file_type: String,
    /// `FileBomPath` — the sender's idea of the path inside the archive.
    pub bom_path: String,
    /// `FileIsDirectory` — the entry is a folder, not a file.
    pub directory: bool,
    /// `FileSize` when the sender declared one. `None` means "unknown", not "empty".
    pub size: Option<u64>,
}

/// The UTI a sender uses for "some content", and our default when none is declared.
pub const DEFAULT_FILE_TYPE: &str = "public.content";

impl Item {
    /// A regular file of unknown size, landing at `./<name>` inside the archive.
    ///
    /// `FileBomPath` defaults to `./` plus the name rather than to the empty string. §6.4
    /// marks it required, says the `./` prefix is required, and is specific about what it
    /// is for: it is the path the receiver announces *and* the name it will look for in
    /// the archive. An offer whose path is empty describes a file with nowhere to land —
    /// the sender here used to emit exactly that, because nothing ever set the field, and
    /// the phone on the other end answered the offer by simply holding the request open.
    pub fn new(name: impl Into<String>) -> Item {
        let name = name.into();
        Item {
            bom_path: format!("./{name}"),
            name,
            file_type: DEFAULT_FILE_TYPE.to_string(),
            directory: false,
            size: None,
        }
    }

    pub fn with_file_type(mut self, file_type: impl Into<String>) -> Item {
        self.file_type = file_type.into();
        self
    }

    pub fn with_bom_path(mut self, bom_path: impl Into<String>) -> Item {
        self.bom_path = bom_path.into();
        self
    }

    pub fn with_size(mut self, size: u64) -> Item {
        self.size = Some(size);
        self
    }

    pub fn as_directory(mut self) -> Item {
        self.directory = true;
        self
    }

    /// The exact `FileBomPath` this item announces in `/Ask`, `./` included.
    ///
    /// One expression feeds both [`Item::to_value`] and the archive writer, and that is the
    /// point of it being a method: UP-7 lets a receiver drop a member whose name is not a
    /// path the offer announced, so the offered string and the member name are not allowed
    /// to differ — not even by the `./` the archive could do without. Written in one place,
    /// that agreement cannot be lost; written in two, it was lost here, and the archive
    /// carried `photo.jpg` while the offer had said `./photo.jpg`.
    pub fn bom_path(&self) -> String {
        // The path is required and must start at `./`; an entry built by parsing an offer
        // that left it out would otherwise be echoed back with the same hole in it.
        if self.bom_path.is_empty() {
            format!("./{}", self.name)
        } else {
            self.bom_path.clone()
        }
    }

    /// The archive member name this item should be written as, or `None` when neither
    /// its declared path nor its name survives sanitising.
    ///
    /// `FileBomPath` wins because it is the sender's own archive layout — it may carry a
    /// folder prefix the receiver should keep. Each candidate is run through
    /// [`opairdrop_cpio::safe_name`], so a path that would escape the destination
    /// directory cannot become a member name here.
    ///
    /// This is the *receiving* side's question — "where may these bytes land?" — and the
    /// leading `./` goes away with the rest of the sanitising. A sender must not ask it:
    /// see [`Item::bom_path`].
    pub fn member_name(&self) -> Option<String> {
        let candidate = if self.bom_path.is_empty() {
            &self.name
        } else {
            &self.bom_path
        };
        opairdrop_cpio::safe_name(candidate).or_else(|| opairdrop_cpio::safe_name(&self.name))
    }

    fn to_value(&self) -> Value {
        let bom_path = self.bom_path();
        let mut pairs = vec![
            ("FileName", Value::String(self.name.clone())),
            ("FileType", Value::String(self.file_type.clone())),
            ("FileBomPath", Value::String(bom_path)),
            ("FileIsDirectory", Value::Bool(self.directory)),
            ("ShouldConvertMediaFormats", Value::Bool(false)),
        ];
        // Pinned after `ShouldConvertMediaFormats`, which is where the capture had it.
        if let Some(size) = self.size {
            pairs.push(("FileSize", Value::Integer(size as i64)));
        }
        Value::dict(pairs)
    }

    fn from_value(entry: &Value) -> Item {
        Item {
            name: field(entry, "FileName"),
            file_type: {
                let declared = field(entry, "FileType");
                if declared.is_empty() {
                    DEFAULT_FILE_TYPE.to_string()
                } else {
                    declared
                }
            },
            bom_path: field(entry, "FileBomPath"),
            directory: entry.get_bool("FileIsDirectory", false),
            size: match entry.get_i64("FileSize", -1) {
                size if size >= 0 => Some(size as u64),
                _ => None,
            },
        }
    }
}

/// A parsed `/Ask` request: what a sender is offering.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Ask {
    pub sender: Sender,
    /// `BundleID` — `com.apple.finder` from a Mac, `com.apple.DocumentsApp` from iOS.
    pub bundle_id: String,
    /// `ConvertMediaFormats` at the top level. Every per-file entry says
    /// `ShouldConvertMediaFormats` instead; see [`ask_request`].
    pub convert_media_formats: bool,
    /// `TransferID` — a nested dictionary whose only key is `id`. `None` when the offer
    /// came in the legacy shape, which is not an error: a receiver that demanded one
    /// would reject every sender older than the current AirDrop.
    ///
    /// The value is the sender's promise that the `/Upload` which follows will repeat it
    /// in its own `TransferID` header, so the receiver has nothing to answer with here —
    /// it is kept to be compared against that header later, on the same connection.
    pub transfer_id: Option<String>,
    pub files: Vec<Item>,
    /// `Items` — links only. Apple's field name says nothing about what it holds.
    pub links: Vec<String>,
    /// Whether the request carried `FileIcon` bytes. The icon itself is not kept: it is
    /// a multi-kilobyte PNG whose only use is a notification we draw ourselves.
    pub has_icon: bool,
}

impl Ask {
    /// Parses an `/Ask` body.
    ///
    /// Entries of the wrong type are skipped rather than refused. A sender that puts a
    /// string where an array of dictionaries belongs is malformed, but the rest of its
    /// offer is still usable, and a `400` here would drop a transfer the user is waiting
    /// for over one junk entry.
    pub fn parse(body: &[u8]) -> Result<Ask, Error> {
        let root = opairdrop_bplist::parse_dict(body)?;
        let files = root
            .get_array("Files")
            .iter()
            .filter(|entry| entry.as_dict().is_some())
            .map(Item::from_value)
            .collect();
        let links = root
            .get_array("Items")
            .iter()
            .filter_map(|entry| entry.as_str().map(str::to_string))
            .collect();
        // 2026-11-26：实况照片的邀请不显示总大小——先看 iPhone 的 /Ask 到底给不给
        // FileSize（.pvt 是目录，FileIsDirectory 时通常不带）。这一行进 logcat。
        for entry in root.get_array("Files") {
            let name = field(entry, "FileName");
            let size = entry.get_i64("FileSize", -1);
            let dir = entry.get_bool("FileIsDirectory", false);
            let ty = field(entry, "FileType");
            eprintln!("ask item: name='{}' size={} dir={} type='{}'", name, size, dir, ty);
        }
        Ok(Ask {
            sender: Sender::from_value(&root),
            bundle_id: field(&root, "BundleID"),
            convert_media_formats: root.get_bool("ConvertMediaFormats", false),
            // `TransferID` is `{"id": "…"}` and not a bare string; a peer that sent a bare
            // string would read as "no id" rather than as a mismatch, which is the right
            // verdict — we do not know what such a value meant.
            transfer_id: root
                .get("TransferID")
                .and_then(|entry| entry.get_str("id"))
                .map(str::to_string),
            files,
            links,
            has_icon: root.get_data("FileIcon").is_some(),
        })
    }

    /// The name to put on the receive card: the sender's computer name, falling back to
    /// its model when the peer sent no name.
    pub fn display_name(&self) -> &str {
        if self.sender.computer_name.is_empty() {
            &self.sender.model_name
        } else {
            &self.sender.computer_name
        }
    }

    /// A short description of what is being offered, for the prompt.
    pub fn summary(&self) -> String {
        if let Some(first) = self.files.first() {
            return if self.files.len() == 1 {
                first.name.clone()
            } else {
                format!("{} files", self.files.len())
            };
        }
        if let Some(first) = self.links.first() {
            return if self.links.len() == 1 {
                first.clone()
            } else {
                format!("{} links", self.links.len())
            };
        }
        String::new()
    }
}

/// Builds an `/Ask` request body in the shape §6.4 calls *current*.
///
/// The distinction is not cosmetic. §6.4 documents two shapes. The legacy one is what
/// AirDrop 1.0 sent and what most independent implementations still send; the current one
/// adds `TransferID`, `TransferType`, and a per-file `FileSize`. ASK-7 states the
/// consequence of getting it wrong: a receiver given the legacy shape
///
/// > may answer `200` without ever prompting its user, and then drop the `/Upload`.
///
/// A `200` that never asked anybody looks exactly like consent at the HTTP layer, so the
/// sender waits on a file that was never accepted — and reports a protocol failure that
/// is really a missing field.
///
/// `TransferID` is also the sender's own promise: UP-9 requires the upload's
/// `TransferID` header to equal the id offered here, so the caller passes the id in
/// rather than letting this function invent one it would then have to be told about out
/// of band.
///
/// `Files` and `Items` are always present, even when one of them is empty. A links offer
/// carries an empty `Files`, a file offer an empty `Items`, and both shapes are what the
/// receiver parses. Omitting the empty one is the difference between an offer the
/// receiver reads and an offer it rejects: an `/Ask` that named only `Files` was answered
/// by an iPhone with a bare TCP reset — no status, no body, no prompt — while the
/// `/Discover` on the same connection had just been answered `200`. A key that says
/// "there are no links" is cheaper than a reset.
///
/// Each entry says `ShouldConvertMediaFormats` and nothing else about conversion. §6.4
/// names that spelling; `ConvertMediaFormats` is the top-level field and it appears once,
/// at the top level. An earlier revision wrote both spellings into every entry as a hedge
/// against either being the real one, and that revision is the one the receiver ignored —
/// 558 bytes, acknowledged to the last byte, then 67 seconds of silence and a reset. The
/// body here is the 522-byte one from the run that produced a prompt, so the hedge is
/// gone and must not come back.
///
/// Files whose size is unknown go out without `FileSize` rather than with a zero. A
/// receiver that believed the declaration would take the transfer for an empty one.
pub fn ask_request(
    sender: &Sender,
    transfer_id: &crate::TransferId,
    files: &[Item],
    links: &[String],
) -> Vec<u8> {
    let mut pairs = sender.pairs();
    pairs.extend([
        ("BundleID", Value::String("com.apple.finder".to_string())),
        ("ConvertMediaFormats", Value::Bool(false)),
        (
            "TransferID",
            Value::dict([("id", Value::String(transfer_id.as_str().to_string()))]),
        ),
        (
            "TransferType",
            // The key names what is being offered; the value is an empty dictionary
            // because the type carries no parameters.
            Value::dict([(
                if files.is_empty() { "links" } else { "files" },
                Value::dict(std::iter::empty::<(&str, Value)>()),
            )]),
        ),
        (
            "Files",
            Value::Array(files.iter().map(Item::to_value).collect()),
        ),
        (
            "Items",
            Value::Array(links.iter().cloned().map(Value::String).collect()),
        ),
    ]);
    opairdrop_bplist::encode(&Value::dict(pairs))
}

/// A required-ish string field: absent or wrongly typed reads as empty.
fn field(root: &Value, key: &str) -> String {
    root.get_str(key).unwrap_or_default().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::TransferId;

    fn sender() -> Sender {
        Sender::new("Air", "OPPO PJZ110", "8A2B:1F:9C")
    }

    fn keys(value: &Value) -> Vec<String> {
        value
            .as_dict()
            .expect("dictionary")
            .iter()
            .map(|(key, _)| key.clone())
            .collect()
    }

    fn decode(body: &[u8]) -> Value {
        opairdrop_bplist::parse_dict(body).expect("decodes")
    }

    fn transfer_id() -> TransferId {
        TransferId::from_bytes16(&[0x11; 16])
    }

    #[test]
    fn discover_request_has_exactly_the_three_captured_fields_in_order() {
        let body = discover_request(&sender());
        let root = decode(&body);
        assert_eq!(
            keys(&root),
            ["SenderComputerName", "SenderModelName", "SenderID"]
        );
        let parsed = Sender::parse(&body).unwrap();
        assert_eq!(parsed, sender());
    }

    #[test]
    fn discover_request_round_trips_through_our_own_parser() {
        let parsed = Sender::parse(&discover_request(&sender())).unwrap();
        assert_eq!(parsed.computer_name, "Air");
        assert_eq!(parsed.model_name, "OPPO PJZ110");
        assert_eq!(parsed.id, "8A2B:1F:9C");
    }

    #[test]
    fn discover_answer_carries_is_airdropable_and_no_model_name() {
        let body = DiscoverAnswer::new("Air").encode();
        let root = decode(&body);
        assert_eq!(
            keys(&root),
            [
                "ReceiverComputerName",
                "ReceiverMediaCapabilities",
                "IsAirDropable"
            ]
        );
        assert!(
            root.get("ReceiverModelName").is_none(),
            "the model name slot is what broke this flow once"
        );

        let parsed = DiscoverAnswer::parse(&body).unwrap();
        assert_eq!(parsed.airdropable, Some(true));
        assert_eq!(parsed.computer_name, "Air");
        assert_eq!(
            parsed.media_capabilities.as_deref(),
            Some(MEDIA_CAPABILITIES.as_bytes())
        );
    }

    /// What a current iPhone sends: the name, the capabilities, and no boolean. Read as
    /// absent, not as a refusal — the reference sender reads the same plist with
    /// `flag(caps, "IsAirDropable", true)` and goes on to `/Ask`.
    #[test]
    fn an_answer_without_the_boolean_says_nothing_rather_than_no() {
        let body = opairdrop_bplist::encode(&Value::dict([
            ("ReceiverComputerName", Value::String("Mac".into())),
            ("ReceiverModelName", Value::String("MacBookPro18,3".into())),
        ]));
        let parsed = DiscoverAnswer::parse(&body).unwrap();
        assert_eq!(parsed.airdropable, None);
        assert_eq!(parsed.computer_name, "Mac");
        assert_eq!(parsed.media_capabilities, None);
    }

    #[test]
    fn an_answer_may_omit_capabilities_and_still_encode() {
        let answer = DiscoverAnswer {
            computer_name: "Air".into(),
            media_capabilities: None,
            airdropable: Some(true),
        };
        let root = decode(&answer.encode());
        assert_eq!(keys(&root), ["ReceiverComputerName", "IsAirDropable"]);
    }

    #[test]
    fn ask_answer_names_the_receiver_twice() {
        let body = AskAnswer::new("Air", "OPPO PJZ110").encode();
        assert_eq!(
            keys(&decode(&body)),
            ["ReceiverComputerName", "ReceiverModelName"]
        );
        let parsed = AskAnswer::parse(&body).unwrap();
        assert_eq!(parsed, AskAnswer::new("Air", "OPPO PJZ110"));
    }

    #[test]
    fn ask_request_has_the_nine_keys_in_the_captured_order() {
        let files = [Item::new("报告 2026.pdf").with_size(2048)];
        let body = ask_request(&sender(), &transfer_id(), &files, &[]);
        assert_eq!(
            keys(&decode(&body)),
            [
                "SenderComputerName",
                "SenderModelName",
                "SenderID",
                "BundleID",
                "ConvertMediaFormats",
                "TransferID",
                "TransferType",
                "Files",
                "Items",
            ]
        );
    }

    #[test]
    fn ask_request_always_carries_both_files_and_items() {
        let only_links = ask_request(
            &sender(),
            &transfer_id(),
            &[],
            &["https://example.com".into()],
        );
        let root = decode(&only_links);
        assert_eq!(root.get_array("Files").len(), 0);
        assert!(
            root.get("Files").is_some(),
            "an empty Files key, not a missing one"
        );
        assert_eq!(root.get_array("Items").len(), 1);

        let only_files = ask_request(&sender(), &transfer_id(), &[Item::new("a.txt")], &[]);
        let root = decode(&only_files);
        assert_eq!(root.get_array("Items").len(), 0);
        assert!(root.get("Items").is_some());
    }

    #[test]
    fn transfer_type_names_what_is_offered() {
        let files = decode(&ask_request(
            &sender(),
            &transfer_id(),
            &[Item::new("a")],
            &[],
        ));
        let transfer_type = files.get("TransferType").expect("TransferType");
        assert_eq!(keys(transfer_type), ["files"]);

        let links = decode(&ask_request(
            &sender(),
            &transfer_id(),
            &[],
            &["https://x".into()],
        ));
        let transfer_type = links.get("TransferType").expect("TransferType");
        assert_eq!(keys(transfer_type), ["links"]);
    }

    #[test]
    fn every_file_entry_says_should_convert_and_not_the_top_level_spelling() {
        let body = ask_request(
            &sender(),
            &transfer_id(),
            &[Item::new("a.heic").with_size(10)],
            &[],
        );
        let root = decode(&body);
        let entry = &root.get_array("Files")[0];
        assert_eq!(
            keys(entry),
            [
                "FileName",
                "FileType",
                "FileBomPath",
                "FileIsDirectory",
                "ShouldConvertMediaFormats",
                "FileSize",
            ]
        );
        assert!(
            entry.get("ConvertMediaFormats").is_none(),
            "the dual-spelling hedge is what got acknowledged and then ignored"
        );
    }

    #[test]
    fn a_file_of_unknown_size_goes_out_without_a_size() {
        let body = ask_request(&sender(), &transfer_id(), &[Item::new("a.bin")], &[]);
        let root = decode(&body);
        let entry = &root.get_array("Files")[0];
        assert!(entry.get("FileSize").is_none());
    }

    #[test]
    fn every_file_entry_names_a_path_inside_the_archive() {
        let files = [Item::new("IMG_0049.HEIC").with_size(141_853)];
        let body = ask_request(&sender(), &transfer_id(), &files, &[]);
        let root = decode(&body);
        let entry = &root.get_array("Files")[0];
        // §6.4: "Path inside the `/Upload` archive: `./<FileName>` … The `./` prefix is
        // required." This was `""` for every file until the offer stopped being ignored.
        assert_eq!(entry.get_str("FileBomPath"), Some("./IMG_0049.HEIC"));
    }

    #[test]
    fn an_entry_without_a_path_of_its_own_gets_one_rather_than_a_hole() {
        let mut item = Item::new("a.txt");
        item.bom_path.clear();
        let body = ask_request(&sender(), &transfer_id(), &[item], &[]);
        let entry = &decode(&body).get_array("Files")[0].clone();
        assert_eq!(entry.get_str("FileBomPath"), Some("./a.txt"));
    }

    #[test]
    fn ask_parses_what_a_sender_sends() {
        let files = [
            Item::new("photo.heic")
                .with_file_type("public.heic")
                .with_size(1234),
            Item::new("folder").as_directory(),
        ];
        let body = ask_request(
            &sender(),
            &transfer_id(),
            &files,
            &["https://example.com".into()],
        );
        let parsed = Ask::parse(&body).unwrap();
        assert_eq!(parsed.sender, sender());
        assert_eq!(parsed.bundle_id, "com.apple.finder");
        assert!(!parsed.convert_media_formats);
        assert_eq!(parsed.files.len(), 2);
        assert_eq!(parsed.files[0].size, Some(1234));
        assert_eq!(parsed.files[0].file_type, "public.heic");
        assert!(parsed.files[1].directory);
        assert_eq!(parsed.files[1].size, None);
        assert_eq!(parsed.links, ["https://example.com"]);
        assert!(!parsed.has_icon);
        assert_eq!(
            parsed.transfer_id.as_deref(),
            Some(transfer_id().as_str()),
            "the id the offer promised is what the upload header has to repeat"
        );
    }

    #[test]
    fn a_legacy_offer_without_a_transfer_id_still_parses() {
        let body = opairdrop_bplist::encode(&Value::dict([(
            "SenderComputerName",
            Value::String("Mac".into()),
        )]));
        let parsed = Ask::parse(&body).unwrap();
        assert_eq!(parsed.transfer_id, None);
        assert_eq!(parsed.display_name(), "Mac");
    }

    #[test]
    fn ask_reports_an_icon_when_one_is_attached() {
        let body = opairdrop_bplist::encode(&Value::dict([
            ("SenderComputerName", Value::String("Air".into())),
            ("FileIcon", Value::Data(vec![0x89, b'P', b'N', b'G'])),
        ]));
        let parsed = Ask::parse(&body).unwrap();
        assert!(parsed.has_icon);
    }

    #[test]
    fn ask_skips_entries_of_the_wrong_type_instead_of_refusing_the_offer() {
        let body = opairdrop_bplist::encode(&Value::dict([
            (
                "Files",
                Value::Array(vec![
                    Value::String("not a dictionary".into()),
                    Value::dict([("FileName", Value::String("real.txt".into()))]),
                ]),
            ),
            (
                "Items",
                Value::Array(vec![
                    Value::Integer(7),
                    Value::String("https://example.com".into()),
                ]),
            ),
        ]));
        let parsed = Ask::parse(&body).unwrap();
        assert_eq!(parsed.files.len(), 1);
        assert_eq!(parsed.files[0].name, "real.txt");
        assert_eq!(parsed.links, ["https://example.com"]);
    }

    #[test]
    fn a_missing_file_type_falls_back_to_public_content() {
        let body = opairdrop_bplist::encode(&Value::dict([(
            "Files",
            Value::Array(vec![Value::dict([("FileName", Value::String("a".into()))])]),
        )]));
        let parsed = Ask::parse(&body).unwrap();
        assert_eq!(parsed.files[0].file_type, DEFAULT_FILE_TYPE);
    }

    #[test]
    fn a_body_that_is_not_a_dictionary_is_refused() {
        let body = opairdrop_bplist::encode(&Value::String("nope".into()));
        assert_eq!(
            Ask::parse(&body).unwrap_err(),
            Error::Plist(opairdrop_bplist::Error::NotADictionary)
        );
        assert!(DiscoverAnswer::parse(&body).is_err());
        assert!(Sender::parse(&body).is_err());
        assert!(AskAnswer::parse(&body).is_err());
    }

    #[test]
    fn display_name_prefers_the_computer_name() {
        let mut ask = Ask {
            sender: Sender::new("Air", "OPPO PJZ110", "id"),
            ..Ask::default()
        };
        assert_eq!(ask.display_name(), "Air");
        ask.sender.computer_name.clear();
        assert_eq!(ask.display_name(), "OPPO PJZ110");
    }

    #[test]
    fn summary_counts_or_names_what_is_offered() {
        let mut ask = Ask::default();
        assert_eq!(ask.summary(), "");

        ask.files = vec![Item::new("a.pdf")];
        assert_eq!(ask.summary(), "a.pdf");

        ask.files = vec![Item::new("a.pdf"), Item::new("b.pdf")];
        assert_eq!(ask.summary(), "2 files");

        ask.files.clear();
        ask.links = vec!["https://example.com".into()];
        assert_eq!(ask.summary(), "https://example.com");

        ask.links.push("https://example.org".into());
        assert_eq!(ask.summary(), "2 links");
    }

    #[test]
    fn member_name_prefers_the_bom_path_then_falls_back() {
        let item = Item::new("photo.heic").with_bom_path("./DCIM/photo.heic");
        assert_eq!(item.member_name().as_deref(), Some("DCIM/photo.heic"));

        let item = Item::new("photo.heic");
        assert_eq!(item.member_name().as_deref(), Some("photo.heic"));

        // A bom path that safe_name refuses falls back to the name rather than escaping.
        let item = Item::new("safe.txt").with_bom_path("../../etc/passwd");
        let member = item.member_name().expect("falls back to the name");
        assert!(!member.starts_with(".."), "{member}");
        assert!(!member.contains(".."), "{member}");
    }

    #[test]
    fn an_item_whose_names_are_all_unusable_has_no_member_name() {
        assert_eq!(Item::new("..").member_name(), None);
        assert_eq!(Item::new("").member_name(), None);
    }
}
