//! The settings surface: the table the screen is drawn from, and the file it is stored in.
//!
//! # Why the file is read here and not by the caller
//!
//! The stored form is the table's own text — `key=value` per line, in table order. If the
//! app wrote that file itself it would need to know the order, the separator, which lines
//! are comments, and what to do with a key it has never heard of; that knowledge would then
//! exist twice, and the copy in Java would be the one that goes stale. Reading and writing
//! here means the only description of the file is [`opairdrop_settings::Values::to_text`].
//!
//! # Why saving validates before it writes
//!
//! A settings file that cannot be read is worse than one that was never changed: the app
//! starts, gets a parse failure, and has to decide between defaults (losing everything the
//! person set) and refusing to start. So a save that would not load again is refused with
//! the reason, and the file on disk keeps its previous contents. The write itself goes to a
//! sibling and is renamed into place, for the same reason a received member is: the moment
//! between "truncated" and "written" must not be observable.
//!
//! # Why a missing file is not an error
//!
//! The first launch has no file and no reason to have one; answering with the table's
//! defaults makes "never configured" and "configured to the defaults" the same state, which
//! is the state a person is in until they change something.
//!
//! # What does not cross
//!
//! No struct, no callback, no value set. The caller gets the catalog as text and a loaded
//! set as text, and hands back text to save. The control flow is: draw the catalog, read
//! the loaded text, put the two side by side, write the result.

use std::fs;
use std::path::{Path, PathBuf};

use opairdrop_airdrop::inbox::Limits;
use opairdrop_settings::Values;

use crate::api::status;
use crate::buffer::{fill, OpairdropBuffer};

/// Key of the byte budget this boundary hands to the inbox.
const BYTE_LIMIT: &str = "receive.byte_limit";

/// Key of the switch that decides whether a member may replace a file.
const OVERWRITE: &str = "receive.overwrite";

/// Key of the name this device answers a peer with.
const DEVICE_NAME: &str = "device.name";

/// Key of the model this device answers a peer with.
const DEVICE_MODEL: &str = "device.model";

/// Key of the largest answer body a send will read.
const RESPONSE_LIMIT: &str = "http.response_limit";

/// Key of the capability blob the `/Discover` answer carries.
const MEDIA_CAPABILITIES: &str = "receive.media_capabilities";

/// Suffix of the sibling a save is written to before it is renamed into place.
const PARTIAL: &str = ".part";

/// Reports the catalog: one line per setting, as [`opairdrop_settings::catalog_text`] gives
/// it.
///
/// The screen draws what this returns. There is no second export listing the keys, and no
/// version of the table compiled into the app — a row added to the catalog appears on the
/// screen without a second edit, and a row removed cannot linger there.
///
/// # Safety
///
/// `out` must point at a zeroed [`OpairdropBuffer`], which must be released with
/// [`crate::buffer::opairdrop_buffer_free`] once read.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_settings_catalog(out: *mut OpairdropBuffer) -> i32 {
    if out.is_null() {
        return status::NULL_ARGUMENT;
    }
    let text = opairdrop_settings::catalog_text();
    // SAFETY: `out` is non-null and zeroed, per the contract.
    unsafe { fill(out, text.into_bytes()) };
    status::OK
}

/// Reads the settings stored at `path`, or the table's defaults when there is no file.
///
/// `out` receives the stored form on success, or the failure's message as UTF-8 on
/// failure. A file that exists but does not parse is [`status::SETTINGS`] and is not
/// silently replaced by defaults: the caller can show which line is wrong and let the
/// person fix it, which it cannot do if the library has already thrown the file away.
///
/// # Safety
///
/// `path` must point at `path_len` bytes of UTF-8 readable for the duration of the call;
/// `out` must point at a zeroed [`OpairdropBuffer`] that outlives it.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_settings_load(
    path: *const u8,
    path_len: usize,
    out: *mut OpairdropBuffer,
) -> i32 {
    if out.is_null() || path.is_null() {
        return status::NULL_ARGUMENT;
    }
    // SAFETY: the caller guarantees the region is readable for the length given.
    let path = unsafe { std::slice::from_raw_parts(path, path_len) };
    let Ok(path) = std::str::from_utf8(path).map(Path::new) else {
        // SAFETY: `out` is non-null and zeroed, per the contract.
        unsafe { fill(out, b"the settings path is not valid UTF-8".to_vec()) };
        return status::BAD_PATH;
    };
    if path.as_os_str().is_empty() {
        // SAFETY: as above.
        unsafe { fill(out, b"the settings path is empty".to_vec()) };
        return status::BAD_PATH;
    }

    let text = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            // SAFETY: as above.
            unsafe { fill(out, Values::new().to_text().into_bytes()) };
            return status::OK;
        }
        Err(error) => {
            let message = format!("the settings could not be read: {error}");
            // SAFETY: as above.
            unsafe { fill(out, message.into_bytes()) };
            return status::IO;
        }
    };

    match parse(&text) {
        Ok(values) => {
            // SAFETY: as above.
            unsafe { fill(out, values.to_text().into_bytes()) };
            status::OK
        }
        Err(message) => {
            // SAFETY: as above.
            unsafe { fill(out, message.into_bytes()) };
            status::SETTINGS
        }
    }
}

