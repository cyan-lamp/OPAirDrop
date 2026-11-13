package com.oshare.airdrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.oshare.airdrop.core.BleWake;

/**
 * An adb-only switch for the BLE wake-up advertisement.
 *
 * <p>It exists to make one measurement possible in isolation: does an iPhone that is
 * sitting in Everyone mode, with AWDL up and answering nothing on 8770, start an
 * AirDrop server once it hears the advertisement? That question cannot be asked
 * through the send path, because the send path has ten other moving parts that would
 * have to be working first.
 *
 * <p>Guarded by {@code android.permission.DUMP}: the shell and the system hold it,
 * ordinary apps do not, and the alternative -- an unguarded exported receiver -- would
 * let any installed app put a discoverable Apple-format advertisement on the air under
 * our tag at will.
 *
 * <pre>
 *   adb shell pm grant com.oshare.airdrop android.permission.BLUETOOTH_ADVERTISE
 *   adb shell pm grant com.oshare.airdrop android.permission.BLUETOOTH_CONNECT
 *   adb shell am broadcast -a com.oshare.airdrop.BLE_WAKE --es op start
 *   adb shell am broadcast -a com.oshare.airdrop.BLE_WAKE --es op status
 *   adb shell am broadcast -a com.oshare.airdrop.BLE_WAKE --es op stop
 * </pre>
 *
 * <p>{@code am broadcast} runs the delivery as an ordered broadcast and prints the
 * result, so the reply line carries the reason advertising could not start -- a bare
 * "onStartFailure 3" in a log three screens away is not an answer. Note that the
 * callback lands after this receiver returns, which is why {@code start} reports
 * {@code starting} and {@code status} is the command that reports the verdict: sleeping
 * here to wait for it would block the very main looper the callback is queued on.
 */
public final class BleWakeReceiver extends BroadcastReceiver {

    private static final String TAG = "OPAirDrop/Ble";

    /** The action adb sends. */
    public static final String ACTION = "com.oshare.airdrop.BLE_WAKE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String op = intent.getStringExtra("op");
        if (op == null) {
            op = "start";
        }
        String result;
        switch (op) {
            case "start":
                result = BleWake.start(context) ? "asked" : "refused";
                result += ": " + BleWake.status();
                break;
            case "stop":
                BleWake.stop();
                result = "stopped: " + BleWake.status();
                break;
            case "status":
                result = BleWake.status();
                break;
            default:
                result = "unknown op \"" + op + "\"; use start, stop or status";
                break;
        }
        Log.i(TAG, "adb " + op + ": " + result);
        setResultCode(0);
        setResultData(result);
    }
}
