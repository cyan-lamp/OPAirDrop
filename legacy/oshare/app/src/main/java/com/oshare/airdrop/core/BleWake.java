package com.oshare.airdrop.core;

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
import android.util.Log;

/**
 * The AirDrop BLE advertisement, transmitted for as long as a browse is running.
 *
 * <p>This is layer 1 of the stack and the only layer that is a trigger rather than a
 * transport: nothing in it carries data. An iPhone scans for these and only then brings
 * AWDL's AirDrop server up. That is the whole reason a phone can be present at every
 * other layer -- its MIFs arrive, our data frames reach it, our TCP SYN draws an RST --
 * while nothing on port 8770 is listening. The radio is on, the server is not, and no
 * amount of correct framing at layers 2 and 3 changes that.
 *
 * <p>So the advertisement is not an optimisation. A sender that skips it is asking a
 * question of a device that has not decided to answer anything yet.
 *
 * <p>Two settings are copied from Apple's own adverts rather than chosen: connectable,
 * and a low-latency interval. Android picks the exact interval (100 ms in low-latency
 * mode on every stack this has been run against), which is what current receivers
 * answer to.
 *
 * <p>The start is deliberately split into a refusal half and an asynchronous half:
 * {@link #start} can only report what it knows synchronously (permissions, adapter,
 * capability), because {@code startAdvertising} answers on the callback. {@link #status}
 * is where the callback's verdict lands, and it is what the adb gate reports.
 */
public final class BleWake {

    private static final String TAG = "OPAirDrop/Ble";

    /** Guards the advertiser registration; never held across the post. */
    private static final Object LOCK = new Object();

    /** Held statically: the advertiser stops if its registration is collected. */
    private static BluetoothLeAdvertiser advertiser;
    private static AdvertiseCallback callback;

    /** What the last attempt ended up as: idle, starting, advertising, or failed: why. */
    private static volatile String state = "idle";

    private BleWake() {
    }

    /** The last failure, or {@code null} if the last attempt did not fail. */
    public static String lastError() {
        String now = state;
        return now.startsWith("failed: ") ? now.substring(8) : null;
    }

    /** Where the advertisement currently stands, in one line. */
    public static String status() {
        return state;
    }

    /**
     * Starts advertising, or explains why it could not.
     *
     * <p>Called on the main looper when there is one, because {@code startAdvertising}
     * delivers its result to the calling thread's looper, and a background thread in a
     * service has none -- which turns a permission problem into a null-pointer that
     * says nothing. A caller that already runs on the main thread is executed inline
     * rather than posted, so the adb gate sees the registration happen in order.
     *
     * @return {@code true} if a registration was handed to the stack
     */
    public static boolean start(final Context context) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            return refuse("BLUETOOTH_ADVERTISE not granted"
                    + " (adb shell pm grant " + context.getPackageName() + " "
                    + Manifest.permission.BLUETOOTH_ADVERTISE + ")");
        }
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            return refuse("no Bluetooth adapter");
        }
        // isEnabled and isMultipleAdvertisementSupported are both guarded by
        // BLUETOOTH_CONNECT on API 31+, so they throw rather than return false when it
        // is missing -- and a SecurityException from a getter reads like a crash.
        try {
            if (!adapter.isEnabled()) {
                // Turning it on programmatically needs BLUETOOTH_CONNECT and, on recent
                // releases, a user gesture; pretending otherwise only hides the cause.
                return refuse("Bluetooth is off");
            }
            if (!adapter.isMultipleAdvertisementSupported()) {
                return refuse("adapter does not support LE advertising");
            }
        } catch (SecurityException blocked) {
            return refuse("adapter query denied: " + blocked.getMessage()
                    + " (adb shell pm grant " + context.getPackageName() + " "
                    + Manifest.permission.BLUETOOTH_CONNECT + ")");
        }

        final BleAdvert advert = BleAdvert.anonymous(BleAdvert.randomTag());
        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        final AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(BleAdvert.APPLE_COMPANY_ID, advert.manufacturerData())
                .build();

        final AdvertiseCallback listener = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings inEffect) {
                state = "advertising";
                Log.i(TAG, "advertising " + advert.hex() + " mode=" + inEffect.getMode()
                        + " tx=" + inEffect.getTxPowerLevel());
            }

            @Override
            public void onStartFailure(int errorCode) {
                state = "failed: onStartFailure " + errorCode + " (" + reason(errorCode) + ")";
                Log.w(TAG, state);
            }
        };

        state = "starting";
        Runnable begin = () -> {
            synchronized (LOCK) {
                if (advertiser != null) {
                    state = "advertising";
                    return; // already advertising; a second registration would fail
                }
                try {
                    advertiser = adapter.getBluetoothLeAdvertiser();
                } catch (SecurityException blocked) {
                    state = "failed: getBluetoothLeAdvertiser denied: " + blocked.getMessage();
                    Log.w(TAG, state);
                    return;
                }
                if (advertiser == null) {
                    state = "failed: getBluetoothLeAdvertiser returned null";
                    Log.w(TAG, state);
                    return;
                }
                callback = listener;
                Log.i(TAG, "start: " + advert.hex());
                try {
                    advertiser.startAdvertising(settings, data, listener);
                } catch (RuntimeException immediate) {
                    // A null looper on the calling thread, or a permission revoked
                    // between the check above and here, surfaces as this.
                    state = "failed: startAdvertising threw " + immediate;
                    Log.w(TAG, state);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            begin.run();
        } else {
            new Handler(Looper.getMainLooper()).post(begin);
        }
        return true;
    }

    /** Withdraws the advertisement; the phone goes back to sleep on its own. */
    public static void stop() {
        Runnable end = () -> {
            synchronized (LOCK) {
                if (advertiser == null) {
                    state = "idle";
                    return;
                }
                try {
                    advertiser.stopAdvertising(callback);
                } catch (RuntimeException alreadyGone) {
                    Log.w(TAG, "stopAdvertising: " + alreadyGone);
                }
                advertiser = null;
                callback = null;
                state = "idle";
                Log.i(TAG, "stopped");
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            end.run();
        } else {
            new Handler(Looper.getMainLooper()).post(end);
        }
    }

    private static boolean refuse(String why) {
        state = "failed: " + why;
        Log.w(TAG, state);
        return false;
    }

    /**
     * The numeric codes {@code onStartFailure} uses, spelled out.
     *
     * <p>They are constants on {@code AdvertiseCallback}, which is why they are not
     * referenced directly: the names read better in a log than {@code error 4}.
     */
    private static String reason(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "data too large";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "too many advertisers";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "already started";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "internal error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "feature unsupported";
            default:
                return "unknown";
        }
    }
}
