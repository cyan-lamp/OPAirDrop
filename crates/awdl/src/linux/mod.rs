//! The two descriptors the link runs on, and the wait that joins them.
//!
//! This is the only part of the crate that is not portable, and the split is deliberate:
//! everything above it is a pure function of bytes, so the wire format is tested on a
//! workstation and only the syscalls need a phone.
//!
//! Both descriptors are opened non-blocking. The loop waits on them together rather than
//! on either alone, because a bridge that blocks reading the radio cannot notice a packet
//! waiting to go out, and at 110 time units between presence frames that is a second of
//! added latency on the first byte of a transfer.

use std::io;
use std::os::fd::RawFd;

mod packet;
mod tun;

pub use packet::RawSocket;
pub use tun::TunDevice;

/// Wait up to `timeout_ms` for any of `fds` to become readable.
///
/// Returns one flag per descriptor in the same order. A descriptor that hung up reports
/// readable, so the caller's next read gets the error rather than a silent zero.
pub fn poll_readable(fds: &[RawFd], timeout_ms: i32) -> io::Result<Vec<bool>> {
    let mut pollfds: Vec<libc::pollfd> = fds
        .iter()
        .map(|&fd| libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        })
        .collect();
    // SAFETY: `pollfds` is a valid, initialised array of `pollfd` whose length is passed
    // alongside it, and `poll` writes only to `revents` inside that array.
    let rc = unsafe {
        libc::poll(
            pollfds.as_mut_ptr(),
            pollfds.len() as libc::nfds_t,
            timeout_ms,
        )
    };
    if rc < 0 {
        let err = io::Error::last_os_error();
        if err.kind() == io::ErrorKind::Interrupted {
            return Ok(vec![false; fds.len()]);
        }
        return Err(err);
    }
    if rc == 0 {
        return Ok(vec![false; fds.len()]);
    }
    Ok(pollfds
        .iter()
        .map(|p| p.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0)
        .collect())
}
