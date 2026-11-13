/*
 * The one part of AWDL that cannot be written in Java: putting a whole 802.11
 * frame on a named interface.
 *
 * android.system.Os stops short in two places. There is no setsockoptBytes, so
 * SO_BINDTODEVICE (25) cannot be set at all -- the method does not exist, so
 * reflection cannot reach it either -- and bind(FileDescriptor, SocketAddress)
 * only understands InetSocketAddress, so there is no way to pass a sockaddr_ll
 * carrying an ifindex.
 *
 * Receiving works without either: an unbound AF_PACKET socket sees every
 * virtual interface on the radio (three copies of each frame, one per vif).
 * Sending needs the ifindex, because the driver picks the vdev from the
 * netdev the skb came in on, which is what this file supplies.
 *
 * Those three copies were free for as long as nothing counted them. The
 * scheduler counts them twice: every copy is one more read() on the thread that
 * also aims the frames, and -- the expensive half -- every copy arrives as a
 * frame during a burst and aborts it (waitEdge() returning true is edgeCut, and
 * edgeCut is why a window that may carry sixteen frames carried three). bind()
 * against the vif the frames really arrive on is the same call that was already
 * here for the transmit side, applied to a socket Java opened itself.
 *
 * The buffer handed to send() starts at radiotap and is passed through
 * untouched: wondertap0 is ARPHRD_IEEE80211_RADIOTAP with no link header, so
 * AF_PACKET in SOCK_RAW mode neither prepends nor rewrites anything.
 *
 * Note the protocol argument: unlike bionic's Os.socket(), this goes straight
 * to the kernel, so it takes the byte-swapped ETH_P_ALL that socket(2)
 * documents.
 */

#include <jni.h>

#include <errno.h>
#include <string.h>

#include <arpa/inet.h> /* htons */
#include <fcntl.h>
#include <net/if.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <linux/if_tun.h>

/*
 * TCP_QUICKACK, spelled out rather than included.
 *
 * netinet/tcp.h would supply it, but this file is compiled against the NDK's
 * headers and the value is fixed by the kernel ABI (uapi/linux/tcp.h: TCP_NODELAY
 * 1, TCP_MAXSEG 2, TCP_CORK 3, TCP_KEEPIDLE 4, TCP_KEEPINTVL 5, TCP_KEEPCNT 6,
 * TCP_SYNCNT 7, TCP_LINGER2 8, TCP_DEFER_ACCEPT 9, TCP_WINDOW_CLAMP 10,
 * TCP_INFO 11, TCP_QUICKACK 12), so stating it here cannot drift.
 */
#ifndef TCP_QUICKACK
#define TCP_QUICKACK 12
#endif

/* The reason the last call failed, for Java to report in words. */
static int inject_errno;

/*
 * Result of the TUNSETOFFLOAD call made when the tun is created.
 *
 * Not reported to Java: the check that matters is behavioural, and the Java side
 * already counts oversized packets ("big=" in the relay statistics). If this ever
 * comes back negative, big= starts moving and says so in one place.
 */
static int offload_error;

/*
 * Opens a transmit socket bound to `iface`; returns the descriptor, or the
 * negated errno. Binding the ifindex is what makes a later plain send()
 * resolve to that interface.
 */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlInj_open(JNIEnv *env, jclass clazz, jstring iface)
{
	const char *name;
	struct sockaddr_ll addr;
	unsigned int index;
	int fd;

	if (iface == NULL) {
		inject_errno = EINVAL;
		return -EINVAL;
	}
	name = (*env)->GetStringUTFChars(env, iface, NULL);
	if (name == NULL) {
		inject_errno = ENOMEM;
		return -ENOMEM;
	}
	index = if_nametoindex(name);
	(*env)->ReleaseStringUTFChars(env, iface, name);
	if (index == 0) {
		inject_errno = ENODEV;
		return -ENODEV;
	}

	fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
	if (fd < 0) {
		inject_errno = errno;
		return -errno;
	}

	memset(&addr, 0, sizeof(addr));
	addr.sll_family = AF_PACKET;
	addr.sll_protocol = htons(ETH_P_ALL);
	addr.sll_ifindex = (int)index;
	if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		inject_errno = errno;
		close(fd);
		return -errno;
	}

	return fd;
}

/*
 * The int behind a java.io.FileDescriptor.
 *
 * The receive socket is opened by Java through android.system.Os, because that is
 * where SO_RCVBUF and SO_RCVTIMEO are set and because the descriptor is then closed
 * by the same class that opened it. Only the bind is missing, so only the bind is
 * native -- and that means reaching the descriptor from here. JNI ignores the field's
 * access modifiers, which is what makes this possible at all: FileDescriptor has no
 * public accessor for it.
 *
 * Returns -1 with a pending exception cleared, so the caller can report EINVAL rather
 * than let a NoSuchFieldError travel back through the scheduler.
 */
