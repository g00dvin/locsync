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

package goodvin.locsync.server;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import goodvin.locsync.shared.AppLog;

/**
 * Auto-start/stop by the car's Wi-Fi, for cars whose head unit runs the hotspot and the phone joins
 * it. A PendingIntent network callback (it outlives the app process) starts the server when the
 * phone joins the configured network; while the server runs, a regular callback follows the
 * network so the shared auto-stop logic knows when it is gone.
 */
public final class WifiTrigger {
    private static final String TAG = "WifiTrigger";

    /** True while the phone is on the trigger network (kept by the receiver and the service). */
    private static volatile boolean connected = false;

    private WifiTrigger() {}

    public static boolean isConnected() {
        return connected;
    }

    /** Registers or removes the background network callback to match the settings. */
    public static void sync(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        PendingIntent pi = pendingIntent(context);
        try {
            cm.unregisterNetworkCallback(pi);
        } catch (IllegalArgumentException ignored) {
            // wasn't registered
        }
        if (!Preferences.wifiAutoStartEnabled(context) || Preferences.wifiTriggerSsid(context) == null) {
            connected = false;
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        try {
            cm.registerNetworkCallback(request, pi);
            AppLog.d(TAG, "Watching for Wi-Fi \"" + Preferences.wifiTriggerSsid(context) + "\"");
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot watch Wi-Fi networks", e);
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, Receiver.class);
        // Mutable: the system adds the network to the intent.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    /**
     * SSID of the current Wi-Fi network without quotes, or null when unknown (not on Wi-Fi, or no
     * location permission / location off — Android hides the SSID then).
     */
    @SuppressWarnings("deprecation")   // getConnectionInfo still returns the SSID with location access
    public static String currentSsid(Context context) {
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        return wifi == null ? null : cleanSsid(wifi.getConnectionInfo());
    }

    static String cleanSsid(WifiInfo info) {
        if (info == null) return null;
        String ssid = info.getSSID();
        if (ssid == null || ssid.isEmpty() || WifiManager.UNKNOWN_SSID.equals(ssid)) return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    static boolean isTrigger(Context context, String ssid) {
        return ssid != null && ssid.equals(Preferences.wifiTriggerSsid(context));
    }

    /** Fired by the system when a Wi-Fi network becomes available (even with the app not running). */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Preferences.wifiAutoStartEnabled(context)) return;
            String ssid = currentSsid(context);
            if (!isTrigger(context, ssid)) {
                AppLog.d(TAG, "Wi-Fi available: " + ssid + " (not the trigger network)");
                return;
            }
            connected = true;
            if (GNSSServerService.isServiceRunning()) {
                GNSSServerService.cancelBluetoothAutoStopRequest();
                return;
            }
            AppLog.i(TAG, "Joined trigger Wi-Fi \"" + ssid + "\", starting the server");
            try {
                ContextCompat.startForegroundService(context, new Intent(context, GNSSServerService.class));
                GNSSServerService.setServiceEnabled(context, true);
            } catch (IllegalStateException | SecurityException e) {
                AppLog.w(TAG, "Cannot start the server in the background: " + e.getMessage()
                        + " (allow unrestricted battery use for LocSync Server)");
            }
        }
    }

    /** While the server runs: follows the trigger network so auto-stop knows when it is gone. */
    static final class Monitor extends ConnectivityManager.NetworkCallback {
        private final Context context;
        private final Runnable onLost;
        private Network triggerNetwork;

        Monitor(Context context, Runnable onLost) {
            super(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? FLAG_INCLUDE_LOCATION_INFO : 0);
            this.context = context.getApplicationContext();
            this.onLost = onLost;
        }

        void start() {
            connected = isTrigger(context, currentSsid(context));
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            try {
                cm.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), this);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot follow Wi-Fi", e);
            }
        }

        void stop() {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            try {
                cm.unregisterNetworkCallback(this);
            } catch (IllegalArgumentException ignored) {
                // never registered
            }
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            String ssid = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps.getTransportInfo() instanceof WifiInfo info) {
                ssid = cleanSsid(info);
            }
            if (ssid == null) ssid = currentSsid(context);
            if (isTrigger(context, ssid)) {
                triggerNetwork = network;
                if (!connected) {
                    connected = true;
                    GNSSServerService.cancelBluetoothAutoStopRequest();
                }
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            if (network.equals(triggerNetwork)) {
                triggerNetwork = null;
                connected = false;
                AppLog.i(TAG, "Left the trigger Wi-Fi");
                onLost.run();
            }
        }
    }
}
