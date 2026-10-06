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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import goodvin.locsync.shared.AppLog;

import androidx.core.content.ContextCompat;

public class BroadcastsReceiver extends BroadcastReceiver {
    private static final String TAG = "BroadcastsReceiver";

    private static final String ACTION_START_SERVICE = "goodvin.locsync.server.START";
    private static final String ACTION_STOP_SERVICE = "goodvin.locsync.server.STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        AppLog.d(TAG, "Received broadcast: " + intent.getAction());
        Context appContext = context.getApplicationContext();

        switch (intent.getAction()) {
            case ACTION_START_SERVICE:
                if (GNSSServerService.isServiceRunning()) {
                    return;
                }

                AppLog.i(TAG, "Starting GNSS server service");
                GNSSServerService.setServiceEnabled(appContext, true);
                startService(appContext);
                break;

            case ACTION_STOP_SERVICE:
                if (!GNSSServerService.isServiceRunning()) {
                    return;
                }

                AppLog.i(TAG, "Stopping GNSS server service");
                GNSSServerService.setServiceEnabled(appContext, false);
                stopService(appContext);
                break;

            case Intent.ACTION_BOOT_COMPLETED:
                AppLog.d(TAG, "Device boot completed, checking if GNSS server should auto-start");
                WifiTrigger.sync(appContext);   // network callbacks don't survive a reboot

                // Check if the service was previously enabled
                if (GNSSServerService.isServiceEnabled(appContext)) {
                    if (GNSSServerService.isServiceRunning()) {
                        AppLog.i(TAG, "GNSS server service is already running. Don't start it again.");
                        return;
                    }

                    AppLog.i(TAG, "Auto-starting GNSS server service");
                    startService(appContext);
                } else {
                    AppLog.d(TAG, "GNSS server service not enabled for auto-start");
                }
                break;

            default:
                Log.w(TAG, "Unknown broadcast action: " + intent.getAction());
        }
    }

    private void startService(Context context) {
        Intent serviceIntent = new Intent(context, GNSSServerService.class);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    private void stopService(Context context) {
        Intent serviceIntent = new Intent(context, GNSSServerService.class);
        context.stopService(serviceIntent);
    }
}
