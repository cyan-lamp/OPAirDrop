package com.oshare.airdrop.core;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Method;

/**
 * Transmits the AirDrop BLE advertisement and reports whether the system took it.
 *
 * <pre>
 *   app_process /system/bin com.oshare.airdrop.core.BleAdvertProbe [seconds]
 * </pre>
 *
 * <p>Written to test one hypothesis, because every layer above it already checks out:
 * an iPhone's AirDrop server is not listening until a BLE advertisement wakes it
 * (SPEC §2, DISC-5), so a TCP SYN sent to a visible AWDL peer draws a RST from a
 * kernel with nothing bound -- no matter how correct the frames, the checksums or the
 * schedule are. If that is what has been happening, advertising here should turn the
 * next SYN into a SYN-ACK.
 *
 * <p>The advertisement content itself lives in {@link BleAdvert}, which is product code
 * and knows nothing about Android; this class only drives the platform and reports.
 * Nothing here needs an app package: the process runs as uid 0, which is why the
 * permission checks on BLUETOOTH_ADVERTISE pass without a manifest.
 */
public final class BleAdvertProbe {

    /**
     * The {@code IBluetoothManager} binder this process resolved, kept so the advertiser
     * can be built from the same object the adapter was.
     */
    private static Object manager;

    private BleAdvertProbe() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("diag")) {
            diag();
            return;
        }
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        long until = System.currentTimeMillis() + seconds * 1000L;

        System.out.println("ble: uid=" + Process.myUid() + " seconds=" + seconds);

        // The platform delivers advertise callbacks through a Handler on the calling
        // thread's looper, so there has to be one before startAdvertising or the
        // callback is silently dropped and every failure looks like a timeout.
        Looper.prepareMainLooper();
        Handler handler = new Handler(Looper.myLooper());

        // Android 13 and later resolve Bluetooth through BluetoothServiceManager, which a
        // framework-launched process fills in from BluetoothFrameworkInitializer. A process
        // started with app_process never runs that, so every adapter call would NPE inside
        // the framework -- run it here, once, before touching the adapter.
        try {
            Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
                    .getMethod("registerServiceWrappers").invoke(null);
            System.out.println("ble: service wrappers registered");
        } catch (Throwable skipped) {
            System.out.println("ble: service wrappers skipped: " + unwrap(skipped));
        }
        seedServiceManager();

        BluetoothAdapter adapter;
        try {
            adapter = defaultAdapter();
        } catch (Throwable refused) {
            System.out.println("ble: adapter unavailable: " + unwrap(refused));
            return;
        }
        if (adapter == null) {
            System.out.println("ble: no adapter");
            return;
        }
        // Every one of these is a binder call into the Bluetooth process, and a refusal
        // there is a fact worth reading rather than a reason to die.
        System.out.println("ble: adapter state=" + safe(adapter::getState)
                + " (12=on) multiAdv=" + safe(() -> adapter.isMultipleAdvertisementSupported())
                + " offloadFilter=" + safe(() -> adapter.isOffloadedFilteringSupported())
                + " addr=" + safe(adapter::getAddress));

        // BluetoothLeAdvertiser is @SystemApi, so it is absent from the public
        // android.jar this compiles against even though the class is public at runtime.
        // Reflection is the only way in from a plain app_process; the call itself is an
        // ordinary public method, so nothing here is fighting the platform.
        Object advertiser = null;
        try {
            advertiser = BluetoothAdapter.class.getMethod("getBluetoothLeAdvertiser")
                    .invoke(adapter);
        } catch (Throwable refused) {
            // The ordinary accessor goes through BluetoothServiceManager, a holder the
            // framework fills in while BluetoothFrameworkInitializer is class-initialised.
            // An app_process start never runs that, so the accessor dereferences a null and
            // throws -- but the binder behind it resolves, which is the whole reason the
            // adapter above could be built at all. Everything the advertiser needs is one
            // call further along that same binder, so build it there instead of giving up
            // on the measurement.
            System.out.println("ble: accessor refused: " + unwrap(refused));
            trace(refused);
        }
        if (advertiser == null) {
            advertiser = advertiserFromBinder(adapter);
        }
        Method startAdvertising;
        Method stopAdvertising;
        try {
            if (advertiser == null) {
                System.out.println("ble: advertiser null (BLE advertising not supported)");
                return;
            }
            startAdvertising = advertiser.getClass().getMethod("startAdvertising",
                    AdvertiseSettings.class, AdvertiseData.class, AdvertiseCallback.class);
            stopAdvertising = advertiser.getClass().getMethod("stopAdvertising",
                    AdvertiseCallback.class);
        } catch (Throwable refused) {
            System.out.println("ble: no advertiser: " + unwrap(refused));
            return;
        }
        // The heartbeat below runs after this frame is gone, so the advertiser it stops has
        // to be a value it captured rather than whatever the variable held by then.
        final Object publisher = advertiser;

        byte[] tag = BleAdvert.randomTag();
        BleAdvert advert = BleAdvert.anonymous(tag);
        byte[] payload = advert.manufacturerData();
        System.out.println("ble: tag=" + BleAdvert.hex(tag) + " payload=" + payload.length
                + "B " + advert.hex());

        AdvertiseData data = new AdvertiseData.Builder()
                // Android writes the company ID itself, so only the 20 payload bytes go in.
                .addManufacturerData(BleAdvert.APPLE_COMPANY_ID, payload)
                // Both would eat packet space and neither is part of the trigger: a
                // receiver matches on the manufacturer data alone.
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                // BLE-5 wants a connectable advert; a receiver that tries to connect is
                // a receiver that woke up, which is the whole point.
                .setConnectable(true)
                .setTimeout(0)
                .build();

        final boolean[] started = {false};
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings inEffect) {
                started[0] = true;
                System.out.println("ble: onStartSuccess mode=" + inEffect.getMode()
                        + " tx=" + inEffect.getTxPowerLevel()
                        + " connectable=" + inEffect.isConnectable()
                        + " timeout=" + inEffect.getTimeout());
            }

            @Override
            public void onStartFailure(int code) {
                System.out.println("ble: onStartFailure code=" + code + " " + reason(code));
            }
        };

        try {
            startAdvertising.invoke(advertiser, settings, data, callback);
        } catch (Throwable refused) {
            System.out.println("ble: startAdvertising threw: " + unwrap(refused));
            trace(refused);
            return;
        }

        // Heartbeats keep the log honest: a run that prints nothing after the start
        // line is a run whose callback never fired, not a run that succeeded quietly.
        handler.post(new Runnable() {
            int tick;

            @Override
            public void run() {
                if (System.currentTimeMillis() >= until) {
                    try {
                        stopAdvertising.invoke(publisher, callback);
                        System.out.println("ble: stopped after " + tick * 5 + "s");
                    } catch (Throwable failed) {
                        System.out.println("ble: stopAdvertising threw: " + unwrap(failed));
                    }
                    Looper.myLooper().quitSafely();
                    return;
                }
                tick++;
                System.out.println("ble: up " + tick * 5 + "s started=" + started[0]);
                handler.postDelayed(this, 5000);
            }
        });

        Looper.loop();
        System.out.println("ble: done");
    }

    /** The platform's failure codes, spelled out; a bare number says nothing in a log. */
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

    private interface Call {
        Object get() throws Throwable;
    }

    /** Reads a property the platform may refuse, without letting the refusal end the run. */
    private static String safe(Call call) {
        try {
            return String.valueOf(call.get());
        } catch (Throwable refused) {
            return "refused(" + refused.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Prints the platform classes this needs to reach, as the device actually declares
     * them. Hidden APIs differ between ROMs, and guessing a signature costs a build and
     * a push each time; asking once costs a run.
     */
    private static void diag() {
        for (String className : new String[]{
                "android.os.BluetoothServiceManager",
                "android.os.BluetoothServiceManager$ServiceRegisterer",
                "android.bluetooth.BluetoothAdapter",
                "android.bluetooth.IBluetooth",
                "android.bluetooth.BluetoothLeAdvertiser",
                "android.bluetooth.IBluetoothManager",
                "android.content.AttributionSource",
                "android.content.AttributionSource$Builder"}) {
            try {
                Class<?> type = Class.forName(className);
                System.out.println("diag: " + className);
                for (java.lang.reflect.Constructor<?> ctor : type.getDeclaredConstructors()) {
                    System.out.println("diag:   new " + ctor);
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().contains("$")) {
                        continue;
                    }
                    System.out.println("diag:   " + method.getReturnType().getSimpleName()
                            + " " + method.getName()
                            + java.util.Arrays.toString(method.getParameterTypes()));
                }
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    boolean isStatic = java.lang.reflect.Modifier.isStatic(field.getModifiers());
                    System.out.println("diag:   field " + (isStatic ? "static " : "")
                            + field.getType().getSimpleName() + " " + field.getName());
                }
            } catch (Throwable missing) {
                System.out.println("diag: " + className + " -> " + unwrap(missing));
            }
        }
        for (String className : new String[]{
                "android.bluetooth.BluetoothAdapter",
                "android.bluetooth.BluetoothAdapter$2",
                "android.bluetooth.BluetoothFrameworkInitializer"}) {
            try {
                Class<?> type = Class.forName(className);
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            || field.getType().isPrimitive()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value;
                    try {
                        value = field.get(null);
                    } catch (Throwable refused) {
                        value = "refused(" + unwrap(refused) + ")";
                    }
                    System.out.println("diag: value " + className + "." + field.getName()
                            + " : " + field.getType().getSimpleName() + " = " + value);
                }
            } catch (Throwable missing) {
                System.out.println("diag: value " + className + " -> " + unwrap(missing));
            }
        }
    }

    /**
     * The system context, which is the only way to a {@code Context} from a process the
     * framework did not start. {@code ActivityThread.systemMain()} is the same call the
     * system server makes to boot itself before it has a context.
     */
    private static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable failed) {
            System.out.println("ble: system context failed: " + unwrap(failed));
            return null;
        }
    }

    /**
     * Fills in the one framework field every Bluetooth call reads first.
     *
     * <p>{@code BluetoothFrameworkInitializer.sBluetoothServiceManager} is assigned while
     * that class is being initialised, and on this ROM the assignment happens in
     * {@code registerServiceWrappers()}, which refuses to run outside a class initialiser.
     * The field is left null, and the first thing that reads it is the cached LE-state
     * query behind {@code getLeState()}: every {@code getState()} and
     * {@code startAdvertising()} then dies on a null receiver rather than on anything to
     * do with permissions or Bluetooth being off.
     *
     * <p>{@code BluetoothServiceManager} itself is a public no-argument class whose only
     * job is to hand out {@code ServiceRegisterer}s by name, so creating one here is not a
     * workaround with different behaviour -- it is the same object the framework would have
     * made, put where the framework looks for it.
     */
    private static void seedServiceManager() {
        try {
            Class<?> initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            java.lang.reflect.Field holder = initializer.getDeclaredField("sBluetoothServiceManager");
            holder.setAccessible(true);
            if (holder.get(null) != null) {
                System.out.println("ble: service manager already present");
                return;
            }
            Class<?> type = Class.forName("android.os.BluetoothServiceManager");
            Object seeded = type.getConstructor().newInstance();
            holder.set(null, seeded);
            System.out.println("ble: service manager seeded");
        } catch (Throwable failed) {
            System.out.println("ble: service manager not seeded: " + unwrap(failed));
        }
    }

    /**
     * The process's Bluetooth adapter.
     *
     * <p>{@code BluetoothAdapter.getDefaultAdapter()} returns null here, and correctly
     * so: the adapter is resolved through a {@code Context}, and an {@code app_process}
     * start has none. With the system context in hand the ordinary
     * {@code BLUETOOTH_SERVICE} path still hands back nothing on this ROM, so the fallback
     * builds the adapter from the binder that service would have held.
     */
    private static BluetoothAdapter defaultAdapter() {
        Context context = systemContext();
        System.out.println("ble: context=" + (context == null ? "null" : "ok"));

        if (context != null) {
            Object raw = context.getSystemService(Context.BLUETOOTH_SERVICE);
            System.out.println("ble: manager=" + (raw == null ? "null" : raw.getClass().getName()));
            if (raw instanceof android.bluetooth.BluetoothManager) {
                BluetoothAdapter viaManager = ((android.bluetooth.BluetoothManager) raw).getAdapter();
                System.out.println("ble: manager.getAdapter()="
                        + (viaManager == null ? "null" : "ok"));
                if (viaManager != null) {
                    return viaManager;
                }
            }
        }

        // The manager hands nothing back here, so build the adapter from the binder it
        // would have held. Both the interface and the constructor are hidden and so are
        // reached by reflection, but this is the same construction the manager performs.
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class)
                    .invoke(null, "bluetooth_manager");
            System.out.println("ble: bluetooth_manager binder=" + (binder == null ? "null" : "ok"));
            if (binder == null) {
                return null;
            }
            Class<?> managerApi = Class.forName("android.bluetooth.IBluetoothManager");
            Object manager = Class.forName("android.bluetooth.IBluetoothManager$Stub")
                    .getMethod("asInterface", Class.forName("android.os.IBinder"))
                    .invoke(null, binder);
            BleAdvertProbe.manager = manager;
            // Two private constructors take this manager: one takes the context and builds
            // its own attribution source, the other takes both. Prefer the shorter one --
            // a null attribution source is a null dereference waiting in every call.
            for (int wanted = 2; wanted <= 3; wanted++) {
                for (java.lang.reflect.Constructor<?> constructor
                        : BluetoothAdapter.class.getDeclaredConstructors()) {
                    Class<?>[] types = constructor.getParameterTypes();
                    if (types.length != wanted || !types[0].equals(managerApi)
                            || !types[1].equals(Context.class)) {
                        continue;
                    }
                    Object[] args = new Object[wanted];
                    args[0] = manager;
                    args[1] = context;
                    constructor.setAccessible(true);
                    Object adapter = constructor.newInstance(args);
                    System.out.println("ble: adapter via " + constructor + "=ok");
                    return (BluetoothAdapter) adapter;
                }
            }
            System.out.println("ble: no usable adapter constructor");
        } catch (Throwable failed) {
            System.out.println("ble: binder route failed: " + unwrap(failed));
        }
        return null;
    }

    /**
     * The advertiser, built from the Bluetooth binder rather than through the accessor.
     *
     * <p>{@code BluetoothAdapter.getBluetoothLeAdvertiser()} reaches the service through
     * {@code BluetoothServiceManager}, a holder the framework fills in while
     * {@code BluetoothFrameworkInitializer} is being class-initialised. This process never
     * runs that initialiser -- the call above says why -- so the accessor dereferences a
     * null holder and throws. The adapter itself was built from
     * {@code ServiceManager.getService("bluetooth_manager")} for exactly the same reason,
     * and the advertiser lives on that same binder: {@code BluetoothLeAdvertiser} is a thin
     * wrapper whose single constructor argument is either the manager itself or the GATT
     * interface hanging off it. Which of the two this platform wants is not written down
     * anywhere we can read, so this asks the loaded class and tries each one-argument
     * constructor with whatever value it actually names.
     *
     * @param adapter the adapter already built from the same binder, used when the
     *                constructor wants a handle the manager does not hand out directly
     * @return the advertiser, or null when this route is closed too
     */
    private static Object advertiserFromBinder(Object adapter) {
        if (manager == null) {
            System.out.println("ble: no manager binder to build an advertiser from");
            return null;
        }
        listMembers("manager", manager.getClass());
        Class<?> type;
        try {
            type = Class.forName("android.bluetooth.le.BluetoothLeAdvertiser");
        } catch (Throwable missing) {
            System.out.println("ble: no BluetoothLeAdvertiser class: " + unwrap(missing));
            return null;
        }
        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            System.out.println("ble: advertiser ctor" + signature(constructor.getParameterTypes()));
        }
        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 1) {
                continue;
            }
            Class<?> wanted = constructor.getParameterTypes()[0];
            Object argument = wanted.isInstance(manager) ? manager
                    : (wanted.isInstance(adapter) ? adapter : callReturning(manager, wanted));
            if (argument == null && adapter != null) {
                argument = callReturning(adapter, wanted);
            }
            if (argument == null) {
                System.out.println("ble: no " + wanted.getSimpleName() + " to construct with");
                continue;
            }
            try {
                constructor.setAccessible(true);
                Object built = constructor.newInstance(argument);
                System.out.println("ble: advertiser via" + signature(new Class<?>[]{wanted}) + "=ok");
                return built;
            } catch (Throwable failed) {
                System.out.println("ble: advertiser via" + signature(new Class<?>[]{wanted})
                        + " failed: " + unwrap(failed));
            }
        }
        return null;
    }

    private static String signature(Class<?>[] parameters) {
        StringBuilder text = new StringBuilder("(");
        for (Class<?> parameter : parameters) {
            text.append(text.length() == 1 ? "" : ", ").append(parameter.getName());
        }
        return text.append(')').toString();
    }

    /**
     * Every type a value of this class can be asked as, most specific first.
     *
     * <p>A binder proxy gets its methods from the interface it implements, not from the
     * class, so {@code getDeclaredMethods} on the proxy alone returns nothing.
     */
    private static java.util.List<Class<?>> hierarchy(Class<?> type) {
        java.util.List<Class<?>> types = new java.util.ArrayList<>();
        for (Class<?> step = type; step != null; step = step.getSuperclass()) {
            types.add(step);
        }
        for (int i = 0; i < types.size(); i++) {
            for (Class<?> face : types.get(i).getInterfaces()) {
                if (!types.contains(face)) {
                    types.add(face);
                }
            }
        }
        return types;
    }

    private static void listMembers(String label, Class<?> type) {
        StringBuilder text = new StringBuilder();
        for (Class<?> owner : hierarchy(type)) {
            for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                text.append(text.length() == 0 ? "" : " ");
                text.append(method.getName()).append(signature(method.getParameterTypes()));
            }
        }
        System.out.println("ble: " + label + " methods:" + text);
    }

    /**
     * Invokes the first no-argument method on {@code holder} that returns the wanted type.
     *
     * <p>This is how the advertiser gets its GATT handle without knowing whether this
     * platform spells the accessor {@code getBluetoothGatt} or something else.
     */
    private static Object callReturning(Object holder, Class<?> wanted) {
        for (Class<?> owner : hierarchy(holder.getClass())) {
            for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || !wanted.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(holder);
                    if (value != null) {
                        System.out.println("ble: " + wanted.getSimpleName() + " via " + method.getName() + "=ok");
                        return value;
                    }
                } catch (Throwable ignored) {
                    // Wrong door: another candidate may still open.
                }
            }
        }
        return null;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * Reflection wraps whatever the platform actually threw, and a log line that says
     * only {@code InvocationTargetException} hides the one fact worth having -- usually
     * a SecurityException naming the permission that is missing.
     */
    private static String unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * Prints where a failure came from, innermost frame first.
     *
     * <p>A message names the symptom; the frames name the method that reached for a null.
     * For a hidden API called by reflection the second is the only one that says which
     * private field the framework expected someone else to have filled in.
     */
    private static void trace(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        StackTraceElement[] frames = cause.getStackTrace();
        for (int i = 0; i < frames.length && i < 12; i++) {
            System.out.println("ble:   at " + frames[i]);
        }
    }
}
