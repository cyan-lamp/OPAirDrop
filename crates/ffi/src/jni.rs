//! The JNI shim: the same export, dressed for a JVM.
//!
//! # Why the symbols are spelled out
//!
//! A JNI export is found by name — `Java_` plus the class's binary name with `/` and `_`
//! replaced by `_`. The alternative is `JNI_OnLoad` plus `RegisterNatives`, which decouples
//! the symbol from the class name at the cost of a hand-built method table, a `FindClass`
//! that fails silently if the class was renamed, and one more thing to get right before a
//! single byte moves. The default Android ProGuard/R8 configuration already keeps native
//! method names, so the spelled-out form survives shrinking without an extra rule; the
//! only requirements on the Java side are that the package stays `com.oshare.airdrop` and the
//! class stays `Native` — the package is part of the symbol, so the two are renamed together
//! or not at all.
//!
//! # Why the archive is an `InputStream`
//!
//! The receiver's budget is 8 GiB and an Android process cannot hold a `byte[]` that size,
//! so an array-taking signature would have promised what it could not be handed. The Java
//! side already has a stream — the socket — and [`api`] already takes a callback, so this
//! layer is a callback that calls `InputStream.read(byte[], int, int)`.
//!
//! # Why the answer is bytes
//!
//! A `java.lang.String[]` of names would exhaust the JVM's local reference table at 512
//! entries, which a share of a few hundred photos reaches, and it would fail with an error
//! that names nothing. One array cannot.
//!
//! # Why `jni-sys` 0.3 and not 0.4
//!
//! 0.4 puts a proc macro over the interface table that regroups its entries into
//! per-version unions — `v1_1`, `v1_2`, `v1_4`, `v9` — so reaching something the C header
//! declares flat means naming a group and reading a union field. That is a fair way to
//! track which JVM has which entry, but this crate only calls entries from JNI 1.1 and 1.2,
//! where the question does not arise, and a union read puts a proof obligation on every
//! call site for no gain. 0.3's table is the header field for field, and its `Option` marks
//! exactly what a JVM may leave unset; `entry!` pays that once, at the call. The 0.4 types
//! it re-exports (it depends on 0.4 for `_jobject`) are the same ones this file names, so
//! nothing incompatible ends up in the binary.

#![allow(non_snake_case)]

use std::ffi::{c_void, CString};
use std::ptr;

use jni_sys::{
    jbyte, jbyteArray, jclass, jint, jlong, jobject, jobjectArray, jsize, jstring, JNIEnv,
    JNINativeInterface_,
};

use crate::api::{self, status};
use crate::ble;
use crate::buffer::{opairdrop_buffer_free, OpairdropBuffer};
use crate::serve::{serve, Decide, Watcher};
use crate::settings;

/// Bytes requested from the Java stream per crossing.
///
/// Matching `opairdrop_cpio::dvzip::CHUNK` keeps a whole DVZip block — the unit a sender
/// writes — inside one read, so nothing below depends on where a block happened to be cut.
const READ_CHUNK: usize = 64 * 1024;

/// One entry from the table.
///
/// `Option` is how the C header describes a slot that a JVM older than the entry's version
/// may leave null. Every entry this file calls dates from JNI 1.1 or 1.2, the oldest
/// versions still in use, and a JVM that reached this code has already loaded an ABI that
/// implies them — so a null here is not a condition to recover from but a sign that the
/// thing calling us is not a JVM. There is no channel left to report it through: the
/// function table is the channel.
macro_rules! entry {
    ($table:expr, $name:ident) => {
        match $table.$name {
            Some(entry) => entry,
            None => panic!(concat!(
                "the JVM's interface table has no ",
                stringify!($name),
                ", which every JVM that can load this library has"
            )),
        }
    };
}

/// Reports the version of the ABI the loaded library was built against.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_abiVersion(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jint {
    api::ABI_VERSION as jint
}

/// Receives one archive from `archive` into `directory`.
///
/// Returns a [`api::Report`] frame on success. On failure it throws and returns null, the
/// message being the failure's own.
///
/// `settings` is the stored settings text, or null for the table's defaults.
///
/// `archive` is typed `jobject` rather than `jbyteArray` because it is a
/// `java.io.InputStream`: the length is not known up front and would not fit in an array
/// even if it were.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and objects of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_receiveArchive(
    env: *mut JNIEnv,
    _class: jclass,
    directory: jstring,
    settings: jstring,
    archive: jobject,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if directory.is_null() || archive.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "directory and archive are required",
            );
        }
        return ptr::null_mut();
    }

    // SAFETY: `directory` is a live `java.lang.String`, per the JVM's call.
    let Some(directory) = (unsafe { read_string(env, directory) }) else {
        // `read_string` has already thrown; a lone surrogate cannot name a path anyway.
        return ptr::null_mut();
    };

    // A null settings string is the documented way to say "the defaults", so it is checked
    // before `read_string`, which would refuse it.
    let settings = if settings.is_null() {
        None
    } else {
        // SAFETY: `settings` is a live `java.lang.String`, per the JVM's call.
        match unsafe { read_string(env, settings) } {
            Some(text) => Some(text),
            None => return ptr::null_mut(),
        }
    };

    // SAFETY: `archive` is a live `java.io.InputStream` and `env` outlives this call.
    let Ok(mut stream) = (unsafe { Stream::new(env, archive) }) else {
        return ptr::null_mut();
    };

    let (settings_pointer, settings_len) = match &settings {
        Some(text) => (text.as_ptr(), text.len()),
        None => (ptr::null(), 0),
    };

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: the directory bytes outlive the call, the settings bytes do too or the pointer
    // is null, the callback and its context are valid for its duration, and `report` is a
    // zeroed buffer on this frame.
    let outcome = unsafe {
        api::opairdrop_receive_archive(
            directory.as_ptr(),
            directory.len(),
            settings_pointer,
            settings_len,
            read_stream,
            &mut stream as *mut Stream as *mut c_void,
            &mut report,
        )
    };

    // SAFETY: `report` is a zeroed buffer the call above filled, one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Serves one connection: requests from `input`, answers to `output`.
///
/// Returns a [`api::Report`] frame on success. On failure it throws and returns null.
///
/// `settings` is the stored settings text, or null for the table's defaults. `model` is
/// what the operating system calls this hardware, or null.
///
/// Both streams are typed `jobject` rather than a socket or a channel: this layer only
/// needs `read` and `write`, and taking the narrower type is what lets the same call serve
/// a socket, a `PipedStream` in the JVM check, or a TLS stream the caller wrapped before
/// handing it over. Closing either stream stays the caller's business, because it owns the
/// socket that outlives this call.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and objects of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_serve(
    env: *mut JNIEnv,
    _class: jclass,
    directory: jstring,
    settings_text: jstring,
    model: jstring,
    input: jobject,
    output: jobject,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if directory.is_null() || input.is_null() || output.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "directory, input and output are required",
            );
        }
        return ptr::null_mut();
    }

    // SAFETY: `directory` is a live `java.lang.String`, per the JVM's call.
    let Some(directory) = (unsafe { read_string(env, directory) }) else {
        return ptr::null_mut();
    };
    // SAFETY: as above, for each optional string. Null is the documented way to say "the
    // defaults" / "no model", and is taken before `read_string`, which would refuse it.
    let Some(settings_text) = (unsafe { read_optional_string(env, settings_text) }) else {
        return ptr::null_mut();
    };
    // SAFETY: as above.
    let Some(model) = (unsafe { read_optional_string(env, model) }) else {
        return ptr::null_mut();
    };

    // SAFETY: both are live streams and `env` outlives this call.
    // 评审 P0-3：Stream::new 失败（异常挂起）后不再发 Sink::new 的 JNI 调用。
    let reader = match unsafe { Stream::new(env, input) } {
        Ok(reader) => reader,
        Err(_) => return ptr::null_mut(),
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let sink = match unsafe { Sink::new(env, output) } {
        Ok(sink) => sink,
        Err(_) => return ptr::null_mut(),
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    };
    // Nothing on the receiving side renews: a receiver answers a connection, it does not
    // ask on one, so its connection has no `Link` to follow.
    let mut connection = Connection {
        reader,
        sink,
        link: None,
    };

    let (settings_pointer, settings_len) = match &settings_text {
        Some(text) => (text.as_ptr(), text.len()),
        None => (ptr::null(), 0),
    };
    let (model_pointer, model_len) = match &model {
        Some(text) => (text.as_ptr(), text.len()),
        None => (ptr::null(), 0),
    };

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: every byte slice outlives the call or is null, each callback is paired with
    // the context it was built for, and `report` is a zeroed buffer on this frame.
    let outcome = unsafe {
        crate::serve::opairdrop_serve(
            directory.as_ptr(),
            directory.len(),
            settings_pointer,
            settings_len,
            model_pointer,
            model_len,
            read_connection,
            write_connection,
            &mut connection as *mut Connection as *mut c_void,
            &mut report,
        )
    };

    // SAFETY: `report` is a zeroed buffer the call above filled, one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Builds the PTR query a sender multicasts to find AirDrop peers.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_mdnsQuery(
    env: *mut JNIEnv,
    _class: jclass,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: `report` is a zeroed buffer on this frame.
    let outcome = unsafe { crate::browse::opairdrop_mdns_query(&mut report) };
    // SAFETY: as above, filled one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Reads one mDNS response into `kind<TAB>…` lines.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and a live `byte[]`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_mdnsRecords(
    env: *mut JNIEnv,
    _class: jclass,
    packet: jbyteArray,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if packet.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "a packet is required",
            )
        };
        return ptr::null_mut();
    }
    // SAFETY: `packet` is a live array, per the JVM's call.
    let Some(packet) = (unsafe { read_bytes(env, packet) }) else {
        return ptr::null_mut();
    };

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: `packet` outlives the call and `report` is a zeroed buffer on this frame.
    let outcome = unsafe {
        crate::browse::opairdrop_mdns_records(packet.as_ptr(), packet.len(), &mut report)
    };
    // SAFETY: as above, filled one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Builds one announcement of `types` as a DNS response.
