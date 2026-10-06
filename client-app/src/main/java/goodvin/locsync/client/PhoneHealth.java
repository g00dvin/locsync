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
 * What the head unit should say about the phone's battery and heat. A phone in a sunny mount
 * overheats and Android then throttles or stops GPS; a flat battery ends the trip's positioning.
 */
public enum PhoneHealth {
    OK, HOT, LOW_BATTERY;

    // PowerManager.THERMAL_STATUS_SEVERE: the system is throttling hard.
    static final int THERMAL_SEVERE = 3;
    static final float HOT_BATTERY_C = 45f;
    static final int LOW_BATTERY_PERCENT = 15;

    /**
     * @param tempC NaN when the phone doesn't report it
     * @param thermalStatus PowerManager.THERMAL_STATUS_*, 0 when unknown (Android 9 and older)
     */
    public static PhoneHealth of(int batteryPercent, boolean charging, float tempC, int thermalStatus) {
        if (thermalStatus >= THERMAL_SEVERE || tempC >= HOT_BATTERY_C) return HOT;
        if (!charging && batteryPercent >= 0 && batteryPercent <= LOW_BATTERY_PERCENT) return LOW_BATTERY;
        return OK;
    }
}
