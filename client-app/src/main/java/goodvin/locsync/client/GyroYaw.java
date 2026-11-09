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

/**
 * The head unit's heading change from its gyroscope: the rotation about the vertical, integrated.
 * The unit is mounted tilted (G426: gravity along -y, ~13° off), so the gyro is projected onto
 * gravity, taken from a slow low-pass of the accelerometer. Positive is clockwise from above, like a
 * bearing. Raw: no bias removal, so a standing car drifts at the gyro's bias. Free of Android types;
 * fed {@code SensorEvent} values (rad/s, m/s², timestamps in ns).
 */
final class GyroYaw {
    // ponytail: centripetal accel in a long turn tilts this "vertical" a little (~2% yaw at 0.2 g);
    // update gravity only while the gyro is quiet if that shows against GPS
    private static final double GRAVITY_TAU_S = 20;
    private static final double MAX_STEP_S = 0.5;   // a longer hole in the gyro stream isn't integrated

    private double gx, gy, gz;
    private long firstAccNs, accNs, gyroNs;   // 0 = no sample yet
    private double yawDeg;

    void accel(long tNs, float x, float y, float z) {
        if (accNs == 0) {
            gx = x; gy = y; gz = z;
            firstAccNs = tNs;
        } else if (tNs > accNs) {
            // A running mean until the time constant has passed, so a recording that opens over a
            // bump doesn't keep a tilted "up" for a minute.
            double tau = Math.min(GRAVITY_TAU_S, (tNs - firstAccNs) / 1e9);
            double a = Math.min(1, (tNs - accNs) / 1e9 / tau);
            gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz);
        } else {
            return;
        }
        accNs = tNs;
    }

    void gyro(long tNs, float x, float y, float z) {
        if (tNs <= gyroNs) {
            return;
        }
        double dt = (tNs - gyroNs) / 1e9;
        double n = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (gyroNs != 0 && dt <= MAX_STEP_S && n > 0) {
            // Right-hand rule: positive about "up" is counter-clockwise, so a bearing falls.
            yawDeg -= Math.toDegrees((x * gx + y * gy + z * gz) / n) * dt;
        }
        gyroNs = tNs;
    }

    /** Both sensors have reported, so {@link #yawDeg} is moving. */
    boolean ready() {
        return accNs != 0 && gyroNs != 0;
    }

    double yawDeg() {
        return yawDeg;
    }

    /** Timestamp of the newest gyro sample (ns, the SensorEvent clock). */
    long lastNs() {
        return gyroNs;
    }
}
