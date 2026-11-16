//! The boundary Android calls: a C ABI for anything that can hold pointers, and a JNI
//! shim over it for the app.
//!
//! # Nothing structured crosses
//!
//! Every export takes bytes and returns bytes or a scalar. No `java.util.Map` is ever
//! built from here, and no `java.lang.String[]` either. That is a deliberate break with
//! the predecessor, whose `Bplist.parse` returned a `Map<String, Object>` that the caller
//! then walked with `string(map, key)` / `flag(map, key, fallback)` / `list(map, key)`:
//!
//! * A map crossing the boundary has no schema, so the keys are re-derived by string
//!   literal at every call site. Fourteen of them in the legacy client, and a typo in one
//!   was silent — `flag(map, "IsAirDropable", false)` and `flag(map, "IsAirdropable",
//!   false)` are both valid programs and only one of them finds the key.
//! * A member list crossing as an array of references exhausts the JVM's local reference
//!   table at 512 entries. A share of more than a few hundred files would fail inside the
//!   JNI layer with an error that names nothing.
//!
//! So the codecs stay inside Rust, where they are typed and already tested, and what
//! crosses is the answer to a question a caller actually asked.
//!
//! # Ownership
//!
//! A buffer this library fills is Rust memory. The caller releases it with
//! [`api::opairdrop_buffer_free`] and with nothing else — in particular not `free`, and
//! not twice with different addresses. `free` zeroes the struct it is given, so a struct
//! can be filled, released, and filled again, and releasing a zeroed struct does nothing.
//!
//! # Failures
//!
//! Every export returns a non-negative status on success and a negative one on failure;
//! the constants are in [`api`]. On failure the same out-buffer holds a human-readable
//! message as UTF-8. Two out-parameters would have meant two releases for the caller to
//! keep straight, and a failure is exactly when a caller is least likely to get that
//! right.
//!
//! A panic in here aborts the process. That is the honest outcome: the release profile is
//! built with `panic = "abort"`, every failure that can be foreseen is a `Result`, and an
//! abort is loud in a way that unwinding across the boundary is not.

#![deny(unsafe_op_in_unsafe_fn)]
#![deny(missing_docs)]

pub mod api;
pub mod ble;
pub mod browse;
pub mod buffer;
pub mod jni;
pub mod respond;
pub mod send;
pub mod serve;
pub mod settings;

pub use api::{Report, ABI_VERSION};
pub use buffer::OpairdropBuffer;
