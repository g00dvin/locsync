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
    /** Coordinated-turn motion model (predict along an arc) instead of straight lines. */
    public boolean turnModel = true;
}
