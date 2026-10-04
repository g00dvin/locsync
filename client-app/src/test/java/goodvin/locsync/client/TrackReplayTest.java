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
    private static final double M_PER_DEG = 111320.0;

    /** One recorded fix as it arrived. */
    static final class Fix {
        long arrival, ts, ageMs;
        double lat, lon, speed, bearing, acc, spdAcc, brgAcc;
        boolean hasSpeed, hasBearing;
    }

    static final class Result {
        int fixes, outputs, jumps;
        List<Double> predErr = new ArrayList<>(), predErrTurn = new ArrayList<>(), headingErrTurn = new ArrayList<>();
        List<Double> along = new ArrayList<>(), cross = new ArrayList<>();
        List<Double> snap = new ArrayList<>();   // icon shift beyond normal motion at each fix arrival

        String summary(String name) {
            return String.format(Locale.US,
                    "%-28s next-fix error p50=%.2f p90=%.2f m | turns p50=%.2f m, heading p50=%.1f° p90=%.1f° | "
                            + "along p50=%+.2f cross p50=%+.2f m | snap at fix p50=%.2f p90=%.2f m | jumps=%d of %d",
                    name, pct(predErr, 50), pct(predErr, 90), pct(predErrTurn, 50), pct(headingErrTurn, 50),
                    pct(headingErrTurn, 90), pct(along, 50), pct(cross, 50), pct(snap, 50), pct(snap, 90), jumps, outputs);
        }
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
        LocationKalmanFilter kf = new LocationKalmanFilter(2.0, 1.0);
        setup.accept(kf);
        FixClock clock = new FixClock();
        Result r = new Result();
        long lastTs = Long.MIN_VALUE, lastArrival = 0, stateTime = 0;
        double prevBearing = Double.NaN;
        double[] lastOut = null;
        for (int i = 0; i < fixes.size(); i++) {
            Fix f = fixes.get(i);
            if (f.ts <= lastTs) continue;
            long latency = networkSync ? clock.latencyMs(f.arrival, f.ts, f.ageMs) : f.ageMs;
            latency = Math.min(latency, 3000);
            long meas = f.arrival - latency;
            boolean gap = lastArrival == 0 || f.arrival - lastArrival > 2500;
            if (gap) {
                kf.reset();
                lastOut = null;
            } else if (kf.isInitialized()) {
                double dt = (f.ts - lastTs) / 1000.0;
                if (!(dt > 0 && dt <= 5)) dt = (meas - stateTime) / 1000.0;
                dt = Math.max(0, Math.min(dt, 5));
                // prediction quality: where the filter expected this fix to be
                LocationKalmanFilter.Estimate e = kf.extrapolate(dt);
                if (f.hasSpeed && f.speed > 5 && f.hasBearing) {
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
            kf.update(f.lat, f.lon, f.speed, f.bearing, f.acc, f.spdAcc, f.brgAcc, f.hasSpeed, f.hasBearing);
            r.fixes++;
            lastTs = f.ts;
            lastArrival = f.arrival;
            stateTime = meas;
            prevBearing = f.hasBearing ? f.bearing : Double.NaN;
            // output ticks until the next fix arrives (or the 2.5 s GPS-loss freeze)
            long next = i + 1 < fixes.size() ? fixes.get(i + 1).arrival : f.arrival + 1000;
            boolean first = true;
            for (long t = f.arrival + 10; t < next && t - f.arrival <= 2500; t += 100) {
                double h = Math.max(0, Math.min((t - stateTime) / 1000.0, 3.0));
                LocationKalmanFilter.Estimate e = kf.extrapolate(h);
                double[] o = {e.latitude, e.longitude, t, e.speed};
                if (lastOut != null) {
                    double dts = (t - lastOut[2]) / 1000.0;
                    double d = dist(lastOut[0], lastOut[1], o[0], o[1]);
                    if (dts > 0 && dts <= 0.5 && d / dts - e.speed > 8) r.jumps++;
                    if (first && dts > 0 && dts <= 0.5 && e.speed > 5) r.snap.add(Math.abs(d - e.speed * dts));
                }
                lastOut = o;
                first = false;
                r.outputs++;
            }
        }
        return r;
    }

    @Test
    public void replayRecordedTrack() throws Exception {
        String path = System.getenv("LOCSYNC_TRACK");
        assumeTrue("set LOCSYNC_TRACK to a recorded track-client.csv", path != null);
        List<Fix> fixes = load(Path.of(path));
        System.out.println(replay(fixes, false, TrackReplayTest::v34).summary("v3.4 (age only)"));
        System.out.println(replay(fixes, true, TrackReplayTest::v34).summary("+ Wi-Fi delay sync"));
        System.out.println(replay(fixes, true, kf -> {
            kf.setAdaptivePosition(false);
            kf.setTurnResponsiveness(0.5);
        }).summary("+ bearing rounding fix"));
        System.out.println(replay(fixes, true, kf -> kf.setTurnResponsiveness(0.5)).summary("+ adaptive position trust"));
        for (double a : new double[]{0.7, 0.85, 1.0}) {
            System.out.println(replay(fixes, true, kf -> kf.setTurnResponsiveness(a))
                    .summary(String.format(Locale.US, "+ turn responsiveness %.2f", a)));
        }
    }

    /** Filter settings as shipped in v3.4.x, for comparison. */
    static void v34(LocationKalmanFilter kf) {
        kf.setBearingHandling(false, 0);
        kf.setAdaptivePosition(false);
        kf.setTurnResponsiveness(0.5);
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
