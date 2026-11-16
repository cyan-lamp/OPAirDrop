package com.oshare.airdrop;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * 设置表里的一行，按毫秒取。
 *
 * <p># 为什么要有这一个类
 *
 * <p>读设置这件事原本只在 {@link Listener} 里，因为只有它在每次连接时重读。等到发送端、
 * 收件箱、邀请卡片、实例名历史都要读同一张表时，每个地方都自己 {@code SettingsScreen.open}
 * 一次会得到四个几乎一样、但各自会漂的方法——而它们必须一致的地方恰恰是「读不出来怎么办」。
 * 所以只留一份。
 *
 * <p># 读不出来就回落到常量
 *
 * <p>文件本身读不出来（权限、半行、坏字节）时用常量，因为「设置页坏了」不该等于「功能全坏」。
 *
 * <p>注意这里回落的粒度是**整份**，不是一行：`from_text` 遇到范围外的数字或者不是数字的
 * 值时，拒的是整个文件，于是 {@code read} 返回 null，每一行都变成默认值。这条在设备上量过
 * （`discovery.remembered_peers=999`、`client.read_timeout_ms=abc` 各一次，两次都是全部回落）。
 * 所以「手改一个越界数字只会让那一行失效」是不成立的，改设置请走设置页。
 */
final class Tuning {

    private Tuning() {}

    /** 当前的设置表，读不出来就是 null——每个调用方都把 null 当「全是默认值」。 */
    static SettingsScreen read(Context context) {
        try {
            return SettingsScreen.open(SettingsFile.of(context).toPath());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** 同一个目录（也就是同一个应用私有目录里）的设置表。给没有 Context 的地方用。 */
    static SettingsScreen read(File directory) {
        if (directory == null) {
            return null;
        }
        try {
            return SettingsScreen.open(SettingsFile.of(directory).toPath());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * 一行时间设置；取不到就用 {@code fallback}。
     *
     * <p>下面那道夹取和那个捕获今天都走不到：能进到这里的值要么是表里的默认值，要么来自
     * 一份通过了 `set` 的文件，两者都在范围内（越界会让整份文件读不出来，见类注释）。
     * 留着，是因为这个函数对任何一份表都要成立，而它防的后果——超时为 0，每次连接都在
     * 对端开口之前就断——比这两行贵得多。
     */
    static long ms(SettingsScreen screen, String key, long fallback) {
        SettingsScreen.Setting setting = screen == null ? null : screen.setting(key);
        if (setting == null) {
            return fallback;
        }
        try {
            long value = Long.parseLong(setting.value().trim());
            return Math.min(Math.max(value, setting.min()), setting.max());
        } catch (NumberFormatException notWhole) {
            return fallback;
        }
    }

    /** 同上，自己把表读一次。 */
    static long ms(Context context, String key, long fallback) {
        return ms(read(context), key, fallback);
    }

    /** 同上，从应用私有目录读。 */
    static long ms(File directory, String key, long fallback) {
        return ms(read(directory), key, fallback);
    }
}