static int fd_of(JNIEnv *env, jobject fdo)
{
	jclass cls;
	jfieldID field;

	cls = (*env)->GetObjectClass(env, fdo);
	if (cls == NULL)
		return -1;
	field = (*env)->GetFieldID(env, cls, "descriptor", "I");
	if (field == NULL) {
		(*env)->ExceptionClear(env);
		return -1;
	}
	return (*env)->GetIntField(env, fdo, field);
}

/*
 * Binds an already-open receive socket to one interface; returns 0 or the negated
 * errno.
 *
 * Unbound is what this socket was for its whole life, and the reason is in the header
 * comment: receiving was believed to need nothing. What it costs is the three vifs.
 * Measured on a 179 s transfer, the send loop's thread was in read() 1909 us at a time
 * while only 78 data frames a second were useful -- the rest was the same frame seen
 * again on wonder0, wondertap0 and opairsharerx0.
 *
 * Binding to an ifindex is the whole point, and it is also the one call Java cannot
 * make: Os.bind() understands InetSocketAddress only, so a sockaddr_ll never reaches
 * the kernel, and SO_BINDTODEVICE needs setsockoptBytes, which does not exist. Failing
 * here is survivable and is therefore not fatal: an unbound socket still receives
 * everything, which is precisely how the link worked before this call existed.
 */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlInj_bind(JNIEnv *env, jclass clazz, jobject fdo,
					  jstring iface)
{
	struct sockaddr_ll addr;
	const char *name;
	unsigned int index;
	int fd;

	if (fdo == NULL || iface == NULL) {
		inject_errno = EINVAL;
		return -EINVAL;
	}
	fd = fd_of(env, fdo);
	if (fd < 0) {
		inject_errno = EINVAL;
		return -EINVAL;
	}
	name = (*env)->GetStringUTFChars(env, iface, NULL);
	if (name == NULL) {
		inject_errno = ENOMEM;
		return -ENOMEM;
	}
	index = if_nametoindex(name);
	(*env)->ReleaseStringUTFChars(env, iface, name);
	if (index == 0) {
		inject_errno = ENODEV;
		return -ENODEV;
	}

	memset(&addr, 0, sizeof(addr));
	addr.sll_family = AF_PACKET;
	addr.sll_protocol = htons(ETH_P_ALL);
	addr.sll_ifindex = (int)index;
	if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		inject_errno = errno;
		return -errno;
	}
	return 0;
}

/*
 * Turns off the delayed-acknowledgement timer for one connection, once.
 *
 * The kernel's receiver holds an acknowledgement for up to 40 ms when a segment
 * arrives with no second segment behind it (tcp_delack_min, hardwired on this
 * kernel -- the sysctl does not exist), and releases it early only when the next
 * segment lands. On a link whose whole round trip is one 64 ms AWDL slot, that
 * 40 ms is the difference between an acknowledgement that catches the current
 * window and one that waits for the next: measured on the inbound side of a
 * phone-to-phone transfer, the median acknowledgement left this process 5 ms
 * after the segment it answered, while 4.7% of them left after 84 ms -- 40 ms of
 * timer plus the wait for the following window.
 *
 * The option is one-shot by design: the kernel clears it as soon as it makes a
 * decision, so the caller has to re-arm it after every read that may have
 * consumed a segment. That is why this takes a raw fd and is called from the
 * receive loop rather than once at accept.
 */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlInj_armQuickAck(JNIEnv *env, jclass clazz, jint fd)
{
	int on = 1;

	(void)env;
	(void)clazz;
	if (fd < 0) {
		inject_errno = EINVAL;
		return -EINVAL;
	}
	if (setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &on, sizeof(on)) < 0) {
		inject_errno = errno;
		return -errno;
	}
	return 0;
}

/* Sends one frame; returns the byte count, or the negated errno. */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlInj_send(JNIEnv *env, jclass clazz, jint fd,
					jbyteArray frame)
{
	jbyte *bytes;
	jsize len;
	ssize_t sent;

	if (frame == NULL || fd < 0) {
		inject_errno = EINVAL;
		return -EINVAL;
	}
	len = (*env)->GetArrayLength(env, frame);
	bytes = (*env)->GetByteArrayElements(env, frame, NULL);
	if (bytes == NULL) {
		inject_errno = ENOMEM;
		return -ENOMEM;
	}
	do {
		sent = send(fd, bytes, (size_t)len, 0);
	} while (sent < 0 && errno == EINTR);
	(*env)->ReleaseByteArrayElements(env, frame, bytes, JNI_ABORT);

	if (sent < 0) {
		inject_errno = errno;
		return -errno;
	}
	return (jint)sent;
}

JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlInj_lastError(JNIEnv *env, jclass clazz)
{
	return inject_errno;
}

JNIEXPORT jstring JNICALL
Java_com_oshare_airdrop_core_AwdlInj_strerror(JNIEnv *env, jclass clazz, jint err)
{
	return (*env)->NewStringUTF(env, strerror((int)err));
}

JNIEXPORT void JNICALL
Java_com_oshare_airdrop_core_AwdlInj_close(JNIEnv *env, jclass clazz, jint fd)
{
	if (fd >= 0)
		close(fd);
}

/*
 * The other end of the same problem: a tun device, so the kernel's own IPv6 and TCP
 * can be used instead of hand-built headers.
 *
 * The vif the driver gives us cannot carry that traffic. wonder0 accepts what the
 * stack emits -- its tx counter rises with every ping -- but it delivers almost
 * nothing back: 8 frames reached it during a run in which 9295 arrived on the radio.
 * So the kernel needs a netdev we feed ourselves, and the relay above it already
 * knows how to frame and parse AWDL.
 *
 * TUNSETIFF both creates and attaches the device, so no "ip tuntap add" is needed and
 * the name in the request is what ends up in /sys/class/net. IFF_NO_PI keeps the
 * 4-byte packet-info prefix out of the stream: what is read is exactly one IP packet.
 *
 * The fd is non-blocking. Reading it from a thread that also has to keep the AWDL
 * schedule is the whole point, and a blocking read would park the scheduler.
 */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlTun_open(JNIEnv *env, jclass clazz, jstring iface)
{
	struct ifreq ifr;
	const char *name;
	int fd, flags, offload;

	if (iface == NULL) {
		inject_errno = EINVAL;
		return -EINVAL;
	}
	fd = open("/dev/tun", O_RDWR);
	if (fd < 0) {
		inject_errno = errno;
		return -errno;
	}
	memset(&ifr, 0, sizeof(ifr));
	ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
	name = (*env)->GetStringUTFChars(env, iface, NULL);
	if (name == NULL) {
		(*env)->ReleaseStringUTFChars(env, iface, name);
		close(fd);
		inject_errno = ENOMEM;
		return -ENOMEM;
	}
	strncpy(ifr.ifr_name, name, IFNAMSIZ - 1);
	(*env)->ReleaseStringUTFChars(env, iface, name);

	if (ioctl(fd, TUNSETIFF, &ifr) < 0) {
		inject_errno = errno;
		close(fd);
		return -errno;
	}
	/*
	 * Segmentation offload off, and this is not a tuning preference.
	 *
	 * The stack hands a tun every packet the socket wrote, and with GSO enabled a
	 * single write of a 64 KB buffer leaves as one skb carrying forty segments. Read
	 * here it is one 64 KB "packet", framed as one 64 KB AWDL frame, and the driver
	 * refuses it -- EMSGSIZE, measured 13 times in the first hundred milliseconds of
	 * every transfer. The whole burst is lost, so the kernel waits for ACKs that were
	 * never asked for and only recovers when RTO has backed off enough to retransmit
	 * single segments, which do fit. That is the several-second stall at the start of
	 * a send: three to four seconds with a 1460-byte path, ten with a smaller one.
	 *
	 * With offload cleared the kernel segments before handing over, so what arrives
	 * here is always MTU-sized. TUN_F_CSUM goes too, which costs nothing: the frames
	 * already carry finished checksums (the relay reports cks=ok on every one).
	 */
	/*
	 * TUNSETOFFLOAD is declared _IOW, but net/tun.c reads the argument as a value
	 * rather than copying from the pointer: "if (arg & ~(TUN_F_...)) return -EINVAL;".
	 * Handing it &offload therefore sends a stack address, whose high bits are outside
	 * the allowed mask, and the call fails with EINVAL without touching a single
	 * feature. The value goes in directly, which is what qemu does too.
	 */
	offload = 0;
	offload_error = (int) ioctl(fd, TUNSETOFFLOAD, (unsigned long) offload);
	flags = fcntl(fd, F_GETFL, 0);
	if (flags >= 0)
		fcntl(fd, F_SETFL, flags | O_NONBLOCK);
	return fd;
}

