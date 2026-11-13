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

import androidx.annotation.NonNull;

import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Handler;
import android.util.Log;
import goodvin.locsync.shared.AppLog;

import java.util.Random;

public class MockLocationManager {
    private static final String TAG = "MockLocationManager";
    private static final float STATIC_SPEED_THRESHOLD = 0.5f; // m/s — below this, location is considered static
    private static final double JITTER_METERS = 0.1; // ±0.1m offset
    // 1 degree of latitude ≈ 111,320 m; 0.1m ≈ 9e-7 degrees
    private static final double JITTER_DEGREES_LAT = JITTER_METERS / 111_320.0;

    private final LocationManager locationManager;
    private final Context context;
    private final Handler handler;   // the caller's thread: the delayed disable is ordered with its calls
    private final Random random = new Random();

    // We need to use such runnable to make scheduled disabling cancelable
    private final Runnable disableMockLocationProvider = this::disableMockLocationProvider;

    private boolean isMockLocationProviderSetup = false;
    private static final long SETUP_RETRY_MS = 1000;
    private long lastSetupAttemptMs;
    private String lastSetupError;   // logged once per distinct reason

    public MockLocationManager(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        locationManager = context.getSystemService(LocationManager.class);
    }

    public void startMockLocationProvider() {
        AppLog.d(TAG, "Starting mock location provider");

        handler.removeCallbacks(this.disableMockLocationProvider);
        setupMockLocationProvider();
        enableMockLocationProvider();
    }

    public void stopMockLocationProvider(long delayMillis) {
        AppLog.d(TAG, "Scheduling stopping of mock location provider in " + delayMillis + " ms");

        handler.removeCallbacks(this.disableMockLocationProvider);
        handler.postDelayed(this.disableMockLocationProvider, delayMillis);
    }

    /**
     * Injects the location. False (nothing injected) while the provider can't be set up, i.e. until the user
     * picks this app as the mock location app: setup is retried once a second, so it then starts by itself.
     */
    public boolean setMockLocation(@NonNull Location location) {
        if (!isMockLocationProviderSetup && !retrySetup()) {
            return false;
        }
        if (Preferences.staticJitterEnabled(context) && isStatic(location)) {
            applyJitter(location);
        }
        try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
            return true;
        } catch (IllegalArgumentException e) {
            // "not a test provider": it was removed behind our back (another mock app took over). Set up again.
            AppLog.i(TAG, "Mock provider lost, setting it up again: " + e.getMessage());
            isMockLocationProviderSetup = false;
            return false;
        }
    }

    private boolean retrySetup() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSetupAttemptMs < SETUP_RETRY_MS) {
            return false;
        }
        lastSetupAttemptMs = now;
        try {
            setupMockLocationProvider();
            enableMockLocationProvider();
            lastSetupError = null;
            AppLog.i(TAG, "Mock location provider set up on retry");
            return true;
        } catch (RuntimeException e) {
            // still not the mock location app; startMockLocationProvider already told the user
            if (!String.valueOf(e).equals(lastSetupError)) {
                lastSetupError = String.valueOf(e);
                AppLog.i(TAG, "Mock location provider not ready, retrying every second: " + e);
            }
            return false;
        }
    }

    private boolean isStatic(Location location) {
        // A moving output always carries a bearing, even with "send speed" off.
        return !location.hasBearing() && (!location.hasSpeed() || location.getSpeed() < STATIC_SPEED_THRESHOLD);
    }

    private void applyJitter(Location location) {
        double jitterLat = (random.nextDouble() * 2 - 1) * JITTER_DEGREES_LAT;
        // Longitude degrees are shorter near poles: adjust by cos(latitude)
        double cosLat = Math.cos(Math.toRadians(location.getLatitude()));
        double jitterLngDegrees = cosLat > 0.01 ? JITTER_DEGREES_LAT / cosLat : JITTER_DEGREES_LAT;
        double jitterLng = (random.nextDouble() * 2 - 1) * jitterLngDegrees;

        location.setLatitude(location.getLatitude() + jitterLat);
        location.setLongitude(location.getLongitude() + jitterLng);
        // Set a small nonzero speed so navigation apps see movement
        location.setSpeed(0.1f);
    }

    public static boolean isMockLocationEnabled(ContentResolver contentResolver) {
        try {
            return android.provider.Settings.Secure.getString(contentResolver, "mock_location") != null;
        } catch (Exception e) {
            Log.e(TAG, "Error checking mock location setting", e);
            return false;
        }
    }

    /**
     * Returns true only if THIS app is the one selected as the mock-location app in Developer
     * Options (i.e. it is actually allowed to inject mock locations). Uses the app-op, which is
     * the authoritative signal — unlike the legacy {@code mock_location} setting.
     */
    @SuppressWarnings("deprecation")
    public static boolean isSelectedMockApp(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        try {
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e(TAG, "Error checking mock location app op", e);
            return false;
        }
    }

    public synchronized void shutdown() {
        AppLog.d(TAG, "Shutdown");

        if (!isMockLocationProviderSetup) {
            return;
        }

        handler.removeCallbacks(this.disableMockLocationProvider);
        disableMockLocationProvider();
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (IllegalArgumentException e) {
            // Provider doesn't exist, which is fine
        }
        isMockLocationProviderSetup = false;
    }

    private synchronized void setupMockLocationProvider() {
        if (isMockLocationProviderSetup) {
            return;
        }
        // Android 10+ ignores addTestProvider from an app that isn't the selected mock app, without an error:
        // believing that "setup" left every later injection failing until the service was restarted.
        if (!isSelectedMockApp(context)) {
            throw new SecurityException("not selected as the mock location app");
        }

        // Remove existing test provider if it exists
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (IllegalArgumentException e) {
            // Provider doesn't exist, which is fine
            AppLog.d(TAG, "GPS test provider doesn't exist, creating new one");
        }

        // Add test provider with correct parameters
        locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, // requiresNetwork - GPS doesn't require network
                true,  // requiresSatellite - GPS uses satellites
                false, // requiresCell - GPS doesn't require cell
                false, // hasMonetaryCost - GPS is free
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                ProviderProperties.POWER_USAGE_HIGH, // powerRequirement
                ProviderProperties.ACCURACY_FINE // accuracy
        );

        isMockLocationProviderSetup = true;

        AppLog.i(TAG, "Mock location provider setup successfully");
    }

    private void enableMockLocationProvider() {
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AppLog.d(TAG, "Mock location provider enabled");
    }

    private void disableMockLocationProvider() {
        if (!isMockLocationProviderSetup) {
            return;
        }
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
            AppLog.d(TAG, "Mock location provider disabled");
        } catch (RuntimeException e) {
            // Runs delayed on the service thread: an exception here (provider gone) took the process down.
            AppLog.i(TAG, "Mock location provider not disabled: " + e);
        }
    }
}
