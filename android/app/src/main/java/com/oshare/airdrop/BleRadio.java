package com.oshare.airdrop;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;

/**
 * 蓝牙电台：把 Rust 给出的那二十字节交给协议栈，此外不做任何决定。
 *
 * <p>分工和这一层其它地方一样——Java 拿着电台，Rust 拿着字节。二十字节的内容、四个广播
 * 参数、以及"这些码各自是什么意思"都在 {@code crates/ffi/src/ble.rs} 里；这里只做三件
 * 在 Rust 里做不了的事：拿 {@link BluetoothLeAdvertiser}、把 builder 拼出来、以及把栈的
 * 判决（走回调）送回 Rust。
 *
 * <p>所以这个类里没有一句话是判断：每个出口都是
 * {@link Native#bleVerdict(int)}，连"没给权限"也是。Rust 那边据此写出人话，一个地方说，
 * 一个地方改。
 *
 * <p>为什么不在这里写 {@code BluetoothAdapter} 之外的逻辑：这条广播**不运数据**。它只是
 * 敲门——对端听到它才把 AWDL 上的 AirDrop 服务拉起来。敲不开不该让页面变成一张错误页，
 * 已经醒着的对端照样会答。
 */
final class BleRadio {

    private static final String TAG = "OPAirDrop/Ble";

    /**
     * {@link Native#bleVerdict(int)} 的负数码，与 {@code ble::refusal} 一一对应。
     *
     * <p>正数留给协议栈自己的 {@code ADVERTISE_FAILED_*}，它从同一个通道回来。两边各写一遍
     * 是有意的：这些数字是 Java 与 Rust 之间的约定，和 {@code ABI_VERSION} 同性质——
     * 改一处就得改另一处，写在一处会让另一边看起来像"别人给的"。
     */
    static final int NO_PERMISSION = -1;
    static final int NO_ADAPTER = -2;
    static final int DISABLED = -3;
    static final int UNSUPPORTED = -4;
    static final int NO_ADVERTISER = -5;

    /** 护住广告注册；绝不在 post 期间持有。 */
    private static final Object LOCK = new Object();

    /**
     * 静态持有：注册一旦被回收，广播就停了。
     *
     * <p>这条坑很实际——把 advertiser 放在局部变量里，视觉上一切都对，广播却只活到方法
     * 返回为止。
     */
    private static BluetoothLeAdvertiser advertiser;
    private static AdvertiseCallback listener;

    private BleRadio() {
    }

    /**
     * 开始广播。
     *
     * <p>参数全是 Rust 给的，包括公司标识：那是 Apple 的 {@code 0x004c}，属于协议知识，
     * 不属于电台知识。
     *
     * <p>结局一律走 {@link Native#bleVerdict(int)}，包括当场就知道的那些。这样"交了注册、
     * 还没判决"和"当场就拒绝了"只有一条路径，Rust 的状态机不必猜这次是同步还是异步。
     *
     * <p>线程：{@code startAdvertising} 把结果投递给调用线程的 looper，而服务里的后台线程
     * 没有 looper——那会把一个权限问题变成一个什么都不说的空指针。已经在主线程时原地执行
     * 而不是 post，日志里的顺序才是真实的顺序。
     */
    static void start(
            final Context context,
            final int companyId,
            final byte[] data,
            final int mode,
            final int txPower,
            final boolean connectable,
            final int timeoutMs) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            settle(NO_PERMISSION);
            return;
        }
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            settle(NO_ADAPTER);
            return;
        }
        // isEnabled 和 isMultipleAdvertisementSupported 在 API 31+ 上都受
        // BLUETOOTH_CONNECT 保护，缺权限时它们**抛**而不是返回 false；而一个 getter 抛出的
        // SecurityException 在日志里看起来像崩溃。
        try {
            if (!adapter.isEnabled()) {
                // 用代码打开它需要 BLUETOOTH_CONNECT，新版本上还要一个用户手势；假装不是
                // 这么回事只会把原因藏起来。
                settle(DISABLED);
                return;
            }
            if (!adapter.isMultipleAdvertisementSupported()) {
                settle(UNSUPPORTED);
                return;
            }
        } catch (SecurityException blocked) {
            settle(NO_PERMISSION);
            return;
        }

        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(mode)
                .setTxPowerLevel(txPower)
                .setConnectable(connectable)
                .setTimeout(timeoutMs)
                .build();
        final AdvertiseData advert = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(companyId, data)
                .build();

        Runnable begin = () -> {
            synchronized (LOCK) {
                if (advertiser != null) {
                    // 已经在广播；第二次注册会失败，那不是失败，是已经成了。
                    settle(0);
                    return;
                }
                try {
                    advertiser = adapter.getBluetoothLeAdvertiser();
                } catch (SecurityException blocked) {
                    settle(NO_PERMISSION);
                    return;
                }
                if (advertiser == null) {
                    settle(NO_ADVERTISER);
                    return;
                }
                listener = new AdvertiseCallback() {
                    @Override
                    public void onStartSuccess(AdvertiseSettings inEffect) {
                        Log.i(TAG, "start: " + hex(data) + " mode=" + inEffect.getMode()
                                + " tx=" + inEffect.getTxPowerLevel());
                        settle(0);
                    }

                    @Override
                    public void onStartFailure(int errorCode) {
                        settle(errorCode);
                    }
                };
                try {
                    advertiser.startAdvertising(settings, advert, listener);
                } catch (RuntimeException immediate) {
                    // 调用线程没有 looper，或者权限在上面那次检查之后被撤了，都会变成这个。
                    advertiser = null;
                    listener = null;
                    settle(NO_ADVERTISER);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            begin.run();
        } else {
            new Handler(Looper.getMainLooper()).post(begin);
        }
    }

    /** 撤掉广播；对端会自己回去睡。没广播过也照样安静地结束。 */
    static void stop() {
        Runnable end = () -> {
            synchronized (LOCK) {
                if (advertiser == null) {
                    return;
                }
                try {
                    advertiser.stopAdvertising(listener);
                } catch (RuntimeException alreadyGone) {
                    Log.w(TAG, "stopAdvertising: " + alreadyGone);
                }
                advertiser = null;
                listener = null;
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            end.run();
        } else {
            new Handler(Looper.getMainLooper()).post(end);
        }
    }

    /**
     * 把结局交给 Rust，顺手把 Rust 写的那句话打进日志。
     *
     * <p>日志在这里打而不是在 Rust 里：这一层没有 logcat，而状态机在那边——所以句子从那边
     * 回来，落笔在这边。
     */
    private static void settle(int code) {
        byte[] line = Native.bleVerdict(code);
        String text = line == null
                ? "(no state line)"
                : new String(line, StandardCharsets.UTF_8);
        if (code == 0) {
            Log.i(TAG, text);
        } else {
            Log.w(TAG, text);
        }
    }

    /** 负载打进日志用；20 字节，一眼能看出是不是那二十字节。 */
    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
    }
}