///
/// Returns the packet, or throws and returns null. `txt` is `key=value` per line in the
/// order the records should carry them; `addresses` is one textual IPv6 address per line.
/// `id` is zero for an announcement, and `ttl` is `-1` for each record's published lifetime
/// or `0` for the withdrawal of RFC 6762 §10.1.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and arguments of the declared types.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_mdnsAnnounce(
    env: *mut JNIEnv,
    _class: jclass,
    instance: jstring,
    host: jstring,
    port: jint,
    txt: jobjectArray,
    addresses: jobjectArray,
    id: jint,
    types: jint,
    ttl: jint,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if instance.is_null() || host.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "an instance name and a host name are required",
            )
        };
        return ptr::null_mut();
    }

    // 同型修复，见 mdnsReply 里评审 P0-3 的注释：一次一个，失败即退。
    let Some(instance) = (unsafe { read_string(env, instance) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(host) = (unsafe { read_string(env, host) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(txt) = (unsafe { read_lines(env, txt) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(addresses) = (unsafe { read_lines(env, addresses) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }

    let (txt_pointer, txt_len) = region(&txt);
    let (addresses_pointer, addresses_len) = region(&addresses);

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: every region outlives the call and `report` is a zeroed buffer on this frame.
    let outcome = unsafe {
        crate::respond::opairdrop_mdns_announce(
            instance.as_ptr(),
            instance.len(),
            host.as_ptr(),
            host.len(),
            port as u16,
            txt_pointer,
            txt_len,
            addresses_pointer,
            addresses_len,
            id as u16,
            types as u32,
            ttl,
            &mut report,
        )
    };
    // SAFETY: as above, filled one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Answers one query packet, or answers nothing when the packet asks nothing of ours.
///
/// The result carries a one-byte header: `0` for "multicast this to `ff02::fb:5353`", `1`
/// for "send it back to the address the query came from", which RFC 6762 §6.7 requires when
/// the querier asked from a port other than 5353. An empty array means there is nothing to
/// say, which is the common case and not an error.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and arguments of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_mdnsReply(
    env: *mut JNIEnv,
    _class: jclass,
    packet: jbyteArray,
    instance: jstring,
    host: jstring,
    port: jint,
    txt: jobjectArray,
    addresses: jobjectArray,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if packet.is_null() || instance.is_null() || host.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "a packet, an instance name and a host name are required",
            )
        };
        return ptr::null_mut();
    }

    // 评审 P0-3：参数必须一次解一个，任一步失败或异常挂起就立即退出。Rust 的元组
    // 表达式不短路——旧写法在 read_bytes 抛出 IllegalArgumentException 并挂起异常后
    // 仍把后面四次 JNI 调用全发了，Android CheckJNI 对「异常挂起时调 JNI」是 abort，
    // 未开 CheckJNI 也是未定义行为。5353 上一个零长度 UDP 包即可触发。
    let Some(packet) = (unsafe { read_bytes(env, packet) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(instance) = (unsafe { read_string(env, instance) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(host) = (unsafe { read_string(env, host) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(txt) = (unsafe { read_lines(env, txt) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(addresses) = (unsafe { read_lines(env, addresses) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }

    let (txt_pointer, txt_len) = region(&txt);
    let (addresses_pointer, addresses_len) = region(&addresses);

    let mut report = OpairdropBuffer::EMPTY;
    let mut unicast = 0u8;
    // SAFETY: every region outlives the call and `report` is a zeroed buffer on this frame.
    let outcome = unsafe {
        crate::respond::opairdrop_mdns_reply(
            packet.as_ptr(),
            packet.len(),
            instance.as_ptr(),
            instance.len(),
            host.as_ptr(),
            host.len(),
            port as u16,
            txt_pointer,
            txt_len,
            addresses_pointer,
            addresses_len,
            &mut report,
            &mut unicast,
        )
    };

    // SAFETY: the call filled `report`, and nothing has released it.
    let content = unsafe { report.as_slice() }.to_vec();
    // SAFETY: the contents were copied out first.
    unsafe { opairdrop_buffer_free(&mut report) };

    if outcome != status::OK {
        let message = String::from_utf8_lossy(&content).into_owned();
        // SAFETY: `env` is valid.
        if unsafe { pending(env) } {
            return ptr::null_mut();
        }
        // SAFETY: `env` is valid.
        unsafe { throw(env, class_for(outcome), &message) };
        return ptr::null_mut();
    }
    if content.is_empty() {
        // SAFETY: `env` is valid.
        return unsafe { new_bytes(env, &[]) };
    }

    let mut framed = Vec::with_capacity(content.len() + 1);
    framed.push(unicast);
    framed.extend_from_slice(&content);
    // SAFETY: `env` is valid.
    unsafe { new_bytes(env, &framed) }
}

/// Sends `paths` to the peer reachable through `input`/`output`.
///
/// Returns a two-line `key<TAB>value` frame on success — `peer` and `bytes` — the same
/// shape the settings catalog uses. On failure it throws and returns null, with
/// `IllegalArgumentException` for a path that cannot be sent and `IOException` for
/// everything the connection did, including the peer refusing.
///
/// `sender` and `model` may be null, which say "nothing to put on the other phone's
/// prompt". `transferId` may not: it has to be 32 hexadecimal digits, optionally with
/// dashes, because the receiver echoes it and a made-up value is a protocol error there
/// rather than here.
///
/// `settings` is the stored settings text, or null for "nothing configured". Only
/// `http.response_limit` is read here, and it is the whole text rather than the one number so
/// that the boundary does not have to grow a parameter per row the sender learns to obey.
///
/// The streams are typed `jobject` for the reason `serve` gives: this layer needs `read`
/// and `write`, and taking the narrower type is what lets a caller hand over a TLS session
/// it has already established. Closing them stays the caller's business.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and objects of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_send(
    env: *mut JNIEnv,
    _class: jclass,
    host: jstring,
    settings: jstring,
    sender: jstring,
    model: jstring,
    sender_id: jstring,
    transfer_id: jstring,
    paths: jobjectArray,
    input: jobject,
    output: jobject,
    link: jobject,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if host.is_null()
        || transfer_id.is_null()
        || paths.is_null()
        || input.is_null()
        || output.is_null()
    {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "host, transfer id, paths, input and output are required",
            );
        }
        return ptr::null_mut();
    }

    // SAFETY: all four are live `java.lang.String`s, per the JVM's call, and null is taken
    // before `read_string`, which would refuse it.
    let Some((host, transfer_id, settings, sender, model, sender_id)) =
        (unsafe { read_send_strings(env, host, settings, sender, model, sender_id, transfer_id) })
    else {
        return ptr::null_mut();
    };
    // SAFETY: `paths` is a live `java.lang.String[]`.
    let Some(paths) = (unsafe { read_paths(env, paths) }) else {
        return ptr::null_mut();
    };
    let Some(mut connection) = (unsafe { build_connection(env, input, output, link) }) else {
        return ptr::null_mut();
    };

    let (settings_pointer, settings_len) = slice_of(&settings);
    let (sender_pointer, sender_len) = slice_of(&sender);
    let (model_pointer, model_len) = slice_of(&model);
    let (sender_id_pointer, sender_id_len) = slice_of(&sender_id);

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: every byte slice outlives the call or is null, each callback is paired with
    // the context it was built for, and `report` is a zeroed buffer on this frame.
    let outcome = unsafe {
        crate::send::opairdrop_send(
            host.as_ptr(),
            host.len(),
            settings_pointer,
            settings_len,
            sender_pointer,
            sender_len,
            model_pointer,
            model_len,
            sender_id_pointer,
            sender_id_len,
            transfer_id.as_ptr(),
            transfer_id.len(),
            paths.as_ptr(),
            paths.len(),
            read_connection,
            write_connection,
            dial_connection,
            &mut connection as *mut Connection as *mut c_void,
            &mut report,
        )
    };

    // SAFETY: `report` is a zeroed buffer the call above filled, one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Sends `paths`, reporting how much has been taken off disk.
///
/// `progress` is a `com.oshare.airdrop.Native$Progress`: its `report(long, long)` runs on the
/// sending thread, once per half-megabyte and once at the end. It must not block — the meter
/// calls it from inside the file read, so a slow listener slows the transfer itself.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and objects of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_sendWithProgress(
    env: *mut JNIEnv,
    _class: jclass,
    host: jstring,
    settings: jstring,
    sender: jstring,
    model: jstring,
    sender_id: jstring,
    transfer_id: jstring,
    paths: jobjectArray,
    input: jobject,
    output: jobject,
    link: jobject,
    progress: jobject,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if host.is_null()
        || transfer_id.is_null()
        || paths.is_null()
        || input.is_null()
        || output.is_null()
        || progress.is_null()
    {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "host, transfer id, paths, both streams and a progress sink are required",
            );
        }
        return ptr::null_mut();
    }

    // SAFETY: all four are live `java.lang.String`s, and null is taken before `read_string`.
    let Some((host, transfer_id, settings, sender, model, sender_id)) =
        (unsafe { read_send_strings(env, host, settings, sender, model, sender_id, transfer_id) })
    else {
        return ptr::null_mut();
    };
    // SAFETY: `paths` is a live `java.lang.String[]`.
    let Some(paths) = (unsafe { read_paths(env, paths) }) else {
        return ptr::null_mut();
    };
    let Some(mut connection) = (unsafe { build_connection(env, input, output, link) }) else {
        return ptr::null_mut();
    };
    // SAFETY: `progress` is a live object; the method id is looked up on its own class.
    let Ok(mut gauge) = (unsafe { Gauge::new(env, progress) }) else {
        return ptr::null_mut();
    };

    let (settings_pointer, settings_len) = slice_of(&settings);
    let (sender_pointer, sender_len) = slice_of(&sender);
    let (model_pointer, model_len) = slice_of(&model);
    let (sender_id_pointer, sender_id_len) = slice_of(&sender_id);

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: every byte slice outlives the call or is null, each callback is paired with the
    // context it was built for, and `report` is a zeroed buffer on this frame.
    let outcome = unsafe {
        crate::send::opairdrop_send_with_progress(
            host.as_ptr(),
            host.len(),
            settings_pointer,
            settings_len,
            sender_pointer,
            sender_len,
            model_pointer,
            model_len,
            sender_id_pointer,
            sender_id_len,
            transfer_id.as_ptr(),
            transfer_id.len(),
            paths.as_ptr(),
            paths.len(),
            read_connection,
            write_connection,
            dial_connection,
            &mut connection as *mut Connection as *mut c_void,
            report_progress,
            &mut gauge as *mut Gauge as *mut c_void,
            // Only advertise the name callback when the Java sink actually implements it.
            if gauge.peer_name.is_some() {
                Some(report_peer_name as crate::send::NameCallback)
            } else {
                None
            },
            &mut report,
        )
    };

    // SAFETY: `report` is a zeroed buffer the call above filled, one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Asks one peer for its name, without starting a transfer.
///
/// Java owns the TLS socket (and its certificate pinning); this call only sends `/Discover`
/// on the streams it hands over. No `/Ask`, no prompt on the peer, no second connection.
/// Returns the name as bytes on success; a failure is thrown by [`finish`].
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and objects of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_discoverName(
    env: *mut JNIEnv,
    _class: jclass,
    host: jstring,
    settings: jstring,
    sender: jstring,
    model: jstring,
    sender_id: jstring,
    input: jobject,
    output: jobject,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if host.is_null() || input.is_null() || output.is_null() {
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "host and both streams are required",
            );
        }
        return ptr::null_mut();
    }

    // SAFETY: all are live strings and null is taken before read_string.
    let Some(host) = (unsafe { read_string(env, host) }) else {
        return ptr::null_mut();
    };
    // 同型修复，见 mdnsReply 里评审 P0-3 的注释：optional 的 None 有两种来历
    // （null 参数是合法默认值；读取失败是异常挂起），所以这里逐个解、失败即退，
    // 而不是四连发之后才看一眼 pending。
    let Some(settings) = (unsafe { read_optional_string(env, settings) }) else {
        return ptr::null_mut();
    };
    let Some(sender) = (unsafe { read_optional_string(env, sender) }) else {
        return ptr::null_mut();
    };
    let Some(model) = (unsafe { read_optional_string(env, model) }) else {
        return ptr::null_mut();
    };
    let Some(sender_id) = (unsafe { read_optional_string(env, sender_id) }) else {
        return ptr::null_mut();
    };

    // SAFETY: both are live streams and `env` outlives this call.
    // 评审 P0-3：见 mdnsReply 注释——流构造一次一个，失败即退。
    let reader = match unsafe { Stream::new(env, input) } {
        Ok(reader) => reader,
        Err(_) => return ptr::null_mut(),
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let sink = match unsafe { Sink::new(env, output) } {
        Ok(sink) => sink,
        Err(_) => return ptr::null_mut(),
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    };
    // No Link: `/Discover` is one request on one connection.
    let mut connection = Connection {
        reader,
        sink,
        link: None,
    };

    let slice = |text: &Option<String>| match text {
        Some(value) => (value.as_ptr(), value.len()),
        None => (ptr::null(), 0),
    };
    let (settings_p, settings_n) = slice(&settings);
    let (sender_p, sender_n) = slice(&sender);
    let (model_p, model_n) = slice(&model);
    let (id_p, id_n) = slice(&sender_id);

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: slices outlive the call; callbacks are paired with `connection` for its life.
    let outcome = unsafe {
        crate::send::opairdrop_discover_name(
            host.as_ptr(),
            host.len(),
            settings_p,
            settings_n,
            sender_p,
            sender_n,
            model_p,
            model_n,
            id_p,
            id_n,
            read_connection,
            write_connection,
            &mut connection as *mut Connection as *mut c_void,
            &mut report,
        )
    };
    unsafe { finish(env, outcome, &mut report) }
}

/// Serves one connection, asking a person before every transfer is taken.
///
/// `decide` is a `com.oshare.airdrop.Native$Decision`: its `accept(String)` runs on the
/// thread serving this connection, after the offer has been read and before `/Ask` is
/// answered. It may block — that is the whole point — and a `false`, an exception, or a
/// decision that never arrives all end the same way: the transfer is refused.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and objects of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_serveDecided(
    env: *mut JNIEnv,
    _class: jclass,
    directory: jstring,
    settings_text: jstring,
    model: jstring,
    input: jobject,
    output: jobject,
    decide: jobject,
) -> jbyteArray {
    // SAFETY: the JVM's own call, and nothing is watching this one.
    unsafe {
        serve_for_java(
            env,
            directory,
            settings_text,
            model,
            // 这个入口没有那个参数，见 `Java_..._serveWatched`。
            ptr::null_mut(),
            input,
            output,
            decide,
            ptr::null_mut(),
        )
    }
}

/// Serves one connection, asking a person before every transfer and saying how far it got.
///
/// [`serveDecided`] with a watcher. `landing` is a `com.oshare.airdrop.Native$Landing`, whose
/// `landed(long done, long total)` runs on the thread serving this connection, from inside
/// the inbox's write loop: once per landed chunk, so as often as bytes arrive.
///
/// Both numbers are payload bytes, which is the unit an offer's sizes are in. `total` is
/// what the sender declared and it is not a promise — a peer may send more or less than it
/// announced — and a `total` of zero means it declared nothing, which is the caller's cue to
/// show motion rather than a fraction.
///
/// The call happens while the transfer waits, so it must be cheap. It cannot fail the
/// receive: a `landing` that throws stops being told and the bytes keep arriving.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and objects of the declared types.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_serveWatched(
    env: *mut JNIEnv,
    _class: jclass,
    directory: jstring,
    settings_text: jstring,
    model: jstring,
    system_name: jstring,
    input: jobject,
    output: jobject,
    decide: jobject,
    landing: jobject,
) -> jbyteArray {
    // SAFETY: the JVM's own call.
    unsafe {
        serve_for_java(
            env,
            directory,
            settings_text,
            model,
            system_name,
            input,
            output,
            decide,
            landing,
        )
    }
}

/// The body of the two exports above. `landing` may be null, which means nobody is watching.
// These nine are the Java signature. The exports above cannot pass a struct — JNI marshals
// one argument per Java parameter — and this body reads each of them exactly once, so grouping
// them here would add a struct whose only purpose is to be unpacked on the next line.
#[allow(clippy::too_many_arguments)]
unsafe fn serve_for_java(
    env: *mut JNIEnv,
    directory: jstring,
    settings_text: jstring,
    model: jstring,
    system_name: jstring,
    input: jobject,
    output: jobject,
    decide: jobject,
    landing: jobject,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if directory.is_null() || input.is_null() || output.is_null() || decide.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "directory, input, output and a decision are required",
            );
        }
        return ptr::null_mut();
    }

    // SAFETY: `directory` is a live `java.lang.String`, per the JVM's call.
    let Some(directory) = (unsafe { read_string(env, directory) }) else {
        return ptr::null_mut();
    };
    // SAFETY: as above, for each optional string.
    let Some(settings_text) = (unsafe { read_optional_string(env, settings_text) }) else {
        return ptr::null_mut();
    };
    let Some(model) = (unsafe { read_optional_string(env, model) }) else {
        return ptr::null_mut();
    };
    // The phone's own name — what Settings calls this device, and what Bluetooth advertises.
    // Null is the "caller has nothing to say" case, which falls back to the model.
    // SAFETY: as above.
    let Some(system_name) = (unsafe { read_optional_string(env, system_name) }) else {
        return ptr::null_mut();
    };

    // SAFETY: both are live streams and `env` outlives this call.
    // 评审 P0-3：Stream::new 失败（异常挂起）后不再发 Sink::new 的 JNI 调用。
    let reader = match unsafe { Stream::new(env, input) } {
        Ok(reader) => reader,
        Err(_) => return ptr::null_mut(),
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let sink = match unsafe { Sink::new(env, output) } {
        Ok(sink) => sink,
        Err(_) => return ptr::null_mut(),
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    };
    // Nothing on the receiving side renews: a receiver answers a connection, it does not
    // ask on one, so its connection has no `Link` to follow.
    let mut connection = Connection {
        reader,
        sink,
        link: None,
    };
    // SAFETY: `decide` is a live object; the method id is looked up on its own class.
    let Ok(mut decider) = (unsafe { Decider::new(env, decide) }) else {
        return ptr::null_mut();
    };
    // SAFETY: `landing` is null or a live `com.oshare.airdrop.Native$Landing`, per the JVM's
    // call. Null means the caller wants no reports, which is `serveDecided`'s whole case and
    // is not an error.
    let mut landing = if landing.is_null() {
        None
    } else {
        match unsafe { Landing::new(env, landing) } {
            Ok(landing) => Some(landing),
            Err(()) => return ptr::null_mut(),
        }
    };

    let (settings_pointer, settings_len) = match &settings_text {
        Some(text) => (text.as_ptr(), text.len()),
        None => (ptr::null(), 0),
    };
    let (model_pointer, model_len) = match &model {
        Some(text) => (text.as_ptr(), text.len()),
        None => (ptr::null(), 0),
    };

    let mut report = OpairdropBuffer::EMPTY;
    // SAFETY: every byte slice outlives the call or is null, each callback is paired with
    // the context it was built for, and `report` is a zeroed buffer on this frame.
    let outcome = unsafe {
        serve(
            directory.as_ptr(),
            directory.len(),
            settings_pointer,
            settings_len,
            model_pointer,
            model_len,
            // Borrowed rather than a pointer: it never leaves this frame, and `serve` wants a
            // `&str` in the first place. `None` here is the older callers' case.
            system_name.as_deref(),
            read_connection,
            write_connection,
            &mut connection as *mut Connection as *mut c_void,
            Some(Decide::new(
                decide_java,
                &mut decider as *mut Decider as *mut c_void,
            )),
            landing.as_mut().map(|landing| {
                Watcher::new(report_landing, landing as *mut Landing as *mut c_void)
            }),
            &mut report,
        )
    };

    // SAFETY: `report` is a zeroed buffer the call above filled, one way or the other.
    unsafe { finish(env, outcome, &mut report) }
}

/// Reports the settings catalog: one line per setting, tab-separated.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_settingsCatalog(
    env: *mut JNIEnv,
    _class: jclass,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    let mut out = OpairdropBuffer::EMPTY;
    // SAFETY: `out` is a zeroed buffer on this frame.
    let outcome = unsafe { settings::opairdrop_settings_catalog(&mut out) };
    // SAFETY: as above, filled one way or the other.
    unsafe { finish(env, outcome, &mut out) }
}

/// Reads the settings stored at `path`, or the table's defaults when there is no file.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and a live `java.lang.String`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_loadSettings(
    env: *mut JNIEnv,
    _class: jclass,
    path: jstring,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if path.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "a settings path is required",
            )
        };
        return ptr::null_mut();
    }
    // SAFETY: `path` is a live `java.lang.String`, per the JVM's call.
    let Some(path) = (unsafe { read_string(env, path) }) else {
        return ptr::null_mut();
    };

    let mut out = OpairdropBuffer::EMPTY;
    // SAFETY: `out` is a zeroed buffer and `path` outlives the call.
    let outcome = unsafe { settings::opairdrop_settings_load(path.as_ptr(), path.len(), &mut out) };
    // SAFETY: as above, filled one way or the other.
    unsafe { finish(env, outcome, &mut out) }
}

