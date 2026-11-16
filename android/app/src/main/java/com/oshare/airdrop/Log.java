package com.oshare.airdrop;

import android.content.Context;

/**
 * 本项目自己的日志口。
 *
 * <p># 它为什么和 {@link android.util.Log} 同名
 *
 * <p>因为改名要改 91 处。全项目的日志调用都是 {@code Log.i(TAG, "…")} 这种标准两参形式，只有
 * {@code SendActivity} 里一处带了异常（{@code Log.i(TAG, "…", alreadyGone)}）。既然签名一样，
 * 那就让同名类顶上去：同包里的类优先于 import，把各文件顶上那句 {@code import android.util.Log;}
 * 删掉，每一处调用就自动落到这里，一个字符都不用动。
 *
 * <p>这不是省事，是**不给以后留分叉**：如果新写一个 {@code Diag.i(...)}，那从今往后每个人都要
 * 记住"想进文件得用 Diag，随手写的 Log 只进 logcat"——而随手写的那一句，恰好会在出问题的那天
 * 成为唯一缺的那一行。同名之后不存在选错的可能：怎么调都在同一个口子上。
 *
 * <p># 两个出口
 *
 * <p>logcat 和文件，各留一份。前者是开发时看的，后者是用户在设置页打开「日志级别」之后要拿去
 * 发给别人的。文件那一路受 {@code log.level} 控制，logcat 那一路不受——把日志级别调成 off 是
 * "别往我手机上写文件"，不是"别让开发者看见"，这两件事没有理由绑在一起。
 *
 * <p># 级别是从哪来的
 *
 * <p>{@code log.level} 是设置表里的一行（见 {@code crates/settings/src/lib.rs}），在
 * {@link App#onCreate} 里读一次，之后每次在设置页按下保存再读一次。不做"每次写日志都读一遍
 * 设置文件"：那是一次文件读取加一次解析，而它会被每一个报文触发。
 */
final class Log {

    /** 与 {@code log.level} 的四个取值一一对应。数值只用于比较，不对外。 */
    static final int OFF = 0;
    static final int ERROR = 1;
    static final int WARN = 2;
    static final int INFO = 3;
    static final int DEBUG = 4;

    private static final String KEY = "log.level";
    private static final String TAG_DEFAULT = "OPAirDrop";

    /** 设置页保存之后会改它，而写入在别的线程上，所以是 volatile。 */
    private static volatile int level = INFO;

    private Log() {}

    /** 接上进程：定级别、把落盘那条线程起起来。{@link App#onCreate} 里调一次。 */
    static void install(Context app) {
        Diagnostics.install(app);
        reload(app);
    }

    /** 重读 {@code log.level}。设置页保存成功后调它，否则改了要等下次启动才生效。 */
    static void reload(Context app) {
        level = levelOf(app);
    }

    /**
     * {@code log.level} 现在是哪一档。
     *
     * <p>读不出来（设置文件坏了、库拒了整份）就是默认值 {@code info}——见 {@link Tuning} 的类
     * 注释：那份文件的粒度是整份，一行越界会让每一行都回落到默认值。
     */
    static int levelOf(Context app) {
        SettingsScreen screen = Tuning.read(app);
        if (screen == null) {
            return INFO;
        }
        String value = screen.setting(KEY).value();
        switch (value) {
            case "off": return OFF;
            case "error": return ERROR;
            case "debug": return DEBUG;
            default: return INFO;
        }
    }

    /**
     * 当前级别的说法，给设置页用。
     *
     * <p>debug 那一档原先写的是「记全部，含报文」，改掉了：**报文不在 Java 这一侧**。AirDrop 的
     * HTTP 收发全在 Rust 核心里（{@code crates/http}、{@code crates/airdrop}），Java 手里一个字节
     * 都不过；而这一层能写进文件的，只有 Java 自己调 {@link #d} 的那些点。留着「含报文」就是一句
     * 兑现不了的承诺——人选了 debug，翻日志找不到任何报文，只会以为日志坏了。
     *
     * <p>什么时候能把这句话改回去：Rust 那边把报文的收发也回调给 Java（或者干脆自己写进同一个
     * 文件），那时候「报文」才有东西可截——配套的 {@code log.preview_bytes} 是同一个前提下才有
     * 意义的旋钮，已经在设置表里一并摘掉了（就一个读取方都没有的滑杆，留着只会让人以为调它能
     * 看到报文）。在那之前，这一档如实说「细节」。
     */
    static String describe(Context app) {
        switch (levelOf(app)) {
            case OFF: return "日志已关";
            case ERROR: return "只记错误";
            case DEBUG: return "记全部，含过程细节";
            default: return "记常规与错误";
        }
    }

    // ------------------------------------------------------------ 四个口子

    static int v(String tag, String message) {
        if (level >= DEBUG) {
            Diagnostics.write("debug", tag, message);
        }
        return android.util.Log.v(tag, message);
    }

    static int d(String tag, String message) {
        if (level >= DEBUG) {
            Diagnostics.write("debug", tag, message);
        }
        return android.util.Log.d(tag, message);
    }

    static int i(String tag, String message) {
        if (level >= INFO) {
            Diagnostics.write("info", tag, message);
        }
        return android.util.Log.i(tag, message);
    }

    static int w(String tag, String message) {
        if (level >= WARN) {
            Diagnostics.write("warn", tag, message);
        }
        return android.util.Log.w(tag, message);
    }

    static int e(String tag, String message) {
        if (level >= ERROR) {
            Diagnostics.write("error", tag, message);
        }
        return android.util.Log.e(tag, message);
    }

    /**
     * 带异常的那几个重载。
     *
     * <p>平台版还有 {@code i(String, String, Throwable)} 这一档，所以这里也补上——同名的前提是
     * 签名得盖得住调用点，少一个就是编不过。异常折叠进那一行：文件日志是给人拿走的，堆栈散在
     * 后面几行、没有级别前缀，读的人会更难认；而且 logcat 那一路本来就有完整堆栈。
     */
    static int i(String tag, String message, Throwable error) {
        return i(tag, message + " — " + error);
    }

    static int w(String tag, String message, Throwable error) {
        return w(tag, message + " — " + error);
    }

    static int e(String tag, String message, Throwable error) {
        return e(tag, message + " — " + error);
    }

    /** {@link android.util.Log#getStackTraceString} 的转发，语义与原版一致。 */
    static String getStackTraceString(Throwable error) {
        return android.util.Log.getStackTraceString(error);
    }
}