/// Validates `text` and, if it holds, writes it to `path`.
///
/// `out` receives the stored form as it was written — table order, comments dropped — so a
/// caller that keeps a copy in memory is holding what the next launch will read. On failure
/// it holds the message and the file is untouched.
///
/// # Safety
///
/// `path` must point at `path_len` bytes of UTF-8, `text` at `text_len` bytes of UTF-8, both
/// readable for the duration; `out` must point at a zeroed [`OpairdropBuffer`] that outlives
/// the call.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_settings_save(
    path: *const u8,
    path_len: usize,
    text: *const u8,
    text_len: usize,
    out: *mut OpairdropBuffer,
) -> i32 {
    if out.is_null() || path.is_null() || text.is_null() {
        return status::NULL_ARGUMENT;
    }
    // SAFETY: the caller guarantees both regions are readable for their lengths.
    let path = unsafe { std::slice::from_raw_parts(path, path_len) };
    // SAFETY: as above.
    let text = unsafe { std::slice::from_raw_parts(text, text_len) };
    let Ok(path) = std::str::from_utf8(path).map(Path::new) else {
        // SAFETY: `out` is non-null and zeroed, per the contract.
        unsafe { fill(out, b"the settings path is not valid UTF-8".to_vec()) };
        return status::BAD_PATH;
    };
    if path.as_os_str().is_empty() {
        // SAFETY: as above.
        unsafe { fill(out, b"the settings path is empty".to_vec()) };
        return status::BAD_PATH;
    }

    let values = match parse(text) {
        Ok(values) => values,
        Err(message) => {
            // SAFETY: as above.
            unsafe { fill(out, message.into_bytes()) };
            return status::SETTINGS;
        }
    };
    let stored = values.to_text();

    if let Err(error) = write(path, &stored) {
        let message = format!("the settings could not be written: {error}");
        // SAFETY: as above.
        unsafe { fill(out, message.into_bytes()) };
        return status::IO;
    }

    // SAFETY: as above.
    unsafe { fill(out, stored.into_bytes()) };
    status::OK
}

/// Reads the stored form, or explains why it cannot be read.
///
/// Empty text is not a parse failure: it is what a caller has before anything is set, and
/// the answer is every default.
pub(crate) fn values_from(text: &[u8]) -> Result<Values, String> {
    parse(text)
}

/// The limits a receive call runs under, taken from `values`.
///
/// A key that is not in the table cannot happen — both sides are the same table — and a
/// number outside its range cannot either, because [`Values::set`] refused it. The
/// fallbacks are therefore the table's own defaults, reached only if a future edit removes a
/// row without removing this function.
pub(crate) fn limits(values: &Values) -> Limits {
    Limits {
        byte_limit: values.number(BYTE_LIMIT).unwrap_or(0).max(0) as u64,
        // 成员数暂不进设置表（评审 P1-9）：它是一道防滥用上限，不是用户偏好。
        member_limit: opairdrop_airdrop::inbox::DEFAULT_MEMBER_LIMIT,
        overwrite: values.switch(OVERWRITE).unwrap_or(false),
    }
}

/// The largest answer body a send may read: `http.response_limit`.
///
/// The row is a bound on what a peer can make the sender hold while it waits for a `/Discover`
/// or `/Ask` answer, and it is set by the person holding the phone rather than by the protocol.
/// Reading it here rather than in the sender keeps the number's range in one place: a value
/// outside the row's range cannot be stored ([`Values::set`] refuses it), so the fallback is
/// only reached if a future edit removes the row without removing this function.
///
/// A stored zero would be a sender that can read no answer at all, which is not a setting
/// anybody means; it falls back like a missing row does.
pub(crate) fn response_limit(values: &Values) -> usize {
    match values.number(RESPONSE_LIMIT) {
        Some(bytes) if bytes > 0 => bytes as usize,
        _ => opairdrop_airdrop::client::DEFAULT_RESPONSE_LIMIT,
    }
}