/// Validates `text` and, if it holds, stores it at `path`.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and live `java.lang.String`s.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_saveSettings(
    env: *mut JNIEnv,
    _class: jclass,
    path: jstring,
    text: jstring,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if path.is_null() || text.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "a settings path and its text are required",
            );
        }
        return ptr::null_mut();
    }
    // SAFETY: both are live `java.lang.String`s, per the JVM's call.
    // 同型修复，见 mdnsReply 里评审 P0-3 的注释：一次一个，失败即退。
    let Some(path) = (unsafe { read_string(env, path) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    let Some(text) = (unsafe { read_string(env, text) }) else {
        return ptr::null_mut();
    };
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }

    let mut out = OpairdropBuffer::EMPTY;
    // SAFETY: `out` is a zeroed buffer and both strings outlive the call.
    let outcome = unsafe {
        settings::opairdrop_settings_save(
            path.as_ptr(),
            path.len(),
            text.as_ptr(),
            text.len(),
            &mut out,
        )
    };
    // SAFETY: as above, filled one way or the other.
    unsafe { finish(env, outcome, &mut out) }
}

/// Starts the advertisement that wakes a receiver, and answers where it got to.
///
/// The twenty bytes and the four settings are [`ble`]'s; the radio is Java's, in `BleRadio`.
/// This export is the join: it builds what goes out, hands it over, and reports the stack's
/// answer as a state line — never as an exception, because a phone that cannot be woken is
/// not an error the page should turn into an error screen. The search behind it works
/// without the advertisement, just not against a receiver that is asleep.
///
/// Called again while something is registered, it answers the current line and does not
/// register twice: the stack refuses the second registration with `ALREADY_STARTED`, so a
/// page returning to the foreground would turn a working advertisement into a failure.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv` and a live `android.content.Context`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_bleAdvertise(
    env: *mut JNIEnv,
    _class: jclass,
    context: jobject,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if context.is_null() {
        // SAFETY: `env` was checked non-null.
        unsafe {
            throw(
                env,
                "java/lang/NullPointerException",
                "a Context is required",
            )
        };
        return ptr::null_mut();
    }
    if ble::current().is_awake() {
        let line = ble::line();
        // SAFETY: `env` is valid.
        return unsafe { new_bytes(env, line.as_bytes()) };
    }

    let payload = ble::manufacturer_data(ble::random_tag());
    // The state moves to `starting` before the call, and the lock is released by then: the
    // inline path answers through `bleVerdict` from inside `start` below, on this thread,
    // and a standard mutex taken twice by one thread deadlocks against itself.
    ble::starting();

    let radio = match unsafe { Radio::new(env) } {
        Ok(radio) => radio,
        // SAFETY: `env` is valid.
        Err(reason) => return unsafe { new_bytes(env, ble::given_up(&reason).as_bytes()) },
    };

    // SAFETY: `env` is valid and `payload` outlives the copy.
    let data = unsafe { new_bytes(env, &payload) };
    if data.is_null() {
        // `new_bytes` has already thrown, and an array that could not be made is not the
        // radio's failure to report. 但 starting() 已经把状态推到了 Starting：没人会来
        // 给这次没发生的投递送 verdict，状态会永久卡在 Starting（评审 P1-20）。
        // 这里只回滚 Rust 侧状态，不碰 JVM——异常仍归调用方。
        let _ = ble::given_up("the advertisement data could not be copied to Java");
        // SAFETY: `radio.class` is a local reference this frame owns.
        unsafe { delete_local(env, radio.class) };
        return ptr::null_mut();
    }
    let table = unsafe { table(env) };
    // SAFETY: `radio.class` is the class object made above, `radio.start` is a static
    // method on it, and every argument matches the descriptor it was looked up with.
    unsafe {
        (entry!(table, CallStaticVoidMethod))(
            env,
            radio.class,
            radio.start,
            context,
            ble::COMPANY_ID as jint,
            data,
            ble::MODE_LOW_LATENCY,
            ble::POWER_HIGH,
            // A `jboolean` in a varargs slot travels as an `int` — C's default argument
            // promotion — which is what the JVM's own dispatch reads back out of it.
            ble::CONNECTABLE as jint,
            ble::TIMEOUT_MS,
        )
    };
    // SAFETY: both are local references this frame owns.
    unsafe {
        delete_local(env, data);
        delete_local(env, radio.class);
    };

    // A pending exception is the honest report and belongs to the caller: copying the state
    // out would answer with null and leave the exception unexplained. 同样要回滚 Starting——
    // 投出去的调用抛了，stack 不会回 verdict，状态不回滚就永久停在 Starting（P1-20）。
    if unsafe { pending(env) } {
        let _ = ble::given_up("BleRadio.start threw before the stack answered");
        return ptr::null_mut();
    }
    let line = ble::line();
    // SAFETY: `env` is valid.
    unsafe { new_bytes(env, line.as_bytes()) }
}

