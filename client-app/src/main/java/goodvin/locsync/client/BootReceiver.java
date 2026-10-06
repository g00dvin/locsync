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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import goodvin.locsync.shared.AppLog;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GNSSClientBootReceiver";

    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())
                || ACTION_QUICKBOOT_POWERON.equals(intent.getAction())) {
            AppLog.d(TAG, "Device boot completed, checking if GNSS client should auto-start");

            // Boot does not gate on WiFi, so pass wifiConnected=true.
            AutostartPolicy.Decision decision = AutostartPolicy.decide(
                    Preferences.autostartBoot(context),
                    true,
                    GNSSClientService.isServiceEnabled(context),
                    GNSSClientService.isServiceRunning());

            Preferences.setLastAutostart(context, "boot", decision.name());

            if (decision == AutostartPolicy.Decision.START) {
                AppLog.i(TAG, "Auto-starting GNSS client service");
                Intent serviceIntent = new Intent(context, GNSSClientService.class);
                context.startForegroundService(serviceIntent);
            } else {
                AppLog.d(TAG, "Not auto-starting GNSS client service: " + decision.name());
            }

            // Re-arm the persisted WiFi-connect autostart job (belt-and-suspenders; the job is
            // itself persisted, but if it was ever lost this restores it on boot).
            if (GNSSClientService.isServiceEnabled(context) && Preferences.autostartWifi(context)) {
                AutostartScheduler.schedule(context);
            }
        }
    }
}