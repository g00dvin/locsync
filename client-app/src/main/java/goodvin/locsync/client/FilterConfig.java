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
 * User-tunable smoothing options (Settings → Smoothing). Plain values with defaults so the filter
 * stays Android-free and unit-testable; {@link Preferences#filterConfig} fills it from storage.
 */
public final class FilterConfig {
    /** Report the filter's uncertainty in Android's 68% convention, incl. speed/bearing accuracy. */
    public boolean reportUncertainty = true;
    /** Extrapolate each fix by its age (plus {@link #extraLatencyMs}) so the output isn't late. */
    public boolean latencyCompensation = true;
    /** Extra delay (ms) on top of the reported fix age, e.g. provider/transport latency. */
    public double extraLatencyMs = 0;
    /** Add the fix's Wi-Fi delivery delay (from the phone's fix timestamps) to its reported age. */
    public boolean networkDelayCompensation = true;
    /** Hold Wi-Fi locks so the radio stays out of power save (avoids delayed, bunched packets). */
    public boolean wifiLowLatency = true;
    /** Coordinated-turn motion model (predict along an arc) instead of straight lines. */
    public boolean turnModel = true;
    /** Weight of the newest heading change in the turn-rate estimate (0.1–1). */
    public double turnResponsiveness = 0.85;
    /** Add 0.5° to bearings once the source is seen to truncate them to whole degrees. */
    public boolean bearingCompensation = true;
    /** Never trust a bearing more than this (degrees, 1-sigma). */
    public double minBearingAccuracyDeg = 2.0;
    /** Learn how much to trust reported position accuracy from the innovation statistics. */
    public boolean adaptivePosition = true;
    /** De-weight fixes whose position innovation fails a χ² gate (multipath outliers). */
    public boolean gating = true;
    /** Gate threshold: χ² with 2 degrees of freedom (9.21 = 99%). */
    public double gateThreshold = 9.21;
    /** Zero-velocity update and position hold while the measured speed is below the threshold. */
    public boolean standstillHold = true;
    /** Speed (m/s) below which the car counts as stopped; also hides speed/bearing in the output. */
    public double standstillSpeed = 0.5;
    /** Base process noise σa (m/s²): how much the car is expected to accelerate between fixes. */
    public double processNoise = 2.0;
    /** Raise σa in turns and when fixes keep disagreeing with the prediction. */
    public boolean adaptiveNoise = true;
}