/// Records the stack's answer about the advertisement, and answers the new state line.
///
/// Called from `BleRadio`'s callback, which runs on whichever thread had a looper when the
/// registration was made. A positive code is the stack's own `ADVERTISE_FAILED_*`; zero is
/// success; a negative one is [`ble::refusal`], meaning the radio never got that far.
///
/// The line comes back rather than being swallowed because the only thing to do with it is
/// write it to the log, and that is one call instead of two.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_bleVerdict(
    env: *mut JNIEnv,
    _class: jclass,
    code: jint,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    let line = ble::verdict(code);
    // SAFETY: `env` is valid.
    unsafe { new_bytes(env, line.as_bytes()) }
}

/// Takes the advertisement down, and answers the state line.
///
/// Stopping what was never started is not a failure: a page stops on its way out whether or
/// not the advertisement ever began, and a phone that refused to be woken must not leave
/// this call throwing.
///
/// # Safety
///
/// Called by the JVM with a valid `JNIEnv`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_oshare_airdrop_Native_bleStop(
    env: *mut JNIEnv,
    _class: jclass,
) -> jbyteArray {
    if env.is_null() {
        return ptr::null_mut();
    }
    if let Ok(radio) = unsafe { Radio::new(env) } {
        // SAFETY: `env` is valid.
        if !unsafe { pending(env) } {
            let table = unsafe { table(env) };
            // SAFETY: `radio.class` is the class object made above and `radio.stop` is a
            // static method on it that takes nothing.
            unsafe { (entry!(table, CallStaticVoidMethod))(env, radio.class, radio.stop) };
        }
        // SAFETY: `radio.class` is a local reference this frame owns.
        unsafe { delete_local(env, radio.class) };
    }
    let line = ble::stopped();
    // SAFETY: `env` is valid.
    unsafe { new_bytes(env, line.as_bytes()) }
}

/// The Java side of the radio: one class, and the two static methods called on it.
///
/// The class is looked up by name on each call rather than cached. A cached `jclass` is a
/// local reference and dies with the frame that made it; keeping one across calls means a
/// global reference and its lifetime, which is a real cost to pay for a lookup that happens
/// twice per page. `FindClass` inside a native method the JVM called uses that caller's
/// class loader — the application's — so `BleRadio` is found where a static initializer
/// would find it. It would not be found from a thread attached by anything but Java, and
/// that is the one condition this design rests on.
struct Radio {
    class: jclass,
    /// `BleRadio.start(Context, int, byte[], int, int, boolean, int)`.
    start: jni_sys::jmethodID,
    /// `BleRadio.stop()`.
    stop: jni_sys::jmethodID,
}

impl Radio {
    /// # Safety
    ///
    /// `env` must be valid.
    unsafe fn new(env: *mut JNIEnv) -> Result<Radio, String> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(env) };
        let class_name = c_str("com/oshare/airdrop/BleRadio");
        // SAFETY: `env` is valid and the name outlives the call.
        let class = unsafe { (entry!(table, FindClass))(env, class_name.as_ptr()) };
        if class.is_null() {
            // `FindClass` has answered by throwing `NoClassDefFoundError`. It is cleared so
            // the failure can be reported the way the rest of this module reports one — as
            // a state line — instead of as an exception out of a call whose job is to not
            // throw.
            // SAFETY: `env` is valid.
            unsafe { (entry!(table, ExceptionClear))(env) };
            return Err("com.oshare.airdrop.BleRadio is not on the class path".to_string());
        }
        let start_name = c_str("start");
        let start_signature = c_str("(Landroid/content/Context;I[BIIZI)V");
        let stop_name = c_str("stop");
        let stop_signature = c_str("()V");
        // SAFETY: `class` is the live class object made above and every string outlives the
        // call.
        let start = unsafe {
            (entry!(table, GetStaticMethodID))(
                env,
                class,
                start_name.as_ptr(),
                start_signature.as_ptr(),
            )
        };
        // SAFETY: as above.
        let stop = unsafe {
            (entry!(table, GetStaticMethodID))(
                env,
                class,
                stop_name.as_ptr(),
                stop_signature.as_ptr(),
            )
        };
        // SAFETY: `env` is valid.
        if start.is_null() || stop.is_null() || unsafe { pending(env) } {
            // SAFETY: `class` is a local reference made here.
            unsafe { delete_local(env, class) };
            return Err(
                "BleRadio has no start(Context,int,byte[],int,int,boolean,int) or stop()"
                    .to_string(),
            );
        }
        Ok(Radio { class, start, stop })
    }
}

/// Turns a status and the buffer it filled into the `byte[]` a Java method returns.
///
/// Every export above ends here, so the rule "success copies the buffer out and releases
/// it, failure throws and returns null" is written once. A second copy of it is a second
/// chance to leak the buffer on the failure path, which is the path that gets exercised
/// least.
///
/// # Safety
///
/// `env` must be valid and `report` a buffer the call filled.
unsafe fn finish(env: *mut JNIEnv, outcome: i32, report: &mut OpairdropBuffer) -> jbyteArray {
    // SAFETY: the call that produced `outcome` filled `report`, and nothing has released it.
    let content = unsafe { report.as_slice() }.to_vec();
    // SAFETY: the contents were copied out first, and the buffer came from this library.
    unsafe { opairdrop_buffer_free(report) };

    if outcome == status::OK {
        // Success copies the buffer out — unless a callback left an exception pending on the
        // way here, which is not a contradiction: the endpoint reports "nothing was stored"
        // as an empty frame and an I/O failure as a thrown exception, and both are true of a
        // peer that closed mid-request. `new_bytes` refuses to touch the JVM in that state
        // and answers null, so the precise exception reaches Java instead of a process abort.
        // SAFETY: `env` is valid.
        return unsafe { new_bytes(env, &content) };
    }

    let message = String::from_utf8_lossy(&content).into_owned();
    // A stream that failed has already thrown; a second throw would replace a precise
    // exception with a vague one.
    // SAFETY: `env` is valid.
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    // SAFETY: `env` is valid.
    unsafe { throw(env, class_for(outcome), &message) };
    ptr::null_mut()
}

/// 把 Rust 文本交给 JVM，走 UTF-16 而不是 modified UTF-8。
///
/// JNI 的 `NewStringUTF` 要的是 modified UTF-8：NUL 编成 `C0 80`，补充平面字符（emoji）
/// 要编成一对 UTF-16 代理项各占三字节。喂它标准 UTF-8 时，一个 emoji 的四个字节会被
/// 当成四个独立的 U+00xx，CheckJNI 下 ART 直接 abort，不是抛异常。设备名和对端摘要
/// 都是对端可控的文本，所以这条路径是远程可达的。
///
/// `NewString` 收 `jchar*`（即 `u16*`），正好是 `str::encode_utf16()` 的产物：没有编码
/// 转换，也没有 NUL 的坑（UTF-16 的 0 就是 0）。空串仍返回一个合法的 Java 空串。
///
/// # Safety
///
/// `env` 必须有效，且调用前没有 pending 异常。
unsafe fn new_utf16_string(env: *mut JNIEnv, text: &str) -> jstring {
    let units: Vec<u16> = text.encode_utf16().collect();
    let table = unsafe { table(env) };
    // SAFETY: `units` 在调用期间有效；长度是 UTF-16 码元数，不是字节数。
    unsafe {
        (entry!(table, NewString))(
            env,
            units.as_ptr(),
            units.len().try_into().unwrap_or(jni_sys::jsize::MAX),
        )
    }
}

/// The Java exception that best describes a status.
///
/// A refused setting and a bad path are both "you asked for something that is not allowed"
/// and cannot be retried into working, so they are unchecked; everything else is an I/O
/// failure the caller is expected to handle.
///
/// [`status::REFUSED`] gets its own class rather than plain `IOException` because the
/// caller has to *say something else* about it. Every other failure on this path means
/// something went wrong, and "the transfer failed" is the right sentence for it. A refusal
/// is a peer that answered: the handshake held, the request was well formed, the name came
/// back. The only missing thing is a setting on a phone the caller cannot reach, so the
/// sentence has to name that setting instead of inviting another try. A caller that has to
/// tell the two apart by reading the message text would be parsing English, which is the
/// kind of coupling that survives exactly until someone rewords a string.
fn class_for(outcome: i32) -> &'static str {
    match outcome {
        status::NULL_ARGUMENT => "java/lang/NullPointerException",
        status::SETTINGS | status::BAD_PATH => "java/lang/IllegalArgumentException",
        status::REFUSED => "com/oshare/airdrop/RefusedException",
        _ => "java/io/IOException",
    }
}

