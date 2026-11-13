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

public class BroadcastsReceiver extends BroadcastReceiver {
    private static final String TAG = "BroadcastsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        AppLog.d(TAG, "Received broadcast: " + intent.getAction());
        Context appContext = context.getApplicationContext();

        switch (intent.getAction()) {
            case Intent.ACTION_BOOT_COMPLETED:
                AppLog.d(TAG, "Device boot completed, checking if GNSS server should auto-start");
                WifiTrigger.sync(appContext, true);   // network callbacks don't survive a reboot
                BluetoothReceiver.sync(appContext);
                Preferences.clearConnectedTriggerDevices(appContext);   // a reboot drops them; ACL events re-add

                // Only when the user wants it (Settings → Automation) and the server was left enabled
                if (!Preferences.bootAutoStartEnabled(appContext)) {
                    AppLog.d(TAG, "Start on boot is off");
                } else if (GNSSServerService.isServiceEnabled(appContext)) {
                    if (GNSSServerService.isServiceRunning()) {
                        AppLog.i(TAG, "GNSS server service is already running. Don't start it again.");
                        return;
                    }

                    AppLog.i(TAG, "Auto-starting GNSS server service");
                    GNSSServerService.start(appContext);
                } else {
                    AppLog.d(TAG, "GNSS server service not enabled for auto-start");
                }
                break;

            default:
                Log.w(TAG, "Unknown broadcast action: " + intent.getAction());
        }
    }

    /**
     * The notification's Stop action. Not exported: the receiver above is (for the boot broadcast), behind a
     * permission any app can hold, so it must not start or stop the server for whoever asks.
     */
    public static class Stop extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!GNSSServerService.isServiceRunning()) {
                return;
            }
            AppLog.i(TAG, "Stopping GNSS server service");
            Context appContext = context.getApplicationContext();
            GNSSServerService.setServiceEnabled(appContext, false);
            appContext.stopService(new Intent(appContext, GNSSServerService.class));
        }
    }
}
