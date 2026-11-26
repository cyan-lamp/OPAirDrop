//! `AF_PACKET` on the interface the driver hands raw 802.11 to.
//!
//! # Which interface
//!
//! `wondertap0`, not `wonder0`. `wonder.ko` is loaded with `physical_name=wondertap0
//! virtual_name=wonder0`: the first is the physical tap that carries whole frames
//! including radiotap, the second is a mac80211 vif that carries what mac80211 has already
//! decided to keep. Injecting on `wonder0` means letting the ordinary TX path build the
//! frame, which is exactly the path that drops a passthru frame for a destination it owns
//! no peer for.
//!
//! `opairsharerx0` is a decoy: a monitor interface whose rings are only populated when
//! `passthru_mon_rings` is non-zero, which it is not in the configuration this project
//! loads. Capturing on it returns nothing and has been mistaken for "the air is quiet"
//! once already.
//!
//! # The frame this hands back
//!
//! A whole 802.11 frame with its radiotap header, because that is what `wonder-ven`
//! arranges and what the driver expects on the way in. The radiotap header is read for
//! its length, never for its contents.

use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::raw::c_void;

use crate::link::Radio;

/// A raw 802.11 socket bound to one interface.
pub struct RawSocket {
    fd: OwnedFd,
    ifname: String,
    ifindex: u32,
}

impl RawSocket {
    /// Bind to `ifname`, which must already exist and be up.
    ///
    /// Requires `CAP_NET_RAW`; as the app this fails with `EPERM`, which is why the
    /// binary that calls it runs as root.
    pub fn open(ifname: &str) -> io::Result<Self> {
        let name = CString::new(ifname)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "interface name has a NUL"))?;
        // SAFETY: `name` is a live NUL-terminated string for the duration of the call.
        let ifindex = unsafe { libc::if_nametoindex(name.as_ptr()) };
        if ifindex == 0 {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("no interface named {ifname}"),
            ));
        }

        // ETH_P_ALL in network byte order. Both the socket argument and `sll_protocol`
        // are compared against an `skb->protocol`, which is stored big-endian, so a host
        // that wrote the bare constant would bind nothing on a little-endian machine.
        let protocol = i32::from(u16::from_be(libc::ETH_P_ALL as u16));
        // SAFETY: the three arguments are the documented constants for an 802.3 socket;
        // the returned descriptor is checked below and owned by `OwnedFd` from then on.
        let fd = unsafe {
            libc::socket(
                libc::AF_PACKET,
                libc::SOCK_RAW | libc::SOCK_NONBLOCK | libc::SOCK_CLOEXEC,
                protocol,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `fd` is a freshly created descriptor that nothing else owns.
        let fd = unsafe { OwnedFd::from_raw_fd(fd) };

        let mut addr: libc::sockaddr_ll = unsafe { std::mem::zeroed() };
        addr.sll_family = libc::AF_PACKET as u16;
        addr.sll_protocol = u16::from_be(libc::ETH_P_ALL as u16);
        addr.sll_ifindex = ifindex as i32;
        // SAFETY: `addr` is initialised as a `sockaddr_ll` and the length passed is its
        // own size, which is what `bind` expects for an `AF_PACKET` socket.
        let rc = unsafe {
            libc::bind(
                fd.as_raw_fd(),
                &addr as *const libc::sockaddr_ll as *const libc::sockaddr,
                std::mem::size_of::<libc::sockaddr_ll>() as libc::socklen_t,
            )
        };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }

        // 接收缓冲（2026-11-26）：这里原来是内核默认的 ~208 KiB，按一帧 1512 字节算是
        // 137 帧、按实测 340 帧/秒只够缓冲 0.4 秒。主循环只要停一下（写 tun 阻塞、发一次
        // presence、调度抖动），排队的帧就被丢掉；AF_PACKET 丢帧对 TCP 是静默的，接下来
        // 就是重传和窗口收缩——表现成「链路只有 450 KB/s」，然后在 20 秒的边缘被对端放弃。
        // 4 MiB 是八秒的量。SO_RCVBUFFORCE 跳过 `net.core.rmem_max`（这个进程是 root，
        // 有 CAP_NET_ADMIN）；没有权限时退到普通 SO_RCVBUF，至少翻倍。
        let buffer: libc::c_int = 4 * 1024 * 1024;
        // SAFETY: `fd` is live, and the option buffer is a `c_int` of the stated length.
        let forced = unsafe {
            libc::setsockopt(
                fd.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_RCVBUFFORCE,
                &buffer as *const libc::c_int as *const libc::c_void,
                std::mem::size_of::<libc::c_int>() as libc::socklen_t,
            )
        };
        if forced < 0 {
            // SAFETY: as above; `SO_RCVBUF` takes the same buffer.
            unsafe {
                libc::setsockopt(
                    fd.as_raw_fd(),
                    libc::SOL_SOCKET,
                    libc::SO_RCVBUF,
                    &buffer as *const libc::c_int as *const libc::c_void,
                    std::mem::size_of::<libc::c_int>() as libc::socklen_t,
                )
            };
        }

        Ok(RawSocket {
            fd,
            ifname: ifname.to_string(),
            ifindex,
        })
    }

    /// The interface this socket is bound to.
    pub fn ifname(&self) -> &str {
        &self.ifname
    }

    /// Its index, which a capture or a log entry needs to be unambiguous.
    pub fn ifindex(&self) -> u32 {
        self.ifindex
    }
}

impl AsRawFd for RawSocket {
    /// The descriptor to poll on. Reading it through `recv` is [`Radio::recv`]'s job; this
    /// exists so a loop can wait on the radio and the tunnel in one `poll`.
    fn as_raw_fd(&self) -> RawFd {
        self.fd.as_raw_fd()
    }
}

impl Radio for RawSocket {
    fn recv(&mut self, buf: &mut [u8]) -> io::Result<Option<usize>> {
        // SAFETY: `buf` is a live mutable slice and the length passed is its own.
        let n = unsafe {
            libc::recv(
                self.fd.as_raw_fd(),
                buf.as_mut_ptr() as *mut c_void,
                buf.len(),
                0,
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

    fn send(&mut self, frame: &[u8]) -> io::Result<()> {
        // SAFETY: `frame` is a live slice and the length passed is its own.
        let n = unsafe {
            libc::send(
                self.fd.as_raw_fd(),
                frame.as_ptr() as *const c_void,
                frame.len(),
                0,
            )
        };
        if n < 0 {
            return Err(io::Error::last_os_error());
        }
        if (n as usize) != frame.len() {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                format!("wrote {n} of {} bytes", frame.len()),
            ));
        }
        Ok(())
    }
}
