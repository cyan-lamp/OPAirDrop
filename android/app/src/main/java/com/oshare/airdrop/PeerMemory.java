package com.oshare.airdrop;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 对端的磁盘记忆（评审拆分路线的第 5 刀：从 {@code Peers} 里切出来的存储层）。
 *
 * <p>只管「上次能连的那几台」的落盘与读回：格式是一行一台 {@code 名字\t地址\t端口}，
 * 存在 {@code peers} 这个 SharedPreferences 里。**筛选谁值得记**（连得上、不可疑、不是
 * 自己）是账目的活，留在 {@code Peers.remember}；这里只做可靠的读写与格式解析。
 */
final class PeerMemory {

    private PeerMemory() {
    }

    /**
     * 上一次浏览里能连的那几台，落在磁盘上，留给下一次冷启动。
     *
     * <p># 为什么要记到磁盘
     *
     * <p>mDNS 是**对方说、我们听**：它那一刻不讲，我们只能等。2026-11-19 18:26 的日志里，
     * 进程被系统 stop 掉（`o-stop(40)`，从最近任务划掉）之后重启，`browse` 在 18:26:29.86
     * 就开始问了，而 iPhone 直到 18:26:45.24 才答——**15.4 秒**的空列表。这期间它可能正在
     * 收一个 24 MB 的文件，也可能只是应答器在睡。等待本身没有错，错在**已经把地址忘掉了**：
     * 那条链路本地地址由 MAC 推出来，跨进程重启不会变。
     *
     * <p>所以能连的时候把「名字 + 地址 + 端口」写下来。下次启动先按这张旧名单敲一遍门——
     * 敲通就立刻显示（实测 25–38 毫秒），不必等它开口；敲不通就当没这回事，交给 mDNS。
     * 判据还是那一个：**端口开着才算在**，所以旧名单不会把一台已经走了的设备留在屏幕上。
     */
    private static final String PREFS = "peers";

    /** 旧名单存在这个键下，一行一台：{@code 名字 \t 地址 \t 端口}。 */
    private static final String REMEMBERED = "remembered";

    /** 最多记几台。名单只是冷启动的加速器，长了既没用又容易被淘汰的老设备占着。 */
    static final int REMEMBERED_MAX = 6;

    /**
     * 把名单落盘。空串也是一次合法写入：这回一台都没记上，就把它上一轮的名单清掉。
     */
    static void save(Context context, String list) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(REMEMBERED, list).apply();
    }

    /**
     * 读回名单，一行一项 {@code [名字, 地址, 端口]}（都已 trim）。行格式不对（不足三列）
     * 的直接跳过；字段本身合不合法（空、端口非正、是不是自己）由调用方判——那需要
     * 表和 own 集合，是账目侧的事。
     */
    static List<String[]> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(REMEMBERED, "");
        List<String[]> rows = new ArrayList<>();
        if (saved == null || saved.isBlank()) {
            return rows;
        }
        for (String line : saved.split("\n")) {
            String[] field = line.split("\t");
            if (field.length < 3) {
                continue;
            }
            rows.add(new String[]{field[0].trim(), field[1].trim(), field[2].trim()});
        }
        return rows;
    }

    /** 解析一个端口号；不是数、是负数都算没有。 */
    static int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