/* Reads one packet from the tun; 0 when there is none, negated errno on failure. */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlTun_read(JNIEnv *env, jclass clazz, jint fd,
					jbyteArray buf)
{
	jbyte *bytes;
	jsize cap;
	ssize_t got;

	if (fd < 0 || buf == NULL)
		return -EINVAL;
	cap = (*env)->GetArrayLength(env, buf);
	bytes = (*env)->GetByteArrayElements(env, buf, NULL);
	if (bytes == NULL)
		return -ENOMEM;
	do {
		got = read(fd, bytes, (size_t)cap);
	} while (got < 0 && errno == EINTR);
	if (got < 0) {
		int err = errno;
		(*env)->ReleaseByteArrayElements(env, buf, bytes, JNI_ABORT);
		return (err == EAGAIN || err == EWOULDBLOCK) ? 0 : -err;
	}
	(*env)->ReleaseByteArrayElements(env, buf, bytes, 0);
	return (jint)got;
}

/* Writes one packet into the tun; returns the byte count or the negated errno. */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlTun_write(JNIEnv *env, jclass clazz, jint fd,
					 jbyteArray buf, jint len)
{
	jbyte *bytes;
	ssize_t put;

	if (fd < 0 || buf == NULL || len <= 0)
		return -EINVAL;
	bytes = (*env)->GetByteArrayElements(env, buf, NULL);
	if (bytes == NULL)
		return -ENOMEM;
	do {
		put = write(fd, bytes, (size_t)len);
	} while (put < 0 && errno == EINTR);
	(*env)->ReleaseByteArrayElements(env, buf, bytes, JNI_ABORT);
	if (put < 0) {
		inject_errno = errno;
		return -errno;
	}
	return (jint)put;
}

/* Configures the tun as the AWDL link: address, route table, MTU. Returns 0 or -1. */
JNIEXPORT jint JNICALL
Java_com_oshare_airdrop_core_AwdlTun_configure(JNIEnv *env, jclass clazz,
					     jstring iface, jstring addr, jint uid)
{
	const char *i;
	const char *a;
	char cmd[1024];
	int rc;

	i = (*env)->GetStringUTFChars(env, iface, NULL);
	a = (*env)->GetStringUTFChars(env, addr, NULL);
	if (i == NULL || a == NULL)
		return -1;
	/*
	 * A route on its own is not enough, which is the one thing this function got wrong
	 * for a long time. Android's IPv6 policy chain has no "lookup main" entry at all:
	 * it runs local, then whatever rules netd installed, then "32000: from all
	 * unreachable". So a route written into table 1087 is consulted only if a rule
	 * names that table, and without one every fe80:: destination from the app dies in
	 * unreachable before a single packet is built -- which surfaces in the app as
	 * ENETUNREACH from connect(), not as anything that looks like a missing route.
	 *
	 * The rule is keyed on the caller's uid rather than matching fe80::/64 outright,
	 * because the peer's link-local is not the only fe80:: address on this device: the
	 * real wlan0 has one too, and an unconditional rule would pull the router's
	 * link-local into a tun that has no path to it. uid comes from the app because this
	 * process runs as root and cannot ask.
	 *
	 * nodad is not cosmetic: the same link-local already sits on wonder0, so a
	 * duplicate-address probe here would either fail the address or leave it
	 * tentative for a second -- and a tentative address is one source selection
	 * refuses, which looks exactly like a route that was never added.
	 */
	snprintf(cmd, sizeof(cmd),
		 "ip link set %s up mtu 1500; "
		 "ip -6 addr flush dev %s 2>/dev/null; "
		 "ip -6 addr add %s/64 dev %s nodad; "
		 "ip -6 route replace fe80::/64 dev %s table 1087",
		 i, i, a, i, i);
	rc = system(cmd);
	if (rc == 0 && uid > 0) {
		/* Replaced rather than added: a node that restarted would otherwise stack
		 * duplicate rules at the same preference and none of them would be wrong,
		 * but the list would grow for the life of the boot. */
		snprintf(cmd, sizeof(cmd),
			 "ip -6 rule del to fe80::/64 uidrange %d-%d lookup 1087 pref 100 2>/dev/null; "
			 "ip -6 rule add to fe80::/64 uidrange %d-%d lookup 1087 pref 100",
			 (int)uid, (int)uid, (int)uid, (int)uid);
		rc = system(cmd);
	}
	(*env)->ReleaseStringUTFChars(env, iface, i);
	(*env)->ReleaseStringUTFChars(env, addr, a);
	return rc == 0 ? 0 : -1;
}
