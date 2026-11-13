//! Writes the archive the JVM round-trip hands to the library.
//!
//! `check.sh` runs this first, then passes the file and the names it prints to
//! `com.oshare.airdrop.RoundTrip`, so the Java side asserts against the archive that was
//! actually written rather than against a second, hand-kept description of it.
//!
//! The contents are chosen to be awkward on purpose: a directory member, a member inside
//! it, and a name in a script the wire format has no opinion about. Nothing here is large —
//! the point is the path, not the payload.

use std::env;
use std::fs;
use std::process::ExitCode;

use opairdrop_cpio::{Entry, Format};

fn main() -> ExitCode {
    let Some(destination) = env::args().nth(1) else {
        eprintln!("usage: fixture <archive-path>");
        return ExitCode::FAILURE;
    };

    let entries = [
        Entry::file("hello.txt", b"hello air drop\n".to_vec()),
        Entry::directory("photos"),
        Entry::file("photos/one.jpg", vec![0xAB; 300]),
        Entry::directory("假期"),
        Entry::file("假期/照片.jpg", b"\xFF\xD8\xFF\xE0".to_vec()),
    ];

    // `Odc` rather than `Newc`: UP-2 requires a sender to send 070707, so this is the
    // shape the receiver meets on a real wire.
    let bytes = match opairdrop_cpio::to_vec(&entries, Format::Odc) {
        Ok(bytes) => bytes,
        Err(error) => {
            eprintln!("the fixture archive could not be built: {error}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(error) = fs::write(&destination, &bytes) {
        eprintln!("{destination} could not be written: {error}");
        return ExitCode::FAILURE;
    }

    // Names as the reader sees them, one per line: the caller compares these with the
    // names the library reports back through the JVM.
    match opairdrop_cpio::read(&bytes) {
        Ok(read_back) => {
            for entry in read_back {
                println!("{}", entry.name);
            }
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("the fixture archive does not read back: {error}");
            ExitCode::FAILURE
        }
    }
}