/// The capability blob a `/Discover` answer carries: `receive.media_capabilities`, or
/// [`opairdrop_airdrop::MEDIA_CAPABILITIES`] when the row is blank.
///
/// The row exists because of what the blob *does*, not because anybody wants to tune it.
/// DISC-3 says a receiver that leaves out `Codecs` and `ContainerFormats` gets media in
/// widely supported formats, and for video that means every sender re-encodes 4K HEVC to
/// H.264 before offering it — which is both why a large clip took minutes to prompt and why
/// a 93 MB video came back as 26.5 MB. The blob now names `hvc1`, and an iPhone stops
/// transcoding.
///
/// The catch is that DISC-3 only ever specifies `Version`; the shape of `Codecs` and
/// `ContainerFormats` is folklore, reconstructed from one macOS capture. A guess that is
/// wrong shows up as a device that stops prompting at all — the failure this file's own
/// notes record for an earlier change to the answer. Waiting for a rebuild to undo that is
/// not acceptable, so the value is editable from the settings screen: paste
/// `{"Version": 1}` and the old behaviour is back on the next connection.
///
/// A blank row and an unset row are the same thing here, exactly as in [`model`]: the table
/// spells "not set" as empty, and a person who clears the field wants the default rather
/// than an answer with no capabilities at all.
pub(crate) fn media_capabilities(values: &Values) -> Vec<u8> {
    match trimmed(values, MEDIA_CAPABILITIES) {
        Some(entered) => entered.as_bytes().to_vec(),
        None => opairdrop_airdrop::MEDIA_CAPABILITIES.as_bytes().to_vec(),
    }
}

/// The name to answer a peer with: `device.name`, then the system's own name for the phone,
/// then [`model`].
///
/// `system_name` is what the system calls this device — the string in Settings → About → Device
/// name, which is also what Bluetooth advertises. It comes from Java (`Settings.Global.DEVICE_NAME`
/// first, then the adapter's name) because it is not derivable here: nothing in the build knows
/// what its owner renamed the phone to.
///
/// It used to fall straight through to the model, and that made a phone called "书桌旁的手机"
/// announce itself as "PJZ110" to every peer — the `/Discover` answer is the only name an iPhone
/// ever draws, so the model was the wrong answer in the one place it was most visible.
///
/// The last step still goes through [`model`] rather than to `system_model` directly, so a person
/// who has set a model but not a name is called by the model they chose. Two answers to one
/// question — the row, and whatever this function decided — is the drift this file exists to
/// prevent. A blank `system_name` (a caller built before this argument existed, or a system that
/// reports nothing) is treated as "not set" and falls through to the same place it always did.
pub(crate) fn name(values: &Values, system_name: &str, system_model: &str) -> String {
    match trimmed(values, DEVICE_NAME) {
        Some(entered) => entered.to_string(),
        None => match system_name.trim() {
            "" => model(values, system_model),
            named => named.to_string(),
        },
    }
}

/// The model to answer a peer with: `device.model`, or the one the system reports when blank.
///
/// `system` is the caller's `Build.MODEL` — the only thing in the process that knows what the
/// phone is — and the row being blank means "use it", exactly as blank in `device.name` means
/// "use the model". A row nobody has touched changes nothing a peer sees.
///
/// This used to be an argument only, with the reason written down: a row for the model would
/// invite a person to rename their phone. That is precisely what it is for. The field is
/// display-only on both sides of the protocol — nothing parses it, nothing branches on it, no
/// sender decides anything from it — and a device that wants to say `iPhone 18` has no other
/// way to say it. What the row cannot change is what the `/Discover` answer claims the
/// device can store: that is `receive.media_capabilities`, whose default is
/// [`opairdrop_airdrop::MEDIA_CAPABILITIES`] — see [`media_capabilities`] for why it is now
/// a row with a default rather than a constant with no way back.
pub(crate) fn model(values: &Values, system: &str) -> String {
    match trimmed(values, DEVICE_MODEL) {
        Some(entered) => entered.to_string(),
        None => system.to_string(),
    }
}

/// The value of `key` when it is present and not blank after trimming.
///
/// Blank is how the table spells "not set" for both device rows, so the emptiness has to be
/// decided in one place: a name of three spaces is a name a person cannot see on the peer's
/// screen, which is a worse outcome than falling back.
fn trimmed<'a>(values: &'a Values, key: &str) -> Option<&'a str> {
    match values.get(key).map(str::trim) {
        Some(entered) if !entered.is_empty() => Some(entered),
        _ => None,
    }
}

