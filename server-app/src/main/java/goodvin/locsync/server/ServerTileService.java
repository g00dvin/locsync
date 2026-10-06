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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.core.content.ContextCompat;

import goodvin.locsync.shared.AppLog;

/** Quick Settings tile: start/stop the server from the notification shade. */
public class ServerTileService extends TileService {
    private static final String TAG = "ServerTileService";

    // The tile while the shade shows it: service changes repaint it directly, because
    // requestListeningState() does nothing for a tile that is already listening.
    private static volatile ServerTileService listening;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        listening = this;
        refresh(GNSSServerService.isServiceRunning());
    }

    @Override
    public void onStopListening() {
        if (listening == this) listening = null;
    }

    @Override
    public void onClick() {
        if (GNSSServerService.isServiceRunning()) {
            AppLog.i(TAG, "Stopping server from the Quick Settings tile");
            // Same as the Stop button: pause, but keep auto-start (Bluetooth, boot) armed.
            stopService(new Intent(this, GNSSServerService.class));
            refresh(false);   // the service stops asynchronously; show the outcome now
            return;
        }
        AppLog.i(TAG, "Starting server from the Quick Settings tile");
        if (!hasLocationPermission()) {
            // The location foreground service would be refused (and crash the service) without it:
            // let the app ask for the permission and start the server.
            openAppAndStart();
            return;
        }
        try {
            ContextCompat.startForegroundService(this, new Intent(this, GNSSServerService.class));
            GNSSServerService.setServiceEnabled(this, true);
            refresh(true);
        } catch (IllegalStateException | SecurityException e) {
            // Background foreground-service start refused (Android 12+ on some ROMs): open the app,
            // which starts the server from the foreground.
            Log.w(TAG, "Foreground service start refused, opening the app", e);
            openAppAndStart();
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openAppAndStart() {
        if (isLocked()) {
            unlockAndRun(this::launchAppAndStart);
        } else {
            launchAppAndStart();
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void launchAppAndStart() {
        Intent intent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_START_SERVER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void refresh(boolean running) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getString(!running ? R.string.tile_off
                    : GNSSServerService.isClientConnected() ? R.string.tile_client : R.string.tile_waiting));
        }
        tile.updateTile();
    }

    /** Asks the system to refresh the tile (service started/stopped, client came or went). */
    public static void requestRefresh(Context context) {
        ServerTileService tile = listening;
        if (tile != null) {
            tile.mainHandler.post(() -> tile.refresh(GNSSServerService.isServiceRunning()));
            return;
        }
        try {
            TileService.requestListeningState(context, new ComponentName(context, ServerTileService.class));
        } catch (RuntimeException e) {
            // No tile added, or the ROM rejects the request: nothing to refresh.
        }
    }
}