/// A Java `InputStream` seen as the C ABI's read callback.
struct Stream {
    env: *mut JNIEnv,
    stream: jobject,
    /// Reused across crossings: a fresh array every 64 KiB would spend more time in the
    /// collector than in the copy.
    buffer: jbyteArray,
    /// Cached `InputStream.read([BII)I`, valid as long as `stream` is.
    read: jni_sys::jmethodID,
    /// Set once this stream has failed, so the callback does not retry a broken stream.
    failed: bool,
}

impl Stream {
    /// # Safety
    ///
    /// `env` must be valid and `stream` a live `java.io.InputStream`.
    unsafe fn new(env: *mut JNIEnv, stream: jobject) -> Result<Stream, ()> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(env) };

        // SAFETY: `stream` is a live object.
        let class = unsafe { (entry!(table, GetObjectClass))(env, stream) };
        if class.is_null() {
            return Err(());
        }
        let name = c_str("read");
        let signature = c_str("([BII)I");
        // SAFETY: `class` is the live class object made above and both strings outlive the
        // call.
        let read =
            unsafe { (entry!(table, GetMethodID))(env, class, name.as_ptr(), signature.as_ptr()) };
        // SAFETY: `class` is the local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, class) };
        if read.is_null() || unsafe { pending(env) } {
            return Err(());
        }

        // SAFETY: a fresh local array of a fixed size.
        let buffer = unsafe { (entry!(table, NewByteArray))(env, READ_CHUNK as jsize) };
        if buffer.is_null() || unsafe { pending(env) } {
            return Err(());
        }

        Ok(Stream {
            env,
            stream,
            buffer,
            read,
            failed: false,
        })
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        // SAFETY: `buffer` is a local reference this struct owns, and `env` is the one it
        // was created with.
        unsafe { delete_local(self.env, self.buffer) };
    }
}

/// The C ABI's read callback, backed by `InputStream.read`.
///
/// # Safety
///
/// `context` must point at a live [`Stream`] and `buf` must be writable for `len` bytes.
unsafe extern "C" fn read_stream(context: *mut c_void, buf: *mut u8, len: usize) -> isize {
    if context.is_null() || buf.is_null() || len == 0 {
        return -1;
    }
    // SAFETY: the caller passes the `Stream` this layer created, and a shared borrow of it
    // cannot alias another because the C ABI drives the callback on one thread.
    let stream = unsafe { &mut *(context as *mut Stream) };
    if stream.failed {
        return -1;
    }
    // SAFETY: `stream.env` is the valid environment the stream was made with.
    //
    // Checked before anything else crosses: a callback can be entered again after an earlier
    // failure left its exception pending, and calling `InputStream.read` then aborts the
    // process under CheckJNI instead of failing this connection.
    if unsafe { pending(stream.env) } {
        stream.failed = true;
        return -1;
    }
    let table = unsafe { table(stream.env) };

    let want = len.min(READ_CHUNK) as jsize;
    // SAFETY: `stream.read` is `InputStream.read([BII)I` on `stream.stream`, `buffer` is an
    // array this layer allocated for exactly that purpose, and the trailing numbers are the
    // `int offset, int length` the variadic call expects.
    let got = unsafe {
        (entry!(table, CallIntMethod))(
            stream.env,
            stream.stream,
            stream.read,
            stream.buffer,
            0,
            want,
        )
    };

    // SAFETY: `stream.env` is valid.
    if unsafe { pending(stream.env) } {
        // The Java exception is the one the caller should see, so it is left pending and
        // the shim is told not to throw over it.
        stream.failed = true;
        return -1;
    }
    if got < 0 {
        // `InputStream` reports end of stream as -1.
        return 0;
    }
    if got == 0 || got > want {
        // A blocking stream cannot report zero for a non-empty request, and more than was
        // asked for is not something the `Read` contract can express.
        stream.failed = true;
        return -1;
    }

    // SAFETY: `got` bytes are present in `stream.buffer`, and `buf` has room for `len`,
    // which is at least `want`.
    unsafe {
        (entry!(table, GetByteArrayRegion))(stream.env, stream.buffer, 0, got, buf as *mut jbyte)
    };
    if unsafe { pending(stream.env) } {
        stream.failed = true;
        return -1;
    }
    got as isize
}

/// A Java `OutputStream` seen as the C ABI's write callback.
///
/// The mirror of [`Stream`], and separately written rather than shared: a reader and a
/// writer differ in the one method they call (`read` returns a count, `write` returns
/// nothing) and in what a failure means (a reader can stop at end of stream, a writer has
/// no such value). A shared struct with two nullable method ids would put both questions
/// at every call site.
struct Sink {
    env: *mut JNIEnv,
    stream: jobject,
    /// Reused across crossings, for the same reason as the reader's.
    buffer: jbyteArray,
    /// Cached `OutputStream.write([BII)V`, valid as long as `stream` is.
    write: jni_sys::jmethodID,
    /// Set once this stream has failed, so the callback does not retry a broken stream.
    failed: bool,
}

impl Sink {
    /// # Safety
    ///
    /// `env` must be valid and `stream` a live `java.io.OutputStream`.
    unsafe fn new(env: *mut JNIEnv, stream: jobject) -> Result<Sink, ()> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(env) };

        // SAFETY: `stream` is a live object.
        let class = unsafe { (entry!(table, GetObjectClass))(env, stream) };
        if class.is_null() {
            return Err(());
        }
        let name = c_str("write");
        let signature = c_str("([BII)V");
        // SAFETY: `class` is the live class object made above and both strings outlive the
        // call.
        let write =
            unsafe { (entry!(table, GetMethodID))(env, class, name.as_ptr(), signature.as_ptr()) };
        // SAFETY: `class` is the local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, class) };
        if write.is_null() || unsafe { pending(env) } {
            return Err(());
        }

        // SAFETY: a fresh local array of a fixed size.
        let buffer = unsafe { (entry!(table, NewByteArray))(env, READ_CHUNK as jsize) };
        if buffer.is_null() || unsafe { pending(env) } {
            return Err(());
        }

        Ok(Sink {
            env,
            stream,
            buffer,
            write,
            failed: false,
        })
    }
}

impl Drop for Sink {
    fn drop(&mut self) {
        // SAFETY: `buffer` is a local reference this struct owns, and `env` is the one it
        // was created with.
        unsafe { delete_local(self.env, self.buffer) };
    }
}

/// The C ABI's write callback, backed by `OutputStream.write`.
///
/// Returns `len`, because this layer either writes all of it or fails: a partial write
/// handed back to the caller would mean the connection asked for the rest, which reads as
/// a peer that stopped reading rather than as the local stream that actually broke.
///
/// # Safety
///
/// `context` must point at a live [`Sink`] and `buf` must be readable for `len` bytes.
unsafe extern "C" fn write_sink(context: *mut c_void, buf: *const u8, len: usize) -> isize {
    if context.is_null() || buf.is_null() {
        return -1;
    }
    // SAFETY: the caller passes the `Sink` this layer created, and a mutable borrow of it
    // cannot alias another because the C ABI drives the callback on one thread.
    let sink = unsafe { &mut *(context as *mut Sink) };
    if sink.failed {
        return -1;
    }
    // SAFETY: `sink.env` is the valid environment the sink was made with.
    //
    // Same rule as the reader: a pending exception from the other half of this connection
    // would turn the first `SetByteArrayRegion` below into a process abort.
    if unsafe { pending(sink.env) } {
        sink.failed = true;
        return -1;
    }
    let table = unsafe { table(sink.env) };

    let mut written = 0;
    while written < len {
        let take = (len - written).min(READ_CHUNK);
        // SAFETY: `buf` has `len` readable bytes and `written < len`, so the tail from
        // `written` is at least `take` long; `sink.buffer` has room for `take`.
        unsafe {
            (entry!(table, SetByteArrayRegion))(
                sink.env,
                sink.buffer,
                0,
                take as jsize,
                buf.add(written) as *const jbyte,
            )
        };
        // SAFETY: `sink.env` is valid.
        if unsafe { pending(sink.env) } {
            sink.failed = true;
            return -1;
        }
        // SAFETY: `sink.write` is `OutputStream.write([BII)V` on `sink.stream`, and the
        // trailing numbers are the `int offset, int length` the variadic call expects.
        unsafe {
            (entry!(table, CallVoidMethod))(
                sink.env,
                sink.stream,
                sink.write,
                sink.buffer,
                0,
                take as jsize,
            )
        };
        // SAFETY: `sink.env` is valid.
        if unsafe { pending(sink.env) } {
            // The Java exception is the one the caller should see, so it is left pending.
            sink.failed = true;
            return -1;
        }
        written += take;
    }
    len as isize
}

/// A Java object told how far a transfer has got.
struct Gauge {
    env: *mut JNIEnv,
    object: jobject,
    /// Cached `Progress.report(int, long, long)V`.
    report: jni_sys::jmethodID,
    /// Cached `Progress.name(String)V`, when the sink implements it. Absent on older sinks.
    peer_name: Option<jni_sys::jmethodID>,
    /// Set once the sink threw. The transfer is not aborted for it, but the sink is not
    /// called again either: one broken listener must not become thousands of exceptions.
    failed: bool,
}

impl Gauge {
    /// # Safety
    ///
    /// `env` must be valid and `object` a live `com.oshare.airdrop.Native$Progress`.
    unsafe fn new(env: *mut JNIEnv, object: jobject) -> Result<Gauge, ()> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(env) };
        // SAFETY: `object` is a live object.
        let class = unsafe { (entry!(table, GetObjectClass))(env, object) };
        if class.is_null() {
            return Err(());
        }
        let name = c_str("report");
        let signature = c_str("(IJJ)V");
        // SAFETY: `class` is the live class object made above, both strings outlive the call.
        let report =
            unsafe { (entry!(table, GetMethodID))(env, class, name.as_ptr(), signature.as_ptr()) };

        // `name(String)` is optional: a sink written before the peer-name callback existed
        // still works for byte counts. A missing method leaves NoSuchMethodError pending, so
        // clear it and remember there is no method to call. Looked up on the same live `class`
        // before it is deleted below.
        let method_name = c_str("name");
        let method_sig = c_str("(Ljava/lang/String;)V");
        let peer_method = unsafe {
            (entry!(table, GetMethodID))(env, class, method_name.as_ptr(), method_sig.as_ptr())
        };
        let peer_absent = peer_method.is_null() || unsafe { pending(env) };
        if peer_absent {
            // SAFETY: `env` is valid; clear the expected NoSuchMethodError.
            unsafe { (entry!(table, ExceptionClear))(env) };
        }

        // SAFETY: `class` is the local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, class) };
        if report.is_null() || unsafe { pending(env) } {
            return Err(());
        }

        Ok(Gauge {
            env,
            object,
            report,
            peer_name: if peer_absent { None } else { Some(peer_method) },
            failed: false,
        })
    }
}

