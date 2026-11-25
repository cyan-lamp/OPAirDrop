//! The tunnel the kernel's IPv6 stack sees as a network interface.
//!
//! # Why a tunnel and not the vif
//!
//! The obvious thing is to let the kernel use `wonder0` directly: it is a real interface,
//! it has a link-local address, and a `ServerSocket` binds to it without help. That was
//! tried, and the discovery test that followed proved it wrong in a way that is easy to
//! miss — the request was answered, the answer was two hundred bytes on `wonder0`, and not
//! one frame left the radio, because `wonder0` is a mac80211 vif and the AWDL path this
//! project drives does not go through mac80211's transmit at all.
//!
//! So the interface the kernel uses has to be one whose every packet this process can see.
//! That is a TUN device: writing to the descriptor is the only way a packet reaches it, so
//! a packet that is written is a packet that gets framed and transmitted, and nothing is
//! silently handled by a stack that assumes a distribution system.
//!
//! # `IFF_NO_PI`
//!
//! Without it, every read and write carries a four-byte prefix naming the protocol. With
//! it, the descriptor carries bare IP packets, which is what the frame builder wants and
//! what keeps a stray prefix from being framed as payload.

use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::raw::{c_char, c_void};

use crate::link::Tunnel;

/// A TUN interface with its descriptor.
pub struct TunDevice {
    fd: OwnedFd,
    name: String,
}

impl TunDevice {
    /// Create (or attach to) the TUN interface called `name`.
    ///
    /// Requires `CAP_NET_ADMIN`. Android puts the device node at `/dev/tun`; a desktop
    /// kernel puts it at `/dev/net/tun`, and both are tried so the binary is runnable on
    /// either without a flag.
    pub fn open(name: &str) -> io::Result<Self> {
        let mut last = None;
        for path in ["/dev/tun", "/dev/net/tun"] {
            match Self::open_at(path, name) {
                Ok(device) => return Ok(device),
                Err(err) => last = Some(err),
            }
        }
        Err(last.unwrap_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no TUN device node")))
    }

    fn open_at(path: &str, name: &str) -> io::Result<Self> {
        let path_c = CString::new(path)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path has a NUL"))?;
        // SAFETY: `path_c` is a live NUL-terminated string, and the flags are the
        // documented ones for a non-blocking descriptor that survives an exec.
        let fd = unsafe {
            libc::open(
                path_c.as_ptr(),
                libc::O_RDWR | libc::O_NONBLOCK | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `fd` is a freshly opened descriptor that nothing else owns.
        let fd = unsafe { OwnedFd::from_raw_fd(fd) };

        if name.len() >= libc::IFNAMSIZ {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!(
                    "interface name {name} is longer than {}",
                    libc::IFNAMSIZ - 1
                ),
            ));
        }

        let mut req: libc::ifreq = unsafe { std::mem::zeroed() };
        for (slot, byte) in req.ifr_name.iter_mut().zip(name.as_bytes()) {
            *slot = *byte as c_char;
        }
        // Writing a union field is safe in Rust — only reading one is not — and this is
        // the flags half of `ifr_ifru`, which is what `TUNSETIFF` reads.
        req.ifr_ifru.ifru_flags = (libc::IFF_TUN | libc::IFF_NO_PI) as libc::c_short;
        // SAFETY: `req` is a fully initialised `ifreq` and the request is `TUNSETIFF`,
        // whose argument is exactly that structure.
        let rc = unsafe { libc::ioctl(fd.as_raw_fd(), libc::TUNSETIFF as _, &mut req) };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }

        Ok(TunDevice {
            fd,
            name: name.to_string(),
        })
    }

    /// The interface name the kernel gave it.
    pub fn name(&self) -> &str {
        &self.name
    }

    /// The descriptor, for a caller that has to poll it alongside the radio's.
    pub fn fd(&self) -> RawFd {
        self.fd.as_raw_fd()
    }
}

impl Tunnel for TunDevice {
    fn recv(&mut self, buf: &mut [u8]) -> io::Result<Option<usize>> {
        // SAFETY: `buf` is a live mutable slice and the length passed is its own.
        let n = unsafe {
            libc::read(
                self.fd.as_raw_fd(),
                buf.as_mut_ptr() as *mut c_void,
                buf.len(),
            )
        };
        if n < 0 {
            let err = io::Error::last_os_error();
            return match err.kind() {
                io::ErrorKind::WouldBlock => Ok(None),
                io::ErrorKind::Interrupted => Ok(None),
                _ => Err(err),
            };
        }
        Ok(Some(n as usize))
    }

    fn send(&mut self, packet: &[u8]) -> io::Result<()> {
        // SAFETY: `packet` is a live slice and the length passed is its own.
        let n = unsafe {
            libc::write(
                self.fd.as_raw_fd(),
                packet.as_ptr() as *const c_void,
                packet.len(),
            )
        };
        if n < 0 {
            return Err(io::Error::last_os_error());
        }
        if (n as usize) != packet.len() {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                format!("wrote {n} of {} bytes", packet.len()),
            ));
        }
        Ok(())
    }
}
