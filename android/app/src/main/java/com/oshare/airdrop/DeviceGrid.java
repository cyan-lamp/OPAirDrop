package com.oshare.airdrop;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 设备格的账目与绘制（评审拆分路线：SendPanel 的一部分，从 {@code SendActivity} 里切出来的
 * UI 状态层）。
 *
 * <p>每台设备在页面上是一格：圆头像（带进度环）+ 名字 + 一行状态。这六个账本——谁在屏幕上、
 * 各自的状态行、各自的颜色、各自的进度——原来散在 Activity 里，任何一处读写都得自己记得
 * 与其余几处的关系；现在收进唯一的地方，Activity 只在「发生了什么事」时调用对应的方法，
 * 说什么、画什么由这里决定。
 *
 * <p>真名（{@code realNames}）与探测调度不在这里：那是发现域的账，Activity 的
 * {@code showRealName} 负责把问到的名字喂给 {@link #name} 之后，这里的账自动跟上。
 */
final class DeviceGrid {

    /** Activity 侧的读口：颜色取自当前 Scheme，点击回到 Activity 的状态机。 */
    interface Host {
        boolean light();

        int accent();

        int text();

        int red();

        float density();

        /** 格子上那行名字显示什么（占位=型号，真名优先——三道来源在 Activity）。 */
        String label(Peers.Peer peer);

        /** 格子被点了。一次只跑一个传输，所以「正在传」时点任何一台都当作取消。 */
        void onCellClick(Peers.Peer peer);
    }

    private final Host host;
    private final int nickDp;

    private final Map<String, String> statuses = new LinkedHashMap<>();
    private final Map<String, Boolean> statusAlarms = new LinkedHashMap<>();
    private final Map<String, Float> progressOf = new LinkedHashMap<>();
    private final Map<String, SendViews.Nick> nicks = new LinkedHashMap<>();
    private final Map<String, TextView> stateViews = new LinkedHashMap<>();
    private final Map<String, TextView> nameViews = new LinkedHashMap<>();

    DeviceGrid(Host host, int nickDp) {
        this.host = host;
        this.nickDp = nickDp;
    }

    private int dp(float value) {
        return Ui.dp(host.density(), value);
    }

    /** 重建/换主题时清视图账本（状态文本与进度的账保留——进度环要接着上一次的位置画）。 */
    void clearViews() {
        nicks.clear();
        stateViews.clear();
        nameViews.clear();
    }

    /** 现在屏幕上画着的设备名集合，供「格子该重建还是只刷新」的比对。 */
    Set<String> tracked() {
        return nicks.keySet();
    }

    /**
     * 一台设备：圆头像（带进度环）+ 名字 + 状态。
     *
     * <p>重建之前就已经在传的那台，环要接着上一次的位置画，而不是从 0 开始。
     * 点的时候回 Activity 的表里取一次这一台：对端可能已经被清掉又加回来了（一次广播
     * 空白就够了），而这个格子抓着的是旧对象，地址端口都可能是上一轮的。
     */
    View cell(Context context, final Peers.Peer peer) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(5), 0, dp(5), dp(14));

        SendViews.Nick nick = new SendViews.Nick(context, host.light(), host.density(), host.accent());
        cell.addView(nick, new LinearLayout.LayoutParams(dp(nickDp), dp(nickDp)));

        TextView name = new TextView(context);
        // 名字换行，不硬截成一行：格子就 106dp 宽，「张三的 iPhone 15 Pro」这类名字截一行
        // 只剩「张三的 iP…」，认不出是谁。两行封顶，还不下才省略——一台设备不该把下面顶出屏幕。
        name.setText(host.label(peer));
        name.setTextSize(12.5f);
        name.setTextColor(host.text());
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(0, 0.95f);
        name.setPadding(0, dp(6), 0, 0);
        cell.addView(name);

        TextView state = new TextView(context);
        state.setTextSize(10.5f);
        state.setTextColor(Boolean.TRUE.equals(statusAlarms.get(peer.name)) ? host.red() : host.accent());
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(2), 0, 0);
        String known = statuses.get(peer.name);
        state.setText(known == null ? "" : known);
        cell.addView(state);

        nicks.put(peer.name, nick);
        stateViews.put(peer.name, state);
        nameViews.put(peer.name, name);
        Float progress = progressOf.get(peer.name);
        if (progress != null) {
            nick.progress(progress);
        }

        cell.setOnClickListener(view -> host.onCellClick(peer));
        return cell;
    }

    /** 只换名字那一行（有动画：占位换真名时淡入，见 {@code setLabel}）。 */
    void name(String peerName, String wanted) {
        setLabel(nameViews.get(peerName), wanted);
    }

    /**
     * 写一句状态，并决定它是普通色还是告警色。
     *
     * <p>{@code alarm} 传 {@code true} 的只有「对方拒绝」这一种：它不是一个过渡中的进度，
     * 而是这一轮投送的结论，和其余几行（正在连接…、已发送…）不是一类话，用同一个蓝色会
     * 读成同一种事。
     */
    void status(String peerName, String line, boolean alarm) {
        statuses.put(peerName, line);
        statusAlarms.put(peerName, alarm);
        TextView state = stateViews.get(peerName);
        if (state != null) {
            state.setText(line);
            state.setTextColor(alarm ? host.red() : host.accent());
        }
    }

    /** 进度环推进到某一格的某一值（0–1）；百分比另由卡片层说。 */
    void progress(String peerName, float value) {
        progressOf.put(peerName, value);
    }

    /** 清某一格的进度（投送结束/换设备）。 */
    void removeProgress(String peerName) {
        progressOf.remove(peerName);
    }

    /** 按账本重画某一台的环。视图可能不在（还没建或已重建）。 */
    void redrawProgress(String peerName) {
        SendViews.Nick nick = nicks.get(peerName);
        if (nick == null) {
            return;
        }
        Float progress = progressOf.get(peerName);
        nick.progress(progress == null ? 0f : progress);
    }

    /**
     * 名字换行不硬截：内容变了才换，换的时候淡入 180ms——占位换真名的那一下不该是一次
     * 生硬的跳变。
     */
    private static void setLabel(TextView view, String wanted) {
        if (view == null || wanted == null || wanted.contentEquals(view.getText())) {
            return;
        }
        view.setText(wanted);
        view.animate().cancel();
        view.setAlpha(0.3f);
        view.animate().alpha(1f).setDuration(180).start();
    }
}
