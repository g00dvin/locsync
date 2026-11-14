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
 * The car's vendor VHAL properties straight from {@code android.car.CarPropertyManager}, bypassing AdaptAPI:
 * the steering wheel angle and the four wheel speeds, which AdaptAPI zeroes on the Geely G426.
 *
 * <p>Those properties ({@code 0x214xxxxx}, named after the CAN signals; recon 2026-11-07) are guarded by
 * {@code android.car.permission.CAR_VENDOR_EXTENSION} (protection level {@code signature|privileged}), so
 * an ordinary install is refused with a {@link SecurityException} (seen on the car, 2026-11-14) and
 * {@link #read()} answers {@link CarSignals.Vendor#DENIED}. Only an install as a privileged system app
 * gets values. Off unless the "Extra car signals" setting is on.
 *
 * <p>All reflection: nothing here links {@code android.car}, so it compiles without the Automotive SDK
 * and answers null on a phone or a head unit without that class. One thread only (the vehicle poller).
 */
final class CarVendor {

    private static final String TAG = "CarVendor";

    private static final int STEER = 0x21401cef;                                        // STEERWHLSNSRAG
    private static final int[] WHEELS = {0x21401675, 0x21401678, 0x21401679, 0x2140167b};   // FL, FR, RL, RR

    /** Logged once per connection, raw, so an export shows what the unit really answers. */
    private static final int[] LOGGED = {0x21401b54 /* VEHSPD */, STEER, 0x21401cf0 /* steering speed */,
            WHEELS[0], WHEELS[1], WHEELS[2], WHEELS[3], 0x21401671 /* WHLDIRROT FL */};

    private final Context context;
    private Object car, manager;
    private Method getProperty;
    private boolean tried;              // connected, or found out it never will: no retry until close()
    private CarSignals.Vendor refused;  // DENIED once a read was refused; null = absent or fine

    CarVendor(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The current values; {@link CarSignals.Vendor#DENIED} without the permission; null without android.car. */
    CarSignals.Vendor read() {
        if (!tried) {
            tried = true;
            connect();
        }
        if (manager == null) {
            return refused;
        }
        float steer = CarSignals.vendorSteerDeg(raw(STEER));
        float[] w = new float[4];
        for (int i = 0; i < 4 && manager != null; i++) {
            w[i] = CarSignals.vendorWheelKmh(raw(WHEELS[i]));
        }
        return manager == null ? refused : new CarSignals.Vendor(false, steer, w[0], w[1], w[2], w[3]);
    }

    /** One property's number, NaN when it can't be read. A refusal ends the session. */
    private double raw(int id) {
        if (manager == null) {
            return Double.NaN;
        }
        try {
            Object value = getProperty.invoke(manager, id, 0);   // area 0 = global
            Object v = value == null ? null : value.getClass().getMethod("getValue").invoke(value);
            return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SecurityException) {
                AppLog.i(TAG, "vendor read denied (CAR_VENDOR_EXTENSION is signature|privileged): "
                        + "needs an install as a privileged system app");
                refused = CarSignals.Vendor.DENIED;
                disconnect();
            }
            return Double.NaN;
        }
    }

    private void connect() {
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            // createCar(Context) blocks until connected (or returns null if the service is absent).
            car = carCls.getMethod("createCar", Context.class).invoke(null, context);
            Object mgr = car == null ? null : carCls.getMethod("getCarManager", String.class).invoke(car, "property");
            if (mgr == null) {
                AppLog.i(TAG, "android.car present but no CarPropertyManager");
                disconnect();
                return;
            }
            getProperty = mgr.getClass().getMethod("getProperty", int.class, int.class);
            getProperty.setAccessible(true);
            manager = mgr;
            StringBuilder sb = new StringBuilder("vendor raw");
            for (int id : LOGGED) {
                sb.append(String.format(Locale.US, " 0x%x=%s", id, raw(id)));
            }
            if (manager != null) {
                AppLog.i(TAG, sb.toString());
            }
        } catch (ClassNotFoundException e) {
            AppLog.v(TAG, "android.car not on this device");
        } catch (Throwable e) {
            AppLog.i(TAG, "vendor connect failed: " + e);
            disconnect();
        }
    }

    private void disconnect() {
        Object c = car;
        car = null;
        manager = null;
        if (c != null) {
            try {
                c.getClass().getMethod("disconnect").invoke(c);
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    /** Drops the connection; the next {@link #read()} starts over. */
    void close() {
        disconnect();
        tried = false;
        refused = null;
    }
}
