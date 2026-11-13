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

import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Replays a drive recorded with "Record track (CSV)" through the smoothing pipeline the way the
 * service runs it (fix arrival → latency → predict/update; 10 Hz extrapolated output between
 * fixes) and prints quality figures. Recorded tracks hold real routes, so none is checked in: run
 * with {@code LOCSYNC_TRACK=/path/track-client.csv ./gradlew :client-app:testDebugUnitTest
 * --tests '*TrackReplayTest*' -i}. Skipped when the variable is not set.
 */
public class TrackReplayTest {
    static final double M_PER_DEG = 111320.0;

    /** One recorded fix as it arrived. */
    static final class Fix {
        long arrival, ts, ageMs, measT;
        double lat, lon, speed, bearing, acc, spdAcc, brgAcc;
        boolean hasSpeed, hasBearing;
    }

    static final class Result {
        int fixes, outputs, jumps;
        List<Double> predErr = new ArrayList<>(), predErrTurn = new ArrayList<>(), headingErrTurn = new ArrayList<>();
        List<Double> along = new ArrayList<>(), cross = new ArrayList<>();
        List<Double> snap = new ArrayList<>();   // icon shift beyond normal motion at each fix arrival
        double gapErr = Double.NaN;              // gap run: last output vs the first fix after the gap
        boolean dataHole;                        // dead reckoning stopped for want of *recorded* wheel data
        // The icon itself at the moment each fix describes (output interpolated to its measurement time): one
        // yardstick for both the filter-only and the fused pipeline.
        List<Double> iconErr = new ArrayList<>(), iconErrTurn = new ArrayList<>(), iconHeadTurn = new ArrayList<>();
        final java.util.ArrayDeque<double[]> outs = new java.util.ArrayDeque<>();   // {lat, lon, t, speed, bearing}

        void addOut(double[] o) {
            outs.addLast(o);
            while (outs.size() > 60) outs.removeFirst();
        }

        /** Scores the icon against fix f (speed > 5, with bearing); turn = the GPS bearing rate into this fix. */
        void scoreIcon(Fix f, double turnDps) {
            double[] a = null, b = null;
            for (double[] o : outs) {
                if (o[2] <= f.measT) a = o;
                else { b = o; break; }
            }
            if (a == null || b == null || b[2] - a[2] > 500) return;
            double k = (f.measT - a[2]) / (b[2] - a[2]);
            double lat = a[0] + k * (b[0] - a[0]), lon = a[1] + k * (b[1] - a[1]);
            double err = dist(lat, lon, f.lat, f.lon);
            if (turnDps > 8) {
                iconErrTurn.add(err);
                iconHeadTurn.add(Math.abs(wrap((k < 0.5 ? a : b)[4] - f.bearing)));
            } else {
                iconErr.add(err);
            }
        }

        String iconSummary(String name) {
            return String.format(Locale.US,
                    "%-28s ICON err p50=%.2f p90=%.2f m | turns p50=%.2f m, heading p50=%.1f° p90=%.1f° (n=%d) | snap p90=%.2f | jumps=%d of %d",
                    name, pct(iconErr, 50), pct(iconErr, 90), pct(iconErrTurn, 50), pct(iconHeadTurn, 50),
                    pct(iconHeadTurn, 90), iconHeadTurn.size(), pct(snap, 90), jumps, outputs);
        }

        String summary(String name) {
            return String.format(Locale.US,
                    "%-28s next-fix error p50=%.2f p90=%.2f m | turns p50=%.2f m, heading p50=%.1f° p90=%.1f° | "
                            + "along p50=%+.2f cross p50=%+.2f m | snap at fix p50=%.2f p90=%.2f m | jumps=%d of %d",
                    name, pct(predErr, 50), pct(predErr, 90), pct(predErrTurn, 50), pct(headingErrTurn, 50),
                    pct(headingErrTurn, 90), pct(along, 50), pct(cross, 50), pct(snap, 50), pct(snap, 90), jumps, outputs);
        }
    }

