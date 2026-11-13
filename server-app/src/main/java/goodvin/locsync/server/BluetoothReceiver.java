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

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import goodvin.locsync.shared.AppLog;

import androidx.core.content.IntentCompat;


/**
 * BroadcastReceiver for Bluetooth connection events.
 * Handles auto-start/stop of GNSS service based on Bluetooth device connections.
 * Supports multiple trigger devices — service starts when any registered device
 * connects and stops only when all registered devices are disconnected.
 */
public class BluetoothReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothReceiver";

    /**
     * Enables this receiver only while Bluetooth auto-start is on, so ACL events don't wake the
     * process otherwise. Component state survives app updates; the manifest default is enabled.
     */
    static void sync(Context context) {
        if (!Preferences.bluetoothAutoStartEnabled(context)) {
            Preferences.clearConnectedTriggerDevices(context);   // no ACL events from here on: don't keep a stale set
        }
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, BluetoothReceiver.class),
                Preferences.bluetoothAutoStartEnabled(context)
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /**
     * Returns true if no registered trigger devices are currently connected. The connected set is persisted:
     * kept in memory, a process restart mid-drive forgot the car was connected, and the next Wi-Fi blip
     * auto-stopped the server.
     */
    public static boolean allTriggerDevicesDisconnected(Context context) {
        for (String mac : Preferences.connectedTriggerDevices(context)) {
            if (Preferences.isBluetoothTriggerDevice(context, mac)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        AppLog.d(TAG, "Received Bluetooth action: " + action);

        // Check if Bluetooth auto-start feature is enabled
        if (!Preferences.bluetoothAutoStartEnabled(context)) {
            AppLog.d(TAG, "Bluetooth auto-start is disabled, ignoring event");
            return;
        }

        // Check if any trigger devices are configured
        if (!Preferences.hasBluetoothTriggerDevices(context)) {
            AppLog.d(TAG, "No trigger devices configured, ignoring event");
            return;
        }

        BluetoothDevice device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (device == null) {
            Log.w(TAG, "No device in intent");
            return;
        }

        String deviceMac = device.getAddress();
        String deviceName = device.getName();
        AppLog.d(TAG, "Device: " + deviceName + " (" + deviceMac + ")");

        // Check if this is one of the trigger devices
        if (!Preferences.isBluetoothTriggerDevice(context, deviceMac)) {
            AppLog.d(TAG, "Not a trigger device, ignoring");
            return;
        }

        switch (action) {
            case BluetoothDevice.ACTION_ACL_CONNECTED:
                handleDeviceConnected(context, deviceMac, deviceName);
                break;

            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                handleDeviceDisconnected(context, deviceMac, deviceName);
                break;

            default:
                AppLog.d(TAG, "Unhandled action: " + action);
        }
    }

    private void handleDeviceConnected(Context context, String deviceMac, String deviceName) {
        AppLog.i(TAG, "Trigger device connected: " + deviceName + " (" + deviceMac + ")");
        Preferences.setTriggerDeviceConnected(context, deviceMac, true);

        if (GNSSServerService.isServiceRunning()) {
            // Cancel any pending auto-stop (don't mark as BT-managed — service may have been started manually)
            GNSSServerService.cancelBluetoothAutoStopRequest();
        } else {
            // Start the service
            AppLog.i(TAG, "Starting GNSS service due to Bluetooth connection");
            GNSSServerService.setServiceEnabled(context, true);
            GNSSServerService.start(context);
        }
    }

    private void handleDeviceDisconnected(Context context, String deviceMac, String deviceName) {
        AppLog.i(TAG, "Trigger device disconnected: " + deviceName + " (" + deviceMac + ")");
        Preferences.setTriggerDeviceConnected(context, deviceMac, false);

        if (!allTriggerDevicesDisconnected(context)) {
            AppLog.d(TAG, "Other trigger devices still connected, not stopping");
            return;
        }

        // All trigger devices disconnected — let the service decide whether to schedule auto-stop
        GNSSServerService.evaluateAutoStop();
    }
}
