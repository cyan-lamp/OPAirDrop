//! `awdld` — the process that owns the radio.
//!
//! # Why this is a separate program
//!
//! It needs `CAP_NET_RAW` (an `AF_PACKET` socket) and `CAP_NET_ADMIN` (creating a TUN
//! interface). An Android app has neither. The predecessor solved this by running its
//! whole data plane through `su -c app_process`, which meant the protocol lived in a
//! process nobody could debug with a debugger.
//!
//! This binary takes the opposite division: it knows how to move an IPv6 packet between a
//! tunnel and a radio and nothing else. Everything about AirDrop — the request, the plist,
//! the archive — happens on the far side of the tunnel, in the app, where a normal
//! process can bind a socket.
//!
//! ```text
//!   app process                     this process (root)
//!   ┌──────────────────┐            ┌──────────────────────┐
//!   │ airdrop protocol │            │ awdl                 │
//!   │ ServerSocket 8770│            │  tun <-> radio       │
//!   └────────┬─────────┘            └──────────┬───────────┘
//!            │  fd: awdltun0                    │ fd: wondertap0
//!            └──────────────────────────────────┘
//! ```
//!
//! # Usage
//!
//! ```text
//!   awdld [--iface wondertap0] [--tun awdltun0] [--name HOST]
//!         [--channel 149] [--rate 36] [--uid N] [--burst N] [--no-live] [--quiet]
//! ```
//!
//! `--uid` names the process the tunnel belongs to. It is what the policy rule that makes
//! `fe80::` reachable is keyed on, and without it the tunnel is addressed but unreachable.
//!
//! `--burst` sets how many availability windows of one slot a small packet is repeated
//! into, one AW apart. Four is the default and four is what the predecessor measured; the
//! switch exists so the number can be swept on a live link, where a run at one copy and a
//! run at eight say different things about why the peer is missing.
//!
//! It prints `TUN_READY name=... ll=...` once the tunnel has an address, and a status line
//! every five seconds after that.

