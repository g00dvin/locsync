package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DriveStatsTest {
    /** Feeds 5 Hz samples for {@code seconds}, speed and heading changing linearly; returns the end time. */
    private static long drive(DriveStats d, long t0, int seconds, double v0, double v1, double h0, double h1) {
        int n = seconds * 5;
        for (int i = 0; i <= n; i++) {
            d.onMotion(t0 + i * 200L, v0 + (v1 - v0) * i / n, h0 + (h1 - h0) * i / n);
        }
        return t0 + seconds * 1000L;
    }

    @Test
    public void countsEachHardManoeuvreOnce() {
        DriveStats d = new DriveStats(20261114);
        long t = drive(d, 0, 60, 20, 20, 90, 90);            // steady: nothing
        assertEquals(0, d.brakes + d.accels + d.turns);
        assertEquals(1200, d.distM, 5);
        assertEquals(100, d.score());
        t = drive(d, t, 4, 20, 4, 90, 90);                   // 4 m/s² for 4 s: one braking, not four
        assertEquals(1, d.brakes);
        t = drive(d, t, 5, 4, 4, 90, 90);
        t = drive(d, t, 4, 4, 16, 90, 90);                   // 3 m/s² up: one hard acceleration
        assertEquals(1, d.accels);
        t = drive(d, t, 5, 16, 16, 90, 90);
        t = drive(d, t, 6, 16, 16, 350, 80);                 // 15 °/s at 16 m/s = 4.2 m/s², through north
        assertEquals(1, d.turns);
        t = drive(d, t, 10, 16, 16, 80, 80);
        drive(d, t, 4, 16, 8, 80, 80);                       // 2 m/s²: ordinary braking
        assertEquals(1, d.brakes);
        assertTrue(d.score() < 100 && d.score() >= 0);
    }

    @Test
    public void unreadableSpeedAndHolesJudgeNothing() {
        DriveStats d = new DriveStats(20261114);
        d.onMotion(0, 30, 0);
        d.onMotion(200, Double.NaN, 0);
        d.onMotion(400, 0, 0);                                // would be a 150 m/s² "braking" across the hole
        d.onMotion(10_000, 30, 0);                            // a 9.6 s hole: starts over
        d.onMotion(11_200, 30, Double.NaN);                   // heading unknown: no turn
        assertEquals(0, d.brakes + d.accels + d.turns);
        assertEquals(-1, d.score());                          // under a kilometre
    }

    @Test
    public void surviveTheDayButNotTheNight() {
        DriveStats d = new DriveStats(20261114);
        drive(d, 0, 100, 20, 20, 0, 0);
        d.brakes = 2;
        d.onGap(15_000, 12.5);
        d.onGap(4_000, 3.0);
        DriveStats same = DriveStats.decode(d.encode(), 20261114);
        assertEquals(2000, same.distM, 1);
        assertEquals(2, same.brakes);
        assertEquals(2, same.gaps);
        assertEquals(15_000, same.gapMsMax);
        assertEquals(19_000, same.gapMsTotal);
        assertEquals(12.5, same.gapErrMax, 0.01);
        assertEquals(15.5, same.gapErrSum, 0.01);
        assertEquals(d.score(), same.score());
        assertEquals(0, DriveStats.decode(d.encode(), 20261115).gaps);
        assertEquals(0, DriveStats.decode("junk", 20261114).distM, 0);
        assertEquals(0, DriveStats.decode(null, 20261114).distM, 0);
    }
}
