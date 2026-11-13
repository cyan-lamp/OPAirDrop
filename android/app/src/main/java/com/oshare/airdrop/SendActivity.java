package com.oshare.airdrop;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 发送面板：仿一加互传「与 iPhone 互传」那一页。
 *
 * <p># 为什么是这一页，而不是一张列表
 *
 * <p>按下分享面板里那行「发送到 iPhone」的人，此刻已经决定要发了；他要看的是「对面在哪、
 * 我点谁」，不是一份文件清单。所以这一页照那个页面的份量分三段：**顶部**（返回 + 标题 +
 * 本设备名）、**中部**（搜索态是雷达，结果态是头像）、**底部**（说明卡）。文件信息收成标题
 * 下面一行小字。
 *
 * <p># 头像上那圈进度是真的
 *
 * <p>它来自 Rust 侧读文件时的计数（每 512 KiB 一次），不是定时器。四个阶段对应协议四步：
 * 连上 → 报价（对面在弹确认）→ 传字节 → 收端确认。中间那段可能停十几秒，所以说的是
 * 「等待对方接受」，而不是一个停在 0% 的进度条。
 *
 * <p># 颜色跟着日夜走
 *
 * <p>浅色近白底黑字、深色近黑底白字，蓝只给状态与进度。颜色来自 {@link Scheme}，和主界面
 * 同一张表——两处各自定义，迟早会出现「设置页是浅的、这里还是深色」。
 */
public final class SendActivity extends Activity implements Peers.Watcher {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 300_000;

    private Scheme S;
    private float density;

    /** 要发的东西。复制到 cache 之后才进这个列表。 */
    private final List<File> files = new ArrayList<>();
    private long totalBytes;

    private Peers peers;
    private LinearLayout stage;
    private TextView self;
    private TextView subtitle;

    /** 每台设备一行状态，键是设备名；重扫时复用，避免文字闪一下。 */
    private final Map<String, String> statuses = new LinkedHashMap<>();