#[cfg(any(target_os = "linux", target_os = "android"))]
fn main() {
    device::run();
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn main() {
    eprintln!("awdld: this binary needs AF_PACKET and a TUN device; build it for Android or Linux");
    std::process::exit(1);
}

#[cfg(any(target_os = "linux", target_os = "android"))]
mod device {
    use std::io::{self, Write};
    use std::os::fd::{AsRawFd, RawFd};
    use std::process::Command;

    use opairdrop_awdl::linux::{poll_readable, RawSocket, TunDevice};
    use opairdrop_awdl::{Link, Mac, NodeInfo, SOCIAL_CHANNEL};

    /// The largest frame this link will ever see: a full-size 802.11 frame with radiotap.
    const FRAME_BUF: usize = 4096;
    /// The largest packet the tunnel will hand up: an IPv6 packet at the link's MTU.
    const PACKET_BUF: usize = 2048;
    /// The tunnel's MTU: the largest IPv6 packet the air actually carries.
    ///
    /// Not 1500, which is what this said and what the tun offers by default, and the
    /// difference is the whole of the hole that made a notification take three seconds to
    /// appear. A packet of `p` bytes leaves as an 802.11 frame of `p + 54` — radiotap, the
    /// 802.11 header, QoS control, SNAP and the data head — and the driver refuses anything
    /// past 1512 with `EMSGSIZE`, so 1458 is where the air stops accepting and every packet
    /// above it is dropped on the way out with nothing said to TCP. Measured 2026-11-15,
    /// receiving: the iPhone's first request carried 1428 bytes of payload — our advertised
    /// 1440-byte segment less the 12-byte timestamp option, filling the 1500-byte tunnel
    /// MTU — so the packet was 1500 on the wire and the frame 1554, and it did not arrive on
    /// any of four connections. The peer then spent a retransmission timeout (3.1–3.6 s)
    /// before splitting the same bytes into 1188-byte segments, which fit and went through.
    /// That wait is the delay between tapping 隔空投送 and this phone showing the
    /// notification.
    ///
    /// The sender's segment size is not ours to choose directly. It is whatever our SYN-ACK
    /// advertises, and the kernel derives that from this number: MTU less 60 for IPv6 and
    /// TCP. So the value is also the clamp — 1458 advertises 1398, and 1398 bytes leave as
    /// exactly the 1512-byte frame the driver takes. The earlier injection-based build
    /// arrived at the same 1398 from the same arithmetic (`MSS_CEILING` in `AwdlData.java`,
    /// `MAX_TUN_PACKET - 60 - 3`), and its notes — 512 was too small because
    /// the peer sizes its receive window from the MSS it is given, and 200 stalled the link
    /// outright — are worth recollecting before changing this one.
    const TUN_MTU: usize = 1458;
    /// How many to move per wakeup before returning to the poll. A cap rather than a loop
    /// to exhaustion, so a busy direction cannot starve the presence timer.
    const DRAIN: usize = 64;
    /// How often the status line repeats.
    const STATUS_EVERY_US: u64 = 5_000_000;

    struct Args {
        iface: String,
        tun: String,
        name: String,
        channel: u8,
        /// Whether `--channel` was typed, as opposed to inferred from the link.
        ///
        /// Kept separate from `channel` because "149" and "the default, which happens to
        /// be 149" are different statements: the first is an instruction, the second is a
        /// guess that a note beside this binary is allowed to overrule.
        channel_given: bool,
        rate: u8,
        /// The process the tunnel politically belongs to, if one was named.
        ///
        /// The route the tunnel needs lives in a table Android reaches only through a
        /// policy rule, and a rule has to name who it applies to. This process runs as
        /// root and cannot work that out for the app, so it is told.
        uid: Option<u32>,
        /// How many availability windows of a slot a small packet is repeated into.
        ///
        /// Left `None` to take the compiled default. A switch rather than a constant
        /// because it is a property of the peer's listening phase, not of us: the four
        /// windows of a slot are only all needed if the peer is on an unknown one, and
        /// sweeping the count from a shell is the only way to tell "the peer is late"
        /// from "the peer is not on this slot at all".
        burst: Option<u8>,
        /// A directory this process may watch for the user's radio preference, and
        /// write its answer back into.
        ///
        /// A directory rather than a socket because the two halves live in different
        /// security domains and only one direction of that crossing is cheap: the app's
        /// private directory is `drwx------` and owned by the app, so the filesystem
        /// already answers "who may ask" for us. An abstract socket has no such owner
        /// — every app on the phone can connect to it — and would need `SO_PEERCRED` on
        /// top of a policy question this build has not answered.
        ///
        /// Left `None`, no control channel is opened at all and the radio is whatever
        /// `awdl-stack.sh` left it as.
        ctl: Option<String>,
        /// Refuse to answer outside the slot map, i.e. restore the rule as it stood before
        /// [`opairdrop_awdl::link::Link`] started treating freshly heard payload as proof
        /// that the sender is listening.
        ///
        /// Only here to make the two runs of an A/B differ in nothing else. Not a
        /// deployment switch: the default is what fixes the ack latency.
        no_live: bool,
        quiet: bool,
    }

    impl Default for Args {
        fn default() -> Self {
            Args {
                iface: "wondertap0".into(),
                tun: "awdltun0".into(),
                name: hostname(),
                channel: SOCIAL_CHANNEL,
                channel_given: false,
                rate: 36,
                uid: None,
                burst: None,
                ctl: None,
                no_live: false,
                quiet: false,
            }
        }
    }

    pub fn run() {
        if let Err(code) = real_main() {
            std::process::exit(code);
        }
    }

    fn real_main() -> Result<(), i32> {
        let mut args = match parse_args() {
            Ok(args) => args,
            Err(err) => {
                eprintln!("awdld: {err}");
                eprintln!("usage: awdld [--iface NAME] [--tun NAME] [--name HOST] [--channel N] [--rate N] [--uid N] [--burst N] [--ctl DIR] [--no-live] [--quiet]");
                return Err(2);
            }
        };

        // The channel this node announces is what the peer schedules its listening
        // against, so it has to be the channel the radio is reachable on. `--channel`
        // cannot know that — it is whatever the caller typed — and on this phone the
        // caller typed 149 for a whole round while the radio sat on the AP's channel,
        // which cost half the pings. `awdl-stack.sh` does know, because it is the thing
        // that feeds the tap and joins the IBSS, so it leaves the number it used next to
        // this binary. An explicit `--channel` still wins: a sweep has to be able to
        // name the number it is testing.
        let channel_src = if args.channel_given {
            "flag"
        } else {
            match link_freq_channel() {
                Some(channel) => {
                    args.channel = channel;
                    "link-freq"
                }
                None => "social-guess",
            }
        };

        // SAFETY: `geteuid` has no preconditions and touches no memory this process owns.
        let uid = unsafe { libc::geteuid() };
        if uid != 0 {
            // The number is in the message because "must run as root" from a shell that
            // has just printed `uid=0` is a sentence with no next step in it.
            eprintln!("awdld: must run as root (euid={uid}, AF_PACKET and TUN both need capabilities)");
            return Err(1);
        }

        let mac = match read_mac(&args.iface) {
            Ok(mac) => mac,
            Err(err) => {
                eprintln!("awdld: cannot read {}'s address: {err}", args.iface);
                return Err(1);
            }
        };

        let tun = match TunDevice::open(&args.tun) {
            Ok(tun) => tun,
            Err(err) => {
                eprintln!("awdld: cannot open a TUN device: {err}");
                return Err(1);
            }
        };
        if let Err(err) = configure_tun(&args.tun, mac, args.uid) {
            // Not fatal: an interface that is up but unconfigured still carries frames,
            // and a caller may have set it up already.
            eprintln!(
                "awdld: warning: could not configure {}: {err}",
                args.tun
            );
        }
        let local = link_local(&args.tun).ok().flatten();
        println!(
            "TUN_READY name={} ll={} mtu={}",
            args.tun,
            local.as_deref().unwrap_or("none"),
            mtu(&args.tun).unwrap_or(0)
        );

        let radio = match RawSocket::open(&args.iface) {
            Ok(socket) => socket,
            Err(err) => {
                eprintln!("awdld: cannot open {}: {err}", args.iface);
                return Err(1);
            }
        };
        if !args.quiet {
            println!(
                "LINK_UP iface={} ifindex={} mac={} tun={} name={} channel={} rate={}M",
                radio.ifname(),
                radio.ifindex(),
                mac,
                args.tun,
                args.name,
                args.channel,
                args.rate
            );
            // Where the number came from, because the three cases are not
            // interchangeable: `flag` is an instruction, `link-freq` is the channel the
            // radio was actually fed, and `social-guess` is the default nobody verified.
            println!("CHANNEL own={} src={channel_src}", args.channel);
            let _ = io::stdout().flush();
        }
        if channel_src == "social-guess" {
            eprintln!(
                "awdld: no link-freq beside this binary and no --channel: announcing {} \
                 because something has to be announced, not because the radio is there",
                args.channel
            );
        }

        let node = NodeInfo::master_of(mac, args.name.clone(), args.channel);
        let start = now_us();
        let mut link = Link::new(radio, tun, node, start, args.rate);
        if let Some(copies) = args.burst {
            link.set_burst(copies);
        }
        if args.no_live {
            link.set_live_override(false);
        }
        if !args.quiet {
            // Printed because a sweep is read afterwards from logs that all look alike:
            // the run's own statement of how many copies it sent is the only thing that
            // separates a run at one from a run at four.
            println!(
                "BURST copies={} over={}B",
                link.burst(),
                opairdrop_awdl::link::BURST_MAX
            );
            let _ = io::stdout().flush();
        }
        // Printed unconditionally, unlike BURST: a log that does not say which arm of the
        // A/B it came from is a log that cannot be compared against the other arm.
        println!("LIVE override={}", if args.no_live { "off" } else { "on" });
        let _ = io::stdout().flush();

        let radio_fd: RawFd = link.radio().as_raw_fd();
        let tun_fd = link.tunnel().fd();

        let mut frame_buf = vec![0u8; FRAME_BUF];
        let mut packet_buf = vec![0u8; PACKET_BUF];
        let mut last_status = start;
        let mut last_stats = link.stats();
        let mut mismatch_reported = false;
        // Opened here rather than at the top so `RADIO` lands after `LINK_UP` and
        // `CHANNEL`: a log read top to bottom should describe the link before it
        // describes what else is allowed to touch the radio.
        let mut radio = args
            .ctl
            .as_deref()
            .and_then(|dir| radio::Radio::open(dir, args.uid, now_us()));

        // 评审 P1-11：socket 读错误不再一刀切退出。脚本注释记录过 ENETDOWN 实测
        // （awdl-stack.sh:1688），ifindex 还会从 286 变 303——一次接口抖动就把守护
        // 杀掉，等于断网后永远连不回来。分级：瞬时（EINTR/EAGAIN/ENOBUFS）小睡后
        // 继续；接口没了（ENETDOWN/ENODEV）大睡等接口回来（脚本自己的 guard 会在
        // 链路变化时重建环境）；只有 EPERM/EBADF 这类"描述符本身坏了"才退出。
        fn survivable(err: &std::io::Error) -> bool {
            match err.kind() {
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::Interrupted => return true,
                _ => {}
            }
            matches!(err.raw_os_error(), Some(code) if matches!(
                code,
                libc::EINTR | libc::EAGAIN | libc::ENOBUFS
                    | libc::ENETDOWN | libc::ENODEV | libc::ENETRESET
            ))
        }

        loop {
            // Both timers, and the wakeup is whichever comes first. A link that only
            // woke for presence frames would sit on a queued packet for up to a period.
            // Asked as one question rather than a `min` here, because an empty queue
            // answers zero to "when may a frame go" and a zero here would mean "wake
            // immediately" — see `Link::wake_in`.
            let due = link.wake_in(now_us());
            let wait = due.min(100_000) as i32 / 1000;
            let ready = match poll_readable(&[radio_fd, tun_fd], wait.clamp(0, 1000)) {
                Ok(ready) => ready,
                Err(err) => {
                    if survivable(&err) {
                        eprintln!("awdld: poll survived: {err}");
                        std::thread::sleep(std::time::Duration::from_millis(
                            if err.raw_os_error() == Some(libc::ENETDOWN)
                                || err.raw_os_error() == Some(libc::ENODEV) { 2000 } else { 10 },
                        ));
                        continue;
                    }
                    eprintln!("awdld: poll failed: {err}");
                    return Err(1);
                }
            };

            if ready[0] {
                // One instant for the whole drain: a frame's phase is measured against
                // the boundary that was current when it was read, and re-reading the
                // clock per frame would stamp the later ones against a boundary they
                // arrived before.
                let stamp = now_us();
                for _ in 0..DRAIN {
                    match link.down(&mut frame_buf, stamp) {
                        Ok(true) => {}
                        Ok(false) => break,
                        Err(err) => {
                            if survivable(&err) {
                                eprintln!("awdld: radio read survived: {err}");
                                std::thread::sleep(std::time::Duration::from_millis(
                                    if err.raw_os_error() == Some(libc::ENETDOWN)
                                        || err.raw_os_error() == Some(libc::ENODEV) { 2000 } else { 10 },
                                ));
                                break;
                            }
                            eprintln!("awdld: reading the radio failed: {err}");
                            return Err(1);
                        }
                    }
                }
            }
            if ready[1] {
                // One instant for the whole drain, for the reason the radio drain gives:
                // every packet read here was waiting at the same moment, and stamping them
                // one at a time would charge the later ones for the earlier ones' copies.
                let stamp = now_us();
                for _ in 0..DRAIN {
                    match link.up(&mut packet_buf, stamp) {
                        Ok(true) => {}
                        Ok(false) => break,
                        Err(err) => {
                            if survivable(&err) {
                                eprintln!("awdld: tunnel read survived: {err}");
                                std::thread::sleep(std::time::Duration::from_millis(
                                    if err.raw_os_error() == Some(libc::ENETDOWN)
                                        || err.raw_os_error() == Some(libc::ENODEV) { 2000 } else { 10 },
                                ));
                                break;
                            }
                            eprintln!("awdld: reading the tunnel failed: {err}");
                            return Err(1);
                        }
                    }
                }
            }

            if link.announce(now_us()).unwrap_or(false) && !args.quiet {
                // Presence frames are the only sign of life a peer sees, so the first
                // handful are worth a line each.
                if link.stats().presence <= 3 {
                    println!(
                        "PRESENCE #{} slot={} next_tu={} aw={}",
                        link.stats().presence,
                        link.schedule().slot_index(now_us()),
                        link.schedule().next_slot_tu(now_us()),
                        link.schedule().aw_counter(now_us())
                    );
                    let _ = io::stdout().flush();
                }
            }

            // After the presence frames, so data rides the window a PSF has just named
            // rather than competing with it for the boundary.
            link.flush(now_us());

            // Last, and deliberately so. Everything above is a frame that a peer is
            // waiting for; everything below is housekeeping. A radio request that delays
            // a presence frame trades a guaranteed loss for a preference.
            if let Some(radio) = radio.as_mut() {
                radio.tick(now_us());
            }

            let now = now_us();
            if now.saturating_sub(last_status) >= STATUS_EVERY_US {
                last_status = now;
                let stats = link.stats();
                // A channel that changes under us — the STA roamed, or a coex policy
                // moved the tap — leaves every slot in the schedule open at the wrong
                // frequency, and that is indistinguishable from a peer that left: the
                // frame count still rises, `err` still converges, `peer=` still names a
                // MAC. Said once, because a line every five seconds is a line nobody
                // reads.
                if !mismatch_reported && link.off_channel() {
                    mismatch_reported = true;
                    eprintln!(
                        "awdld: CHANNEL_MISMATCH the last frame that carried a frequency \
                         arrived on {} ({} MHz) but this node announces {}",
                        link.phy_channel().unwrap_or(0),
                        link.phy_mhz().unwrap_or(0),
                        link.node().channel
                    );
                }
                if !args.quiet && stats != last_stats {
                    // `err` is the number that says whether the clock is converging: it
                    // is the offset in TU between this node's slot boundary and the
                    // peer's, and a link that is hearing the peer but not following it
                    // shows a large steady value here while everything else looks fine.
                    // Two maps, because they answer two questions and printing one of them
                    // as "the map" is what hid the defect for a whole round: `own` is what
                    // this node announces, all sixteen on its channel; `peer` is what it
                    // believes about the phone and what the gate obeys. A `peer=none` on a
                    // live link is the interesting one — either no map is being read, or
                    // every one of them names no slot on this channel, and `maps=` tells
                    // the two apart.
                    // The first field is the one that was missing for a whole round: `phy`
                    // is the frequency the driver stamped on the last frame from the air,
                    // and `own` is the channel this node advertises. They are printed
                    // together because they can disagree, and when they do every slot in
                    // the schedule is a window that never opens — the link still shows a
                    // peer, a converging error and a rising frame count, so nothing else
                    // in this line would say so.
                    // `radio=` is on this line rather than in its own because the two
                    // numbers answer the same question and only make sense together:
                    // `phy=149` says where the tap is, and `radio=share sta=5220` says
                    // where the station went, which is the whole reason a shared radio
                    // costs 45% and an exclusive one costs nothing.
                    let radio_line = match radio.as_ref() {
                        Some(radio) => radio.describe(),
                        None => "off".to_string(),
                    };
                    println!(
                        "STAT {} phy={} peer={} err={}tu elect={} slot={} next_tu={} aw={} own={} radio={} peer_map={}",
                        stats,
                        match (link.phy_channel(), link.phy_mhz()) {
                            (Some(ch), Some(mhz)) if link.off_channel() =>
                                format!("{ch}!{mhz}MHz"),
                            (Some(ch), _) => ch.to_string(),
                            _ => "?".to_string(),
                        },
                        link.peer()
                            .map(|mac| mac.to_string())
                            .unwrap_or_else(|| "none".to_string()),
                        link.phase_err_tu(),
                        if link.node().height == 0 { "self" } else { "follow" },
                        link.schedule().slot_index(now),
                        link.schedule().next_slot_tu(now),
                        link.schedule().aw_counter(now),
                        link.schedule().describe(),
                        radio_line,
                        link.schedule().describe_peer()
                    );
                    let _ = io::stdout().flush();
                }
                last_stats = stats;
            }
        }
    }

    fn parse_args() -> Result<Args, String> {
        let mut args = Args::default();
        let mut it = std::env::args().skip(1);
        while let Some(flag) = it.next() {
            match flag.as_str() {
                "--iface" => args.iface = it.next().ok_or("--iface needs a value")?,
                "--tun" => args.tun = it.next().ok_or("--tun needs a value")?,
                "--name" => args.name = it.next().ok_or("--name needs a value")?,
                "--channel" => {
                    args.channel = it
                        .next()
                        .ok_or("--channel needs a value")?
                        .parse()
                        .map_err(|_| "--channel is not a number")?;
                    args.channel_given = true;
                }
                "--rate" => {
                    args.rate = it
                        .next()
                        .ok_or("--rate needs a value")?
                        .parse()
                        .map_err(|_| "--rate is not a number")?
                }
                "--uid" => {
                    args.uid = Some(
                        it.next()
                            .ok_or("--uid needs a value")?
                            .parse()
                            .map_err(|_| "--uid is not a number")?,
                    )
                }
                "--quiet" => args.quiet = true,
                "--no-live" => args.no_live = true,
                "--ctl" => args.ctl = Some(it.next().ok_or("--ctl needs a value")?),
                "--burst" => {
                    args.burst = Some(
                        it.next()
                            .ok_or("--burst needs a value")?
                            .parse()
                            .map_err(|_| "--burst is not a number")?,
                    )
                }
                other => return Err(format!("unknown argument {other}")),
            }
        }
        Ok(args)
    }

    /// Make the tunnel the address a peer can answer, and put the route where Android looks.
    ///
    /// # The address is not cosmetic
    ///
    /// Left alone, the kernel gives this device a stable-privacy link-local: a hash of a
    /// secret and the interface name. It works in both directions on a link where both
    /// ends already know each other, which is why it went unnoticed here for a while. A
    /// peer starting cold has exactly one input to build a destination MAC from — the
    /// address in our IPv6 header — and the only mapping it has is RFC 4291's modified
    /// EUI-64. A privacy address implies no MAC at all, so the peer cannot address a
    /// reply, and the exchange dies at neighbour discovery without a single frame of ours
    /// looking wrong. The address has to be the MAC's EUI-64, which is exactly what the
    /// frame's own source address says it is.
    ///
    /// `nodad` is not a shortcut. The same link-local can be held by another interface on
    /// this device, and a duplicate-address probe would either refuse the address outright
    /// or leave it tentative for a second — and a tentative address is one source selection
    /// ignores, which reads as a route that was never added.
    ///
    /// # The route is not in the table you would expect
    ///
    /// Android's IPv6 policy chain never consults `main`. It runs `local`, then the rules
    /// netd installed, then `32000: from all unreachable`. So a route written with plain
    /// `ip -6 route` lands in a table nothing reads, and the caller gets `ENETUNREACH`
    /// from `connect()` on a link that is perfectly alive. Table 1087 is where the rule
    /// looks. The rule itself is keyed on a uid rather than on `fe80::/64` outright,
    /// because `fe80::` also covers `wlan0`'s link-local and the router's, and an
    /// unconditional rule would drag those into a tunnel with no path to them.
    fn configure_tun(name: &str, mac: Mac, uid: Option<u32>) -> io::Result<()> {
        // `mtu` is not a default worth leaving alone; see {@link TUN_MTU}. The kernel derives
        // the MSS we advertise from it, so this single number is what keeps a sender's full
        // segment inside the largest frame the driver accepts — and a sender that overshoots
        // it loses the segment silently and waits out a retransmission timeout.
        let mtu = TUN_MTU.to_string();
        exec("ip", &["link", "set", name, "up", "mtu", &mtu])?;
        // DAD holds the address tentative for a second, and a listener that starts in that
        // second finds no address to bind.
        let dad = format!("/proc/sys/net/ipv6/conf/{name}/accept_dad");
        let _ = std::fs::write(&dad, b"0\n");

        let link = opairdrop_awdl::mac::format_ipv6(&mac.link_local());
        let cidr = format!("{link}/64");
        // Flushed rather than replaced: whatever the kernel generated has to go, or the
        // device keeps two link-locals and source selection can pick either.
        let _ = exec("ip", &["-6", "addr", "flush", "dev", name]);
        exec("ip", &["-6", "addr", "add", &cidr, "dev", name, "nodad"])?;

        exec(
            "ip",
            &["-6", "route", "replace", "fe80::/64", "dev", name, "table", "1087"],
        )?;

        if let Some(uid) = uid {
            // Replaced rather than stacked: a node that restarted would otherwise add a
            // second rule at the same preference on every run, and none of them would be
            // wrong, but the list would grow for the life of the boot. The delete is
            // allowed to fail — on the first run there is nothing to remove.
            let range = format!("{uid}-{uid}");
            let _ = exec(
                "ip",
                &[
                    "-6", "rule", "del", "to", "fe80::/64", "uidrange", &range, "lookup",
                    "1087", "pref", "100",
                ],
            );
            exec(
                "ip",
                &[
                    "-6", "rule", "add", "to", "fe80::/64", "uidrange", &range, "lookup",
                    "1087", "pref", "100",
                ],
            )?;
        }

        // mDNS is delivered to a group address; a device that never joined it receives
        // unicast queries only, which is exactly the case this project kept failing.
        let _ = exec("ip", &["-6", "maddr", "add", "ff02::fb", "dev", name]);
        Ok(())
    }

    fn exec(program: &str, args: &[&str]) -> io::Result<()> {
        let output = Command::new(program).args(args).output()?;
        if output.status.success() {
            return Ok(());
        }
        let stderr = String::from_utf8_lossy(&output.stderr);
        Err(io::Error::other(format!(
            "{program} {}: {}",
            args.join(" "),
            stderr.trim()
        )))
    }

    fn read_mac(iface: &str) -> io::Result<Mac> {
        let path = format!("/sys/class/net/{iface}/address");
        let text = std::fs::read_to_string(path.as_str())?;
        Mac::parse(text.trim())
            .ok_or_else(|| io::Error::other(format!("{path} does not hold an address")))
    }

    fn mtu(iface: &str) -> Option<u32> {
        std::fs::read_to_string(format!("/sys/class/net/{iface}/mtu"))
            .ok()?
            .trim()
            .parse()
            .ok()
    }

    /// The channel `awdl-stack.sh` fed the tap, from the note it leaves beside this
    /// binary.
    ///
    /// A note rather than another flag because the flag already exists and is already
    /// the thing callers get wrong: `--channel` is typed by whoever starts the process,
    /// while the radio's channel is decided by whoever feeds the tap. Two processes
    /// spelling the same number from memory is how the announcement and the radio came
    /// apart, so the one that decides writes it down.
    ///
    /// Missing is not an error — this binary also runs where nothing wrote a note — but
    /// it is the difference between an announcement that was measured and one that was
    /// guessed, so the caller is told which it got.
    fn link_freq_channel() -> Option<u8> {
        let exe = std::env::current_exe().ok()?;
        let path = exe.parent()?.join("link-freq");
        channel_from_note(&std::fs::read_to_string(path).ok()?)
    }

    /// Parse the note: a single frequency in MHz, which is what `iw` reports and what
    /// the tap is fed; the channel is derived because that is what goes into a frame.
    fn channel_from_note(text: &str) -> Option<u8> {
        let mhz: u16 = text.trim().parse().ok()?;
        opairdrop_awdl::frame::channel_of_mhz(mhz)
    }

    /// The first link-local address on `iface`, from `/proc/net/if_inet6`.
    ///
    /// The file is `address, index, prefix length, scope, flags, name` per line, with the
    /// address as 32 hex digits. Scope `0x20` is link-local.
    fn link_local(iface: &str) -> io::Result<Option<String>> {
        let text = std::fs::read_to_string("/proc/net/if_inet6")?;
        for line in text.lines() {
            let fields: Vec<&str> = line.split_whitespace().collect();
            if fields.len() < 6 || fields[5] != iface || fields[3] != "20" {
                continue;
            }
            let hex = fields[0];
            if hex.len() != 32 {
                continue;
            }
            let mut addr = [0u8; 16];
            let mut ok = true;
            for (i, byte) in addr.iter_mut().enumerate() {
                match u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16) {
                    Ok(value) => *byte = value,
                    Err(_) => {
                        ok = false;
                        break;
                    }
                }
            }
            if ok {
                return Ok(Some(opairdrop_awdl::mac::format_ipv6(&addr)));
            }
        }
        Ok(None)
    }

    fn now_us() -> u64 {
        let mut ts: libc::timespec = unsafe { std::mem::zeroed() };
        // SAFETY: `ts` is a live `timespec` and `CLOCK_MONOTONIC` always exists.
        unsafe {
            libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts);
        }
        ts.tv_sec as u64 * 1_000_000 + ts.tv_nsec as u64 / 1_000
    }

    fn hostname() -> String {
        std::fs::read_to_string("/proc/sys/kernel/hostname")
            .map(|text| text.trim().to_string())
            .ok()
            .filter(|name| !name.is_empty() && name != "localhost")
            .unwrap_or_else(|| "OPAirDrop".to_string())
    }

    /// The Wi-Fi radio, as a thing the user asked for rather than a thing we own.
    ///
    /// `awdl-stack.sh` can put the radio in either state and both are real: exclusive
    /// measured 0% loss over 20 pings, sharing measured 0% when the phone's station
    /// happened to land on 5745 and 45% when it landed on 5220. That range is not ours
    /// to narrow. The tap is pinned to 149 by the driver — `opairshare_pas_rx_fixed_channel()`
    /// in `wma_opairshare_pas_mgmt.h` returns `-EOPNOTSUPP` for every other frequency, and
    /// its own comment calls it the "Fixed149 profile" — and every attempt to move a live
    /// tap onto the station's channel took `wondertap0`, this process and the tunnel with
    /// it. So the switch does not converge the two; it only decides whether the station
    /// competes for the radio at all.
    ///
    /// Nothing here retunes anything. Switching is `svc wifi enable|disable` and nothing
    /// else: measured, that leaves the tap, its ifindex, its MAC and this process
    /// untouched, which is what makes a live switch possible in the first place.
    ///
    /// # Why files and not a socket
    ///
    /// The two sides live in different security domains — the app in `untrusted_app`,
    /// this process in whatever domain `su` transitions to (`ksu` here) — and only one
    /// direction of that crossing was verified to work. A file in the app's private
    /// directory is `drwx------` and owned by the app, so the filesystem already answers
    /// "who may ask" with the kernel's own authority; an abstract socket belongs to
    /// nobody and every app on the phone could connect to it. Measured on this phone
    /// 2026-11-14: app writes, `ksu` reads it with its category intact; `ksu` writes a
    /// file that loses the category but the app reads it anyway, because MLS only asks
    /// that the subject's categories be a superset of the object's.
    ///
    /// # Why not a thread
    ///
    /// `svc wifi` returns in 21 ms and `cmd wifi status` in 15 ms; both are binder calls
    /// that answer "asked", not "done", and the state they ask about takes 1.5 s to
    /// change. So this is a state machine that gets looked at, not an operation that
    /// gets waited on, and it costs the poll loop nothing while settled: reading a file's
    /// mtime is a syscall, not a fork. Only while a request is in flight does this fork
    /// anything, and then only twice a second.
    mod radio {
        use std::os::unix::fs::MetadataExt;
        use std::path::{Path, PathBuf};
        use std::process::Command;
        use std::time::SystemTime;

        /// How often the request file is looked at. A person taps a switch; there is no
        /// producer here that needs a tighter bound, and a `stat` every wakeup would be
        /// ten a second for nothing.
        const POLL_US: u64 = 250_000;
        /// How often an in-flight request is re-checked. Two forks at ~14 ms each, so
        /// about 5% of the radio's time while switching — affordable because switching
        /// is a second and a half, and because the link is already losing frames then.
        const PROBE_US: u64 = 500_000;
        /// How long `exclusive` may take to confirm. Measured 88 ms to report disabled,
        /// so this is two orders of magnitude of slack for a busy framework.
        const OFF_LIMIT_US: u64 = 5_000_000;
        /// How long `share` may take to associate. Measured 1474 ms from command to a
        /// channel; the limit is deliberately far above that, because a station that is
        /// slow to associate is not a failure, and the ones that never do are the case
        /// that has to be reported rather than papered over.
        const ON_LIMIT_US: u64 = 20_000_000;

        /// What the user wants the radio to be doing.
        #[derive(Clone, Copy, PartialEq, Eq, Debug)]
        pub enum Want {
            /// The station keeps the radio. Loss depends on the channel it picks, which
            /// is why this is reported as a measurement and never as a guarantee.
            Share,
            /// The radio belongs to the tap. The phone is offline for the duration.
            Exclusive,
        }

        impl Want {
            fn parse(text: &str) -> Option<Want> {
                match text.trim() {
                    "share" => Some(Want::Share),
                    "exclusive" => Some(Want::Exclusive),
                    _ => None,
                }
            }

            fn word(self) -> &'static str {
                match self {
                    Want::Share => "share",
                    Want::Exclusive => "exclusive",
                }
            }

            fn svc_arg(self) -> &'static str {
                match self {
                    Want::Share => "enable",
                    Want::Exclusive => "disable",
                }
            }
        }

        /// Where a request stands. `Pending` is a phase and not a wait, so the loop can
        /// keep carrying frames while the framework thinks about it.
        enum Phase {
            Settled,
            Pending { since: u64 },
            /// The last attempt did not take effect, with the reason kept so the app can
            /// show it. A switch that stays where it was put while claiming success is
            /// the one failure this page must not have.
            Failed { why: String },
        }

        pub struct Radio {
            req: PathBuf,
            receipt: PathBuf,
            /// The uid allowed to write the request, when the caller named one. The same
            /// number the tunnel's policy rule is keyed on, because it is the same app:
            /// the one that owns the tunnel is the one that gets to ask for the radio.
            owner: Option<u32>,
            want: Want,
            phase: Phase,
            /// Modification stamp of the request file the last time it was acted on.
            seen: Option<(i64, u32)>,
            next_poll: u64,
            next_probe: u64,
            /// The station's channel, once it has one. Reported because it is the whole
            /// difference between a 0% share and a 45% one.
            sta_mhz: Option<u16>,
            /// The last line written to the receipt, so an unchanged state is not
            /// rewritten twice a second for the life of the process.
            written: String,
        }

        impl Radio {
            /// Open the control channel, or `None` if the directory is not there.
            ///
            /// A missing directory is not an error: this binary is also run by hand and
            /// by tests, and neither has a place to put a receipt.
            pub fn open(dir: &str, owner: Option<u32>, now: u64) -> Option<Radio> {
                let base = Path::new(dir);
                if !base.is_dir() {
                    return None;
                }
                let req = base.join("radio-share");
                let receipt = base.join("radio-state");
                let want = std::fs::read_to_string(&req)
                    .ok()
                    .and_then(|text| Want::parse(&text))
                    .unwrap_or(Want::Exclusive);
                let mut radio = Radio {
                    req,
                    receipt,
                    owner,
                    want,
                    // `Pending`, not `Settled`. The file says what the user wants, not
                    // what the radio is doing, and nothing has been applied yet -- this
                    // process was not running when the request was written. A receipt of
                    // `share confirmed` here would be a switch reporting a state that
                    // nobody had asked the framework for.
                    phase: Phase::Pending { since: now },
                    seen: None,
                    next_poll: 0,
                    next_probe: 0,
                    sta_mhz: None,
                    written: String::new(),
                };
                // Stated once so an operator reading the log knows which mode the daemon
                // believes it is in before the first frame, not after the first switch.
                println!(
                    "RADIO want={} ctl={}",
                    want.word(),
                    base.display()
                );
                radio.apply_initial(now);
                Some(radio)
            }

            /// Apply the request that was already on disk when this process started.
            ///
            /// The file outlives the daemon, so a restart must neither leave a stale
            /// `confirmed` standing nor silently adopt the radio as it happens to be:
            /// "the file is the preference" only means something if the daemon acts on
            /// it. Both `svc` calls are idempotent, so re-applying a state that `start`
            /// already put there costs one fork and changes nothing.
            fn apply_initial(&mut self, now: u64) {
                match self.apply(self.want) {
                    Ok(()) => {
                        self.phase = Phase::Pending { since: now };
                        self.next_probe = 0;
                        println!("RADIO_SET {} rc=0", self.want.word());
                    }
                    Err(err) => {
                        self.phase = Phase::Failed { why: err.clone() };
                        eprintln!("awdld: radio {} failed: {err}", self.want.word());
                    }
                }
                self.publish();
            }

            /// Called once per wakeup. Cheap while settled: one `stat` four times a
            /// second, and nothing at all for the rest of the loop.
            pub fn tick(&mut self, now: u64) {
                if now >= self.next_poll {
                    self.next_poll = now + POLL_US;
                    self.poll_request(now);
                }
                if let Phase::Pending { since } = self.phase {
                    if now >= self.next_probe {
                        self.next_probe = now + PROBE_US;
                        self.probe(now, since);
                    }
                }
            }

            /// The state as one line, for `STAT` and for anything reading the log.
            pub fn describe(&self) -> String {
                self.line()
            }

            fn line(&self) -> String {
                match &self.phase {
                    Phase::Settled => match (self.want, self.sta_mhz) {
                        (Want::Share, Some(mhz)) => format!("share confirmed sta={mhz}"),
                        _ => format!("{} confirmed", self.want.word()),
                    },
                    Phase::Pending { .. } => format!("{} pending", self.want.word()),
                    Phase::Failed { why } => format!("{} failed {why}", self.want.word()),
                }
            }

            fn poll_request(&mut self, now: u64) {
                // Ownership first, and before the stamp is remembered: a file written by
                // somebody else must not be able to make this process ignore the app's
                // next real request by looking like it was already handled.
                if let Some(owner) = self.owner {
                    match std::fs::metadata(&self.req) {
                        Ok(meta) if meta.uid() == owner => {}
                        _ => return,
                    }
                }
                let stamp = match std::fs::metadata(&self.req).and_then(|meta| meta.modified()) {
                    Ok(time) => time,
                    // Absent is the normal state until the page is first used.
                    Err(_) => return,
                };
                let key = match stamp.duration_since(SystemTime::UNIX_EPOCH) {
                    Ok(delta) => (delta.as_secs() as i64, delta.subsec_nanos()),
                    Err(_) => return,
                };
                if self.seen == Some(key) {
                    return;
                }
                self.seen = Some(key);

                let text = match std::fs::read_to_string(&self.req) {
                    Ok(text) => text,
                    Err(err) => {
                        // Worth a line: the file exists and is ours but cannot be read,
                        // which is a permissions problem and not a missing request.
                        eprintln!("awdld: cannot read {}: {err}", self.req.display());
                        return;
                    }
                };
                let want = match Want::parse(&text) {
                    Some(want) => want,
                    None => {
                        // The app wrote something this build does not understand, which
                        // means a newer page against an older daemon. Say so in the
                        // receipt rather than in a log nobody is watching.
                        self.phase = Phase::Failed {
                            why: format!("unknown request {:?}", text.trim()),
                        };
                        self.publish();
                        return;
                    }
                };
                self.request(want, now);
            }

            fn request(&mut self, want: Want, now: u64) {
                // The newest request wins outright. There is no "busy, try later": one
                // of the two states is always safe to enter, and refusing a switch
                // because a previous one is still settling is how a toggle ends up
                // showing the opposite of what it did.
                if want == self.want && !matches!(self.phase, Phase::Failed { .. }) {
                    // Already heading there or already there; re-state the receipt so a
                    // deleted or truncated file comes back.
                    self.publish();
                    return;
                }
                self.want = want;
                match self.apply(want) {
                    Ok(()) => {
                        self.phase = Phase::Pending { since: now };
                        self.sta_mhz = None;
                        self.next_probe = 0;
                        println!("RADIO_SET {} rc=0", want.word());
                    }
                    Err(err) => {
                        self.phase = Phase::Failed { why: err.clone() };
                        eprintln!("awdld: radio {} failed: {err}", want.word());
                    }
                }
                self.publish();
            }

            fn apply(&self, want: Want) -> Result<(), String> {
                let arg = want.svc_arg();
                // The absolute path, because this process's `PATH` is whatever `su`
                // handed it and `/system/bin` is the only place `svc` has ever been.
                let output = Command::new("/system/bin/svc")
                    .args(["wifi", arg])
                    .output()
                    .map_err(|err| format!("svc wifi {arg}: {err}"))?;
                if output.status.success() {
                    return Ok(());
                }
                let err = String::from_utf8_lossy(&output.stderr);
                Err(format!(
                    "svc wifi {arg} exited {}: {}",
                    output.status.code().unwrap_or(-1),
                    err.trim()
                ))
            }

            fn probe(&mut self, now: u64, since: u64) {
                let enabled = wifi_enabled();
                match self.want {
                    Want::Exclusive => {
                        if enabled == Some(false) {
                            self.phase = Phase::Settled;
                            self.sta_mhz = None;
                            self.publish();
                        } else if now.saturating_sub(since) >= OFF_LIMIT_US {
                            self.phase = Phase::Failed {
                                why: format!("wi-fi still {}", enabled_word(enabled)),
                            };
                            self.publish();
                        }
                    }
                    Want::Share => match enabled {
                        Some(false) => {
                            if now.saturating_sub(since) >= OFF_LIMIT_US {
                                self.phase = Phase::Failed {
                                    why: "wi-fi did not turn on".to_string(),
                                };
                                self.publish();
                            }
                        }
                        Some(true) => {
                            // On is not the same as associated, and only the second one
                            // is what decides the loss: coex scheduling begins with the
                            // association, and the legacy code that waited only for
                            // `cmd wifi status` declared success 1.1 s before the
                            // station had a channel at all.
                            match sta_mhz() {
                                Some(mhz) => {
                                    self.phase = Phase::Settled;
                                    self.sta_mhz = Some(mhz);
                                    self.publish();
                                }
                                None => {
                                    if now.saturating_sub(since) >= ON_LIMIT_US {
                                        self.phase = Phase::Failed {
                                            why: "wi-fi on, no association".to_string(),
                                        };
                                        self.publish();
                                    }
                                }
                            }
                        }
                        None => {
                            if now.saturating_sub(since) >= ON_LIMIT_US {
                                self.phase = Phase::Failed {
                                    why: "cannot read wi-fi state".to_string(),
                                };
                                self.publish();
                            }
                        }
                    },
                }
            }

            /// Write the receipt if it changed.
            ///
            /// Permissions are set explicitly rather than left to the umask: this file
            /// is created by root inside a directory owned by the app, and a `0600` here
            /// would look exactly like the daemon never answering.
            fn publish(&mut self) {
                let line = self.line();
                if line == self.written {
                    return;
                }
                if std::fs::write(&self.receipt, format!("{line}\n")).is_err() {
                    return;
                }
                let _ = Command::new("/system/bin/chmod")
                    .args(["0644", &self.receipt.to_string_lossy()])
                    .output();
                self.written = line;
            }
        }

        fn enabled_word(enabled: Option<bool>) -> &'static str {
            match enabled {
                Some(true) => "on",
                Some(false) => "off",
                None => "unreadable",
            }
        }

        /// The framework's own answer to "is Wi-Fi on".
        ///
        /// Not `/sys/class/net/wlan0/operstate`: that says whether the interface is up,
        /// and a station that is on but associated to nothing still reads `dormant`,
        /// which is indistinguishable from a dozen other states.
        fn wifi_enabled() -> Option<bool> {
            let output = Command::new("/system/bin/cmd")
                .args(["wifi", "status"])
                .output()
                .ok()?;
            if !output.status.success() {
                return None;
            }
            let text = String::from_utf8_lossy(&output.stdout);
            let head = text.lines().next()?;
            if head.contains("disabled") {
                Some(false)
            } else if head.contains("enabled") {
                Some(true)
            } else {
                None
            }
        }

        /// The channel the station associated to, in MHz, or `None` if it has not.
        ///
        /// `iw dev wlan0 info` prints `channel 44 (5220 MHz)` only once the interface is
        /// associated, which is exactly the event that matters: the tap cannot move, so
        /// the only useful thing to report is where the station landed.
        fn sta_mhz() -> Option<u16> {
            let output = Command::new("/system/bin/iw")
                .args(["dev", "wlan0", "info"])
                .output()
                .ok()?;
            if !output.status.success() {
                return None;
            }
            let text = String::from_utf8_lossy(&output.stdout);
            for line in text.lines() {
                let line = line.trim();
                let rest = match line.strip_prefix("channel ") {
                    Some(rest) => rest,
                    None => continue,
                };
                // `44 (5220 MHz)` -> 5220
                let inside = rest.split('(').nth(1)?;
                return inside.split_whitespace().next()?.parse().ok();
            }
            None
        }
    }
}
