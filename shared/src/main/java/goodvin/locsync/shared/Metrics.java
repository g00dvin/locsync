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

package goodvin.locsync.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Performance-metrics collector. Hot paths call the cheap record* methods; a 1 Hz sampler calls
 * snapshot(). All state is guarded by a single lock — record rates (≤10 Hz) make contention
 * negligible. Android-free so it is JVM unit-testable.
 *
 * CPU% assumes CLK_TCK = 100 jiffies/sec (Android standard). It counts all process threads and may
 * exceed 100% on multi-core devices.
 */
public final class Metrics {
    private static final double JIFFIES_PER_SEC = 100.0;
    private static final int MAX_SAMPLES = 4096;

    private final Object lock = new Object();

    // cumulative counters
    private long packetsSent, bytesSent, packetsRecv, bytesRecv, fixes;
    // per-window samples
    private final List<Double> ages = new ArrayList<>();
    private final List<Double> jitters = new ArrayList<>();
    private double maxGapMs = Double.NaN;
    private long lastRecvElapsedMs = -1;

    // previous-snapshot baselines
    private boolean primed = false;
    private SystemStats prev;
    private long pPacketsSent, pBytesSent, pPacketsRecv, pBytesRecv, pFixes;

    public void recordPacketSent(int bytes) {
        synchronized (lock) { packetsSent++; bytesSent += bytes; }
    }

    public void recordPacketRecv(int bytes, long elapsedRealtimeMs) {
        synchronized (lock) {
            packetsRecv++; bytesRecv += bytes;
            if (lastRecvElapsedMs >= 0) {
                double gap = elapsedRealtimeMs - lastRecvElapsedMs;
                if (Double.isNaN(maxGapMs) || gap > maxGapMs) maxGapMs = gap;
            }
            lastRecvElapsedMs = elapsedRealtimeMs;
        }
    }

    public void recordFix() {
        synchronized (lock) { fixes++; }
    }

    public void recordFixAgeMs(double ageMs) {
        synchronized (lock) { if (ages.size() < MAX_SAMPLES) ages.add(ageMs); }
    }

    public void recordTickJitterMs(double jitterMs) {
        synchronized (lock) { if (jitters.size() < MAX_SAMPLES) jitters.add(jitterMs); }
    }

    /** Visible for testing: total pending (unsnapshotted) sample count, to assert the cap holds. */
    int sampleBacklog() {
        synchronized (lock) { return ages.size() + jitters.size(); }
    }

    /** Compute the snapshot for the window since the previous call; resets per-window state. */
    public MetricsSnapshot snapshot(SystemStats now) {
        synchronized (lock) {
            if (!primed || prev == null) {
                primeLocked(now);
                return zero();
            }
            double windowMs = now.elapsedRealtimeMs - prev.elapsedRealtimeMs;
            double windowS = windowMs / 1000.0;
            double cpuPct = (prev.cpuJiffies < 0 || now.cpuJiffies < 0 || windowS <= 0) ? Double.NaN
                    : ((now.cpuJiffies - prev.cpuJiffies) / JIFFIES_PER_SEC) / windowS * 100.0;
            double gcCountDelta = (prev.gcCount < 0 || now.gcCount < 0) ? Double.NaN : now.gcCount - prev.gcCount;
            double gcPauseDelta = (prev.gcPauseMs < 0 || now.gcPauseMs < 0) ? Double.NaN : now.gcPauseMs - prev.gcPauseMs;
            double allocKbPerSec = (prev.allocBytes < 0 || now.allocBytes < 0 || windowS <= 0) ? Double.NaN
                    : (now.allocBytes - prev.allocBytes) / 1024.0 / windowS;
            double currentUa = now.currentUa == Integer.MIN_VALUE ? Double.NaN : now.currentUa;
            double denom = windowS <= 0 ? Double.NaN : windowS;

            MetricsSnapshot s = new MetricsSnapshot(
                    windowMs, cpuPct, gcCountDelta, gcPauseDelta, allocKbPerSec, currentUa,
                    rate(fixes - pFixes, denom),
                    rate(packetsSent - pPacketsSent, denom), rate(bytesSent - pBytesSent, denom),
                    rate(packetsRecv - pPacketsRecv, denom), rate(bytesRecv - pBytesRecv, denom),
                    maxGapMs, mean(ages), percentile(ages, 95), mean(jitters), max(jitters), percentile(jitters, 95));

            primeLocked(now);
            return s;
        }
    }

    private void primeLocked(SystemStats now) {
        prev = now; primed = true;
        pPacketsSent = packetsSent; pBytesSent = bytesSent;
        pPacketsRecv = packetsRecv; pBytesRecv = bytesRecv; pFixes = fixes;
        ages.clear(); jitters.clear();
        maxGapMs = Double.NaN;
        // keep lastRecvElapsedMs so gaps span windows
    }

    private static MetricsSnapshot zero() {
        return new MetricsSnapshot(0, 0, 0, 0, 0, Double.NaN, 0, 0, 0, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static double rate(long delta, double windowS) {
        return Double.isNaN(windowS) ? Double.NaN : delta / windowS;
    }

    private static double mean(List<Double> xs) {
        if (xs.isEmpty()) return Double.NaN;
        double s = 0; for (double x : xs) s += x; return s / xs.size();
    }

    private static double max(List<Double> xs) {
        if (xs.isEmpty()) return Double.NaN;
        double m = Double.NEGATIVE_INFINITY; for (double x : xs) m = Math.max(m, x); return m;
    }

    /** Nearest-rank percentile: index = ceil(p/100 * n) - 1, clamped. */
    private static double percentile(List<Double> xs, double p) {
        if (xs.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }
}