/// Validates the stored form, prefixing a parse failure with where it came from.
fn parse(text: &[u8]) -> Result<Values, String> {
    let Ok(text) = std::str::from_utf8(text) else {
        return Err("the settings are not valid UTF-8".to_string());
    };
    // `from_text` starts from the defaults, so a file that omits a key keeps that key's
    // default rather than failing — which is what a person editing by hand expects.
    Values::from_text(text).map_err(|error| format!("the settings could not be read: {error}"))
}

/// Writes `text` to `path` through a sibling, so a partial file is never the one on disk.
fn write(path: &Path, text: &str) -> std::io::Result<()> {
    let mut temp = PathBuf::from(path);
    let name = path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default();
    temp.set_file_name(format!(".{name}{PARTIAL}"));
    fs::write(&temp, text)?;
    let renamed = fs::rename(&temp, path);
    if renamed.is_err() {
        // The sibling is left behind otherwise, and the next save would overwrite it
        // anyway; removing it keeps the directory holding exactly the files it should.
        let _ = fs::remove_file(&temp);
    }
    renamed
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A directory of this test's own, removed when the test ends.
    fn scratch(name: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("opairdrop-settings-{name}"));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).expect("the scratch directory is creatable");
        root
    }

    #[test]
    fn a_missing_file_answers_with_every_default() {
        let root = scratch("missing");
        let mut out = OpairdropBuffer::EMPTY;
        let path = root.join("settings.conf");
        let path = path.to_str().expect("the fixture path is UTF-8");

        // SAFETY: the buffer is a live local and the path outlives the call.
        let code = unsafe {
            opairdrop_settings_load(path.as_ptr(), path.len(), &mut out)
        };

        assert_eq!(code, status::OK);
        // SAFETY: the call that just returned filled the buffer.
        let text = String::from_utf8_lossy(unsafe { out.as_slice() }).into_owned();
        // SAFETY: the buffer came from the call above.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
        assert!(text.starts_with(opairdrop_settings::HEAD), "{text}");
        assert_eq!(text, Values::new().to_text());
    }

    #[test]
    fn a_saved_set_reads_back_identical_and_leaves_no_sibling() {
        let root = scratch("round-trip");
        let path = root.join("settings.conf");
        let path = path.to_str().expect("the fixture path is UTF-8");
        let mut edited = Values::new();
        edited.set(BYTE_LIMIT, "134217728").expect("in range");
        edited.set(OVERWRITE, "true").expect("a switch");
        let stored = edited.to_text();

        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: the buffer is a live local and both strings outlive the call.
        let code = unsafe {
            opairdrop_settings_save(path.as_ptr(), path.len(), stored.as_ptr(), stored.len(), &mut out)
        };
        assert_eq!(code, status::OK);
        // SAFETY: the buffer came from the call above.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };

        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: as above.
        let code = unsafe { opairdrop_settings_load(path.as_ptr(), path.len(), &mut out) };
        assert_eq!(code, status::OK);
        // SAFETY: the call that just returned filled the buffer.
        let text = String::from_utf8_lossy(unsafe { out.as_slice() }).into_owned();
        // SAFETY: the buffer came from the call above.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };

        assert_eq!(text, edited.to_text());
        assert_eq!(
            fs::read_dir(&root).expect("the directory is readable").count(),
            1,
            "the temporary was renamed, not left behind"
        );
        assert_eq!(text, stored);
    }

    #[test]
    fn a_save_that_would_not_load_again_is_refused_and_changes_nothing() {
        let root = scratch("refused");
        let path = root.join("settings.conf");
        let path = path.to_str().expect("the fixture path is UTF-8");
        let good = Values::new().to_text();
        fs::write(path, &good).expect("the fixture is writable");

        let bad = format!("{}\n{OVERWRITE}=maybe\n", opairdrop_settings::HEAD);
        let mut out = OpairdropBuffer::EMPTY;
        // SAFETY: the buffer is a live local and both strings outlive the call.
        let code = unsafe {
            opairdrop_settings_save(
                path.as_ptr(),
                path.len(),
                bad.as_ptr(),
                bad.len(),
                &mut out,
            )
        };

        assert_eq!(code, status::SETTINGS);
        // SAFETY: the call that just returned filled the buffer.
        let message = String::from_utf8_lossy(unsafe { out.as_slice() }).into_owned();
        // SAFETY: the buffer came from the call above.
        unsafe { crate::buffer::opairdrop_buffer_free(&mut out) };
        assert!(message.contains(OVERWRITE), "{message}");
        assert_eq!(
            fs::read_to_string(path).expect("the file is still there"),
            good,
            "the previous contents survived"
        );
    }

    #[test]
    fn limits_come_from_the_two_keys_that_describe_them() {
        let mut values = Values::new();
        assert_eq!(
            limits(&values),
            Limits {
                byte_limit: 8 * 1024 * 1024 * 1024,
                member_limit: opairdrop_airdrop::inbox::DEFAULT_MEMBER_LIMIT,
                overwrite: false,
            }
        );

        values.set(BYTE_LIMIT, "134217728").expect("in range");
        values.set(OVERWRITE, "true").expect("a switch");
        assert_eq!(
            limits(&values),
            Limits {
                byte_limit: 134_217_728,
                member_limit: opairdrop_airdrop::inbox::DEFAULT_MEMBER_LIMIT,
                overwrite: true,
            }
        );
    }

    #[test]
    fn the_response_limit_is_the_row_and_nothing_else() {
        let mut values = Values::new();
        // An installation nobody has touched answers with the sender's own constant, so the
        // row existing changes nothing until somebody changes it.
        assert_eq!(
            response_limit(&values),
            opairdrop_airdrop::client::DEFAULT_RESPONSE_LIMIT
        );

        values.set(RESPONSE_LIMIT, "65536").expect("in range");
        assert_eq!(response_limit(&values), 65_536);

        // The row's range ends at sixteen mebibytes and that is the largest answer this can
        // be asked to hold: nothing here inflates it.
        values.set(RESPONSE_LIMIT, "16777216").expect("the top of the range");
        assert_eq!(response_limit(&values), 16 * 1024 * 1024);
    }

    #[test]
    fn empty_text_is_the_defaults_rather_than_a_parse_failure() {
        assert_eq!(values_from(b"").expect("empty is allowed"), Values::new());
    }

    #[test]
    fn text_that_is_not_utf8_is_refused_with_an_explanation() {
        let message = values_from(&[0xff, 0xfe]).expect_err("not UTF-8");
        assert!(message.contains("UTF-8"), "{message}");
    }

    #[test]
    fn a_blank_name_falls_back_to_the_system_name_then_through_the_model_row() {
        let mut values = Values::new();
        // Nothing set and no system name: the caller's own model is the answer, which is what a
        // caller built before this argument existed still gets.
        assert_eq!(name(&values, "", "pjz110"), "pjz110");
        assert_eq!(model(&values, "pjz110"), "pjz110");

        // A system name is the whole point of the argument: an untouched installation is called
        // what the phone is called everywhere else, not what the hardware is called.
        assert_eq!(name(&values, "书桌旁的手机", "pjz110"), "书桌旁的手机");

        // Model set, name not, no system name: both answers become the model, because a peer
        // that draws the name and the model in the same panel should not see two devices.
        values.set(DEVICE_MODEL, "iPhone 18").expect("a text row");
        assert_eq!(model(&values, "pjz110"), "iPhone 18");
        assert_eq!(name(&values, "", "pjz110"), "iPhone 18");
        // With a system name the row still loses to it: the phone's own name outranks the
        // hardware, and the model row only ever answers the model question.
        assert_eq!(name(&values, "书桌旁的手机", "pjz110"), "书桌旁的手机");

        // Name set: it wins the name answer, and the model answer keeps its own row.
        values.set(DEVICE_NAME, "书桌旁的手机").expect("a text row");
        assert_eq!(name(&values, "另一个名字", "pjz110"), "书桌旁的手机");
        assert_eq!(model(&values, "pjz110"), "iPhone 18");
    }

    #[test]
    fn whitespace_only_counts_as_not_set_and_surrounding_space_is_trimmed() {
        let mut values = Values::new();
        // Spaces are legal in a text row and invisible on the peer's screen, so a row holding
        // nothing but spaces has to fall back like an empty one rather than answer with them.
        values.set(DEVICE_NAME, "   ").expect("spaces are text");
        values.set(DEVICE_MODEL, "  ").expect("spaces are text");
        assert_eq!(name(&values, "", "pjz110"), "pjz110");
        assert_eq!(model(&values, "pjz110"), "pjz110");

        values.set(DEVICE_NAME, "  书房  ").expect("a text row");
        assert_eq!(name(&values, "", "pjz110"), "书房");

        // A system name arrives from another process, so it can be blank or padded too; both
        // are "not set" rather than a device called " ".
        values.set(DEVICE_NAME, "").expect("clearing is allowed");
        assert_eq!(name(&values, "   ", "pjz110"), "pjz110");
        assert_eq!(name(&values, "  书房  ", "pjz110"), "书房");
    }
}
