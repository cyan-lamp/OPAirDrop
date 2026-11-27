package com.oshare.airdrop;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * BLE 扫描探针：只看「对方的隔空投送现在开着吗」。
 *
 * <p># 为什么需要它
 *
 * <p>2026-11-27 把三条判据都实测了一遍，结论是它们都答不了「AirDrop 开没开」：
 *
 * <ul>
 *   <li>驱动 peer 槽位：关掉 AirDrop 后 `created=1 mapped=1 deleting=0 peers=1` 一个字不变；</li>
 *   <li>AWDL presence：关掉后照样 6–112 ms 新鲜；</li>
 *   <li>mDNS 应答：能答，但实测最长中断 11.1 秒。</li>
 * </ul>
 *
 * <p>而 iOS 在隔空投送接收范围开启时会持续广播 BLE nearby-info（Apple 厂商 ID {@code 0x004C}）。
 * 扫到 → 开着；扫不到 → 关了。这是**事件驱动、秒级**的，不依赖它愿不愿意答 mDNS。
 *
 * <p># 这一步只打日志
 *
 * <p>先验证「AirDrop 开/关时广播到底有没有、停不停」，**不改任何显示逻辑**。锁屏后 iOS 会降低
 * 广播频率（甚至停播），这一点必须先量出来，才知道能不能当唯一判据、还是只当“解锁时的即时判据”。
 */
final class BleScanProbe {

    private static final String TAG = "OPAirDrop";

    /** Apple 的厂商 ID（小端写在广播里是 4C 00）。 */
    private static final int APPLE = 0x004C;

    private static BluetoothLeScanner scanner;
    private static ScanCallback callback;

    private BleScanProbe() {
    }

    /** 开始扫描。重复调用无副作用；没权限时如实记一行，不抛。 */
    static void start(Context context) {
        if (scanner != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "ble scan: 没有 BLUETOOTH_SCAN，跳过");
            return;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.i(TAG, "ble scan: 适配器没开，跳过");
            return;
        }
        BluetoothLeScanner found = adapter.getBluetoothLeScanner();
        if (found == null) {
            Log.i(TAG, "ble scan: 拿不到 scanner，跳过");
            return;
        }
        callback = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                report("result", result, result.getScanRecord());
            }

            @Override
            public void onBatchScanResults(java.util.List<ScanResult> results) {
                for (ScanResult result : results) {
                    report("batch", result, result.getScanRecord());
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.w(TAG, "ble scan: failed code=" + errorCode);
            }
        };
        try {
            found.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build(), callback);
            scanner = found;
            Log.i(TAG, "ble scan: started (low latency)");
        } catch (Throwable failed) {
            Log.w(TAG, "ble scan: 起不来 " + failed);
        }
    }

    /** 停止扫描：只打状态，不保证一定停了（进程结束时会自己停）。 */
    static void stop() {
        BluetoothLeScanner held = scanner;
        scanner = null;
        if (held == null || callback == null) {
            return;
        }
        try {
            held.stopScan(callback);
            Log.i(TAG, "ble scan: stopped");
        } catch (Throwable failed) {
            Log.w(TAG, "ble scan: 停不下来 " + failed);
        }
        callback = null;
    }

    /**
     * 只记「有没有 Apple 的帧」，其余不记：这条日志每几秒可能几十行，写多了会把别的证据冲掉。
     * 命中时把厂商数据长度与首字节一起写下来——AirDrop 的 nearby-info 帧首字节是 0x10/0x05 一类，
     * 长度能区分「它只是普通 BLE 广播」和「它确实在喊隔空投送」。
     */
    private static void report(String how, ScanResult result, ScanRecord record) {
        if (record == null) {
            return;
        }
        byte[] apple = record.getManufacturerSpecificData(APPLE);
        if (apple == null || apple.length == 0) {
            return;
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(apple.length, 6); i++) {
            hex.append(String.format("%02x", apple[i]));
        }
        Log.i(TAG, "ble scan: apple " + how + " " + result.getDevice().getAddress()
                + " rssi=" + result.getRssi() + " len=" + apple.length + " head=" + hex);
    }
}
