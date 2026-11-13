package com.oshare.airdrop;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 发送：把分享进来的文件送到一台附近的 iPhone 或 Mac。
 *
 * <p># 为什么是一个页面，而不是一个直接开传的对话框
 *
 * <p>AirDrop 是「人先选设备，设备再问人」：选错了，对面手机上会弹出一个它在等的确认框，而
 * 这边还以为已经发出去了。一个能看见设备名的列表把选择留在这边，代价是少一次点击。
 *
 * <p># 为什么列表是活的
 *
 * <p>对端会在打开这一页之后才出现（它刚被点亮屏幕，或刚从 AWDL 上醒来），也会在这一页停着
 * 的时候消失。列表定格在一开始的那一刻，等于让人对着一个空屏幕猜要不要再等等。
 *
 * <p># 证书
 *
 * <p>AirDrop 用的是自签证书，指纹由对端在自己广播的 TXT 里给出（{@code phash}）。这里接受
 * 任何证书，把「是不是那台设备」交给对面手机上的确认框——那条确认是这条链路上唯一由人做出的
 * 判断，也是这一版唯一有的。按指纹校验是欠着的（Task.md 记着），它挡的是同一链路里的冒名者，
 * 而那种攻击需要先在这块网卡上抢到同一个实例名。
 */
public final class SendActivity extends Activity implements Peers.Watcher {

    /** 连接超时。iPhone 在 AWDL 上从收到 SYN 到握手完成常在 2 秒以上。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;

    /** 读答案的超时。对面弹出确认框之后可能要等人按，给了 5 分钟。 */
    private static final int READ_TIMEOUT_MS = 300_000;

    private Chrome chrome;
    private Scheme S;

    private final List<File> files = new ArrayList<>();
    private long totalBytes;

    private Peers peers;
    private LinearLayout fileBody;
    private LinearLayout deviceCard;
    private TextView statusLine;
    private TextView fileSummary;

    /** 传输进行中时不许再点第二台：一个 socket 一条会话，并发没有意义。 */
    private boolean sending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        S = Scheme.of(mode != Configuration.UI_MODE_NIGHT_YES);
        chrome = new Chrome(this, S);

        LinearLayout body = chrome.pageBody();
        body.addView(back());
        body.addView(title());
        body.addView(fileCard(), chrome.margins(0, 16));
        body.addView(chrome.sectionTitle("附近设备"));
        deviceCard = chrome.card();
        body.addView(deviceCard, chrome.margins(0, 16));
        statusLine = chrome.caption("正在找附近的设备……");
        statusLine.setPadding(chrome.dp(2), chrome.dp(4), chrome.dp(2), 0);
        body.addView(statusLine);

