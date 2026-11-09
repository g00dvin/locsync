/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.client;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import goodvin.locsync.shared.AppLog;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Reads the head unit's car signals through the eCarX vendor AdaptAPI by reflection, so the app
 * builds and runs without the vendor SDK and degrades to "no data" on any other unit. App-level,
 * vendor-supported path: no root, no telnet. IDs and decoding live in {@link CarSignals}.
 *
 * <p>{@link #start()} / {@link #stop()} are called on the owner's thread (MainActivity's main
 * thread, the service's own thread). Each start spins up a fresh
 * {@link Poller} (own thread and connection); only the current poller may publish, so a quick
 * stop/start can't let an old thread overwrite the new one's results.
 */
final class VehicleData {

    private static final String TAG = "VehicleData";
    private static final long FULL_READ_MS = 1000;   // all signals; in between, speed only
    private static final long RETRY_MS = 5000;
    private static final int STALE_POLLS = 5;   // all-empty full reads in a row before reconnecting

    /** Set once the vendor SDK proved absent on this unit; later starts are no-ops. */
    private static volatile boolean sdkMissing;

    private final Context context;
    private final Handler uiHandler;      // delivers onUpdate back to the caller's thread
    private final Runnable onUpdate;      // called on the caller's thread after each full read; may be null
    private final long pollMs;
    private final int fullEvery;          // polls per full read
    private final CarSignals.SpeedHistory speeds = new CarSignals.SpeedHistory();
    private volatile CarSignals.Snapshot latest = CarSignals.Snapshot.EMPTY;
    private volatile Poller poller;
    private volatile SpeedListener speedListener;

    /**
     * Every wheel-speed poll (km/h, null = not readable), called on the poll thread, with the W4m group read
     * at the same poll (null when unreadable) and, on full reads, the census probe cell (else null).
     */
    interface SpeedListener {
        void onSpeed(long elapsedMs, Float kmh, CarSignals.W4m w4m, String probe);
    }

    /** For the track log's 5 Hz car rows; null to stop. The listener must hop to its own thread. */
    void setSpeedListener(SpeedListener l) {
        speedListener = l;
    }

    /** @param pollMs how often the speed is read; all signals are read about once a second */
    VehicleData(Context context, Handler uiHandler, Runnable onUpdate, long pollMs) {
        this.context = context.getApplicationContext();
        this.uiHandler = uiHandler;
        this.onUpdate = onUpdate;
        this.pollMs = pollMs;
        this.fullEvery = (int) Math.max(1, FULL_READ_MS / pollMs);
    }

    CarSignals.Snapshot snapshot() {
        return latest;
    }

    /** Wheel speed (km/h) at {@code elapsedMs} (SystemClock.elapsedRealtime), or null if not covered. */
    Float speedKmhAt(long elapsedMs) {
        return speeds.at(elapsedMs);
    }

    synchronized void start() {
        if (sdkMissing || poller != null) {
            return;
        }
        poller = new Poller();   // under the lock: its first publish waits until it's current
    }

    void stop() {
        Poller p;
        synchronized (this) {
            p = poller;
            poller = null;
            latest = CarSignals.Snapshot.EMPTY;
            speeds.add(0, null);
        }
        if (p != null) {
            p.shutdown();
        }
    }

    /** Speed-only poll between full reads; same current-poller rule as {@link #publish}. */
    private void addSpeed(Poller from, long elapsedMs, Float kmh, CarSignals.W4m w4m) {
        synchronized (this) {
            if (poller != from) {
                return;
            }
            speeds.add(elapsedMs, kmh);
        }
        SpeedListener l = speedListener;
        if (l != null) {
            l.onSpeed(elapsedMs, kmh, w4m, null);
        }
    }

    private void publish(Poller from, CarSignals.Snapshot snap) {
        publish(from, snap, null, null);
    }

    private void publish(Poller from, CarSignals.Snapshot snap, CarSignals.W4m w4m, String probe) {
        // Locked with stop(): a poll that passed the check can't land after stop() cleared the state.
        synchronized (this) {
            if (poller != from) {
                return;   // a stopped/replaced poller must not overwrite the current state
            }
            latest = snap;
            speeds.add(snap.readElapsedMs, snap.speedKmh);
        }
        SpeedListener l = speedListener;
        if (l != null) {
            l.onSpeed(snap.readElapsedMs, snap.speedKmh, w4m, probe);
        }
        if (onUpdate != null) {
            uiHandler.post(onUpdate);
        }
    }

    /** One polling session: its own thread and AdaptAPI connection. All work runs on {@link #worker}. */
    private final class Poller implements Runnable {
        private final HandlerThread thread = new HandlerThread("vehicle-poll");
        private final Handler worker;
        private volatile boolean stopped;

        // Reflective binder calls can block if the head-unit service is slow: never on the main looper.
        private Object car;             // com.ecarx.xui.adaptapi.car.ICar
        private Object sensorManager;   // com.ecarx.xui.adaptapi.car.sensor.ISensor
        private Method getLatest;       // ISensor#getSensorLatestValue(int) -> float
        private Method getEvent;        // ISensor#getSensorEvent(int) -> int
        // The record-everything probe (only for a recording owner, i.e. with a speed listener):
        private Method getGroup;        // ISensor#getSensorGroupLatestValue(int) -> ISensorGroupValue
        private Method isSupported;     // ISensor#isSensorSupported(int) -> FunctionStatus
        private Method[] w4mGetters;    // IW4mValue: yaw, latAcc, lonAcc, vFL, vFR, vRL, vRR, steer, gear
        private Method w4mTick, w4mInterval;
        private boolean w4mFailed;
        private int[] probeIds;
        private CarSignals.ProbeDiff probe;
        private int emptyPolls;
        private int polls;
        private boolean loggedRaw;
        private boolean loggedFailure;
        private boolean reconnecting;   // after the first connect, connect/raw lines drop to verbose

        Poller() {
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(this);
        }

        /** Caller's thread. Disconnect runs on the worker before it quits (quitSafely drains posted work). */
        void shutdown() {
            stopped = true;
            worker.removeCallbacks(this);
            worker.post(this::disconnect);
            thread.quitSafely();
        }

        @Override
        public void run() {
            if (stopped) {
                return;
            }
            try {
                if (sensorManager == null) {
                    connect();
                    polls = 0;
                }
                if (polls++ % fullEvery != 0) {
                    float raw = rawLatest(sensorManager, CarSignals.SPEED);
                    CarSignals.W4m w4m = speedListener != null ? readW4m(sensorManager) : null;
                    if (!stopped) {
                        addSpeed(this, SystemClock.elapsedRealtime(), CarSignals.speedKmh(CarSignals.latest(raw)), w4m);
                        worker.postDelayed(this, pollMs);
                    }
                    return;
                }
                CarSignals.Snapshot snap = read();
                if (snap.hasAny()) {
                    emptyPolls = 0;
                } else if (++emptyPolls >= STALE_POLLS) {
                    // ponytail: 5-poll heuristic; switch to IConnectWatcher if reconnects flap
                    emptyPolls = 0;
                    disconnect();
                }
                CarSignals.W4m w4m = null;
                String probeRow = null;
                if (speedListener != null) {
                    w4m = readW4m(sensorManager);
                    probeRow = probeRow(sensorManager);
                }
                if (!stopped) {
                    publish(this, snap, w4m, probeRow);
                    worker.postDelayed(this, pollMs);
                }
            } catch (Exception | LinkageError e) {
                if (CarSignals.isMissingSdk(e)) {
                    AppLog.i(TAG, "AdaptAPI not present, car card disabled until the app restarts");
                    sdkMissing = true;
                    stopped = true;
                    publish(this, CarSignals.Snapshot.EMPTY);
                    uiHandler.post(() -> {
                        if (poller == this) {
                            stop();
                        }
                    });
                    return;
                }
                String msg = "AdaptAPI read failed, retrying: " + e;
                if (loggedFailure) {
                    AppLog.v(TAG, msg);
                } else {
                    AppLog.i(TAG, msg);
                    loggedFailure = true;
                }
                disconnect();
                if (!stopped) {
                    publish(this, CarSignals.Snapshot.EMPTY);
                    worker.postDelayed(this, RETRY_MS);
                }
            }
        }

        private void connect() throws Exception {
            Class<?> carCls = Class.forName("com.ecarx.xui.adaptapi.car.Car");
            Object c = carCls.getMethod("create", Context.class).invoke(null, context);
            if (c == null) {
                throw new IllegalStateException("Car.create returned null");
            }
            car = c;
            // Some builds need an explicit connect() before the managers are live.
            Class<?> connectable = connectableClass();
            if (connectable != null && connectable.isInstance(c)) {
                connectable.getMethod("connect").invoke(c);
            }
            Object mgr = c.getClass().getMethod("getSensorManager").invoke(c);
            if (mgr == null) {
                throw new IllegalStateException("getSensorManager returned null");
            }
            // Resolve on the public interface, not the impl class (robust to non-public impls/proxies).
            Class<?> iSensor = Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensor");
            getLatest = iSensor.getMethod("getSensorLatestValue", int.class);
            getEvent = iSensor.getMethod("getSensorEvent", int.class);
            sensorManager = mgr;
            loggedRaw = false;
            log("AdaptAPI sensor manager connected");
            if (speedListener != null) {
                census(iSensor, mgr);
            }
        }

        /**
         * Once per connection, for the record-everything build: every ISensor.SENSOR_TYPE_* with its support status
         * and current value, one log line each; the ones that may report become the 1 Hz probe. Plus the W4m
         * group's first value and the mount angle.
         */
        private void census(Class<?> iSensor, Object mgr) {
            try {
                getGroup = iSensor.getMethod("getSensorGroupLatestValue", int.class);
            } catch (Exception | LinkageError e) {
                getGroup = null;
            }
            try {
                isSupported = iSensor.getMethod("isSensorSupported", int.class);
            } catch (Exception | LinkageError e) {
                isSupported = null;
            }
            java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
            for (java.lang.reflect.Field f : iSensor.getFields()) {
                if (!f.getName().startsWith("SENSOR_TYPE_") || f.getType() != int.class) {
                    continue;
                }
                int id;
                try {
                    id = f.getInt(null);
                } catch (Exception e) {
                    continue;
                }
                String status = "?";
                try {
                    Object st = isSupported == null ? null : isSupported.invoke(mgr, id);
                    status = String.valueOf(st);
                } catch (Exception | LinkageError e) {
                    status = "fail";
                }
                float latest = rawLatest(mgr, id);
                int event = rawEvent(mgr, id);
                log(String.format(Locale.US, "census %s 0x%x %s latest=%s(0x%x) event=%d",
                        f.getName(), id, status, latest, bits(latest), event));
                if (!"notavailable".equals(status) && !"error".equals(status)) {
                    ids.add(id);
                }
            }
            probeIds = new int[ids.size()];
            for (int i = 0; i < probeIds.length; i++) probeIds[i] = ids.get(i);
            probe = new CarSignals.ProbeDiff(probeIds);
            CarSignals.W4m w = readW4m(mgr);
            log(w == null ? "census W4m: none" : String.format(Locale.US,
                    "census W4m yaw=%s lat=%s lon=%s fl=%s fr=%s rl=%s rr=%s steer=%s gear=%d tick=%d interval=%d",
                    w.yawRate, w.latAcc, w.lonAcc, w.vFL, w.vFR, w.vRL, w.vRR, w.steer, w.gear, w.tick, w.interval));
            try {
                Object ma = iSensor.getMethod("getMountAngle").invoke(mgr);
                if (ma != null) {
                    Class<?> c = Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensorGroup$IMountAngle");
                    log("census mount angle has=" + c.getMethod("hasMountAngle").invoke(ma)
                            + " pitch=" + c.getMethod("getPitchMountAngle").invoke(ma)
                            + " roll=" + c.getMethod("getRollMountAngle").invoke(ma)
                            + " yaw=" + c.getMethod("getYawMountAngle").invoke(ma));
                } else {
                    log("census mount angle: none");
                }
            } catch (Exception | LinkageError e) {
                log("census mount angle failed: " + e);
            }
            log("census probe: " + probeIds.length + " sensors");
        }

        /** The W4m group (car yaw rate, accelerations, four wheel speeds); null when unreadable. */
        private CarSignals.W4m readW4m(Object mgr) {
            if (getGroup == null || w4mFailed) {
                return null;
            }
            try {
                Object v = getGroup.invoke(mgr, CarSignals.W4m.GROUP);
                if (v == null) {
                    return null;
                }
                if (w4mGetters == null) {
                    Class<?> c = Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensorGroupValue$IW4mValue");
                    Class<?> g = Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensorGroupValue");
                    String[] names = {"getYawRate", "getLatAcc", "getLonAcc", "getVFLSpeed", "getVFRSpeed",
                            "getVRLSpeed", "getVRRSpeed", "getSteerAngle", "getGearState"};
                    Method[] m = new Method[names.length];
                    for (int i = 0; i < names.length; i++) m[i] = c.getMethod(names[i]);
                    w4mTick = g.getMethod("getTickTime");
                    w4mInterval = g.getMethod("getInterval");
                    w4mGetters = m;
                }
                float[] f = new float[8];
                for (int i = 0; i < 8; i++) f[i] = ((Number) w4mGetters[i].invoke(v)).floatValue();
                int gear = ((Number) w4mGetters[8].invoke(v)).intValue();
                long tick = ((Number) w4mTick.invoke(v)).longValue();
                int interval = ((Number) w4mInterval.invoke(v)).intValue();
                return CarSignals.W4m.of(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], gear, tick, interval);
            } catch (Exception | LinkageError e) {
                w4mFailed = true;   // once per connection: a missing API stays missing
                log("W4m read failed, not retried this connection: " + e);
                return null;
            }
        }

        /** The census probe cell: continuous types (0x1xxxxx) as floats, the rest as events. */
        private String probeRow(Object mgr) {
            int[] ids = probeIds;
            if (ids == null || probe == null) {
                return null;
            }
            double[] v = new double[ids.length];
            for (int i = 0; i < ids.length; i++) {
                v[i] = (ids[i] & 0xF00000) == 0x100000 ? rawLatest(mgr, ids[i]) : rawEvent(mgr, ids[i]);
            }
            return probe.row(SystemClock.elapsedRealtime(), v);
        }

        private void disconnect() {
            Object c = car;
            car = null;
            sensorManager = null;
            w4mFailed = false;
            w4mGetters = null;
            if (c == null) {
                return;
            }
            try {
                Class<?> connectable = connectableClass();
                if (connectable != null && connectable.isInstance(c)) {
                    connectable.getMethod("disconnect").invoke(c);
                }
            } catch (Exception | LinkageError e) {
                AppLog.v(TAG, "AdaptAPI disconnect failed: " + e);
            }
        }

        private Class<?> connectableClass() {
            try {
                return Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable");
            } catch (ClassNotFoundException | LinkageError e) {
                return null;   // older adaptapi without IConnectable
            }
        }

        private CarSignals.Snapshot read() {
            Object mgr = sensorManager;
            float speed = rawLatest(mgr, CarSignals.SPEED);
            float ipk = rawLatest(mgr, CarSignals.SPEED_IPK);
            int gear = rawEvent(mgr, CarSignals.GEAR);
            float fuel = rawLatest(mgr, CarSignals.FUEL);
            float range = rawLatest(mgr, CarSignals.RANGE);
            float temp = rawLatest(mgr, CarSignals.OUTSIDE_TEMP);
            float odo = rawLatest(mgr, CarSignals.ODOMETER);
            float steer = rawLatest(mgr, CarSignals.STEERING_ANGLE);
            if (!loggedRaw) {
                // The calibration knob for units and encodings (fuel turned out to be mL in raw bits).
                log(String.format(Locale.US,
                        "raw speed=%s(0x%x) ipk=%s(0x%x) gear=0x%x fuel=%s(0x%x) range=%s(0x%x) temp=%s(0x%x) odo=%s(0x%x) steer=%s(0x%x)",
                        speed, bits(speed), ipk, bits(ipk), gear, fuel, bits(fuel), range, bits(range),
                        temp, bits(temp), odo, bits(odo), steer, bits(steer)));
                loggedRaw = true;
                reconnecting = true;
            }
            return CarSignals.fromRaw(speed, ipk, gear, fuel, range, temp, odo, steer, SystemClock.elapsedRealtime());
        }

        /** getSensorLatestValue, or the "unsupported" sentinel if the call fails. */
        private float rawLatest(Object mgr, int id) {
            try {
                return ((Number) getLatest.invoke(mgr, id)).floatValue();
            } catch (Exception e) {
                if (AppLog.isDebug()) {   // up to 5 Hz per signal while reads fail
                    AppLog.v(TAG, "read id=0x" + Integer.toHexString(id) + " failed: " + e);
                }
                return Float.intBitsToFloat(1);
            }
        }

        /** getSensorEvent, or -1 (no value) if the call fails. */
        private int rawEvent(Object mgr, int id) {
            try {
                return ((Number) getEvent.invoke(mgr, id)).intValue();
            } catch (Exception e) {
                if (AppLog.isDebug()) {   // up to 5 Hz per signal while reads fail
                    AppLog.v(TAG, "read id=0x" + Integer.toHexString(id) + " failed: " + e);
                }
                return -1;
            }
        }

        /** Info on the first connection of this poller; verbose on reconnects (stale/empty cycles). */
        private void log(String msg) {
            if (reconnecting) {
                AppLog.v(TAG, msg);
            } else {
                AppLog.i(TAG, msg);
            }
        }

        private int bits(float v) {
            return Float.floatToRawIntBits(v);
        }
    }
}
