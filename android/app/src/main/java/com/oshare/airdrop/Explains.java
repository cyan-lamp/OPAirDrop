package com.oshare.airdrop;

import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 接收侧的错误分类与文案（评审拆分路线：从 {@code Listener} 里切出来的纯函数层）。
 *
 * <p>只回答三个问题：**这条失败值不值得打扰人**（{@link #routine}）、**该说什么**
 * （{@link #because}/{@link #readable}，及给日志的 {@link #describe}）、以及那些
 * 数字和落点怎么读（{@link #bytes}/{@link #where}）。全部无状态：输入异常（或数字），
 * 输出一句话。因此每一条判据都可以拿真机日志写单元测试，不用起一条真的连接。
 */
final class Explains {

    private Explains() {
    }

    /**
     * Whether a connection failure is the routine kind: the peer went away.
     *
     * <p>Two of the three ways out of a transfer are not failures by this device, and they
     * arrive as the same unchecked {@link IOException} a broken disk would: a reset after the
     * sender closed its share sheet, and a pipe closed under a reply nobody is left to read.
     * Telling them apart here is what keeps the state line meaning "something is wrong with
     * this receiver" rather than "somebody's phone changed its mind".
     *
     * <p>Checked through the cause chain because the reset surfaces wrapped: an
     * {@link SSLException} is a normal part of the path, and its cause is the socket one.
     */
    static boolean routine(Throwable failed) {
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketException) {
                // Reset by peer, broken pipe, socket closed: all "the other end stopped
                // listening", which is what a closed share sheet looks like from here.
                //
                // 一个例外：「Software caused connection abort」（ECONNABORTED）。它不是对端
                // 发来的 RST，而是**本机内核**中止了这条连接——和上面那几种恰好相反。归纳进
                // 「对端走掉」的代价在 2026-11-17 付过一次：ColorOS 的后台限网（nwpower）在
                // 应用离开前台的同一毫秒掐掉 socket，人点「接收」时已经晚了三秒，这里把
                // 它当成别人自己走了，于是屏幕上什么都没有。
                return !aborted(cause);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * 这条连接是不是被本机掐断的（{@code ECONNABORTED}）。
     *
     * <p>Java 把 errno 丢在异常消息里，所以只能读那句话。选消息而不是选异常类型，是因为
     * {@code SocketException} 在这一层已经把 reset、broken pipe、closed 全都收进了同一个类，
     * 而这三句是 libcore 的固定译文、Android 每个版本都这么写。
     *
     * <p>为什么不把 ECONNABORTED 归成「对端走掉」：两个 errno 的方向不一样。reset 一定是
     * **对面**发了 RST（分享面板关了）；abort 是本机这条连接被中止——后台限网、VPN 掉了、
     * 息屏断网，或者，如 2026-11-19 实测的那样，**对端在 AWDL 这条隧道上收手**，我们这边的
     * 内核跟着把 fd 收了。前者不该打扰人，后者要看情况，所以它单独判一次，具体说法见
     * {@link #because(Throwable, long)}。
     */
    static boolean aborted(Throwable socket) {
        String message = socket.getMessage();
        return message != null && message.contains("Software caused connection abort");
    }

    /**
     * 这一批落在哪几处。
     *
     * <p>五类分开存之后，「存到 X/」这句话可能同时有五个答案，而通知只有一行。一处就把
     * 整个相对路径说出来（{@code Pictures/OPAirDrop/}）；两处点名；三处以上点名前三个加
     * 总数——再多就不是一句话，而是一张表了。
     */
    static String where(List<Publish.Entry> entries) {
        Set<String> roots = new LinkedHashSet<>();
        for (Publish.Entry entry : entries) {
            // 只取第一段：五个桶共用一个 OPAirDrop 子目录，能区分它们的是前面那一层。
            String directory = entry.directory;
            int slash = directory.indexOf('/');
            roots.add(slash < 0 ? directory : directory.substring(0, slash));
        }
        if (roots.size() == 1) {
            return entries.get(0).directory + "/";
        }
        List<String> names = new ArrayList<>(roots);
        if (names.size() == 2) {
            return names.get(0) + "、" + names.get(1) + " 两处";
        }
        return names.get(0) + "、" + names.get(1) + "、" + names.get(2)
                + " 等 " + names.size() + " 处";
    }

    /**
     * A byte count in the largest unit that keeps it readable.
     *
     * <p>Package-private because the transfer notification prints the same numbers: one rule
     * for how a byte count reads, not two — the same reason the name chain above is not
     * duplicated in the view that shows it.
     */
    static String bytes(long total) {
        if (total < 1024) {
            return total + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = total;
        for (String unit : units) {
            value /= 1024;
            if (value < 1024 || unit.equals(units[units.length - 1])) {
                return String.format(java.util.Locale.US, "%.1f %s", value, unit);
            }
        }
        return total + " B";
    }

    /**
     * A failure's own words, or its class when it has none.
     *
     * <p>只给日志用。屏幕上那句走 {@link #readable}：这里的原文可以是一整段英文加十六进制
     * （见 {@code dvzip.rs} 的诊断），那是拿来搜和拿来读证据的，不是拿来给人看的。
     */
    static String describe(Throwable failed) {
        String message = failed.getMessage();
        return message == null || message.isEmpty() ? failed.toString() : message;
    }

    /**
     * 同一件事给人看的说法。
     *
     * <p>{@link #describe} 是给日志的：异常原文，能拿去搜。这一句是给拿着手机的人的——他
     * 不知道 socket 是什么，他要的是「刚才那一下为什么没成、下回该改哪里」。
     *
     * <p>只有 ECONNABORTED 需要翻译。其余非例行的失败（目录写不进去、设置读不出来）原文
     * 本来就带着人能看懂的那半句，比如文件名和路径。
     *
     * <p># ECONNABORTED 要看「断了之前走过字节没有」
     *
     * <p>这个 errno 原来是**一律**翻成「检查后台限网」的，依据是 2026-11-17 那次：ColorOS 的
     * nwpower 在应用离开前台时掐掉 socket，那时确实一个字节都没走。
     *
     * <p>2026-11-19 14:55 的实测推翻了「一律」：iPhone 发一个 15.5 MB 的视频过来，传到 2.3 MB
     * 时那边按了取消，我们这一侧抛的是
     *
     * <pre>
     * 14:55:18.287 W/OPAirDrop: receive from fe80::704d:92ff:fe08:dd2%awdltun0 failed:
     *                            Software caused connection abort
     * </pre>
     *
     * <p>——同一个 errno，意思却相反：这次是**对面**停的。按原来那句话去查后台限网，人会在
     * 一个没有问题的设置上打转。
     *
     * <p>所以判据换成「有没有字节真的落地」：{@code /Upload} 一个字节都没开始就走完的连接，
     * 只可能是本机这边先没了（限网、息屏、接口掉了）；走了几兆才断的，是传到一半被停下——
     * 而这一次是谁停的，从我们这一侧分不出来（对面取消、和对面手机息屏断网，留下的都是这一句），
     * 所以那句话不点名，只说发生了什么、丢了多少。
     *
     * @param landed 断之前已经落地的字节数
     */
    static String because(Throwable failed, long landed) {
        // 兜底那一句走 {@link #readable} 而不是 {@link #describe}：原因见那边。这一处是**唯一**
        // 会把这句送到屏幕上的地方（{@code setState} 与 {@code attempt.failed} 都用它）。
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (aborted(cause)) {
                if (landed > 0) {
                    return "传输在中途被中断，已收到 " + bytes(landed) + "，没有存下";
                }
                // 把可行动的那一步一起说出来。措辞与首屏那条提示条、设置页同一个说法对齐
                // （见 Notices.gap 的「后台被限网」），否则人会以为是两件事。
                return "连接被系统切断，检查后台限网";
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return readable(failed);
    }

    /**
     * 一句人能读的失败原因。原文只进日志。
     *
     * <p>这里原来直接把 {@link #describe} 的返回值交出去，理由是「目录写不进去、设置读不出来
     * 这类原文本来就带着人能看懂的那半句」。2026-11-19 20:57 那张卡把这个理由推翻了：人看到的是
     *
     * <pre>
     * 接收失败 · transport: the archive could not be read:
     *            implausible DVZip block length 0 (header 0x00000000 at offset 115)
     * </pre>
     *
     * <p>——英文、术语、十六进制，三样他都不知道该怎么读。而那时这段原文还只有一句；随后
     * {@code dvzip.rs} 为了查清那一次的成因又往里加了「读过几块、body 起始字节、首块解出什么」，
     * 加起来上百个字符的十六进制。那些字节是**给日志的**：{@link #describe} 原样进 logcat
     * （见 {@code serve} 里那句 {@code Log.w(… failed: …)}），屏幕上不该出现它们。
     *
     * <p>所以分成两条出口。认不出来的失败回一句不点名的实话，而不是把英文倒给屏幕：认不出来的
     * 那些（SSL 握手没谈成、磁盘写满）本来就该去日志里看，屏幕上多编一个字都是猜。
     */
    static String readable(Throwable failed) {
        String raw = describe(failed);
        // 这几句都来自 Rust 侧 {@code Display} 的固定措辞（inbox::Error 的五个变体），
        // 是这条链路上**唯一**稳定的锚点：异常类型一路被 JNI 抹平成 IOException。
        if (raw.contains("the archive could not be read")) {
            return "对面发来的文件包读不出来，让他再发一次";
        }
        if (raw.contains("the inbox could not be written")) {
            return "文件没能写到存储上，检查空间够不够";
        }
        if (raw.contains("exceeds the inbox budget of")) {
            return "一次传的超过了单次接收上限，没有存下";
        }
        if (raw.contains("no free name for")) {
            return "同名文件太多，另起一个名字也存不下";
        }
        if (raw.contains("resolves outside the inbox")) {
            return "这一份的文件名不安全，没有存下";
        }
        return "连接在中途出了问题，这一份没有存下";
    }

    /** The peer's address, for a log line; "?" when the socket never had one. */
    static String where(Socket peer) {
        java.net.InetAddress address = peer.getInetAddress();
        return address == null ? "?" : address.getHostAddress();
    }

    /** How long something took, in whole milliseconds, for a log line. */
    static long millis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