    /** Head-unit motion samples from a track: gyro heading (fix + out rows) and wheel speed. */
    static final class Motion {
        long[] yawT; double[] yaw;
        long[] wheelT; double[] wheelMps; String[] gear;
        long maxGapMs;   // wheel interpolation bracket: 600 with 5 Hz car rows, 1500 with 1 Hz fix snapshots
    }

    static final double WHEEL_FACTOR = 1.0249;   // the 2026-11-09 calibrated factor

    /** Null when the track has neither gyro nor wheel values. "No gyro" = an empty {@code yaw}. */
    static Motion loadMotion(Path p) throws Exception {
        List<String> lines = Files.readAllLines(p);
        String[] h = lines.get(0).split(",", -1);
        Map<String, Integer> ix = new HashMap<>();
        for (int i = 0; i < h.length; i++) ix.put(h[i], i);
        Integer yawCol = ix.get("gyro_yaw_deg"), gearCol = ix.get("car_gear");
        java.util.TreeMap<Long, Double> yaw = new java.util.TreeMap<>();
        java.util.TreeMap<Long, Object[]> carRows = new java.util.TreeMap<>(), fixCar = new java.util.TreeMap<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] c = l.split(",", -1);
            String type = c[ix.get("type")];
            long el = Long.parseLong(c[ix.get("elapsed_ms")]);
            if (yawCol != null && c.length > yawCol && !c[yawCol].isEmpty() && !c[ix.get("gyro_dt_ms")].isEmpty()) {
                yaw.putIfAbsent(el + Long.parseLong(c[ix.get("gyro_dt_ms")]), Double.parseDouble(c[yawCol]));
            }
            String gear = gearCol != null && c.length > gearCol && !c[gearCol].isEmpty() ? c[gearCol] : null;
            if ("car".equals(type) && !c[ix.get("car_speed")].isEmpty()) {
                carRows.put(el, new Object[]{Double.parseDouble(c[ix.get("car_speed")]), gear});
            } else if ("fix".equals(type) && !c[ix.get("car_speed")].isEmpty() && !c[ix.get("car_dt_ms")].isEmpty()) {
                fixCar.put(el + Long.parseLong(c[ix.get("car_dt_ms")]), new Object[]{Double.parseDouble(c[ix.get("car_speed")]), gear});
            }
        }
        if (yaw.isEmpty() && carRows.isEmpty() && fixCar.isEmpty()) return null;
        Motion m = new Motion();
        m.yawT = new long[yaw.size()]; m.yaw = new double[yaw.size()];
        int i = 0;
        for (Map.Entry<Long, Double> e : yaw.entrySet()) { m.yawT[i] = e.getKey(); m.yaw[i++] = e.getValue(); }
        java.util.TreeMap<Long, Object[]> car = carRows.isEmpty() ? fixCar : carRows;
        m.maxGapMs = carRows.isEmpty() ? REPLAY_MAX_HOLE_MS : 600;
        m.wheelT = new long[car.size()]; m.wheelMps = new double[car.size()]; m.gear = new String[car.size()];
        i = 0;
        for (Map.Entry<Long, Object[]> e : car.entrySet()) {
            m.wheelT[i] = e.getKey();
            m.wheelMps[i] = (Double) e.getValue()[0] * WHEEL_FACTOR / 3.6;
            m.gear[i++] = (String) e.getValue()[1];
        }
        return m;
    }

    /** Like CarSignals.SpeedHistory: linear inside a bracket, the newest held 300 ms, else NaN. */
    static double wheelAt(Motion m, long t) {
        if (m == null || m.wheelT.length == 0) return Double.NaN;
        int i = java.util.Arrays.binarySearch(m.wheelT, t);
        if (i >= 0) return m.wheelMps[i];
        i = -i - 1;   // first sample after t
        if (i == 0) return Double.NaN;
        if (i == m.wheelT.length) return t - m.wheelT[i - 1] <= 300 ? m.wheelMps[i - 1] : Double.NaN;
        long t0 = m.wheelT[i - 1], t1 = m.wheelT[i];
        if (t1 - t0 > m.maxGapMs) return Double.NaN;
        return m.wheelMps[i - 1] + (m.wheelMps[i] - m.wheelMps[i - 1]) * (t - t0) / (double) (t1 - t0);
    }

    static List<Fix> load(Path p) throws Exception {
        List<String> lines = Files.readAllLines(p);
        String[] h = lines.get(0).split(",", -1);
        Map<String, Integer> ix = new HashMap<>();
        for (int i = 0; i < h.length; i++) ix.put(h[i], i);
        List<Fix> out = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] c = l.split(",", -1);
            if (!"fix".equals(c[ix.get("type")])) continue;
            Fix f = new Fix();
            f.arrival = Long.parseLong(c[ix.get("elapsed_ms")]);
            f.ts = Long.parseLong(c[ix.get("fix_ts")]);
            f.ageMs = Math.round(Double.parseDouble(c[ix.get("age_s")]) * 1000);
            f.lat = Double.parseDouble(c[ix.get("lat")]);
            f.lon = Double.parseDouble(c[ix.get("lon")]);
            f.acc = Double.parseDouble(c[ix.get("acc")]);
            f.hasSpeed = !c[ix.get("speed")].isEmpty();
            f.hasBearing = !c[ix.get("bearing")].isEmpty();
            f.speed = f.hasSpeed ? Double.parseDouble(c[ix.get("speed")]) : 0;
            f.bearing = f.hasBearing ? Double.parseDouble(c[ix.get("bearing")]) : 0;
            f.spdAcc = parseOr0(c[ix.get("spd_acc")]);
            f.brgAcc = parseOr0(c[ix.get("brg_acc")]);
            out.add(f);
        }
        return out;
    }

    /** Mirrors GNSSClientService: ordering, gap reset, latency, predict/update, 100 ms output ticks. */
    static Result replay(List<Fix> fixes, boolean networkSync, Consumer<LocationKalmanFilter> setup) {
        return replay(fixes, null, networkSync, false, 0, -1, Long.MIN_VALUE, Long.MAX_VALUE, setup);
    }

    /**
     * As above, plus: wheel speed as the speed measurement ({@code useWheels}, like the service's "Use car
     * speed"); scoring only for fixes arriving in [fromT, toT]; and an artificial GPS gap of {@code gapS}
     * seconds after the fix arriving at {@code gapStart} (-1 = none), ending the run at the first fix after it.
     */
    static Result replay(List<Fix> fixes, Motion motion, boolean networkSync, boolean useWheels, int gapS,
                         long gapStart, long fromT, long toT, Consumer<LocationKalmanFilter> setup) {
        LocationKalmanFilter kf = new LocationKalmanFilter(2.0, 1.0);
        setup.accept(kf);
        FixClock clock = new FixClock();
        Result r = new Result();
        long lastTs = Long.MIN_VALUE, lastArrival = 0, stateTime = 0;
        double prevBearing = Double.NaN;
        double[] lastOut = null;
        long gapEnd = gapStart < 0 ? -1 : gapStart + gapS * 1000L;
        for (int i = 0; i < fixes.size(); i++) {
            Fix f = fixes.get(i);
            if (f.ts <= lastTs) continue;
            if (gapStart >= 0 && f.arrival > gapStart && f.arrival <= gapEnd) continue;
            long latency = networkSync ? clock.latencyMs(f.arrival, f.ts, f.ageMs) : f.ageMs;
            latency = Math.min(latency, 3000);
            long meas = f.arrival - latency;
            f.measT = meas;
            if (gapStart >= 0 && f.arrival > gapEnd) {
                r.gapErr = lastOut == null ? Double.NaN : dist(lastOut[0], lastOut[1], f.lat, f.lon);
                return r;
            }
            boolean scored = f.arrival >= fromT && f.arrival <= toT;
            boolean gap = lastArrival == 0 || f.arrival - lastArrival > 2500;
            if (gap) {
                kf.reset();
                lastOut = null;
            } else if (kf.isInitialized()) {
                double dt = (f.ts - lastTs) / 1000.0;
                if (!(dt > 0 && dt <= 5)) dt = (meas - stateTime) / 1000.0;
                dt = Math.max(0, Math.min(dt, 5));
                // prediction quality: where the filter expected this fix to be
                if (scored && f.hasSpeed && f.speed > ICON_MIN_SPEED && f.hasBearing) {
                    r.scoreIcon(f, Double.isNaN(prevBearing) ? 0 : Math.abs(wrap(f.bearing - prevBearing)) / dt);
                }
                LocationKalmanFilter.Estimate e = kf.extrapolate(dt);
                if (scored && f.hasSpeed && f.speed > 5 && f.hasBearing) {
                    double[] ac = alongCross(e.latitude, e.longitude, f);
                    double err = Math.hypot(ac[0], ac[1]);
                    double turn = Double.isNaN(prevBearing) ? 0 : Math.abs(wrap(f.bearing - prevBearing)) / dt;
                    if (turn > 8) {
                        r.predErrTurn.add(err);
                        r.headingErrTurn.add(Math.abs(wrap(e.bearingDeg - f.bearing)));
                    } else {
                        r.predErr.add(err);
                        r.along.add(ac[0]);
                        r.cross.add(ac[1]);
                    }
                }
                kf.predict(dt);
            }
            double wheel = useWheels ? wheelAt(motion, meas) : Double.NaN;
            if (!Double.isNaN(wheel)) {
                kf.update(f.lat, f.lon, wheel, f.bearing, f.acc, CAR_SPEED_ACC, f.brgAcc, true, f.hasBearing);
            } else {
                kf.update(f.lat, f.lon, f.speed, f.bearing, f.acc, f.spdAcc, f.brgAcc, f.hasSpeed, f.hasBearing);
            }
            r.fixes++;
            lastTs = f.ts;
            lastArrival = f.arrival;
            stateTime = meas;
            prevBearing = f.hasBearing ? f.bearing : Double.NaN;
            // output ticks until the next fix arrives (or the 2.5 s GPS-loss freeze)
            long next = nextArrival(fixes, i, gapStart, gapEnd);
            boolean first = true;
            for (long t = f.arrival + 10; kf.isInitialized() && t < next && t - f.arrival <= 2500; t += 100) {
                double h = Math.max(0, Math.min((t - stateTime) / 1000.0, 3.0));
                LocationKalmanFilter.Estimate e = kf.extrapolate(h);
                double[] o = {e.latitude, e.longitude, t, e.speed, e.bearingDeg};
                r.addOut(o);
                if (lastOut != null) {
                    double dts = (t - lastOut[2]) / 1000.0;
                    double d = dist(lastOut[0], lastOut[1], o[0], o[1]);
                    if (scored && dts > 0 && dts <= 0.5 && d / dts - e.speed > 8) r.jumps++;
                    if (scored && first && dts > 0 && dts <= 0.5 && e.speed > 5) r.snap.add(Math.abs(d - e.speed * dts));
                }
                lastOut = o;
                first = false;
                if (scored) r.outputs++;
            }
        }
        return r;
    }

    static final long REPLAY_MAX_HOLE_MS = 2500;   // recorded holes the replay bridges (live: 50 Hz gyro, 5 Hz wheel)

    static double yawAt(Motion m, long t) {
        int i = java.util.Arrays.binarySearch(m.yawT, t);
        if (i >= 0) return m.yaw[i];
        i = -i - 1;
        if (i == 0 || i == m.yawT.length || m.yawT[i] - m.yawT[i - 1] > REPLAY_MAX_HOLE_MS) return Double.NaN;
        return m.yaw[i - 1] + (m.yaw[i] - m.yaw[i - 1]) * (t - m.yawT[i - 1]) / (double) (m.yawT[i] - m.yawT[i - 1]);
    }

    static String gearAt(Motion m, long t) {
        if (m == null || m.wheelT.length == 0) return null;
        int i = java.util.Arrays.binarySearch(m.wheelT, t);
        i = i >= 0 ? i : -i - 2;
        return i < 0 ? null : m.gear[i];
    }

    static final long DR_MAX_MS = DeadReckoner.MAX_MS;
    static final double DR_MAX_ACC_M = DeadReckoner.MAX_ACC_M;

    /**
     * The motion-fusion pipeline (the reference glue GNSSClientService copies step for step): gyro heading
     * change in the predict step, the tracker pulled by GPS bearings, the shipped update, and output from
     * dead reckoning (wheel distance along the gyro heading) re-anchored at every fix, falling back to the
     * filter's extrapolation whenever the tracker or the wheels are missing.
     */
    static Result replayFused(List<Fix> fixes, Motion m, int gapS, long gapStart, long fromT, long toT, int gpsLagMs) {
        LocationKalmanFilter kf = new LocationKalmanFilter(2.0, 1.0);
        HeadingTracker tracker = new HeadingTracker(0.15);
        DeadReckoner.WheelSpeed wheel = t -> {
            double v = wheelAt(m, t);
            return "R".equals(gearAt(m, t)) ? -v : v;
        };
        DeadReckoner dr = new DeadReckoner(tracker, wheel);
        DeadReckoner.Slide slide = new DeadReckoner.Slide();
        LocationKalmanFilter.Estimate drOut = new LocationKalmanFilter.Estimate();
        FixClock clock = new FixClock();
        Result r = new Result();
        // The CSV holds the gyro at ≤ 10 Hz (1 Hz in "once per second" mode); live it's 50 Hz. Feed a 50 Hz
        // stream interpolated between recorded samples up to 2.5 s apart (the live gyro is never "behind").
        long[] fedT = {Long.MIN_VALUE};
        java.util.function.LongConsumer feedYaw = t -> {
            if (m == null || m.yawT.length == 0) return;
            long u = fedT[0] == Long.MIN_VALUE ? m.yawT[0] : fedT[0] + 20;
            for (; u <= t; u += 20) {
                double y = yawAt(m, u);
                if (!Double.isNaN(y)) tracker.onYaw(u, y);
                fedT[0] = u;
            }
        };
        long lastTs = Long.MIN_VALUE, lastArrival = 0, stateTime = 0;
        double prevBearing = Double.NaN;
        double[] lastOut = null;
        boolean lastOutDr = false;
        long gapEnd = gapStart < 0 ? -1 : gapStart + gapS * 1000L;
        for (int i = 0; i < fixes.size(); i++) {
            Fix f = fixes.get(i);
            if (f.ts <= lastTs) continue;
            if (gapStart >= 0 && f.arrival > gapStart && f.arrival <= gapEnd) continue;
            long latency = Math.min(clock.latencyMs(f.arrival, f.ts, f.ageMs), 3000);
            long meas = f.arrival - latency;
            f.measT = meas;
            if (gapStart >= 0 && f.arrival > gapEnd) {
                r.gapErr = lastOut == null ? Double.NaN : dist(lastOut[0], lastOut[1], f.lat, f.lon);
                return r;
            }
            feedYaw.accept(f.arrival);
            boolean scored = f.arrival >= fromT && f.arrival <= toT;
            boolean gap = lastArrival == 0 || f.arrival - lastArrival > 2500;
            boolean drBridged = gap && lastOutDr && dr.active() && lastOut != null;
            if (gap && !drBridged) {
                kf.reset();
                lastOut = null;
            } else if (drBridged) {
                // GPS is back after a gap the dead reckoning bridged: re-anchor the filter there, at this fix's time.
                dr.fill(drOut, (long) lastOut[2]);
                double back = Math.abs(drOut.speed) * Math.max(0, lastOut[2] - meas) / 1000.0;
                double br = Math.toRadians(drOut.bearingDeg);
                double lat = drOut.latitude - back * Math.cos(br) / M_PER_DEG;
                double lon = drOut.longitude - back * Math.sin(br) / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
                kf.setState(lat, lon, drOut.speed, drOut.bearingDeg, drOut.accuracy, 0.5);
            } else if (kf.isInitialized()) {
                double dt = (f.ts - lastTs) / 1000.0;
                if (!(dt > 0 && dt <= 5)) dt = (meas - stateTime) / 1000.0;
                dt = Math.max(0, Math.min(dt, 5));
                if (scored && f.hasSpeed && f.speed > ICON_MIN_SPEED && f.hasBearing) {
                    r.scoreIcon(f, Double.isNaN(prevBearing) ? 0 : Math.abs(wrap(f.bearing - prevBearing)) / dt);
                }
                double dTheta = tracker.valid(meas) && !Double.isNaN(wheel.mpsAt(meas)) ? tracker.deltaRadCcw(stateTime - gpsLagMs, stateTime - gpsLagMs + Math.round(dt * 1000)) : Double.NaN;
                kf.predict(dt, dTheta);
            }
            if (f.hasBearing) {
                boolean reverse = "R".equals(gearAt(m, meas));
                tracker.onGpsBearing(meas - gpsLagMs, f.speed, f.bearing + (kf.isBearingQuantized() ? 0.5 : 0), reverse);
            }
            double w = wheelAt(m, meas);
            if (!Double.isNaN(w)) {
                kf.update(f.lat, f.lon, w, f.bearing, f.acc, CAR_SPEED_ACC, f.brgAcc, true, f.hasBearing);
            } else {
                kf.update(f.lat, f.lon, f.speed, f.bearing, f.acc, f.spdAcc, f.brgAcc, f.hasSpeed, f.hasBearing);
            }
            r.fixes++;
            lastTs = f.ts;
            lastArrival = f.arrival;
            stateTime = meas;
            prevBearing = f.hasBearing ? f.bearing : Double.NaN;
            // Re-anchor the dead reckoning at the new state; slide away the jump if the icon was dead-reckoned.
            double[] oldNow = null;
            if (lastOutDr && dr.active() && dr.step(f.arrival)) {
                dr.fill(drOut, f.arrival);
                slide.apply(drOut, f.arrival);
                oldNow = new double[]{drOut.latitude, drOut.longitude};
            }
            if (kf.isInitialized()) {
                LocationKalmanFilter.Estimate e0 = kf.extrapolate(0);
                if (dr.anchor(e0.latitude, e0.longitude, e0.accuracy, meas, f.arrival) && oldNow != null) {
                    dr.fill(drOut, f.arrival);
                    double kLon = M_PER_DEG * Math.cos(Math.toRadians(drOut.latitude));
                    slide.start((oldNow[1] - drOut.longitude) * kLon, (oldNow[0] - drOut.latitude) * M_PER_DEG, f.arrival);
                } else {
                    slide.start(0, 0, f.arrival);
                }
            }
            long next = nextArrival(fixes, i, gapStart, gapEnd);
            boolean first = true;
            for (long t = f.arrival + 10; kf.isInitialized() && t < next && t - f.arrival <= DR_MAX_MS; t += 100) {
                feedYaw.accept(t);
                tracker.onWheel(t, wheel.mpsAt(t));
                LocationKalmanFilter.Estimate e = null;
                boolean fromDr = false;
                boolean wasActive = dr.active();
                if (!dr.step(t) && wasActive && lastOutDr && gapStart >= 0 && t > gapStart && Double.isNaN(wheel.mpsAt(t))) {
                    r.dataHole = true;   // live the wheels are polled at 5 Hz; the CSV has holes
                }
                if (dr.active()) {
                    dr.fill(drOut, t);
                    if (drOut.accuracy <= DR_MAX_ACC_M) {
                        slide.apply(drOut, t);
                        e = drOut;
                        fromDr = true;
                    }
                }
                if (e == null && t - f.arrival <= 2500) {
                    e = kf.extrapolate(Math.max(0, Math.min((t - stateTime) / 1000.0, 3.0)));
                }
                if (e == null) {
                    lastOutDr = false;   // as the service: a tick without a DR output clears it
                    break;               // freeze
                }
                double[] o = {e.latitude, e.longitude, t, e.speed, e.bearingDeg};
                r.addOut(o);
                if (lastOut != null) {
                    double dts = (t - lastOut[2]) / 1000.0;
                    double d = dist(lastOut[0], lastOut[1], o[0], o[1]);
                    if (scored && dts > 0 && dts <= 0.5 && d / dts - e.speed > 8) r.jumps++;
                    if (scored && first && dts > 0 && dts <= 0.5 && e.speed > 5) r.snap.add(Math.abs(d - e.speed * dts));
                }
                lastOut = o;
                lastOutDr = fromDr;
                first = false;
                if (scored) r.outputs++;
            }
            if (next - f.arrival > DR_MAX_MS) lastOutDr = false;   // the service stops dead reckoning at DR_MAX_MS
        }
        return r;
    }

    static final double CAR_SPEED_ACC = 0.15;
    static final double ICON_MIN_SPEED = 3.0;   // city turns are slow: > 5 m/s left 11 turns in a 23 min drive   // GNSSClientService.CAR_SPEED_ACC_MPS

    /** Arrival of the next fix the run will see (skipping the artificial gap), or +1 s at the end. */
    static long nextArrival(List<Fix> fixes, int i, long gapStart, long gapEnd) {
        for (int j = i + 1; j < fixes.size(); j++) {
            long a = fixes.get(j).arrival;
            if (gapStart >= 0 && a > gapStart && a <= gapEnd) continue;
            return a;
        }
        return fixes.get(i).arrival + 1000;
    }

    /** One run per gap start: every 60 s, the first fix at ≥ 4 m/s and acc ≤ 10 m arriving in [fromT, toT]. */
    static List<Double> gapErrors(List<Fix> fixes, long fromT, long toT, java.util.function.LongFunction<Result> run) {
        List<Double> out = new ArrayList<>();
        long nextStart = Long.MIN_VALUE;
        for (Fix f : fixes) {
            if (f.arrival < fromT || f.arrival > toT || f.arrival < nextStart) continue;
            if (!f.hasSpeed || f.speed < 4 || f.acc > 10) continue;
            Result r = run.apply(f.arrival);
            if (r.dataHole) {
                excludedGaps++;
            } else if (!Double.isNaN(r.gapErr)) {
                out.add(r.gapErr);
            }
            nextStart = f.arrival + 60_000;
        }
        return out;
    }

    static int excludedGaps;   // gap runs dropped for holes in the recorded wheel data (printed, then reset)

    static String gapSummary(String name, List<Double> g30, List<Double> g60) {
        String line = String.format(Locale.US,
                "%-28s gap30 p50=%.1f max=%.1f m (n=%d) | gap60 p50=%.1f max=%.1f m (n=%d) | excluded (wheel data holes)=%d",
                name, pct(g30, 50), pct(g30, 100), g30.size(), pct(g60, 50), pct(g60, 100), g60.size(), excludedGaps);
        excludedGaps = 0;
        return line;
    }

    @Test
    public void replayRecordedTrack() throws Exception {
        String path = System.getenv("LOCSYNC_TRACK");
        assumeTrue("set LOCSYNC_TRACK to a recorded track-client.csv", path != null);
        List<Fix> fixes = load(Path.of(path));
        System.out.println(replay(fixes, false, TrackReplayTest::v34).summary("v3.4 (age only)"));
        System.out.println(replay(fixes, true, TrackReplayTest::v34).summary("+ Wi-Fi delay sync"));
        System.out.println(replay(fixes, true, kf -> {
            v34(kf);
            kf.setBearingHandling(true, 2.0);
        }).summary("+ bearing rounding fix"));
        System.out.println(replay(fixes, true, kf -> {
            v34(kf);
            kf.setBearingHandling(true, 2.0);
            kf.setAdaptivePosition(true);
        }).summary("+ adaptive position trust"));
        System.out.println(replay(fixes, true, kf -> {
            kf.setSplitVelocityNoise(false);
            kf.setAccelerationExtrapolation(false);
        }).summary("v3.7 (isotropic velocity)"));
        System.out.println(replay(fixes, true, kf -> kf.setAccelerationExtrapolation(false))
                .summary("+ speed trusted along course"));
        System.out.println(replay(fixes, true, kf -> { }).summary("+ acceleration ramp"));
        for (double a : new double[]{0.7, 0.85, 1.0}) {
            System.out.println(replay(fixes, true, kf -> kf.setTurnResponsiveness(a))
                    .summary(String.format(Locale.US, "+ turn responsiveness %.2f", a)));
        }
        Motion motion = loadMotion(Path.of(path));
        long mid = (fixes.get(0).arrival + fixes.get(fixes.size() - 1).arrival) / 2;
        long[][] spans = {{Long.MIN_VALUE, Long.MAX_VALUE}, {Long.MIN_VALUE, mid}, {mid + 1, Long.MAX_VALUE}};
        String[] spanNames = {"", " [1st half]", " [2nd half]"};
        for (int s = 0; s < spans.length; s++) {
            long from = spans[s][0], to = spans[s][1];
            Motion m = motion;
            String name = "+ wheel speed (as shipped)" + spanNames[s];
            Result shipped = replay(fixes, m, true, true, 0, -1, from, to, kf -> { });
            System.out.println(shipped.summary(name));
            System.out.println(shipped.iconSummary(name));
            List<Double> g30 = gapErrors(fixes, from, to, g -> replay(fixes, m, true, true, 30, g, from, to, kf -> { }));
            List<Double> g60 = gapErrors(fixes, from, to, g -> replay(fixes, m, true, true, 60, g, from, to, kf -> { }));
            System.out.println(gapSummary(name, g30, g60));
            for (int lag : new int[]{0, 200, 400}) {
                String fn = "+ motion fusion lag " + lag + spanNames[s];
                System.out.println(replayFused(fixes, m, 0, -1, from, to, lag).iconSummary(fn));
                List<Double> f30 = gapErrors(fixes, from, to, g -> replayFused(fixes, m, 30, g, from, to, lag));
                List<Double> f60 = gapErrors(fixes, from, to, g -> replayFused(fixes, m, 60, g, from, to, lag));
                System.out.println(gapSummary(fn, f30, f60));
            }
        }
    }

    // The recorder's 5 Hz car rows are what the replay reads for wheel speed and gear when present.
    @Test
    public void loadMotionReadsCarRows() throws Exception {
        Path dir = Files.createTempDirectory("track");
        TrackRecorder r = new TrackRecorder(dir.toFile(), new GyroYaw());
        r.car(1000, 36f, "D", null, null, Float.NaN, null);
        r.car(1200, 36f, "R", null, null, Float.NaN, null);
        r.car(1400, 18f, "R", null, null, Float.NaN, null);
        r.close();
        Motion m = loadMotion(TrackRecorder.fileFor(dir.toFile()).toPath());
        org.junit.Assert.assertEquals(600, m.maxGapMs);
        org.junit.Assert.assertEquals(0, m.yawT.length);
        org.junit.Assert.assertEquals(36 * WHEEL_FACTOR / 3.6, wheelAt(m, 1100), 1e-9);
        org.junit.Assert.assertEquals(27 * WHEEL_FACTOR / 3.6, wheelAt(m, 1300), 1e-9);
        org.junit.Assert.assertEquals("D", gearAt(m, 1100));
        org.junit.Assert.assertEquals("R", gearAt(m, 1250));
    }

    /** Filter settings as shipped in v3.4.x, for comparison. */
    static void v34(LocationKalmanFilter kf) {
        kf.setBearingHandling(false, 0);
        kf.setAdaptivePosition(false);
        kf.setTurnResponsiveness(0.5);
        kf.setSplitVelocityNoise(false);
        kf.setAccelerationExtrapolation(false);
    }

    static double[] alongCross(double lat, double lon, Fix f) {
        double de = (lon - f.lon) * M_PER_DEG * Math.cos(Math.toRadians(f.lat));
        double dn = (lat - f.lat) * M_PER_DEG;
        double b = Math.toRadians(f.bearing);
        return new double[]{de * Math.sin(b) + dn * Math.cos(b), de * Math.cos(b) - dn * Math.sin(b)};
    }

    static double dist(double lat1, double lon1, double lat2, double lon2) {
        return Math.hypot((lon2 - lon1) * M_PER_DEG * Math.cos(Math.toRadians(lat1)), (lat2 - lat1) * M_PER_DEG);
    }

    static double wrap(double a) {
        a = ((a + 180) % 360 + 360) % 360 - 180;
        return a;
    }

    static double pct(List<Double> v, double p) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) (p / 100 * s.size())));
    }

    private static double parseOr0(String s) {
        return s.isEmpty() ? 0 : Double.parseDouble(s);
    }
}
