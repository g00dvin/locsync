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
 * <p>{@link #start()} / {@link #stop()} are called on the main thread. Each start spins up a fresh
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
    private final Handler uiHandler;      // delivers onUpdate back to the caller's (UI) thread
    private final Runnable onUpdate;      // called on the UI thread after each full read; may be null
    private final long pollMs;
    private final int fullEvery;          // polls per full read
    private final CarSignals.SpeedHistory speeds = new CarSignals.SpeedHistory();
    private volatile CarSignals.Snapshot latest = CarSignals.Snapshot.EMPTY;
    private volatile Poller poller;

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
    private void addSpeed(Poller from, long elapsedMs, Float kmh) {
        synchronized (this) {
            if (poller == from) {
                speeds.add(elapsedMs, kmh);
            }
        }
    }

    private void publish(Poller from, CarSignals.Snapshot snap) {
        // Locked with stop(): a poll that passed the check can't land after stop() cleared the state.
        synchronized (this) {
            if (poller != from) {
                return;   // a stopped/replaced poller must not overwrite the current state
            }
            latest = snap;
            speeds.add(snap.readElapsedMs, snap.speedKmh);
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

        /** Main thread. Disconnect runs on the worker before it quits (quitSafely drains posted work). */
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
                    if (!stopped) {
                        addSpeed(this, SystemClock.elapsedRealtime(), CarSignals.speedKmh(CarSignals.latest(raw)));
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
                if (!stopped) {
                    publish(this, snap);
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
        }

        private void disconnect() {
            Object c = car;
            car = null;
            sensorManager = null;
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
            if (!loggedRaw) {
                // The calibration knob for units and encodings (fuel turned out to be mL in raw bits).
                float steer = rawLatest(mgr, CarSignals.STEERING_ANGLE);
                log(String.format(Locale.US,
                        "raw speed=%s(0x%x) ipk=%s(0x%x) gear=0x%x fuel=%s(0x%x) range=%s(0x%x) temp=%s(0x%x) odo=%s(0x%x) steer=%s(0x%x)",
                        speed, bits(speed), ipk, bits(ipk), gear, fuel, bits(fuel), range, bits(range),
                        temp, bits(temp), odo, bits(odo), steer, bits(steer)));
                loggedRaw = true;
                reconnecting = true;
            }
            return CarSignals.fromRaw(speed, ipk, gear, fuel, range, temp, odo, SystemClock.elapsedRealtime());
        }

        /** getSensorLatestValue, or the "unsupported" sentinel if the call fails. */
        private float rawLatest(Object mgr, int id) {
            try {
                return ((Number) getLatest.invoke(mgr, id)).floatValue();
            } catch (Exception e) {
                AppLog.v(TAG, "read id=0x" + Integer.toHexString(id) + " failed: " + e);
                return Float.intBitsToFloat(1);
            }
        }

        /** getSensorEvent, or -1 (no value) if the call fails. */
        private int rawEvent(Object mgr, int id) {
            try {
                return ((Number) getEvent.invoke(mgr, id)).intValue();
            } catch (Exception e) {
                AppLog.v(TAG, "read id=0x" + Integer.toHexString(id) + " failed: " + e);
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
