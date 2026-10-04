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
import android.content.SharedPreferences;

public class Preferences {
    private static final String PREF_IS_SERVICE_ENABLED = "isServiceEnabled";
    private static final String PREF_AUTO_DISCOVER = "autoDiscover";
    private static final String LEGACY_USE_GATEWAY_IP = "useGatewayIp";
    private static final String PREF_SERVER_ADDRESS = "serverAddress";
    private static final String PREF_STATIC_JITTER_ENABLED = "staticJitterEnabled";
    private static final String PREF_LAST_AUTOSTART_SOURCE = "lastAutostartSource";
    private static final String PREF_LAST_AUTOSTART_RESULT = "lastAutostartResult";
    private static final String PREF_LAST_AUTOSTART_TIME = "lastAutostartTime";

    // SharedPreferences helper methods
    public static void setServiceEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_IS_SERVICE_ENABLED, enabled).apply();
    }

    public static boolean serviceEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_IS_SERVICE_ENABLED, false);
    }

    public static void setAutoDiscover(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(PREF_AUTO_DISCOVER, value).apply();
    }

    public static boolean autoDiscover(Context context) {
        SharedPreferences prefs = getPrefs(context);
        // One-time migration from the old "useGatewayIp" key (same semantics: both true = auto/gateway).
        if (!prefs.contains(PREF_AUTO_DISCOVER) && prefs.contains(LEGACY_USE_GATEWAY_IP)) {
            boolean legacy = prefs.getBoolean(LEGACY_USE_GATEWAY_IP, true);
            prefs.edit().putBoolean(PREF_AUTO_DISCOVER, legacy).remove(LEGACY_USE_GATEWAY_IP).apply();
            return legacy;
        }
        return prefs.getBoolean(PREF_AUTO_DISCOVER, true);
    }

    public static void setServerAddress(Context context, String value) {
        getPrefs(context).edit().putString(PREF_SERVER_ADDRESS, value).apply();
    }

    public static String serverAddress(Context context) {
        return getPrefs(context).getString(PREF_SERVER_ADDRESS, "192.168.43.1");
    }

    public static void setStaticJitterEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_STATIC_JITTER_ENABLED, enabled).apply();
    }

    public static boolean staticJitterEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_STATIC_JITTER_ENABLED, false);
    }

    /**
     * Records the outcome of an autostart trigger so it survives a reboot (device-protected
     * storage) and can be shown in the UI — useful for diagnosing whether/what fired on head
     * units where logcat rolls across reboots.
     */
    public static void setLastAutostart(Context context, String source, String result) {
        getPrefs(context).edit()
                .putString(PREF_LAST_AUTOSTART_SOURCE, source)
                .putString(PREF_LAST_AUTOSTART_RESULT, result)
                .putLong(PREF_LAST_AUTOSTART_TIME, System.currentTimeMillis())
                .apply();
    }

    public static String lastAutostartSource(Context context) {
        return getPrefs(context).getString(PREF_LAST_AUTOSTART_SOURCE, null);
    }

    public static String lastAutostartResult(Context context) {
        return getPrefs(context).getString(PREF_LAST_AUTOSTART_RESULT, null);
    }

    public static long lastAutostartTime(Context context) {
        return getPrefs(context).getLong(PREF_LAST_AUTOSTART_TIME, 0);
    }

    private static final String PREF_AUTOSTART_WIFI_BOOT = "autostartWifiBootEnabled";

    /** User-facing toggle for the Wi-Fi/boot autostart job. Default true preserves prior behaviour. */
    public static void setAutostartWifiBoot(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_AUTOSTART_WIFI_BOOT, enabled).apply();
    }

    public static boolean autostartWifiBoot(Context context) {
        return getPrefs(context).getBoolean(PREF_AUTOSTART_WIFI_BOOT, true);
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

    private static final String PREF_TRACK_RECORDING = "trackRecording";

    /** Record raw fixes and filter output to a CSV for offline analysis. Off by default. */
    public static void setTrackRecording(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_TRACK_RECORDING, enabled).apply();
    }

    public static boolean trackRecording(Context context) {
        return getPrefs(context).getBoolean(PREF_TRACK_RECORDING, false);
    }

    private static final String PREF_METRICS_ENABLED = "metricsEnabled";

    public static void setMetricsEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_METRICS_ENABLED, enabled).apply();
    }

    public static boolean metricsEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_METRICS_ENABLED, false);
    }

    private static final String PREF_FILTER_REPORT_UNCERTAINTY = "filterReportUncertainty";

    public static void setFilterReportUncertainty(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FILTER_REPORT_UNCERTAINTY, enabled).apply();
    }

    public static boolean filterReportUncertainty(Context context) {
        return getPrefs(context).getBoolean(PREF_FILTER_REPORT_UNCERTAINTY, true);
    }

    private static final String PREF_FILTER_LATENCY_COMP = "filterLatencyCompensation";
    private static final String PREF_FILTER_EXTRA_LATENCY_MS = "filterExtraLatencyMs";
    private static final String PREF_FILTER_TURN_MODEL = "filterTurnModel";

    public static void setFilterLatencyCompensation(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FILTER_LATENCY_COMP, enabled).apply();
    }

    public static boolean filterLatencyCompensation(Context context) {
        return getPrefs(context).getBoolean(PREF_FILTER_LATENCY_COMP, true);
    }

    public static void setFilterExtraLatencyMs(Context context, float ms) {
        getPrefs(context).edit().putFloat(PREF_FILTER_EXTRA_LATENCY_MS, ms).apply();
    }

    public static float filterExtraLatencyMs(Context context) {
        return getPrefs(context).getFloat(PREF_FILTER_EXTRA_LATENCY_MS, 0f);
    }

    public static void setFilterTurnModel(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FILTER_TURN_MODEL, enabled).apply();
    }

    public static boolean filterTurnModel(Context context) {
        return getPrefs(context).getBoolean(PREF_FILTER_TURN_MODEL, true);
    }

    private static final String PREF_FILTER_GATING = "filterGating";
    private static final String PREF_FILTER_GATE_THRESHOLD = "filterGateThreshold";

    public static void setFilterGating(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FILTER_GATING, enabled).apply();
    }

    public static boolean filterGating(Context context) {
        return getPrefs(context).getBoolean(PREF_FILTER_GATING, true);
    }

    public static void setFilterGateThreshold(Context context, float threshold) {
        getPrefs(context).edit().putFloat(PREF_FILTER_GATE_THRESHOLD, threshold).apply();
    }

    public static float filterGateThreshold(Context context) {
        return getPrefs(context).getFloat(PREF_FILTER_GATE_THRESHOLD, 9.21f);
    }

    private static final String PREF_FILTER_STANDSTILL_HOLD = "filterStandstillHold";
    private static final String PREF_FILTER_STANDSTILL_SPEED = "filterStandstillSpeed";

    public static void setFilterStandstillHold(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FILTER_STANDSTILL_HOLD, enabled).apply();
    }

    public static boolean filterStandstillHold(Context context) {
        return getPrefs(context).getBoolean(PREF_FILTER_STANDSTILL_HOLD, true);
    }

    public static void setFilterStandstillSpeed(Context context, float speed) {
        getPrefs(context).edit().putFloat(PREF_FILTER_STANDSTILL_SPEED, speed).apply();
    }

    public static float filterStandstillSpeed(Context context) {
        return getPrefs(context).getFloat(PREF_FILTER_STANDSTILL_SPEED, 0.5f);
    }

    private static final String PREF_FILTER_PROCESS_NOISE = "filterProcessNoise";
    private static final String PREF_FILTER_ADAPTIVE_NOISE = "filterAdaptiveNoise";

    public static void setFilterProcessNoise(Context context, float sigmaA) {
        getPrefs(context).edit().putFloat(PREF_FILTER_PROCESS_NOISE, sigmaA).apply();
    }

    public static float filterProcessNoise(Context context) {
        return getPrefs(context).getFloat(PREF_FILTER_PROCESS_NOISE, 2.0f);
    }

    public static void setFilterAdaptiveNoise(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FILTER_ADAPTIVE_NOISE, enabled).apply();
    }

    public static boolean filterAdaptiveNoise(Context context) {
        return getPrefs(context).getBoolean(PREF_FILTER_ADAPTIVE_NOISE, true);
    }

    private static final String PREF_FILTER_NETWORK_DELAY = "filterNetworkDelayCompensation";
    private static final String PREF_WIFI_LOW_LATENCY = "wifiLowLatency";

    public static void setFilterNetworkDelay(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FILTER_NETWORK_DELAY, enabled).apply();
    }

    public static boolean filterNetworkDelay(Context context) {
        return getPrefs(context).getBoolean(PREF_FILTER_NETWORK_DELAY, true);
    }

    public static void setWifiLowLatency(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_WIFI_LOW_LATENCY, enabled).apply();
    }

    public static boolean wifiLowLatency(Context context) {
        return getPrefs(context).getBoolean(PREF_WIFI_LOW_LATENCY, true);
    }

    /** Current smoothing options; cheap enough to re-read on every fix so changes apply live. */
    public static FilterConfig filterConfig(Context context) {
        FilterConfig c = new FilterConfig();
        c.reportUncertainty = filterReportUncertainty(context);
        c.latencyCompensation = filterLatencyCompensation(context);
        c.extraLatencyMs = filterExtraLatencyMs(context);
        c.networkDelayCompensation = filterNetworkDelay(context);
        c.wifiLowLatency = wifiLowLatency(context);
        c.turnModel = filterTurnModel(context);
        c.gating = filterGating(context);
        c.gateThreshold = filterGateThreshold(context);
        c.standstillHold = filterStandstillHold(context);
        c.standstillSpeed = filterStandstillSpeed(context);
        c.processNoise = filterProcessNoise(context);
        c.adaptiveNoise = filterAdaptiveNoise(context);
        return c;
    }

    // Cached: settings are read on every 10 Hz output tick and on every fix. Resolving the
    // device-protected context each time is wasteful, and some head-unit ROMs log a full stack trace
    // for every getApplicationContext() call (~26 lines/s), flooding logcat so exported logs lose
    // everything else.
    private static volatile SharedPreferences prefs;

    private static SharedPreferences getPrefs(Context context) {
        SharedPreferences p = prefs;
        if (p == null) {
            synchronized (Preferences.class) {
                p = prefs;
                if (p == null) {
                    final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
                    p = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    prefs = p;
                }
            }
        }
        return p;
    }
}
