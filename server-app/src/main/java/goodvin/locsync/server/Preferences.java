/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
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

package goodvin.locsync.server;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Helper class for managing app preferences.
 * Stores Bluetooth auto-start settings and other configuration.
 */
public class Preferences {
    private static final String PREF_BLUETOOTH_AUTO_START_ENABLED = "bluetoothAutoStartEnabled";
    private static final String PREF_BLUETOOTH_TRIGGER_DEVICES = "bluetoothTriggerDevices";
    private static final String PREF_FUSED_LOCATION_ENABLED = "fusedLocationEnabled";

    // Legacy keys for migration
    private static final String PREF_BLUETOOTH_TRIGGER_DEVICE_MAC = "bluetoothTriggerDeviceMac";
    private static final String PREF_BLUETOOTH_TRIGGER_DEVICE_NAME = "bluetoothTriggerDeviceName";

    private static final String DEVICE_SEPARATOR = "|";

    // Bluetooth Auto-Start Enabled
    public static void setBluetoothAutoStartEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_BLUETOOTH_AUTO_START_ENABLED, enabled).apply();
    }

    public static boolean bluetoothAutoStartEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_BLUETOOTH_AUTO_START_ENABLED, false);
    }

    // Bluetooth Trigger Devices (multi-device support)

    public static void addBluetoothTriggerDevice(Context context, String mac, String name) {
        Set<String> devices = new HashSet<>(getRawDeviceSet(context));
        // Remove existing entry for this MAC (in case name changed)
        devices.removeIf(entry -> entry.startsWith(mac + DEVICE_SEPARATOR));
        devices.add(mac + DEVICE_SEPARATOR + name);
        getPrefs(context).edit().putStringSet(PREF_BLUETOOTH_TRIGGER_DEVICES, devices).apply();
    }

    public static void removeBluetoothTriggerDevice(Context context, String mac) {
        Set<String> devices = new HashSet<>(getRawDeviceSet(context));
        devices.removeIf(entry -> entry.startsWith(mac + DEVICE_SEPARATOR));
        getPrefs(context).edit().putStringSet(PREF_BLUETOOTH_TRIGGER_DEVICES, devices).apply();
    }

    public static boolean isBluetoothTriggerDevice(Context context, String mac) {
        for (String entry : getRawDeviceSet(context)) {
            if (entry.startsWith(mac + DEVICE_SEPARATOR)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasBluetoothTriggerDevices(Context context) {
        return !getRawDeviceSet(context).isEmpty();
    }

    public static Set<String> getBluetoothTriggerDeviceMacs(Context context) {
        Set<String> macs = new HashSet<>();
        for (String entry : getRawDeviceSet(context)) {
            int sep = entry.indexOf(DEVICE_SEPARATOR);
            if (sep > 0) {
                macs.add(entry.substring(0, sep));
            }
        }
        return macs;
    }

    public static Map<String, String> getBluetoothTriggerDeviceNames(Context context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : getRawDeviceSet(context)) {
            int sep = entry.indexOf(DEVICE_SEPARATOR);
            if (sep > 0) {
                result.put(entry.substring(0, sep), entry.substring(sep + 1));
            }
        }
        return result;
    }

    private static Set<String> getRawDeviceSet(Context context) {
        migrateIfNeeded(context);
        return getPrefs(context).getStringSet(PREF_BLUETOOTH_TRIGGER_DEVICES, Collections.emptySet());
    }

    private static void migrateIfNeeded(Context context) {
        SharedPreferences prefs = getPrefs(context);
        String oldMac = prefs.getString(PREF_BLUETOOTH_TRIGGER_DEVICE_MAC, null);
        if (oldMac != null && !oldMac.isEmpty()) {
            String oldName = prefs.getString(PREF_BLUETOOTH_TRIGGER_DEVICE_NAME, oldMac);
            Set<String> devices = new HashSet<>(prefs.getStringSet(PREF_BLUETOOTH_TRIGGER_DEVICES, Collections.emptySet()));
            devices.add(oldMac + DEVICE_SEPARATOR + oldName);
            prefs.edit()
                    .putStringSet(PREF_BLUETOOTH_TRIGGER_DEVICES, devices)
                    .remove(PREF_BLUETOOTH_TRIGGER_DEVICE_MAC)
                    .remove(PREF_BLUETOOTH_TRIGGER_DEVICE_NAME)
                    .apply();
        }
    }

    // Fused Location Provider
    public static void setFusedLocationEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FUSED_LOCATION_ENABLED, enabled).apply();
    }

    // Bumped: an earlier migration flag was consumed while the pref could still be flipped off by the
    // broken toggle rendering. A new key forces the intended default (on) once more.
    private static final String PREF_FUSED_DEFAULT_ON_MIGRATED = "fusedDefaultOnMigrated2";

    public static boolean fusedLocationEnabled(Context context) {
        SharedPreferences prefs = getPrefs(context);
        // One-time reset to on: earlier builds drew the toggle with the wrong (always-off) state,
        // so users could flip the pref off by accident. Force the intended default (on) once.
        if (!prefs.getBoolean(PREF_FUSED_DEFAULT_ON_MIGRATED, false)) {
            prefs.edit()
                    .putBoolean(PREF_FUSED_LOCATION_ENABLED, true)
                    .putBoolean(PREF_FUSED_DEFAULT_ON_MIGRATED, true)
                    .apply();
            return true;
        }
        return prefs.getBoolean(PREF_FUSED_LOCATION_ENABLED, true);
    }

    private static final String PREF_DEBUG_LOGGING = "debugLoggingEnabled";

    public static void setDebugLoggingEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_DEBUG_LOGGING, enabled).apply();
    }

    public static boolean debugLoggingEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_DEBUG_LOGGING, false);
    }

    private static final String PREF_LIVE_MONITORING = "liveMonitoring";

    /** Real-time updates in the Monitor screen. Off by default. */
    public static void setLiveMonitoring(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_LIVE_MONITORING, enabled).apply();
    }

    public static boolean liveMonitoring(Context context) {
        return getPrefs(context).getBoolean(PREF_LIVE_MONITORING, false);
    }

    private static final String PREF_METRICS_ENABLED = "metricsEnabled";

    public static void setMetricsEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_METRICS_ENABLED, enabled).apply();
    }

    public static boolean metricsEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_METRICS_ENABLED, false);
    }

    // Cached: read on hot paths; avoids re-resolving the prefs file on every call.
    private static volatile SharedPreferences prefs;

    private static SharedPreferences getPrefs(Context context) {
        SharedPreferences p = prefs;
        if (p == null) {
            synchronized (Preferences.class) {
                p = prefs;
                if (p == null) {
                    p = context.getApplicationContext()
                            .getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    prefs = p;
                }
            }
        }
        return p;
    }
}
