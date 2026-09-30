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
 * Pure decision logic for whether an autostart trigger should launch the client service.
 * Kept free of Android APIs so it can be unit-tested. Shared by BootReceiver (which passes
 * wifiConnected=true, since boot does not gate on WiFi) and AutostartJobService.
 */
public final class AutostartPolicy {

    private AutostartPolicy() {}

    public enum Decision {
        START,
        SKIP_NO_WIFI,
        SKIP_NOT_ENABLED,
        SKIP_ALREADY_RUNNING,
    }

    /**
     * @param wifiConnected  whether the device is currently connected to WiFi (pass true for
     *                       triggers that do not depend on WiFi, e.g. boot)
     * @param serviceEnabled whether the user has enabled the client service
     * @param serviceRunning whether the client service is already running
     */
    public static Decision decide(boolean wifiConnected, boolean serviceEnabled, boolean serviceRunning) {
        if (!wifiConnected) {
            return Decision.SKIP_NO_WIFI;
        }
        if (!serviceEnabled) {
            return Decision.SKIP_NOT_ENABLED;
        }
        if (serviceRunning) {
            return Decision.SKIP_ALREADY_RUNNING;
        }
        return Decision.START;
    }

    /**
     * Parses {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} (a ':'-separated list of
     * flattened component names, in either full or short form) and reports whether our service
     * is in it.
     */
    public static boolean isAccessibilityServiceListed(String enabledServices,
                                                       String fullName, String shortName) {
        if (enabledServices == null || enabledServices.isEmpty()) {
            return false;
        }
        for (String entry : enabledServices.split(":")) {
            String name = entry.trim();
            if (name.equalsIgnoreCase(fullName) || name.equalsIgnoreCase(shortName)) {
                return true;
            }
        }
        return false;
    }
}