        ScrollView scroll = chrome.scroller(body);
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(0, bars.top + chrome.dp(4), 0, bars.bottom + chrome.dp(24));
            return insets;
        });
        setContentView(scroll);

        // 复制文件要读磁盘，可能要几秒（一条 4 GB 的视频）。放在后台线程，页面先画出来，
        // 人能看到「正在准备」而不是一块白屏。
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

    /** 把这次分享的内容取到本地。 */
    private void intake() {
        Intent intent = getIntent();
        ShareIntake.Batch batch = ShareIntake.resolve(this, intent);
        runOnUiThread(() -> {
            files.clear();
            files.addAll(batch.files);
            totalBytes = 0;
            for (File file : files) {
                totalBytes += file.length();
            }
            paintFiles();
            if (batch.problem != null) {
                statusLine.setText(batch.problem);
            }
        });
    }

    private View back() {
        TextView back = chrome.text("‹ 返回", 15, true, S.accent);
        back.setPadding(0, chrome.dp(12), 0, chrome.dp(12));
        back.setOnClickListener(view -> finish());
        chrome.pressable(back);
        return back;
    }

    private View title() {
        LinearLayout box = chrome.column();
        box.addView(chrome.text("发送", 22, true, S.text));
        fileSummary = chrome.caption("正在准备……");
        fileSummary.setPadding(0, chrome.dp(4), 0, 0);
        box.addView(fileSummary);
        return box;
    }

    /** 要发的东西：一个总览，加前几项的名字。重建时只清空 {@link #fileBody}。 */
    private View fileCard() {
        LinearLayout card = chrome.card();
        fileBody = chrome.column();
        fileBody.addView(placeholderRow("准备中"));
        card.addView(fileBody);
        return card;
    }

    private View placeholderRow(String text) {
        TextView line = chrome.text(text, 14, false, S.textDim);
        line.setPadding(0, chrome.dp(4), 0, chrome.dp(4));
        return line;
    }

    /** 重建文件卡与标题下那行摘要。 */
    private void paintFiles() {
        if (fileSummary != null) {
            fileSummary.setText(files.isEmpty()
                    ? "没有可发送的内容"
                    : files.size() + " 个文件 · " + size(totalBytes));
        }
        LinearLayout card = fileBody;
        if (card == null) {
            return;
        }
        card.removeAllViews();
        if (files.isEmpty()) {
            card.addView(placeholderRow("没有可发送的内容"));
            return;
        }
        // 一张卡放得下六个名字；更多就只报数量——列二十个文件名，会把下面的设备列表挤出屏幕。
        int shown = Math.min(files.size(), 6);
        for (int index = 0; index < shown; index++) {
            File file = files.get(index);
            LinearLayout line = chrome.row();
            TextView name = chrome.text(file.getName(), 14, false, S.text);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            line.addView(name, chrome.mm(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView size = chrome.text(size(file.length()), 13, false, S.textDim);
            line.addView(size);
            card.addView(line, chrome.margins(4, 4));
        }
        if (files.size() > shown) {
            card.addView(chrome.caption("还有 " + (files.size() - shown) + " 个"), chrome.margins(4, 0));
        }
    }

    // ---------------------------------------------------------------- 设备列表

    @Override
    public void onPeers(List<Peers.Peer> found, String problem) {
        if (deviceCard == null) {
            return;
        }
        List<Peers.Peer> usable = new ArrayList<>();
        for (Peers.Peer peer : found) {
            if (peer.connectable()) {
                usable.add(peer);
            }
        }
        deviceCard.removeAllViews();
        if (usable.isEmpty()) {
            deviceCard.addView(placeholderRow(problem != null
                    ? problem
                    : "没有找到设备。请确认对面的隔空投送是「所有人」或「仅联系人」，并且屏幕亮着。"));
            return;
        }
        for (Peers.Peer peer : usable) {
            deviceCard.addView(deviceRow(peer));
            if (usable.indexOf(peer) < usable.size() - 1) {
                deviceCard.addView(chrome.divider(), chrome.margins(10, 10));
            }
        }
    }

    private View deviceRow(Peers.Peer peer) {
        TextView detail = chrome.text("", 13.5f, false, S.textDim);
        LinearLayout row = chrome.deviceRow(IconView.SEND, peer.name, detail);
        detail.setText(detailOf(peer));
        chrome.pressableRow(row, new float[]{14, 14, 14, 14});
        row.setOnClickListener(view -> send(peer));
        return row;
    }

    private static String detailOf(Peers.Peer peer) {
        String model = peer.model.isEmpty() ? "隔空投送" : peer.model;
        return model + " · 点一下发送";
    }

    // ---------------------------------------------------------------- 传输

    private void send(Peers.Peer peer) {
        if (sending) {
            return;
        }
        if (files.isEmpty()) {
            statusLine.setText("没有可发送的内容");
            return;
        }
        sending = true;
        statusLine.setText("正在连接 " + peer.name + "……");
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }

        new Thread(() -> {
            try {
                Report report = transfer(peer, paths);
                runOnUiThread(() -> {
                    sending = false;
                    statusLine.setText("已发送到 " + report.peer + " · " + size(report.bytes));
                });
            } catch (Exception failed) {
                String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                runOnUiThread(() -> {
                    sending = false;
                    statusLine.setText("没有发出去：" + message);
                });
            }
        }, "opairdrop-send").start();
    }

    /** 一次完整会话：连接、握手、三个请求。 */
    private Report transfer(Peers.Peer peer, List<String> paths) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(peer.address, peer.port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        SSLSocket tls = (SSLSocket) trusting().getSocketFactory()
                .createSocket(raw, peer.host.isEmpty() ? peer.address : peer.host, peer.port, true);
        tls.setUseClientMode(true);
        tls.startHandshake();

        try (SSLSocket session = tls) {
            // 进度来自读文件那一层（每 512 KiB 一次），所以这一条在传输过程中会一直动；
            // 它跑在发送线程上，界面只在主线程改，两件事不能混。
            byte[] frame = Native.sendWithProgress(
                    peer.host.isEmpty() ? peer.address : peer.host,
                    senderName(),
                    modelName(),
                    UUID.randomUUID().toString(),
                    paths.toArray(new String[0]),
                    session.getInputStream(),
                    session.getOutputStream(),
                    (sent, total) -> runOnUiThread(() -> statusLine.setText(
                            "正在发送 " + Math.min(100, total == 0 ? 0 : sent * 100 / total) + "%")));
            return Report.parse(new String(frame, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** 一个什么都不校验的上下文。理由写在类注释里：能信的那一方是对面的确认框。 */
    private static SSLContext trusting() throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust, new SecureRandom());
        return context;
    }

    /** 我在这台机器上的名字：设置里的 {@code device.name}，没设就用系统给的型号。 */
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
            // 读不出来就用型号：消息头里这一项不是必填，缺了对面只显示一个「iPhone 附近」。
        }
        return modelName();
    }

    private static String modelName() {
        String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL;
        if (model.toLowerCase().startsWith(maker.toLowerCase())) {
            return model;
        }
        return (maker + " " + model).trim();
    }

    // ---------------------------------------------------------------- 小工具

    /** 传输报告：{@code peer} 与 {@code bytes} 两行。 */
    private static final class Report {
        String peer = "";
        long bytes;

        static Report parse(String text) {
            Report report = new Report();
            for (String line : text.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String key = line.substring(0, tab);
                String value = line.substring(tab + 1);
                if (key.equals("peer")) {
                    report.peer = value;
                } else if (key.equals("bytes")) {
                    try {
                        report.bytes = Long.parseLong(value.trim());
                    } catch (NumberFormatException notANumber) {
                        report.bytes = 0;
                    }
                }
            }
            return report;
        }
    }

    /** 人读的字节数：三位有效数字以内，单位跟着数量走。 */
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
        return String.format(java.util.Locale.US, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }
}