/// The C ABI's progress callback, backed by `Progress.report`.
///
/// # Safety
///
/// `context` must point at a live [`Gauge`].
unsafe extern "C" fn report_progress(context: *mut c_void, phase: i32, sent: u64, total: u64) {
    if context.is_null() {
        return;
    }
    // SAFETY: the caller passes the `Gauge` this layer created, on the thread doing the
    // sending, so the borrow cannot alias.
    let gauge = unsafe { &mut *(context as *mut Gauge) };
    if gauge.failed {
        return;
    }
    // SAFETY: `gauge.env` is valid.
    //
    // A pending exception — from a write that failed a moment ago, say — makes the call
    // below abort the process. The transfer is not abandoned for this; the meter simply
    // stops being told.
    if unsafe { pending(gauge.env) } {
        gauge.failed = true;
        return;
    }
    let table = unsafe { table(gauge.env) };
    // SAFETY: `report` is `Progress.report(IJJ)V` on the object the caller handed over.
    unsafe {
        (entry!(table, CallVoidMethod))(
            gauge.env,
            gauge.object,
            gauge.report,
            phase as jint,
            sent as jlong,
            total as jlong,
        )
    };
    // SAFETY: `gauge.env` is valid.
    if unsafe { pending(gauge.env) } {
        // The exception stays pending: it is the caller's, and it is the honest report.
        gauge.failed = true;
    }
}

/// The C ABI's peer-name callback, backed by `Progress.name(String)`.
///
/// # Safety
///
/// `context` must point at a live [`Gauge`] and `name` must be readable for `name_len` bytes.
unsafe extern "C" fn report_peer_name(context: *mut c_void, name: *const u8, name_len: usize) {
    if context.is_null() || name.is_null() {
        return;
    }
    let gauge = unsafe { &mut *(context as *mut Gauge) };
    let Some(method) = gauge.peer_name else {
        return;
    };
    if gauge.failed || unsafe { pending(gauge.env) } {
        return;
    }
    let bytes = unsafe { std::slice::from_raw_parts(name, name_len) };
    // UTF-16 crossing: emoji and embedded NUL both survive, unlike NewStringUTF, which
    // ART's CheckJNI aborts on for non-BMP text from a peer-controlled name.
    let text = String::from_utf8_lossy(bytes);
    // SAFETY: `env` is valid and checked pending above.
    let argument = unsafe { new_utf16_string(gauge.env, &text) };
    if argument.is_null() {
        gauge.failed = true;
        return;
    }
    let table = unsafe { table(gauge.env) };
    // SAFETY: `method` is `Progress.name(String)V` on the object the caller handed over.
    unsafe { (entry!(table, CallVoidMethod))(gauge.env, gauge.object, method, argument) };
    // SAFETY: `argument` is a local reference made above.
    unsafe { (entry!(table, DeleteLocalRef))(gauge.env, argument) };
    if unsafe { pending(gauge.env) } {
        gauge.failed = true;
    }
}

/// A Java object that answers whether a transfer should be taken.
///
/// The summary crosses as a `String` rather than as bytes because this is the one place a
/// person reads it: `String` is what the prompt is built from, and every byte-level reason
/// this layer usually avoids arrays does not apply to one short line.
struct Decider {
    env: *mut JNIEnv,
    object: jobject,
    /// Cached `Decision.accept(String)Z`.
    accept: jni_sys::jmethodID,
}

impl Decider {
    /// # Safety
    ///
    /// `env` must be valid and `object` a live `com.oshare.airdrop.Native$Decision`.
    unsafe fn new(env: *mut JNIEnv, object: jobject) -> Result<Decider, ()> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(env) };
        // SAFETY: `object` is a live object.
        let class = unsafe { (entry!(table, GetObjectClass))(env, object) };
        if class.is_null() {
            return Err(());
        }
        let name = c_str("accept");
        let signature = c_str("(Ljava/lang/String;)Z");
        // SAFETY: `class` is the live class object made above and both strings outlive the
        // call.
        let accept =
            unsafe { (entry!(table, GetMethodID))(env, class, name.as_ptr(), signature.as_ptr()) };
        // SAFETY: `class` is the local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, class) };
        if accept.is_null() || unsafe { pending(env) } {
            return Err(());
        }
        Ok(Decider {
            env,
            object,
            accept,
        })
    }
}

/// The C ABI's decision callback, backed by `Decision.accept`.
///
/// Every failure path answers zero, which the endpoint reads as a refusal. A prompt that
/// threw is not a person saying yes: taking a file on behalf of an exception is the one
/// outcome that cannot be undone.
///
/// # Safety
///
/// `context` must point at a live [`Decider`] and `summary` must be readable for `len` bytes.
unsafe extern "C" fn decide_java(context: *mut c_void, summary: *const u8, len: usize) -> i32 {
    if context.is_null() || summary.is_null() {
        return 0;
    }
    // SAFETY: the caller passes the `Decider` this layer created, on the thread serving
    // one connection, so the borrow cannot alias.
    let decider = unsafe { &mut *(context as *mut Decider) };
    // SAFETY: `decider.env` is valid.
    //
    // A prompt is a person's decision and must never be asked on behalf of an exception:
    // the body before it may already have failed, and building the string below would abort
    // the process rather than return a refusal.
    if unsafe { pending(decider.env) } {
        return 0;
    }
    let bytes = unsafe { std::slice::from_raw_parts(summary, len) };
    // A sender name may be any text the other phone allows; invalid UTF-8 is replaced
    // rather than dropped, because a prompt with a mangled name still describes a transfer
    // and an empty one does not.
    let text = String::from_utf8_lossy(bytes);
    // SAFETY: `env` is valid and checked pending above. UTF-16 crossing keeps a sender
    // name with emoji intact; NewStringUTF would hand ART invalid modified UTF-8.
    let argument = unsafe { new_utf16_string(decider.env, &text) };
    if argument.is_null() {
        return 0;
    }
    let table = unsafe { table(decider.env) };
    // SAFETY: `argument` is the string just made, and `accept` is
    // `Decision.accept(String)Z` on the object the caller handed over.
    let answer = unsafe {
        (entry!(table, CallBooleanMethod))(decider.env, decider.object, decider.accept, argument)
    };
    // SAFETY: `decider.env` is valid.
    if unsafe { pending(decider.env) } {
        // The Java exception stays pending: it is the caller's, and it says more than a
        // refusal code would. The local reference is left to the frame, which releases every
        // local when the native call returns.
        return 0;
    }
    // SAFETY: `argument` is a local reference this frame owns.
    unsafe { (entry!(table, DeleteLocalRef))(decider.env, argument) };
    // `CallBooleanMethod` answers a `jboolean`: 0 or 1, not `false`/`true`.
    i32::from(answer != 0)
}

/// A Java object that is told how far a receive has got.
///
/// The mirror of [`Gauge`], which watches a send. It carries no phase because a receive has
/// none worth reporting: an offer is either being read or bytes are landing, and only the
/// second one produces a number.
struct Landing {
    env: *mut JNIEnv,
    object: jobject,
    /// Cached `Landing.landed(JJ)V`.
    landed: jni_sys::jmethodID,
    /// Cached `Landing.members(II)V` —— 实况的成员进度（0 表示 jmethodID 缺失，
    /// 只是说卡上少一句「已收 N/M」，不影响别的）。
    members: jni_sys::jmethodID,
    /// Set once the Java side threw. The receive is not abandoned for it, but the callback
    /// is not made again either: one broken listener must not become thousands of
    /// exceptions on a long transfer.
    failed: bool,
}

impl Landing {
    /// # Safety
    ///
    /// `env` must be valid and `object` a live `com.oshare.airdrop.Native$Landing`.
    unsafe fn new(env: *mut JNIEnv, object: jobject) -> Result<Landing, ()> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(env) };
        // SAFETY: `object` is a live object.
        let class = unsafe { (entry!(table, GetObjectClass))(env, object) };
        if class.is_null() {
            return Err(());
        }
        let name = c_str("landed");
        let signature = c_str("(JJ)V");
        // SAFETY: `class` is the live class object made above, both strings outlive the call.
        let landed =
            unsafe { (entry!(table, GetMethodID))(env, class, name.as_ptr(), signature.as_ptr()) };
        // SAFETY: `class` is the local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, class) };
        if landed.is_null() || unsafe { pending(env) } {
            return Err(());
        }
        // 可选：老的 Landing 实现没有 members 方法时，接口的 default 实现也在 class 上，
        // 所以正常都能查到；查不到就当「这台设备少一句成员进度」。
        let members_name = c_str("members");
        let members_signature = c_str("(II)V");
        // SAFETY: same class object, valid for the call.
        let class = unsafe { (entry!(table, GetObjectClass))(env, object) };
        let members = if class.is_null() {
            std::ptr::null_mut()
        } else {
            let id = unsafe {
                (entry!(table, GetMethodID))(
                    env,
                    class,
                    members_name.as_ptr(),
                    members_signature.as_ptr(),
                )
            };
            unsafe { (entry!(table, DeleteLocalRef))(env, class) };
            id
        };
        if unsafe { pending(env) } {
            unsafe { (entry!(table, ExceptionClear))(env) };
        }
        Ok(Landing {
            env,
            object,
            landed,
            members,
            failed: false,
        })
    }
}

/// The C ABI's landing callback, backed by `Landing.landed`.
///
/// Runs on the thread serving the connection, from inside the inbox's write loop. A null
/// context means nobody is watching, which is not an error: the two exports differ only in
/// whether a `Landing` existed to hand over.
///
/// # Safety
///
/// `context` must point at a live [`Landing`].
unsafe extern "C" fn report_landing(
    context: *mut c_void,
    done: u64,
    total: u64,
    members: u32,
    member_total: u32,
) {
    if context.is_null() {
        return;
    }
    // SAFETY: the caller passes the `Landing` this layer created, on the thread serving one
    // connection, so the borrow cannot alias.
    let landing = unsafe { &mut *(context as *mut Landing) };
    if landing.failed {
        return;
    }
    // SAFETY: `landing.env` is valid.
    //
    // A pending exception — from a write that failed a moment ago, say — makes the call
    // below abort the process. The receive is not abandoned for this; the meter simply
    // stops being told.
    if unsafe { pending(landing.env) } {
        landing.failed = true;
        return;
    }
    let table = unsafe { table(landing.env) };
    // SAFETY: `landed` is `Landing.landed(JJ)V` on the object the caller handed over.
    unsafe {
        (entry!(table, CallVoidMethod))(
            landing.env,
            landing.object,
            landing.landed,
            done as jlong,
            total as jlong,
        )
    };
    // SAFETY: `landing.env` is valid.
    if unsafe { pending(landing.env) } {
        // The exception stays pending: it is the Java caller's, and it is the honest report.
        landing.failed = true;
    }
    if !landing.members.is_null() {
        // SAFETY: `members` is `Landing.members(II)V` on the same live object.
        unsafe {
            (entry!(table, CallVoidMethod))(
                landing.env,
                landing.object,
                landing.members,
                members as jint,
                member_total as jint,
            )
        };
        // SAFETY: `landing.env` is valid.
        if unsafe { pending(landing.env) } {
            landing.failed = true;
        }
    }
}

