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

import android.content.Context;

/**
 * One-tap smoothing styles. Each sets the few parameters that decide how smooth vs. how quick the
 * icon is; the switches (latency compensation, turn model, …) stay as they are. BALANCED is the
 * default set of values.
 */
public enum FilterPreset {
    //            σa (m/s²)  turn  χ² gate  stop (m/s)
    SMOOTH(       1.2f,      0.6f,  6.0f,   0.7f),
    BALANCED(     2.0f,      0.85f, 9.21f,  0.5f),
    RESPONSIVE(   3.5f,      1.0f,  16.0f,  0.3f);

    public final float processNoise, turnResponsiveness, gateThreshold, standstillSpeed;

    FilterPreset(float processNoise, float turnResponsiveness, float gateThreshold, float standstillSpeed) {
        this.processNoise = processNoise;
        this.turnResponsiveness = turnResponsiveness;
        this.gateThreshold = gateThreshold;
        this.standstillSpeed = standstillSpeed;
    }

    /** The preset these values belong to, or null when they were tuned by hand. */
    public static FilterPreset match(float processNoise, float turnResponsiveness, float gateThreshold,
                                     float standstillSpeed) {
        for (FilterPreset p : values()) {
            if (same(p.processNoise, processNoise) && same(p.turnResponsiveness, turnResponsiveness)
                    && same(p.gateThreshold, gateThreshold) && same(p.standstillSpeed, standstillSpeed)) {
                return p;
            }
        }
        return null;
    }

    public static FilterPreset current(Context context) {
        return match(Preferences.filterProcessNoise(context), Preferences.filterTurnResponsiveness(context),
                Preferences.filterGateThreshold(context), Preferences.filterStandstillSpeed(context));
    }

    public void apply(Context context) {
        Preferences.setFilterProcessNoise(context, processNoise);
        Preferences.setFilterTurnResponsiveness(context, turnResponsiveness);
        Preferences.setFilterGateThreshold(context, gateThreshold);
        Preferences.setFilterStandstillSpeed(context, standstillSpeed);
    }

    /** Next preset in the cycle; hand-tuned values (null) go to BALANCED. */
    public static FilterPreset next(FilterPreset current) {
        if (current == null) return BALANCED;
        return values()[(current.ordinal() + 1) % values().length];
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }
}
