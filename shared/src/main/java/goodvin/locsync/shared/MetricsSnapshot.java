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

import java.util.Locale;

/** Immutable computed view over one sampling window. NaN = not applicable / no data. Android-free. */
public final class MetricsSnapshot {
    public final double windowMs, cpuPct, gcCountDelta, gcPauseMsDelta, allocKbPerSec,
            currentUa, fixesPerSec, pktSentPerSec, bytesSentPerSec,
            pktRecvPerSec, bytesRecvPerSec, maxGapMs, ageMeanMs, ageP95Ms,
            jitterMeanMs, jitterMaxMs, jitterP95Ms;

    MetricsSnapshot(double windowMs, double cpuPct, double gcCountDelta, double gcPauseMsDelta,
                    double allocKbPerSec, double currentUa, double fixesPerSec,
                    double pktSentPerSec, double bytesSentPerSec, double pktRecvPerSec,
                    double bytesRecvPerSec, double maxGapMs, double ageMeanMs, double ageP95Ms,
                    double jitterMeanMs, double jitterMaxMs, double jitterP95Ms) {
        this.windowMs = windowMs; this.cpuPct = cpuPct; this.gcCountDelta = gcCountDelta;
        this.gcPauseMsDelta = gcPauseMsDelta; this.allocKbPerSec = allocKbPerSec;
        this.currentUa = currentUa; this.fixesPerSec = fixesPerSec;
        this.pktSentPerSec = pktSentPerSec; this.bytesSentPerSec = bytesSentPerSec;
        this.pktRecvPerSec = pktRecvPerSec; this.bytesRecvPerSec = bytesRecvPerSec;
        this.maxGapMs = maxGapMs; this.ageMeanMs = ageMeanMs; this.ageP95Ms = ageP95Ms;
        this.jitterMeanMs = jitterMeanMs; this.jitterMaxMs = jitterMaxMs; this.jitterP95Ms = jitterP95Ms;
    }

    private static final String[] COLUMNS = {
            "ts_iso", "uptime_s", "cpu_pct", "gc_count", "gc_pause_ms", "alloc_kb_s",
            "current_ua", "fixes_s", "pkts_sent_s", "bytes_sent_s",
            "pkts_recv_s", "bytes_recv_s", "max_gap_ms", "age_mean_ms", "age_p95_ms",
            "tick_jit_mean_ms", "tick_jit_max_ms", "tick_jit_p95_ms"
    };

    public static String csvHeader() {
        return String.join(",", COLUMNS);
    }

    private static String c(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%.2f", v);
    }

    /** Appends one CSV row (no newline) to {@code sb}; NaN cells are left empty. */
    public StringBuilder toCsvRow(StringBuilder sb, String tsIso, long uptimeS) {
        sb.append(tsIso).append(',').append(uptimeS);
        for (double v : new double[]{cpuPct, gcCountDelta, gcPauseMsDelta, allocKbPerSec, currentUa,
                fixesPerSec, pktSentPerSec, bytesSentPerSec, pktRecvPerSec, bytesRecvPerSec, maxGapMs,
                ageMeanMs, ageP95Ms, jitterMeanMs, jitterMaxMs, jitterP95Ms}) {
            CsvLog.appendFixed(sb.append(','), v, 2);
        }
        return sb;
    }

    public String toLogLine() {
        return String.format(Locale.US,
                "cpu=%s%% gc=%s/%sms alloc=%sKB/s cur=%suA fix=%s/s sent=%s/s recv=%s/s "
                        + "gap=%sms age=%s/%sms jit=%s/%s/%sms",
                c(cpuPct), c(gcCountDelta), c(gcPauseMsDelta), c(allocKbPerSec), c(currentUa), c(fixesPerSec), c(pktSentPerSec), c(pktRecvPerSec), c(maxGapMs),
                c(ageMeanMs), c(ageP95Ms), c(jitterMeanMs), c(jitterMaxMs), c(jitterP95Ms));
    }
}