/// One connection: the stream it reads from and the stream it writes to.
///
/// The C ABI gives both callbacks one context, and that is not a compromise here — the
/// connection is the unit the call serves, so its context is the connection's state. The
/// two trampolines below exist because a callback receives a context, not a field: each
/// hands the field's address to the adapter that expects it, which is the same shape
/// `receiveArchive` uses when it passes a lone [`Stream`].
///
/// The streams are replaced in place by [`dial_connection`]: a receiver reads one request
/// per connection, so `/Ask` arrives on a socket of its own and this struct follows it
/// there. That is why they are fields rather than borrows.
struct Connection {
    reader: Stream,
    sink: Sink,
    /// How a second connection is obtained, or `None` when the caller asked for a
    /// single-connection transfer.
    link: Option<Link>,
}

/// A Java `com.oshare.airdrop.Native$Link`: the caller's way of opening another connection.
///
/// Everything that makes a connection — the socket, the TLS handshake, the certificate
/// policy, the timeouts — stays on the Java side, because none of it crosses this boundary
/// as anything but bytes. This struct holds the object and the three method ids needed to
/// turn one call into two fresh streams.
struct Link {
    env: *mut JNIEnv,
    object: jobject,
    /// Cached `Link.connect()Ljava/net/Socket;`, valid as long as `object` is.
    connect: jni_sys::jmethodID,
    /// Cached `Socket.getInputStream()` and `Socket.getOutputStream()`.
    ///
    /// Resolved on `java.net.Socket` rather than on what `connect` returns, because a
    /// caller may hand back an `SSLSocket` — JNI resolves a superclass method id against
    /// any instance of the subclass, which is what makes one lookup enough for both.
    input: jni_sys::jmethodID,
    output: jni_sys::jmethodID,
}

impl Link {
    /// # Safety
    ///
    /// `env` must be valid and `object` a live `com.oshare.airdrop.Native$Link`.
    unsafe fn new(env: *mut JNIEnv, object: jobject) -> Result<Link, ()> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(env) };

        // SAFETY: `object` is a live object.
        let class = unsafe { (entry!(table, GetObjectClass))(env, object) };
        if class.is_null() {
            return Err(());
        }
        let name = c_str("connect");
        let signature = c_str("()Ljava/net/Socket;");
        // SAFETY: `class` is the live class object made above and both strings outlive the
        // call.
        let connect =
            unsafe { (entry!(table, GetMethodID))(env, class, name.as_ptr(), signature.as_ptr()) };
        // SAFETY: `class` is the local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, class) };
        if connect.is_null() || unsafe { pending(env) } {
            return Err(());
        }

        // `java.net.Socket` is a boot class, so `FindClass` resolves it from this thread
        // whatever loader the caller's own class came from.
        let socket_name = c_str("java/net/Socket");
        // SAFETY: the name is a valid class name and the string outlives the call.
        let socket = unsafe { (entry!(table, FindClass))(env, socket_name.as_ptr()) };
        if socket.is_null() || unsafe { pending(env) } {
            return Err(());
        }
        let get_input = c_str("getInputStream");
        let input_signature = c_str("()Ljava/io/InputStream;");
        // SAFETY: `socket` is the live class object made above.
        let input = unsafe {
            (entry!(table, GetMethodID))(env, socket, get_input.as_ptr(), input_signature.as_ptr())
        };
        let get_output = c_str("getOutputStream");
        let output_signature = c_str("()Ljava/io/OutputStream;");
        // SAFETY: as above.
        let output = unsafe {
            (entry!(table, GetMethodID))(
                env,
                socket,
                get_output.as_ptr(),
                output_signature.as_ptr(),
            )
        };
        // SAFETY: `socket` is the local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, socket) };
        if input.is_null() || output.is_null() || unsafe { pending(env) } {
            return Err(());
        }

        Ok(Link {
            env,
            object,
            connect,
            input,
            output,
        })
    }

    /// Asks the caller for another connection and wraps the streams it comes with.
    ///
    /// A Java exception from `connect` is left pending on purpose: it carries the caller's
    /// own diagnosis — a refused TCP connection, a failed handshake — and the shim above is
    /// told not to throw over it, exactly as a failing `InputStream.read` is.
    ///
    /// # Safety
    ///
    /// The environment and the object this was built with must still be live.
    unsafe fn dial(&self) -> Result<(Stream, Sink), ()> {
        // SAFETY: `env` is valid for the whole call, and the table outlives it.
        let table = unsafe { table(self.env) };

        // SAFETY: `object` is live and `connect` is a method of its class.
        let socket =
            unsafe { (entry!(table, CallObjectMethod))(self.env, self.object, self.connect) };
        if socket.is_null() || unsafe { pending(self.env) } {
            return Err(());
        }
        // SAFETY: `socket` is a live `java.net.Socket`, and both ids were resolved on that
        // class.
        let (input, output) = unsafe {
            (
                (entry!(table, CallObjectMethod))(self.env, socket, self.input),
                (entry!(table, CallObjectMethod))(self.env, socket, self.output),
            )
        };
        // The socket object is not held past this point: the two streams keep the
        // connection alive, and closing it belongs to the caller that opened it.
        if input.is_null() || output.is_null() || unsafe { pending(self.env) } {
            return Err(());
        }

        // SAFETY: both are live streams made by the call above, and `env` outlives them.
        // 评审 P0-3：一次一个，失败即退。
        let reader = match unsafe { Stream::new(self.env, input) } {
            Ok(reader) => reader,
            Err(_) => return Err(()),
        };
        let sink = match unsafe { Sink::new(self.env, output) } {
            Ok(sink) => sink,
            Err(_) => return Err(()),
        };
        Ok((reader, sink))
    }
}

/// The C ABI's dial callback: the caller closes the served connection and opens another.
///
/// # Safety
///
/// `context` must point at a live [`Connection`].
unsafe extern "C" fn dial_connection(context: *mut c_void) -> isize {
    if context.is_null() {
        return -1;
    }
    // SAFETY: the caller passes the `Connection` created above, and the C ABI drives the
    // callbacks on one thread, so this cannot overlap a read or a write.
    let connection = unsafe { &mut *(context as *mut Connection) };
    let Some(link) = connection.link.as_ref() else {
        return -1;
    };
    // SAFETY: the link was built against the same environment, which outlives this call.
    let Ok((reader, sink)) = (unsafe { link.dial() }) else {
        return -1;
    };
    // The old pair is dropped here: its local references go, and the caller has closed the
    // socket they read from. Replaced rather than mutated because a [`Stream`] caches the
    // method id of one object.
    connection.reader = reader;
    connection.sink = sink;
    0
}

/// The C ABI's read callback, pointed at the connection's reader.
///
/// # Safety
///
/// `context` must point at a live [`Connection`] and `buf` must be writable for `len` bytes.
unsafe extern "C" fn read_connection(context: *mut c_void, buf: *mut u8, len: usize) -> isize {
    if context.is_null() {
        return -1;
    }
    // SAFETY: the caller passes the `Connection` created above, and the C ABI drives the
    // callbacks on one thread, so the borrows the two trampolines take cannot overlap.
    let connection = unsafe { &mut *(context as *mut Connection) };
    // SAFETY: `buf` and `len` come from the caller unchanged, and the field's address is
    // valid for the call.
    unsafe {
        read_stream(
            &mut connection.reader as *mut Stream as *mut c_void,
            buf,
            len,
        )
    }
}

/// The C ABI's write callback, pointed at the connection's sink.
///
/// # Safety
///
/// `context` must point at a live [`Connection`] and `buf` must be readable for `len` bytes.
unsafe extern "C" fn write_connection(context: *mut c_void, buf: *const u8, len: usize) -> isize {
    if context.is_null() {
        return -1;
    }
    // SAFETY: as above.
    let connection = unsafe { &mut *(context as *mut Connection) };
    // SAFETY: as above, for the sink.
    unsafe { write_sink(&mut connection.sink as *mut Sink as *mut c_void, buf, len) }
}

/// 可空串的读取：null 是文档化的「省略」（默认值 / 不上报机型），不是错误。
///
/// 返回 `None` 表示已经向 JVM 抛了异常，入口直接返回空指针。九个入口逐字重复的
/// 同一段 if/match 收在这里（评审 4.1：`Args::decode` 的种子）。
///
/// # Safety
///
/// `env` 必须有效，`value` 是 JVM 传来的有效引用或 null。
unsafe fn read_optional_string(env: *mut JNIEnv, value: jstring) -> Option<Option<String>> {
    if value.is_null() {
        return Some(None);
    }
    // SAFETY: `value` is a live `java.lang.String` and `env` outlives the call.
    Some(unsafe { read_string(env, value) })
}

/// 解两个必填串与四个可空串：`host`/`transfer_id` 必填，其余 `None` 即省略。
///
/// 返回 `None` 表示已经向 JVM 抛了异常，入口直接返回空指针。两个 send 入口的
/// 前六段参数解码逐字相同（评审 4.1：约 90 行重复），收在这里。
///
/// # Safety
///
/// `env` 与各 jstring 必须是 JVM 传进来的有效引用；`value` 为 null 时会先被拦下。
/// [`read_send_strings`] 的返回形状：两个必填串加四个可空串。
type SendStrings = (
    String,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
);

unsafe fn read_send_strings(
    env: *mut JNIEnv,
    host: jstring,
    settings: jstring,
    sender: jstring,
    model: jstring,
    sender_id: jstring,
    transfer_id: jstring,
) -> Option<SendStrings> {
    // SAFETY: both are live `java.lang.String`s, per the JVM's call, and null is taken
    // before `read_string`, which would refuse it.
    let host = unsafe { read_string(env, host) }?;
    let transfer_id = unsafe { read_string(env, transfer_id) }?;
    let optional = |value: jstring| unsafe { read_optional_string(env, value) };
    let settings = optional(settings)?;
    let sender = optional(sender)?;
    let model = optional(model)?;
    let sender_id = optional(sender_id)?;
    Some((host, transfer_id, settings, sender, model, sender_id))
}

/// 可空串的指针与长度：`None` 传 null/0，`opairdrop_send` 那一层认这个形状。
fn slice_of(text: &Option<String>) -> (*const u8, usize) {
    match text {
        Some(text) => (text.as_ptr(), text.len()),
        None => (ptr::null(), 0),
    }
}

/// 建两条流与可选的换线器，包成 [`Connection`]。返回 `None` 表示已经向 JVM 抛了异常。
///
/// 评审 P0-3 的顺序在这里：`Stream::new` 失败（异常挂起）后不再发 `Sink::new` 的
/// JNI 调用。两个 send 入口逐字相同，收在这里。
///
/// # Safety
///
/// `env` 与 `input`/`output`/`link` 必须是 JVM 传进来的有效引用。
unsafe fn build_connection(
    env: *mut JNIEnv,
    input: jobject,
    output: jobject,
    link: jobject,
) -> Option<Connection> {
    // SAFETY: both are live streams and `env` outlives this call.
    let reader = match unsafe { Stream::new(env, input) } {
        Ok(reader) => reader,
        Err(_) => return None,
    };
    if unsafe { pending(env) } {
        return None;
    }
    let sink = match unsafe { Sink::new(env, output) } {
        Ok(sink) => sink,
        Err(_) => return None,
    };
    if unsafe { pending(env) } {
        return None;
    };
    // A null `Link` means the caller asked for a single-connection transfer, which is a
    // request the receiver will not answer — so the library reports it rather than waiting
    // out a read timeout. See `dial_connection`.
    let link = if link.is_null() {
        None
    } else {
        // SAFETY: `link` is a live object and `env` outlives this call.
        match unsafe { Link::new(env, link) } {
            Ok(link) => Some(link),
            Err(()) => return None,
        }
    };
    Some(Connection { reader, sink, link })
}