    private volatile boolean sending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        S = Scheme.of(mode != Configuration.UI_MODE_NIGHT_YES);
        density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(S.bg);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), 0, dp(20), 0);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(topBar());
        column.addView(subtitleLine());
        stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        column.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(bottomCard());

        setContentView(root);
        showSearching("正在搜索附近设备……");

        new Thread(this::intake, "opairdrop-intake").start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        peers = new Peers(this, this);
        peers.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (peers != null) {
            peers.stop();
            peers = null;
        }
    }

    // ------------------------------------------------------------------ 顶部

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(28), 0, dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(24);
        back.setTextColor(S.text);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(S.cardHi, 22));
        back.setOnClickListener(view -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("与 iPhone 互传");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(S.text);
        titles.addView(title);

        self = new TextView(this);
        self.setTextSize(12.5f);
        self.setTextColor(S.textDim);
        self.setPadding(0, dp(3), 0, 0);
        self.setText("本设备：" + senderName() + "（Air）");
        titles.addView(self);
        bar.addView(titles);
        return bar;
    }

    /** 标题下面那一行：发了什么。空的时候说「正在准备」，别留一行空的。 */
    private View subtitleLine() {
        subtitle = new TextView(this);
        subtitle.setTextSize(12.5f);
        subtitle.setTextColor(S.textDim);
        subtitle.setPadding(dp(2), 0, 0, dp(6));
        subtitle.setText("正在准备……");
        return subtitle;
    }

    // ------------------------------------------------------------------ 中部

    /** 搜索态：雷达 + 一行字。 */
    private void showSearching(String line) {
        stage.removeAllViews();
        Radar radar = new Radar();
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(180), dp(180));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.topMargin = dp(36);
        stage.addView(radar, size);

        TextView hint = new TextView(this);
        hint.setText(line);
        hint.setTextSize(14);
        hint.setTextColor(S.textDim);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(20), 0, 0);
        stage.addView(hint);
    }

    @Override
    public void onPeers(List<Peers.Peer> found, String problem) {
        List<Peers.Peer> usable = new ArrayList<>();
        for (Peers.Peer peer : found) {
            if (peer.connectable()) {
                usable.add(peer);
            }
        }
        if (usable.isEmpty()) {
            showSearching(problem != null ? problem : "正在搜索附近设备……");
            return;
        }
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setGravity(Gravity.TOP);
        grid.setPadding(0, dp(20), 0, 0);
        for (Peers.Peer peer : usable) {
            grid.addView(deviceCell(peer));
        }
        stage.removeAllViews();
        stage.addView(grid);
    }

    /** 一台设备：圆头像（带进度环）+ 名字 + 状态。 */
    private View deviceCell(Peers.Peer peer) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(8), 0, dp(8), dp(16));

        Nick nick = new Nick();
        cell.addView(nick, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView name = new TextView(this);
        name.setText(peer.name);
        name.setTextSize(14);
        name.setTextColor(S.text);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(8), 0, 0);
        cell.addView(name);

        TextView state = new TextView(this);
        state.setTextSize(12);
        state.setTextColor(S.accent);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(4), 0, 0);
        String known = statuses.get(peer.name);
        state.setText(known == null ? "" : known);
        cell.addView(state);

        cell.setOnClickListener(view -> send(peer, nick, state));
        return cell;
    }

    // ------------------------------------------------------------------ 底部

    /** 说明卡：和参照一样，告诉人为什么对面还没出现。 */
    private View bottomCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(S.card, 22));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(20);
        card.setLayoutParams(params);

        TextView head = new TextView(this);
        head.setText("如何与 iPhone 互传？");
        head.setTextSize(15);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(S.text);
        card.addView(head);

        TextView body = new TextView(this);
        body.setText("方式一：在控制中心打开 NFC，碰触一下 iPhone 即可互传\n"
                + "方式二：iPhone 安装并打开「OPPO 互联」应用");
        body.setTextSize(12.5f);
        body.setTextColor(S.textDim);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(8), 0, 0);
        card.addView(body);
        return card;
    }

    // ------------------------------------------------------------------ 传输

    private void intake() {
        ShareIntake.Batch batch = ShareIntake.resolve(this, getIntent());
        runOnUiThread(() -> {
            files.clear();
            files.addAll(batch.files);
            totalBytes = 0;
            for (File file : files) {
                totalBytes += file.length();
            }
            if (files.isEmpty()) {
                subtitle.setText(batch.problem == null ? "没有可发送的内容" : batch.problem);
            } else {
                subtitle.setText(files.size() + " 个文件 · " + size(totalBytes));
            }
        });
    }

    private void send(Peers.Peer peer, Nick nick, TextView state) {
        if (sending) {
            return;
        }
        if (files.isEmpty()) {
            state.setText("没有可发送的内容");
            return;
        }
        sending = true;
        say(peer, state, "正在连接…");
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }

        new Thread(() -> {
            try {
                byte[] frame = transfer(peer, paths, nick, state);
                Report report = Report.parse(new String(frame, java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    sending = false;
                    nick.finish();
                    say(peer, state, "已发送 · " + size(report.bytes));
                });
            } catch (Exception failed) {
                String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                runOnUiThread(() -> {
                    sending = false;
                    // 出错时把环褪回未开始的样子：留着半圈会读成「还在跑」。
                    nick.finish();
                    say(peer, state, message);
                });
            }
        }, "opairdrop-send").start();
    }

    private byte[] transfer(Peers.Peer peer, List<String> paths, Nick nick, TextView state)
            throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(peer.address, peer.port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        SSLSocket tls = (SSLSocket) trusting().getSocketFactory()
                .createSocket(raw, peer.host.isEmpty() ? peer.address : peer.host, peer.port, true);
        tls.setUseClientMode(true);
        tls.startHandshake();

        try (SSLSocket session = tls) {
            return Native.sendWithProgress(
                    peer.host.isEmpty() ? peer.address : peer.host,
                    senderName(),
                    modelName(),
                    UUID.randomUUID().toString(),
                    paths.toArray(new String[0]),
                    session.getInputStream(),
                    session.getOutputStream(),
                    (phase, sent, total) -> runOnUiThread(() -> {
                        switch (phase) {
                            case Native.Progress.READY -> say(peer, state, "已连接，正在请求发送…");
                            case Native.Progress.WAITING -> say(peer, state, "等待对方接受…");
                            case Native.Progress.SENDING -> {
                                nick.progress(total == 0 ? 0f : Math.min(1f, (float) sent / total));
                                say(peer, state, "发送中 "
                                        + (total == 0 ? 0 : sent * 100 / total) + "%");
                            }
                            case Native.Progress.DONE -> {
                                nick.progress(1f);
                                say(peer, state, "已发送，等待确认…");
                            }
                            default -> {
                            }
                        }
                    }));
        }
    }

    private void say(Peers.Peer peer, TextView state, String line) {
        statuses.put(peer.name, line);
        state.setText(line);
    }

    // ------------------------------------------------------------------ 自绘

    /** 圆头像：手机图形 + 一圈进度。 */
    private final class Nick extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float progress;

        Nick() {
            super(SendActivity.this);
            fill.setColor(S.light ? Color.WHITE : 0xFF2A2D33);
            ink.setColor(S.light ? Color.BLACK : Color.WHITE);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(dp(2.2f));
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(S.accent);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3));
            ring.setStrokeCap(Paint.Cap.ROUND);
        }

        void progress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        void finish() {
            progress = 0f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = Math.min(w, h) / 2f - dp(4);
            float cx = w / 2f;
            float cy = h / 2f;

            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, fill);

            // 手机图形：窄、高、圆角大，比例照参照图。
            float phoneW = radius * 0.60f;
            float phoneH = radius * 0.96f;
            box.set(cx - phoneW / 2f, cy - phoneH / 2f, cx + phoneW / 2f, cy + phoneH / 2f);
            float corner = phoneW * 0.28f;
            canvas.drawRoundRect(box, corner, corner, ink);
            canvas.drawLine(cx - phoneW * 0.16f, box.top + phoneH * 0.11f,
                    cx + phoneW * 0.16f, box.top + phoneH * 0.11f, ink);

            if (progress > 0f) {
                box.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawArc(box, -90f, 360f * progress, false, ring);
            }
        }
    }

    /** 搜索态的雷达：两圈很淡的呼吸环 + 一个蓝圆 + 手机图形。 */
    private final class Radar extends View {
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float phase;

        Radar() {
            super(SendActivity.this);
            halo.setColor(S.accent);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(dp(1));
            disc.setColor(S.accent);
            glyph.setColor(Color.WHITE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(dp(2.4f));
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float core = Math.min(w, h) * 0.30f;
            float far = Math.min(w, h) * 0.47f;

            // 两圈呼吸环，相位差半圈：读起来像「它在找人」。
            phase = (phase + 0.012f) % 1f;
            for (int index = 0; index < 2; index++) {
                float grown = (phase + index * 0.5f) % 1f;
                halo.setAlpha((int) (70 * (1f - grown)));
                canvas.drawCircle(cx, cy, core + (far - core) * grown, halo);
            }

            canvas.drawCircle(cx, cy, core, disc);
            float phoneW = core * 0.52f;
            float phoneH = core * 0.82f;
            box.set(cx - phoneW / 2f, cy - phoneH / 2f, cx + phoneW / 2f, cy + phoneH / 2f);
            float corner = phoneW * 0.3f;
            canvas.drawRoundRect(box, corner, corner, glyph);
            canvas.drawLine(cx - phoneW * 0.14f, box.top + phoneH * 0.1f,
                    cx + phoneW * 0.14f, box.top + phoneH * 0.1f, glyph);
            postInvalidateDelayed(32);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private android.graphics.drawable.Drawable round(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static String modelName() {
        String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL;
        if (model.toLowerCase(Locale.US).startsWith(maker.toLowerCase(Locale.US))) {
            return model;
        }
        return (maker + " " + model).trim();
    }

    /** 我在这台机器上的名字：设置里的 {@code device.name}，没设就用型号。 */
    private String senderName() {
        try {
            byte[] text = Native.loadSettings(SettingsFile.of(this).getAbsolutePath());
            for (String line : new String(text, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                int equals = line.indexOf('=');
                if (equals > 0 && line.substring(0, equals).trim().equals("device.name")) {
                    String value = line.substring(equals + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception unreadable) {
            // 读不出来就用型号：这一项不是必填。
        }
        return modelName();
    }

    private static SSLContext trusting() throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust, new SecureRandom());
        return context;
    }

    private static final class Report {
        long bytes;

        static Report parse(String text) {
            Report report = new Report();
            for (String line : text.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0 && line.substring(0, tab).equals("bytes")) {
                    try {
                        report.bytes = Long.parseLong(line.substring(tab + 1).trim());
                    } catch (NumberFormatException notANumber) {
                        report.bytes = 0;
                    }
                }
            }
            return report;
        }
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }
}
