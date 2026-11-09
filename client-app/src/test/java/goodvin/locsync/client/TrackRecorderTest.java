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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class TrackRecorderTest {
    private static final float S = Float.intBitsToFloat(1);   // AdaptAPI "unsupported" sentinel

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // Both row kinds must line up with the header, or the CSV can't be replayed/analysed.
    @Test
    public void rowsMatchHeaderColumns() throws Exception {
        File dir = tmp.newFolder();
        TrackRecorder r = new TrackRecorder(dir, new GyroYaw());
        LocationKalmanFilter f = new LocationKalmanFilter(2.0, 1.0);
        f.update(55.75, 37.6, 10.0, 90.0, 4.0, 0.3, 5.0);
        r.fix(1000, 1_700_000_000_000L, "fused", 17, 55.75, 37.6, 150.0, 4.0f,
                true, 10.0f, true, 90.0f, 0.3f, 5.0f, 0.04f, 40, f, null);
        r.fix(2000, 1_700_000_001_000L, "gps", 0, 55.75, 37.6, 150.0, 4.0f,
                false, 0f, false, 0f, 0f, 0f, 0.05f, 50, f,
                CarSignals.fromRaw(10f, 10.5f, 0x200220, 38f, 408f, 0.5f, 23275f, Float.intBitsToFloat(95), 1500L));
        r.fix(2050, 1_700_000_001_050L, "gps", 0, 55.75, 37.6, 150.0, 4.0f,
                false, 0f, false, 0f, 0f, 0f, 0.05f, 50, f,
                CarSignals.fromRaw(S, S, 0x200220, 38f, 408f, 0.5f, 23275f, S, 1500L));   // car data, no speed
        r.gyro.accel(2_000_000_000L, 0, -9.8f, 0);
        r.gyro.gyro(2_000_000_000L, 0, 1, 0);
        r.gyro.gyro(2_040_000_000L, 0, 1, 0);   // 1 rad/s clockwise for 40 ms = 2.292°
        r.fix(2060, 1_700_000_001_060L, "gps", 0, 55.75, 37.6, 150.0, 4.0f,
                false, 0f, false, 0f, 0f, 0f, 0.05f, 50, f,
                CarSignals.fromRaw(S, 10.5f, 0x200220, 38f, 408f, 0.5f, 23275f, S, 1560L));  // cluster speed only
        r.out(2100, 55.75, 37.6, true, 10.0, 90.0, 6.0, 0.3, Double.NaN, 100, "dr");
        r.out(2200, 55.75, 37.6, false, 0.1, 0, 6.0, Double.NaN, Double.NaN, 200, "kf");
        r.close();

        List<String> lines = Files.readAllLines(TrackRecorder.fileFor(dir).toPath());
        assertEquals(TrackRecorder.HEADER, lines.get(0));
        int cols = TrackRecorder.HEADER.split(",", -1).length;
        assertEquals(7, lines.size());
        for (String line : lines.subList(1, lines.size())) {
            assertEquals(line, cols, line.split(",", -1).length);
        }
        assertTrue(lines.get(1).contains(",fix,"));
        String[] noCar = lines.get(1).split(",", -1);
        List<String> header = java.util.Arrays.asList(TrackRecorder.HEADER.split(","));
        for (String c : new String[]{"car_speed", "car_speed_ipk", "car_dt_ms", "car_steer_bits", "gyro_yaw_deg", "gyro_dt_ms"}) {
            assertEquals(c, "", noCar[header.indexOf(c)]);
        }
        assertTrue(TrackRecorder.HEADER.contains(",car_dt_ms,car_steer_bits,gyro_yaw_deg,gyro_dt_ms,car_gear,out_src,"));
        // Car read at 1500, fix arrived at 2000: the car value is 500 ms older than the fix.
        assertTrue(lines.get(2), lines.get(2).endsWith(",36.00,37.80,-500,95,,,D,,,,,,,,,,,,,,,,"));
        assertTrue(lines.get(3), lines.get(3).endsWith(",,,,,,,D,,,,,,,,,,,,,,,,"));             // no speed: no car_dt_ms
        // Newest gyro sample at 2040, fix at 2060.
        assertTrue(lines.get(4), lines.get(4).endsWith(",,37.80,-500,,2.292,-20,D,,,,,,,,,,,,,,,,"));
        assertTrue(lines.get(5).contains(",out,"));
        assertTrue(lines.get(5), lines.get(5).endsWith(",,,2.292,-60,,dr,,,,,,,,,,,,,,,"));
        assertTrue(lines.get(6), lines.get(6).endsWith(",kf,,,,,,,,,,,,,,,"));
    }

    // Recording is appended across service restarts; the header is written only once.
    @Test
    public void appendsWithoutRepeatingHeader() throws Exception {
        File dir = tmp.newFolder();
        LocationKalmanFilter f = new LocationKalmanFilter(2.0, 1.0);
        f.update(55.75, 37.6, 0, 0, 4.0, 0.3, 5.0);
        for (int i = 0; i < 2; i++) {
            TrackRecorder r = new TrackRecorder(dir, new GyroYaw());
            r.out(i, 55.75, 37.6, false, 0, 0, 5, Double.NaN, Double.NaN, 0, "kf");
            r.close();
        }
        List<String> lines = Files.readAllLines(TrackRecorder.fileFor(dir).toPath());
        assertEquals(3, lines.size());
    }

    // 5 Hz wheel-speed rows (with the gear) make a drive replayable the way the service sees it.
    @Test
    public void carRowWritesSpeedAndGear() throws Exception {
        File dir = tmp.newFolder();
        TrackRecorder r = new TrackRecorder(dir, new GyroYaw());
        r.car(3000, 12.5f, "R", null, null, Float.NaN, null);
        r.car(3200, null, null, null, null, Float.NaN, null);
        r.close();
        List<String> lines = Files.readAllLines(TrackRecorder.fileFor(dir).toPath());
        List<String> header = java.util.Arrays.asList(TrackRecorder.HEADER.split(","));
        String[] c = lines.get(1).split(",", -1);
        assertEquals(header.size(), c.length);
        assertEquals("car", c[header.indexOf("type")]);
        assertEquals("3000", c[header.indexOf("elapsed_ms")]);
        assertEquals("12.50", c[header.indexOf("car_speed")]);
        assertEquals("R", c[header.indexOf("car_gear")]);
        String[] none = lines.get(2).split(",", -1);
        assertEquals(header.size(), none.length);
        assertEquals("", none[header.indexOf("car_speed")]);
    }

    // The record-everything build: the car's W4m group, the raw accelerometer, the gyro temperature and the
    // AdaptAPI census probe ride on the 5 Hz car rows.
    @Test
    public void carRowWritesW4mImuAndProbe() throws Exception {
        File dir = tmp.newFolder();
        TrackRecorder r = new TrackRecorder(dir, new GyroYaw());
        CarSignals.W4m w = CarSignals.W4m.of(0.05f, -1.25f, 0.5f, 10f, 10.1f, 9.9f, 10f, 12f, 3, 123456L, 20);
        r.car(3000, 36f, "D", w, new float[]{0.1f, -9.6f, 2.2f}, 31.5f, "100100=10;200200=3");
        r.close();
        List<String> lines = Files.readAllLines(TrackRecorder.fileFor(dir).toPath());
        List<String> header = java.util.Arrays.asList(TrackRecorder.HEADER.split(","));
        assertTrue(TrackRecorder.HEADER.endsWith(
                ",w4m_tick,yaw_rate,lat_acc,lon_acc,v_fl,v_fr,v_rl,v_rr,w4m_steer,w4m_gear,acc_x,acc_y,acc_z,gyro_temp,probe"));
        String[] c = lines.get(1).split(",", -1);
        assertEquals(header.size(), c.length);
        assertEquals("123456", c[header.indexOf("w4m_tick")]);
        assertEquals("0.0500", c[header.indexOf("yaw_rate")]);
        assertEquals("-1.250", c[header.indexOf("lat_acc")]);
        assertEquals("10.100", c[header.indexOf("v_fr")]);
        assertEquals("3", c[header.indexOf("w4m_gear")]);
        assertEquals("-9.600", c[header.indexOf("acc_y")]);
        assertEquals("31.5", c[header.indexOf("gyro_temp")]);
        assertEquals("100100=10;200200=3", c[header.indexOf("probe")]);
    }
}