/// Reads a `java.lang.String` as UTF-16 and converts it, throwing if it is not a string.
///
/// # Safety
///
/// `env` must be valid and `value` a live `java.lang.String`.
unsafe fn read_string(env: *mut JNIEnv, value: jstring) -> Option<String> {
    // SAFETY: `env` is valid for the whole call.
    let table = unsafe { table(env) };
    // SAFETY: `value` is a live string.
    let length = unsafe { (entry!(table, GetStringLength))(env, value) };
    if length < 0 {
        return None;
    }
    let mut units = vec![0u16; length as usize];
    // SAFETY: `units` has room for `length` UTF-16 code units.
    unsafe { (entry!(table, GetStringRegion))(env, value, 0, length, units.as_mut_ptr()) };
    // SAFETY: `env` is valid.
    if unsafe { pending(env) } {
        return None;
    }
    match String::from_utf16(&units) {
        Ok(text) => Some(text),
        Err(_) => {
            // A lone surrogate is not a path any filesystem can hold, and silently
            // replacing it would create a file under a name the caller never asked for.
            // SAFETY: `env` is valid.
            unsafe {
                throw(
                    env,
                    "java/lang/IllegalArgumentException",
                    "the inbox path is not a well-formed string",
                )
            };
            None
        }
    }
}

/// Reads a `java.lang.String[]` as one NUL-separated blob.
///
/// The C ABI takes paths as a single region — a path cannot contain NUL, so NUL separates
/// without escaping — and Java's natural way to say "these files" is an array. This is
/// where the two meet, and it is the only place that has to know both.
///
/// Each element's local reference is deleted as soon as it has been read: a share of a few
/// thousand files would otherwise fill the 512-entry table before the loop ended. That is
/// the same limit that keeps names from crossing as a `String[]` in the other direction,
/// and here it is avoided rather than documented as a ceiling.
///
/// # Safety
///
/// `env` must be valid and `array` a live `java.lang.String[]`.
unsafe fn read_paths(env: *mut JNIEnv, array: jobjectArray) -> Option<String> {
    // SAFETY: `env` is valid for the whole call.
    let table = unsafe { table(env) };
    // SAFETY: `array` is a live array.
    let length = unsafe { (entry!(table, GetArrayLength))(env, array) };
    if length <= 0 {
        // SAFETY: `env` is valid.
        unsafe {
            throw(
                env,
                "java/lang/IllegalArgumentException",
                "at least one path is required",
            )
        };
        return None;
    }

    let mut joined = String::new();
    for index in 0..length {
        // SAFETY: `index` is inside the array, and the element is a live reference this
        // frame owns from here on.
        let element = unsafe { (entry!(table, GetObjectArrayElement))(env, array, index) };
        if element.is_null() {
            // SAFETY: `env` is valid.
            unsafe {
                throw(
                    env,
                    "java/lang/NullPointerException",
                    "a path in the array is null",
                )
            };
            return None;
        }
        // SAFETY: the elements of a `String[]` are `java.lang.String`s, per the signature.
        let text = unsafe { read_string(env, element as jstring) };
        // SAFETY: `element` is a local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, element) };
        let text = text?;
        if !joined.is_empty() {
            joined.push('\0');
        }
        joined.push_str(&text);
    }
    Some(joined)
}

/// Reads a `String[]` as one newline-separated blob.
///
/// Both of the arrays the responder takes are lines — TXT entries as `key=value`, addresses
/// as text — and neither a key nor an address can hold a newline, so this needs no escaping
/// where [`read_paths`] needs NUL. A null array is the empty list rather than an error: a
/// device with no address yet publishes what it has.
///
/// # Safety
///
/// `env` must be valid and `array` a live `java.lang.String[]` or null.
unsafe fn read_lines(env: *mut JNIEnv, array: jobjectArray) -> Option<String> {
    if array.is_null() {
        return Some(String::new());
    }
    // SAFETY: `env` is valid for the whole call.
    let table = unsafe { table(env) };
    // SAFETY: `array` is a live array.
    let length = unsafe { (entry!(table, GetArrayLength))(env, array) };

    let mut joined = String::new();
    for index in 0..length {
        // SAFETY: `index` is inside the array, and the element is a live reference this
        // frame owns from here on.
        let element = unsafe { (entry!(table, GetObjectArrayElement))(env, array, index) };
        if element.is_null() {
            // SAFETY: `env` is valid.
            unsafe {
                throw(
                    env,
                    "java/lang/NullPointerException",
                    "an entry in the array is null",
                )
            };
            return None;
        }
        // SAFETY: the elements of a `String[]` are `java.lang.String`s, per the signature.
        let text = unsafe { read_string(env, element as jstring) };
        // SAFETY: `element` is a local reference made above.
        unsafe { (entry!(table, DeleteLocalRef))(env, element) };
        if index > 0 {
            joined.push('\n');
        }
        joined.push_str(&text?);
    }
    Some(joined)
}

/// The pointer and length a C ABI call takes for `text`, or null for an empty one.
fn region(text: &str) -> (*const u8, usize) {
    if text.is_empty() {
        (ptr::null(), 0)
    } else {
        (text.as_ptr(), text.len())
    }
}

/// Copies a `byte[]` into a `Vec<u8>`.
///
/// A packet is copied rather than pinned: the C ABI wants a plain region, and the copy is a
/// four-kilobyte memcpy on a path that runs once per answer.
///
/// # Safety
///
/// `env` must be valid and `array` a live `byte[]`.
unsafe fn read_bytes(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    // SAFETY: `env` is valid for the whole call.
    let table = unsafe { table(env) };
    // SAFETY: `array` is a live array.
    let length = unsafe { (entry!(table, GetArrayLength))(env, array) };
    if length <= 0 {
        // An empty packet is not a packet; the parser would refuse it a frame later with a
        // message about a truncated header, which names the wrong thing.
        // SAFETY: `env` is valid.
        unsafe {
            throw(
                env,
                "java/lang/IllegalArgumentException",
                "the packet is empty",
            )
        };
        return None;
    }
    let mut bytes = vec![0u8; length as usize];
    // SAFETY: `bytes` has room for `length` and `array` holds at least that many.
    unsafe {
        (entry!(table, GetByteArrayRegion))(env, array, 0, length, bytes.as_mut_ptr() as *mut jbyte)
    };
    // SAFETY: `env` is valid.
    if unsafe { pending(env) } {
        return None;
    }
    Some(bytes)
}

/// Copies bytes into a new `byte[]`.
///
/// # Safety
///
/// `env` must be valid.
unsafe fn new_bytes(env: *mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    // A JNI call made while an exception is already waiting aborts the process: CheckJNI
    // answers "JNI NewByteArray called with pending exception" and the whole app goes down,
    // not just this connection. Measured — a peer that reset after the handshake left
    // `SocketException: Connection reset` pending from the reader and killed the process
    // here while copying out an empty report.
    //
    // Returning null keeps that exception, which is the one the Java caller should see. The
    // array is not made, so nothing is leaked: the caller gets null and throws.
    // SAFETY: `env` is valid.
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    // SAFETY: `env` is valid for the whole call.
    let table = unsafe { table(env) };
    // SAFETY: a fresh local array of the given length.
    let array = unsafe { (entry!(table, NewByteArray))(env, bytes.len() as jsize) };
    if array.is_null() {
        return ptr::null_mut();
    }
    // SAFETY: `array` has room for `bytes.len()` and the source slice is that long.
    unsafe {
        (entry!(table, SetByteArrayRegion))(
            env,
            array,
            0,
            bytes.len() as jsize,
            bytes.as_ptr() as *const jbyte,
        )
    };
    // SAFETY: `env` is valid.
    if unsafe { pending(env) } {
        return ptr::null_mut();
    }
    array
}

/// Raises `class` with `message`.
///
/// `message` is passed through as UTF-8. JNI wants modified UTF-8, which differs only for
/// a NUL (impossible here, the messages name paths and sizes) and for characters outside
/// the basic plane, which would come out garbled rather than lost. A diagnostic is worth
/// more than the rule that would keep it exact.
///
/// # Safety
///
/// `env` must be valid.
unsafe fn throw(env: *mut JNIEnv, class: &str, message: &str) {
    // SAFETY: `env` is valid for the whole call.
    let table = unsafe { table(env) };
    // A class the JVM cannot find raises `NoClassDefFoundError` by itself, which is a
    // better diagnosis than returning silently.
    let class = CString::new(class).unwrap_or_else(|_| c_str("java/lang/RuntimeException"));
    let text = CString::new(message)
        .unwrap_or_else(|_| c_str("the operation failed, and its message could not be passed"));
    // SAFETY: `env` is valid and both strings outlive the call.
    let found = unsafe { (entry!(table, FindClass))(env, class.as_ptr()) };
    if found.is_null() {
        return;
    }
    // SAFETY: `found` is a live local class reference.
    unsafe { (entry!(table, ThrowNew))(env, found, text.as_ptr()) };
    // SAFETY: `found` is the local reference made above, and the throw has taken what it
    // needs from it.
    unsafe { (entry!(table, DeleteLocalRef))(env, found) };
}

/// True when the JVM has an exception waiting for the caller.
///
/// # Safety
///
/// `env` must be valid.
unsafe fn pending(env: *mut JNIEnv) -> bool {
    // SAFETY: `env` is valid.
    // SAFETY: `env` is valid. `ExceptionCheck` answers with a `jboolean`, which is a byte
    // in this ABI and not Rust's `bool`.
    unsafe { (entry!(table(env), ExceptionCheck))(env) != 0 }
}

/// Releases a local reference.
///
/// # Safety
///
/// `env` must be valid and `reference` a local reference that has not been released.
unsafe fn delete_local(env: *mut JNIEnv, reference: jobject) {
    if reference.is_null() {
        return;
    }
    // SAFETY: `reference` is a live local reference.
    unsafe { (entry!(table(env), DeleteLocalRef))(env, reference) };
}

/// The function table the JVM handed us.
///
/// # Safety
///
/// `env` must be valid for the lifetime of the borrow.
unsafe fn table<'a>(env: *mut JNIEnv) -> &'a JNINativeInterface_ {
    // SAFETY: `env` is a `JNIEnv`, which is a pointer to the table, and the table itself
    // outlives any call.
    unsafe { &**env }
}

/// A static C string, for the JNI entry points that take one.
fn c_str(text: &'static str) -> CString {
    CString::new(text).expect("a static string has no interior NUL")
}
