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

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import goodvin.locsync.shared.AppLog;

/**
 * Optional autostart path used by many head-unit apps: an accessibility service that does nothing
 * with accessibility events, but which the system binds on every boot and re-binds whenever the
 * app process is killed.
 *
 * <p>Why it helps on head units: many of them never deliver {@code BOOT_COMPLETED} (fast-boot /
 * "sleep" modes) and run aggressive task killers that also drop persisted jobs, yet they always
 * restore enabled accessibility services. While bound, the process sits at bound-foreground
 * priority, so it is allowed to start the foreground {@link GNSSClientService} from the
 * background. The service also listens for Wi-Fi connects for as long as it is bound, which is
 * more immediate than the JobScheduler path.
 *
 * <p>It is enabled by the user in system Settings → Accessibility; it never reads window content.
 */
public class AutostartAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutostartA11yService";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback wifiCallback;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AppLog.i(TAG, "Accessibility service connected");

        // Like boot: does not gate on Wi-Fi (the client itself waits for the server).
        // Its own on/off is the system Accessibility switch, so no app toggle gates it.
        tryAutostart("accessibility", true, true);

        if (GNSSClientService.isServiceEnabled(this) && Preferences.autostartWifi(this)) {
            AutostartScheduler.schedule(this);
        }
        registerWifiCallback();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: the service only exists to keep the app alive and trigger autostart.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        unregisterWifiCallback();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        unregisterWifiCallback();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void tryAutostart(String source, boolean triggerEnabled, boolean wifiConnected) {
        AutostartPolicy.Decision decision = AutostartPolicy.decide(
                triggerEnabled,
                wifiConnected,
                GNSSClientService.isServiceEnabled(this),
                GNSSClientService.isServiceRunning());
        Preferences.setLastAutostart(this, source, decision.name());
        AppLog.d(TAG, "Autostart check (" + source + "): " + decision.name());

        if (decision == AutostartPolicy.Decision.START) {
            try {
                startForegroundService(new Intent(this, GNSSClientService.class));
                AppLog.i(TAG, "Auto-started GNSS client service from accessibility service");
            } catch (RuntimeException e) {
                // e.g. ForegroundServiceStartNotAllowedException on a restrictive ROM
                AppLog.w(TAG, "Failed to start GNSS client service: " + e);
            }
        }
    }

    private void registerWifiCallback() {
        if (wifiCallback != null) {
            return;
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // Callback runs on the handler's (main) thread; the service start is cheap.
                tryAutostart("a11y_wifi", Preferences.autostartWifi(AutostartAccessibilityService.this), true);
            }
        };
        try {
            cm.registerNetworkCallback(request, wifiCallback, handler);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Failed to register Wi-Fi callback: " + e);
            wifiCallback = null;
        }
    }

    private void unregisterWifiCallback() {
        if (wifiCallback == null) {
            return;
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
                // already unregistered
            }
        }
        wifiCallback = null;
    }

    /** Whether the user has enabled this service in system accessibility settings. */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName self = new ComponentName(context, AutostartAccessibilityService.class);
        return AutostartPolicy.isAccessibilityServiceListed(enabled,
                self.flattenToString(), self.flattenToShortString());
    }
}
