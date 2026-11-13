/*
 * Copyright © 2026 LocSync
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.client;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.Locale;

import goodvin.locsync.shared.AppLog;

/**
 * Diagnostic: read the car's vendor VHAL properties straight from {@code android.car.CarPropertyManager},
 * bypassing AdaptAPI.
 *
 * <p>Why: on the Geely G426 (recon 2026-11-07) AdaptAPI answers 0 for odometer, steering angle and the
 * four wheel speeds, while those signals DO move as vendor properties {@code 0x214xxxxx} named after the
 * CAN signals. Those properties are guarded by {@code android.car.permission.CAR_VENDOR_EXTENSION}
 * (protection level {@code signature|privileged}), so an ordinary install cannot read them — the call
 * throws {@link SecurityException}. A build that is platform-signed (the unit is userdebug + public
 * AOSP test-keys) or installed as a privileged app holds the permission and gets real values.
 *
 * <p>This runs once per connection, only while "Record track" is on, and logs one line per candidate so
 * the export tells us definitively whether the privileged path yields the missing signals. It is all
 * reflection: nothing here links {@code android.car}, so it compiles without the Automotive SDK and
 * no-ops cleanly on a phone or any head unit without that class.
 */
final class CarVendorProbe {

    private static final String TAG = "CarVendorProbe";

    /** A vendor property to try. {@code scale} is only a hint for the log; the raw value is logged too. */
    private record Prop(String name, int id, String scale) {}

    // Vendor VHAL property ids from the 2026-11-07 recon (car-recon SUMMARY.md §2–3). Area id is global (0).
    private static final Prop[] CANDIDATES = {
            // Known-good cross-check: if this reads, the privileged path works and the rest are trustworthy.
            new Prop("VEHSPD (speed)", 0x21401b54, "raw/256 = m/s"),
            // The three AdaptAPI gives up on:
            new Prop("STEERWHLSNSRAG (steering angle)", 0x21401cef, "signed 15-bit, wraps at 32768"),
            new Prop("STEERWHLSNSRAGSPD (steering speed)", 0x21401cf0, "signed 14-bit"),
            new Prop("WHLSPD front-left", 0x21401675, "0..6756, within ±2%"),
            new Prop("WHLSPD front-right", 0x21401678, "0..6756"),
            new Prop("WHLSPD rear-left", 0x21401679, "0..6756"),
            new Prop("WHLSPD rear-right", 0x2140167b, "0..6756"),
            new Prop("WHLDIRROT front-left", 0x21401671, "1 fwd, 2/3 seen"),
            new Prop("WHLROTTOOTHCNTR front-left", 0x2140167d, "8-bit wrapping counter"),
            // Odometer candidates: recon found none on the Android side; worth a definitive try.
            new Prop("paddodometertripmeter1 (SOME/IP)", 0x21709036, "?"),
            new Prop("PERF_ODOMETER (AOSP stub)", 0x11600404, "km (was a dead stub)"),
    };

    private CarVendorProbe() {}

    /**
     * Reads every candidate once and logs the outcome. Safe to call from any thread off the main looper
     * (it does blocking binder work). Never throws.
     */
    static void run(Context context) {
        Object car = null;
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            // createCar(Context) blocks until connected (or returns null if the service is absent).
            Method create = carCls.getMethod("createCar", Context.class);
            car = create.invoke(null, context.getApplicationContext());
            if (car == null) {
                AppLog.i(TAG, "android.car present but createCar returned null — car service not connected");
                return;
            }
            Object mgr = carCls.getMethod("getCarManager", String.class).invoke(car, "property");
            if (mgr == null) {
                AppLog.i(TAG, "no CarPropertyManager (\"property\" service missing)");
                return;
            }
            Method getProperty = mgr.getClass().getMethod("getProperty", int.class, int.class);
            getProperty.setAccessible(true);
            int ok = 0;
            boolean denied = false;
            for (Prop p : CANDIDATES) {
                try {
                    Object value = getProperty.invoke(mgr, p.id(), 0);   // area 0 = global
                    Object raw = value == null ? null : value.getClass().getMethod("getValue").invoke(value);
                    long bits = raw instanceof Float ? Float.floatToRawIntBits((Float) raw) & 0xFFFFFFFFL
                            : raw instanceof Integer ? ((Integer) raw) & 0xFFFFFFFFL : 0;
                    AppLog.i(TAG, String.format(Locale.US, "vendor %s 0x%x = %s (0x%x) [%s]",
                            p.name(), p.id(), raw, bits, p.scale()));
                    ok++;
                } catch (Throwable e) {
                    // SecurityException = no CAR_VENDOR_EXTENSION; IllegalArgumentException = prop not on this VHAL.
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof SecurityException) denied = true;
                    AppLog.i(TAG, String.format(Locale.US, "vendor %s 0x%x: %s",
                            p.name(), p.id(), cause.getClass().getSimpleName()));
                }
            }
            if (denied) {
                AppLog.i(TAG, "vendor read denied (CAR_VENDOR_EXTENSION is signature|privileged): "
                        + "this needs a platform-signed or privileged install to reach these signals");
            } else {
                AppLog.i(TAG, "vendor probe done: " + ok + "/" + CANDIDATES.length + " properties read");
            }
        } catch (ClassNotFoundException e) {
            AppLog.v(TAG, "android.car not on this device — vendor probe skipped");
        } catch (Throwable e) {
            AppLog.i(TAG, "vendor probe failed: " + e);
        } finally {
            if (car != null) {
                try {
                    car.getClass().getMethod("disconnect").invoke(car);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }
}
