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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GyroYawTest {
    // G426 head unit at rest (recon 2026-11-07): gravity mostly along -y, tilted ~13° toward +z.
    private static final double[] ACC = {0.05, -9.63, 2.15};
    private static final long STEP_NS = 20_000_000;   // 50 Hz

    // A right turn (bearing rising) on the tilted mount, plus a pitch wobble that must not count.
    @Test
    public void integratesRotationAboutGravity() {
        double n = Math.sqrt(ACC[0] * ACC[0] + ACC[1] * ACC[1] + ACC[2] * ACC[2]);
        double[] up = {ACC[0] / n, ACC[1] / n, ACC[2] / n};
        double[] side = {1, 0, 0};   // made perpendicular to up below
        double d = side[0] * up[0] + side[1] * up[1] + side[2] * up[2];
        for (int i = 0; i < 3; i++) side[i] -= d * up[i];
        double yaw = Math.toRadians(10);   // °/s, clockwise from above
        double pitch = Math.toRadians(5);
        for (int dir : new int[]{1, -1}) {
            GyroYaw g = new GyroYaw();
            long t = 1_000_000_000L;
            for (int k = 0; k <= 100; k++, t += STEP_NS) {   // 2 s
                g.accel(t, (float) ACC[0], (float) ACC[1], (float) ACC[2]);
                // Right-hand rule: positive about "up" is counter-clockwise from above.
                float[] w = new float[3];
                for (int i = 0; i < 3; i++) w[i] = (float) (-dir * yaw * up[i] + pitch * side[i]);
                g.gyro(t, w[0], w[1], w[2]);
            }
            assertTrue(g.ready());
            assertEquals(dir * 20.0, g.yawDeg(), 0.1);
            assertEquals(t - STEP_NS, g.lastNs());
        }
    }

    // A recording that opens over a bump: one skewed first sample must not tilt "up" for minutes.
    @Test
    public void firstAccelSampleDoesNotSkewGravity() {
        GyroYaw g = new GyroYaw();
        long t = 1_000_000_000L;
        g.accel(t, 4f, -9.8f, 0);
        for (int k = 0; k < 100; k++) g.accel(t += STEP_NS, 0, -9.8f, 0);   // 2 s level
        g.gyro(t, 0, 1, 0);
        g.gyro(t + STEP_NS, 0, 1, 0);   // 1 rad/s about -up for 20 ms
        assertEquals(Math.toDegrees(0.02), g.yawDeg(), 0.01);
    }

    // Nothing integrates before gravity is known, or across a hole in the sensor stream.
    @Test
    public void skipsUnknownGravityAndHoles() {
        GyroYaw g = new GyroYaw();
        g.gyro(1_000_000_000L, 0, 1, 0);
        g.gyro(1_020_000_000L, 0, 1, 0);
        assertFalse(g.ready());
        assertEquals(0, g.yawDeg(), 0);
        g.accel(1_030_000_000L, 0, -9.8f, 0);
        g.gyro(1_040_000_000L, 0, 1, 0);   // 1 rad/s about -up (clockwise) for 20 ms
        assertTrue(g.ready());
        double step = Math.toDegrees(0.02);
        assertEquals(step, g.yawDeg(), 1e-6);
        g.gyro(2_040_000_000L, 0, 1, 0);   // 1 s hole
        assertEquals(step, g.yawDeg(), 1e-6);
        g.gyro(2_060_000_000L, 0, 1, 0);
        assertEquals(2 * step, g.yawDeg(), 1e-6);
    }
}
