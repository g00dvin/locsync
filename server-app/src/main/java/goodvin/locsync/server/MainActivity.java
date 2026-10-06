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
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import goodvin.locsync.proto.LocationProto;
import goodvin.locsync.shared.AppLog;
import goodvin.locsync.shared.LinkState;
import goodvin.locsync.shared.LogExporter;
import goodvin.locsync.shared.PowerOrbView;
import goodvin.locsync.shared.SatelliteBarsView;
import goodvin.locsync.shared.SettingsRows;
import goodvin.locsync.shared.SparklineView;
import goodvin.locsync.shared.VersionGetter;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSServerActivity";
    private static final int VIEW_CONNECT = 0, VIEW_MONITOR = 1, VIEW_SETTINGS = 2;
    /** Quick Settings tile fallback: open the app and start the server from the foreground. */
    static final String ACTION_START_SERVER = "goodvin.locsync.server.action.START_FROM_TILE";

    private static final String[] FOREGROUND_LOCATION_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    };
    private static final String[] BLUETOOTH_PERMISSIONS = {
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
    };

    private ViewFlipper viewFlipper;
    private ImageView btnLeft, btnRight;
    private TextView titleText, subtitleText;
    private androidx.activity.OnBackPressedCallback backCallback;

    private PowerOrbView powerOrb;
    private TextView statusLine, statusSub, signalDetail, bannerText;
    private View connectBanner;
    private SatelliteBarsView satBars;
    private View statCard1, statCard2, statCard3;

    private TextView monLocation, monAltAcc, logText;
    private View logDot;
    private SparklineView sparkAgeView, sparkPktView, sparkSatView;
    private TextView sparkAgeVal, sparkPktVal, sparkSatVal;

    private CompoundButton fusedSwitch, bluetoothSwitch;
    private boolean liveMonitoring = false;   // real-time Monitor updates, off by default

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private String appVersion = "<unknown>";
    private long connectedSinceElapsed = 0;

    private double mPktSent = Double.NaN, mBytesSent = Double.NaN, mMaxGap = Double.NaN,
            mAgeMean = Double.NaN, mAgeP95 = Double.NaN, mCpu = Double.NaN, mFixes = Double.NaN;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshState();
            if (liveMonitoring) {
                updateMonitorLocation();
                if (viewFlipper.getDisplayedChild() == VIEW_MONITOR) renderLog();
            }
            blinkLogDot();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = !result.containsValue(false);
                if (allGranted) {
                    requestBackgroundLocationIfNeeded();
                } else {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                    refreshPermissions();
                }
            });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                    checkBatteryOptimization();
                } else {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                }
                refreshPermissions();
            });

    private final ActivityResultLauncher<Intent> batteryOptimizationLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result ->
                    refreshPermissions());

    private final ActivityResultLauncher<String[]> bluetoothPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = !result.containsValue(false);
                if (allGranted) {
                    showBluetoothDevicePicker();
                } else {
                    Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show();
                }
            });

    private final BroadcastReceiver metricsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.METRICS".equals(intent.getAction())) {
                mPktSent = intent.getDoubleExtra("pktSentPerSec", Double.NaN);
                mBytesSent = intent.getDoubleExtra("bytesSentPerSec", Double.NaN);
                mMaxGap = intent.getDoubleExtra("maxGapMs", Double.NaN);
                mAgeMean = intent.getDoubleExtra("ageMeanMs", Double.NaN);
                mAgeP95 = intent.getDoubleExtra("ageP95Ms", Double.NaN);
                mCpu = intent.getDoubleExtra("cpuPct", Double.NaN);
                mFixes = intent.getDoubleExtra("fixesPerSec", Double.NaN);
                updateConnectReadouts();
                if (liveMonitoring) {
                    if (!Double.isNaN(mAgeMean)) sparkAgeView.push((float) mAgeMean);
                    if (!Double.isNaN(mPktSent)) sparkPktView.push((float) mPktSent);
                    sparkSatView.push(GNSSServerService.currentSatelliteCount());
                    updateMonitorMetrics();
                }
            }
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleStartRequest(intent);
    }

    /** Start request from the Quick Settings tile (it couldn't start the service itself). */
    private void handleStartRequest(Intent intent) {
        if (intent == null || !ACTION_START_SERVER.equals(intent.getAction())) return;
        intent.setAction(null);   // once: not again on recreate
        if (GNSSServerService.isServiceRunning()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startGNSSService();
        } else {
            requestPermissions();   // the user starts the server once the permission is granted
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        setContentView(R.layout.activity_main_server);
        AppLog.setDebug(Preferences.debugLoggingEnabled(this));

        appVersion = VersionGetter.getAppVersionName(this);
        liveMonitoring = Preferences.liveMonitoring(this);

        bindTopBar();
        bindConnect();
        bindMonitor();
        bindSettings();

        ContextCompat.registerReceiver(this, metricsReceiver,
                new IntentFilter("goodvin.locsync.METRICS"), ContextCompat.RECEIVER_NOT_EXPORTED);

        showView(VIEW_CONNECT);

        if (GNSSServerService.isServiceEnabled(this) && !GNSSServerService.isServiceRunning()) {
            startGNSSService();
        }
        handleStartRequest(getIntent());
        WifiTrigger.sync(this, false);   // arm if missing (e.g. after an app update)
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(metricsReceiver);
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshPermissions();
        refreshState();
        mainHandler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(tick);
    }

    // --- binding ---

    private void bindTopBar() {
        viewFlipper = findViewById(R.id.viewFlipper);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        titleText = findViewById(R.id.titleText);
        subtitleText = findViewById(R.id.subtitleText);
        btnLeft.setOnClickListener(v -> showView(
                viewFlipper.getDisplayedChild() == VIEW_CONNECT ? VIEW_SETTINGS : VIEW_CONNECT));
        btnRight.setOnClickListener(v -> showView(VIEW_MONITOR));

        // System back returns to Connect from a sub-view (works with predictive back on targetSdk 36,
        // where the legacy onBackPressed() override is no longer invoked).
        backCallback = new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                showView(VIEW_CONNECT);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    private void showView(int index) {
        viewFlipper.setDisplayedChild(index);
        if (backCallback != null) backCallback.setEnabled(index != VIEW_CONNECT);
        boolean connect = index == VIEW_CONNECT;
        btnLeft.setImageResource(connect ? R.drawable.ic_gear_six : R.drawable.ic_arrow_left);
        btnLeft.setContentDescription(getString(connect ? R.string.cd_settings : R.string.cd_back));
        btnRight.setVisibility(connect ? View.VISIBLE : View.INVISIBLE);
        switch (index) {
            case VIEW_MONITOR -> {
                titleText.setText(R.string.nav_monitor);
                subtitleText.setVisibility(View.GONE);
            }
            case VIEW_SETTINGS -> {
                titleText.setText(R.string.nav_settings);
                subtitleText.setVisibility(View.GONE);
            }
            default -> {
                titleText.setText(R.string.app_name);
                subtitleText.setVisibility(View.VISIBLE);
                refreshState();
            }
        }
    }

    private void bindConnect() {
        powerOrb = findViewById(R.id.powerOrb);
        statusLine = findViewById(R.id.statusLine);
        statusSub = findViewById(R.id.statusSub);
        signalDetail = findViewById(R.id.signalDetail);
        satBars = findViewById(R.id.satBars);
        connectBanner = findViewById(R.id.connectBanner);
        bannerText = findViewById(R.id.bannerText);
        statCard1 = findViewById(R.id.statCard1);
        statCard2 = findViewById(R.id.statCard2);
        statCard3 = findViewById(R.id.statCard3);

        setText(statCard1, R.id.statLabel, getString(R.string.stat_satellites));
        setText(statCard2, R.id.statLabel, getString(R.string.stat_sent));
        setText(statCard2, R.id.statUnit, getString(R.string.unit_pkts));
        setText(statCard3, R.id.statLabel, getString(R.string.uptime_label));

        powerOrb.setOnClickListener(v -> togglePower());
        connectBanner.setOnClickListener(v -> requestPermissions());
        findViewById(R.id.bannerDismiss).setOnClickListener(v -> connectBanner.setVisibility(View.GONE));
    }

    private void bindMonitor() {
        monLocation = findViewById(R.id.monLocation);
        monAltAcc = findViewById(R.id.monAltAcc);
        logText = findViewById(R.id.logText);
        logDot = findViewById(R.id.logDot);
        logText.setMovementMethod(new android.text.method.ScrollingMovementMethod());

        setText(R.id.kvProvider, R.id.kvKey, getString(R.string.label_provider));
        setText(R.id.kvSatellites, R.id.kvKey, getString(R.string.stat_satellites));
        setText(R.id.kvSpeed, R.id.kvKey, getString(R.string.label_speed));
        setText(R.id.kvBearing, R.id.kvKey, getString(R.string.label_bearing));
        setText(R.id.kvAge, R.id.kvKey, getString(R.string.label_age));
        setText(R.id.kvFixes, R.id.kvKey, getString(R.string.label_fixes));

        setText(R.id.lhPackets, R.id.metricLabel, getString(R.string.lh_packets));
        setText(R.id.lhBytes, R.id.metricLabel, getString(R.string.lh_bytes));
        setText(R.id.lhMaxGap, R.id.metricLabel, getString(R.string.lh_max_gap));
        setText(R.id.lhAgeMean, R.id.metricLabel, getString(R.string.lh_age_mean));
        setText(R.id.lhAgeP95, R.id.metricLabel, getString(R.string.lh_age_p95));
        setText(R.id.lhCpu, R.id.metricLabel, getString(R.string.lh_cpu));

        CompoundButton liveSwitch = findViewById(R.id.monitorLiveSwitch);
        liveSwitch.setChecked(liveMonitoring);
        liveSwitch.setOnCheckedChangeListener((b, checked) -> {
            liveMonitoring = checked;
            Preferences.setLiveMonitoring(this, checked);
        });

        View sparkAge = findViewById(R.id.sparkAge);
        View sparkPkt = findViewById(R.id.sparkPkt);
        View sparkSat = findViewById(R.id.sparkSat);
        setText(sparkAge, R.id.sparkLabel, getString(R.string.spark_fix_age));
        setText(sparkPkt, R.id.sparkLabel, getString(R.string.spark_packets));
        setText(sparkSat, R.id.sparkLabel, getString(R.string.spark_satellites));
        sparkAgeView = sparkAge.findViewById(R.id.sparkView);
        sparkPktView = sparkPkt.findViewById(R.id.sparkView);
        sparkSatView = sparkSat.findViewById(R.id.sparkView);
        sparkAgeVal = sparkAge.findViewById(R.id.sparkValue);
        sparkPktVal = sparkPkt.findViewById(R.id.sparkValue);
        sparkSatVal = sparkSat.findViewById(R.id.sparkValue);

        findViewById(R.id.logClear).setOnClickListener(v -> {
            AppLog.clearRing();
            renderLog();
        });
        TextView exportBtn = findViewById(R.id.btnExportLogs);
        exportBtn.setText(R.string.export_all_short);
        exportBtn.setOnClickListener(v -> exportAllData());
        findViewById(R.id.btnExportCsv).setVisibility(View.GONE);
    }

    private void bindSettings() {
        bindActionButton(R.id.rowPermissions, "",
                getString(R.string.permission_fine_location) + " · " + getString(R.string.permission_coarse_location)
                        + " · " + getString(R.string.permission_background_location),
                getString(R.string.request_permissions_short), this::requestPermissions);

        // Location provider
        View rowFused = findViewById(R.id.rowFusedLocation);
        setText(rowFused, R.id.row_label, getString(R.string.fused_location_enabled));
        fusedSwitch = rowFused.findViewById(R.id.row_switch);
        boolean supported = GNSSServerService.isFusedLocationSupported(this);
        // Reflect the stored preference (default on) regardless of support, so it isn't shown off by
        // default. `supported` only controls whether the row is editable.
        fusedSwitch.setChecked(Preferences.fusedLocationEnabled(this));
        fusedSwitch.setEnabled(supported);
        rowFused.setEnabled(supported);
        if (!supported) {
            TextView sub = rowFused.findViewById(R.id.row_sub);
            sub.setText(R.string.fused_location_not_supported);
            sub.setVisibility(View.VISIBLE);
        }
        rowFused.setOnClickListener(v -> {
            if (!supported) {
                return;
            }
            boolean next = !fusedSwitch.isChecked();
            fusedSwitch.setChecked(next);
            Preferences.setFusedLocationEnabled(this, next);
            GNSSServerService.reapplyLocationSettings();
        });
        bindNumberInput(R.id.rowLocationInterval, getString(R.string.location_interval),
                Preferences.locationIntervalMs(this), 100, 5000, v -> {
                    Preferences.setLocationIntervalMs(this, (int) Math.round(v));
                    GNSSServerService.reapplyLocationSettings();
                });
        bindToggle(R.id.rowBalancedPower, getString(R.string.location_balanced),
                getString(R.string.location_balanced_sub), Preferences.balancedPower(this), checked -> {
                    Preferences.setBalancedPower(this, checked);
                    GNSSServerService.reapplyLocationSettings();
                });
        bindToggle(R.id.rowWaitAccurate, getString(R.string.location_wait_accurate),
                getString(R.string.location_wait_accurate_sub), Preferences.waitForAccurate(this),
                checked -> Preferences.setWaitForAccurate(this, checked));

        // Automation
        View rowBt = findViewById(R.id.rowBluetooth);
        setText(rowBt, R.id.row_label, getString(R.string.bluetooth_auto_start_enabled));
        TextView btSub = rowBt.findViewById(R.id.row_sub);
        btSub.setText(R.string.bluetooth_settings_description);
        btSub.setVisibility(View.VISIBLE);
        bluetoothSwitch = rowBt.findViewById(R.id.row_switch);
        bluetoothSwitch.setChecked(Preferences.bluetoothAutoStartEnabled(this));
        rowBt.setOnClickListener(v -> {
            boolean next = !bluetoothSwitch.isChecked();
            bluetoothSwitch.setChecked(next);
            Preferences.setBluetoothAutoStartEnabled(this, next);
        });
        bindNumberInput(R.id.rowBtStopDelay, getString(R.string.bt_stop_delay),
                Preferences.bluetoothStopDelaySeconds(this), 0, 600,
                v -> Preferences.setBluetoothStopDelaySeconds(this, (int) Math.round(v)));
        bindNumberInput(R.id.rowGpsIdleStop, getString(R.string.gps_idle_stop),
                Preferences.gpsIdleStopSeconds(this), 5, 600,
                v -> Preferences.setGpsIdleStopSeconds(this, (int) Math.round(v)));
        bindActionChevron(R.id.rowTriggerDevices, getString(R.string.trigger_devices),
                triggerDevicesSummary(), this::showTriggerDevicesDialog);
        bindToggle(R.id.rowWifiAutostart, getString(R.string.wifi_autostart),
                getString(R.string.wifi_autostart_sub), Preferences.wifiAutoStartEnabled(this), checked -> {
                    Preferences.setWifiAutoStartEnabled(this, checked);
                    if (checked) warnIfWifiNameHidden();
                    WifiTrigger.sync(this, false);
                    GNSSServerService.refreshWifiMonitor();
                });
        bindActionButton(R.id.rowWifiNetwork, getString(R.string.wifi_network), wifiNetworkSummary(),
                getString(R.string.wifi_network_use_current), this::useCurrentWifiAsTrigger);

        // Diagnostics
        bindToggle(R.id.rowDebug, getString(R.string.debug_logging), null,
                Preferences.debugLoggingEnabled(this), checked -> {
                    Preferences.setDebugLoggingEnabled(this, checked);
                    AppLog.setDebug(checked);
                });
        bindToggle(R.id.rowMetrics, getString(R.string.metrics_enabled), null,
                Preferences.metricsEnabled(this), checked -> Preferences.setMetricsEnabled(this, checked));
        bindToggle(R.id.rowTrackRecording, getString(R.string.server_track_recording),
                getString(R.string.server_track_recording_sub), Preferences.trackRecording(this),
                checked -> {
                    Preferences.setTrackRecording(this, checked);
                    GNSSServerService.reapplyLocationSettings(); // starts/stops the GPS reference
                });
        bindToggle(R.id.rowGpsReference, getString(R.string.record_gps_reference),
                getString(R.string.record_gps_reference_sub), Preferences.recordGpsReference(this),
                checked -> {
                    Preferences.setRecordGpsReference(this, checked);
                    GNSSServerService.reapplyLocationSettings();
                });
        bindActionChevron(R.id.rowExportAll, getString(R.string.export_all),
                getString(R.string.export_all_sub), this::exportAllData);
        bindActionChevron(R.id.rowClearLogs, getString(R.string.clear_logs),
                getString(R.string.clear_logs_sub), this::confirmClearLogs);

        // About
        String buildLabel = getString(R.string.build_label);
        String shown = buildLabel.isEmpty() ? appVersion : buildLabel;
        bindAction(R.id.rowVersion, String.format(getString(R.string.version_label), shown),
                getString(R.string.about_protocol), false, null);
        bindActionChevron(R.id.rowLicense, getString(R.string.license_gpl3),
                getString(R.string.license_view),
                () -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"))));
    }

    // --- power / state ---

    private void togglePower() {
        if (GNSSServerService.isServiceRunning()) {
            stopGNSSService();
        } else {
            startGNSSService();
        }
        refreshState();
    }

    private LinkState currentState() {
        return LinkState.of(GNSSServerService.isServiceRunning(), GNSSServerService.isClientConnected());
    }

    private void refreshState() {
        LinkState state = currentState();
        powerOrb.setState(state);
        if (state == LinkState.CONNECTED) {
            if (connectedSinceElapsed == 0) connectedSinceElapsed = SystemClock.elapsedRealtime();
        } else {
            connectedSinceElapsed = 0;
        }

        switch (state) {
            case CONNECTED -> {
                statusLine.setText(R.string.notification_clients_single);
                statusLine.setTextColor(getColor(R.color.ls_accent_400));
                statusSub.setText(R.string.sub_server_connected);
            }
            case WAITING -> {
                statusLine.setText(R.string.status_server_waiting);
                statusLine.setTextColor(getColor(R.color.ls_accent_300));
                statusSub.setText(String.format(getString(R.string.sub_server_waiting), serverIp(), 8887));
            }
            default -> {
                statusLine.setText(R.string.status_hint_start);
                statusLine.setTextColor(getColor(R.color.ls_text_muted));
                statusSub.setText(R.string.subtitle_server);
            }
        }
        subtitleText.setText(statusSub.getText());
        updateBanner();
        updateConnectReadouts();
    }

    private void updateBanner() {
        boolean bgMissing = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED;
        if (bgMissing) {
            bannerText.setText(R.string.warn_background_location);
            connectBanner.setVisibility(View.VISIBLE);
        } else {
            connectBanner.setVisibility(View.GONE);
        }
    }

    private String uptime() {
        long s = connectedSinceElapsed == 0 ? 0 : (SystemClock.elapsedRealtime() - connectedSinceElapsed) / 1000;
        return String.format(getString(R.string.uptime_format), s / 60, s % 60);
    }

    private void updateConnectReadouts() {
        boolean connected = currentState() == LinkState.CONNECTED;
        int textColor = getColor(R.color.ls_text);
        int dimColor = getColor(R.color.ls_text_dim);
        int used = GNSSServerService.currentSatelliteCount();
        int visible = GNSSServerService.currentVisibleSatelliteCount();
        float[] cn0 = GNSSServerService.currentUsedCn0();

        if (connected) {
            setStat(statCard1, String.valueOf(used), textColor);
            setStat(statCard2, fmt1(mPktSent), textColor);
            setStat(statCard3, uptime(), textColor);
            setText(statCard3, R.id.statUnit, Double.isNaN(mBytesSent) ? "" :
                    String.format(Locale.US, "%.1f %s", mBytesSent / 1024.0, getString(R.string.unit_kbs)));
            // used-in-fix / total tracked, so the bar scale (up to 14 strongest) is clear.
            signalDetail.setText(String.format(getString(R.string.signal_used_total), used, Math.max(used, visible)));
            satBars.setData(used, cn0);
        } else {
            String none = getString(R.string.value_none);
            setStat(statCard1, none, dimColor);
            setStat(statCard2, none, dimColor);
            setStat(statCard3, none, dimColor);
            setText(statCard3, R.id.statUnit, "");
            signalDetail.setText(none);
            satBars.clear();
        }
    }

    private void setStat(View card, String value, int color) {
        TextView v = card.findViewById(R.id.statValue);
        v.setText(value);
        v.setTextColor(color);
    }

    // --- monitor readouts ---

    private void updateMonitorLocation() {
        boolean connected = currentState() == LinkState.CONNECTED;
        LocationProto.LocationUpdate loc = connected ? GNSSServerService.currentLocationUpdate() : null;
        String none = getString(R.string.value_none);
        if (loc == null) {
            monLocation.setText(none);
            monAltAcc.setText("");
            setText(R.id.kvProvider, R.id.kvValue, none);
            setText(R.id.kvSatellites, R.id.kvValue, none);
            setText(R.id.kvSpeed, R.id.kvValue, none);
            setText(R.id.kvBearing, R.id.kvValue, none);
            setText(R.id.kvAge, R.id.kvValue, none);
            setText(R.id.kvFixes, R.id.kvValue, none);
            return;
        }
        monLocation.setText(String.format(getString(R.string.location_format), loc.getLatitude(), loc.getLongitude()));
        StringBuilder alt = new StringBuilder();
        alt.append(String.format(getString(R.string.altitude_format), loc.getAltitude()));
        alt.append(String.format(getString(R.string.location_accuracy_format), loc.getAccuracy()));
        monAltAcc.setText(alt.toString().trim());
        setText(R.id.kvProvider, R.id.kvValue, loc.getProvider().isEmpty() ? getString(R.string.unknown) : loc.getProvider());
        setText(R.id.kvSatellites, R.id.kvValue, String.valueOf(GNSSServerService.currentSatelliteCount()));
        setText(R.id.kvSpeed, R.id.kvValue, String.format(getString(R.string.speed_format), loc.getSpeed()));
        setText(R.id.kvBearing, R.id.kvValue, String.format(getString(R.string.bearing_format), loc.getBearing()));
        setText(R.id.kvAge, R.id.kvValue, String.format(getString(R.string.age_format), loc.getLocationAge()));
        setText(R.id.kvFixes, R.id.kvValue, Double.isNaN(mFixes) ? none : String.format(getString(R.string.fixes_format), mFixes));
    }

    private void updateMonitorMetrics() {
        boolean connected = currentState() == LinkState.CONNECTED;
        setText(R.id.lhPackets, R.id.metricValue, connected ? fmt1(mPktSent) : none());
        setText(R.id.lhBytes, R.id.metricValue, connected ? fmt0(mBytesSent) : none());
        setText(R.id.lhMaxGap, R.id.metricValue, connected ? fmt0(mMaxGap) : none());
        setText(R.id.lhAgeMean, R.id.metricValue, connected ? fmt0(mAgeMean) : none());
        setText(R.id.lhAgeP95, R.id.metricValue, connected ? fmt0(mAgeP95) : none());
        setText(R.id.lhCpu, R.id.metricValue, fmt0(mCpu));
        sparkAgeVal.setText(fmt0(mAgeMean));
        sparkPktVal.setText(fmt1(mPktSent));
        sparkSatVal.setText(String.valueOf(GNSSServerService.currentSatelliteCount()));
    }

    private void renderLog() {
        List<AppLog.Entry> entries = AppLog.snapshot();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int max = Math.min(entries.size(), 120);
        for (int i = 0; i < max; i++) {
            AppLog.Entry e = entries.get(i);
            int start = sb.length();
            sb.append(logTime.format(e.timeMillis)).append(' ');
            span(sb, start, sb.length(), getColor(R.color.ls_text_dim));
            int ls = sb.length();
            sb.append(e.level).append(' ');
            span(sb, ls, sb.length(), levelColor(e.level));
            sb.append(e.message).append('\n');
        }
        logText.setText(sb);
    }

    private int levelColor(char level) {
        return switch (level) {
            case 'I' -> getColor(R.color.ls_accent_400);
            case 'W' -> getColor(R.color.ls_error);
            default -> getColor(R.color.ls_log_debug);
        };
    }

    private void span(SpannableStringBuilder sb, int start, int end, int color) {
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private ObjectAnimator dotAnimator;
    private void blinkLogDot() {
        boolean running = GNSSServerService.isServiceRunning();
        if (running && dotAnimator == null) {
            dotAnimator = ObjectAnimator.ofFloat(logDot, "alpha", 1f, 0.2f);
            dotAnimator.setDuration(1400);
            dotAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            dotAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            dotAnimator.start();
        } else if (!running && dotAnimator != null) {
            dotAnimator.cancel();
            dotAnimator = null;
            logDot.setAlpha(0.3f);
        }
    }

    private String serverIp() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = intf.getDisplayName();
                if (!(name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap"))) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    String s = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && s != null && !s.contains(":")) return s;
                }
            }
        } catch (Exception e) {
            AppLog.d(TAG, "serverIp failed: " + e.getMessage());
        }
        return "192.168.43.1";
    }

    // --- permissions (behaviour preserved) ---

    private void refreshPermissions() {
        boolean allGranted = true;
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                missing.add(getPermissionName(p));
            }
        }
        View row = findViewById(R.id.rowPermissions);
        TextView label = row.findViewById(R.id.row_label);
        TextView button = row.findViewById(R.id.row_button);
        if (allGranted) {
            label.setText(R.string.all_permissions_granted);
            button.setVisibility(View.GONE);
        } else {
            label.setText(String.format(getString(R.string.missing_permissions), String.join(", ", missing)));
            button.setVisibility(View.VISIBLE);
        }
        updateBanner();
    }

    private String getPermissionName(String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION -> getString(R.string.permission_coarse_location);
            case Manifest.permission.ACCESS_BACKGROUND_LOCATION -> getString(R.string.permission_background_location);
            default -> permission.substring(permission.lastIndexOf('.') + 1);
        };
    }

    private void requestPermissions() {
        List<String> foregroundToRequest = new ArrayList<>();
        for (String permission : FOREGROUND_LOCATION_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                foregroundToRequest.add(permission);
            }
        }
        if (!foregroundToRequest.isEmpty()) {
            permissionLauncher.launch(foregroundToRequest.toArray(new String[0]));
            return;
        }
        requestBackgroundLocationIfNeeded();
    }

    private void requestBackgroundLocationIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        } else {
            checkBatteryOptimization();
        }
    }

    @SuppressLint("BatteryLife")
    private void checkBatteryOptimization() {
        String packageName = getPackageName();
        if (!Settings.System.canWrite(this)) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName));
            batteryOptimizationLauncher.launch(intent);
        } else {
            refreshPermissions();
        }
    }

    // --- service control (behaviour preserved) ---

    private void startGNSSService() {
        GNSSServerService.setServiceEnabled(this, true);
        ContextCompat.startForegroundService(this, new Intent(this, GNSSServerService.class));
    }

    private void stopGNSSService() {
        // Pause only: stop the running instance but keep the service "enabled" so it restarts on
        // relaunch and Bluetooth auto-start still fires. Automatic BT auto-stop clears the flag.
        stopService(new Intent(this, GNSSServerService.class));
        connectedSinceElapsed = 0;
    }

    // --- car Wi-Fi trigger ---

    private String wifiNetworkSummary() {
        String ssid = Preferences.wifiTriggerSsid(this);
        return ssid != null ? getString(R.string.wifi_network_chosen, ssid) : getString(R.string.wifi_network_none);
    }

    /** The phone must be on the car's Wi-Fi now; Android reveals the name only with location on. */
    private void useCurrentWifiAsTrigger() {
        String ssid = WifiTrigger.currentSsid(this);
        if (ssid == null) {
            Toast.makeText(this, R.string.wifi_network_unknown, Toast.LENGTH_LONG).show();
            return;
        }
        Preferences.setWifiTriggerSsid(this, ssid);
        setText(R.id.rowWifiNetwork, R.id.row_sub, wifiNetworkSummary());
        warnIfWifiNameHidden();
        WifiTrigger.sync(this, false);
        GNSSServerService.refreshWifiMonitor();
    }

    /** In the background Android reveals the Wi-Fi name only with "Allow all the time" location. */
    private void warnIfWifiNameHidden() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.wifi_needs_background_location, Toast.LENGTH_LONG).show();
        }
    }

    // --- bluetooth trigger devices (behaviour preserved) ---

    private String triggerDevicesSummary() {
        Map<String, String> devices = Preferences.getBluetoothTriggerDeviceNames(this);
        if (devices.isEmpty()) return "";
        return String.join(", ", devices.values());
    }

    private void showTriggerDevicesDialog() {
        Map<String, String> devices = new LinkedHashMap<>(Preferences.getBluetoothTriggerDeviceNames(this));
        List<String> macs = new ArrayList<>(devices.keySet());
        List<String> names = new ArrayList<>(devices.values());
        String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.trigger_devices)
                .setItems(items, (dialog, which) -> confirmRemoveDevice(macs.get(which), names.get(which)))
                .setPositiveButton(R.string.add_bluetooth_device, (d, w) -> showBluetoothDevicePicker())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemoveDevice(String mac, String name) {
        new AlertDialog.Builder(this)
                .setMessage(name)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Preferences.removeBluetoothTriggerDevice(this, mac);
                    setText(findViewById(R.id.rowTriggerDevices), R.id.row_sub, triggerDevicesSummary());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean hasBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            for (String permission : BLUETOOTH_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void showBluetoothDevicePicker() {
        if (!hasBluetoothPermissions()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                bluetoothPermissionLauncher.launch(BLUETOOTH_PERMISSIONS);
            }
            return;
        }
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        BluetoothAdapter bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, R.string.bluetooth_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(BLUETOOTH_PERMISSIONS);
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, R.string.bluetooth_no_paired_devices, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> existingMacs = Preferences.getBluetoothTriggerDeviceMacs(this);
        List<String> availableNames = new ArrayList<>();
        List<String> availableAddresses = new ArrayList<>();
        for (BluetoothDevice device : pairedDevices) {
            if (!existingMacs.contains(device.getAddress())) {
                String name = device.getName();
                availableNames.add((name != null && !name.isEmpty()) ? name : device.getAddress());
                availableAddresses.add(device.getAddress());
            }
        }
        if (availableNames.isEmpty()) {
            Toast.makeText(this, R.string.bluetooth_all_devices_added, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = availableNames.toArray(new String[0]);
        String[] addresses = availableAddresses.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.bluetooth_add_device_title)
                .setItems(names, (dialog, which) -> {
                    Preferences.addBluetoothTriggerDevice(this, addresses[which], names[which]);
                    setText(findViewById(R.id.rowTriggerDevices), R.id.row_sub, triggerDevicesSummary());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- exports (behaviour preserved) ---

    private final Runnable disarmClearLogs = this::disarmClearLogs;

    /**
     * First tap on "Clear logs" arms an inline confirmation (a "Clear" button in the row) instead of
     * an AlertDialog: some head-unit ROMs restyle system dialogs so their buttons are invisible.
     * Disarms by itself after a few seconds.
     */
    private void confirmClearLogs() {
        View row = findViewById(R.id.rowClearLogs);
        setText(row, R.id.row_sub, getString(R.string.clear_logs_confirm));
        TextView button = row.findViewById(R.id.row_button);
        button.setText(R.string.clear_logs_action);
        button.setVisibility(View.VISIBLE);
        row.findViewById(R.id.row_chevron).setVisibility(View.GONE);
        button.setOnClickListener(v -> {
            disarmClearLogs();
            long freed = LogExporter.clearAll(this);
            renderLog();
            Toast.makeText(this, getString(R.string.clear_logs_done,
                    android.text.format.Formatter.formatShortFileSize(this, freed)), Toast.LENGTH_SHORT).show();
        });
        mainHandler.removeCallbacks(disarmClearLogs);
        mainHandler.postDelayed(disarmClearLogs, 5000);
    }

    private void disarmClearLogs() {
        mainHandler.removeCallbacks(disarmClearLogs);
        View row = findViewById(R.id.rowClearLogs);
        setText(row, R.id.row_sub, getString(R.string.clear_logs_sub));
        row.findViewById(R.id.row_button).setVisibility(View.GONE);
        row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
    }

    /** Settings → Diagnostics / Monitor: one archive with logs, metrics, track and settings. */
    private void exportAllData() {
        Toast.makeText(this, R.string.export_all_in_progress, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File zip = null;
            try {
                zip = LogExporter.exportAll(this, "locsync-server", appVersion, Preferences.dump(this));
            } catch (Exception e) {
                Log.e(TAG, "Error exporting data", e);
            }
            final File result = zip;
            runOnUiThread(() -> {
                if (result != null) {
                    shareFile(result, "application/zip", getString(R.string.export_all));
                } else {
                    Toast.makeText(this, R.string.export_all_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void shareFile(File file, String mime, String chooserTitle) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, chooserTitle));
        } catch (Exception e) {
            Log.w(TAG, "Failed to share file", e);
        }
    }

    // --- small view helpers ---

    private String none() { return getString(R.string.value_none); }

    private static String fmt0(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.0f", v);
    }

    private static String fmt1(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.1f", v);
    }

    private void setText(int rootId, int childId, CharSequence text) {
        setText(findViewById(rootId), childId, text);
    }

    private void setText(View root, int childId, CharSequence text) {
        TextView tv = root.findViewById(childId);
        if (tv != null) tv.setText(text);
    }

    private void bindNumberInput(int rowId, String label, double value, double min, double max,
                                 java.util.function.DoubleConsumer onChange) {
        SettingsRows.bindNumber(findViewById(rowId), label, value, min, max, onChange);
    }

    private void bindToggle(int rowId, String label, String sub, boolean checked,
                            java.util.function.Consumer<Boolean> onChange) {
        SettingsRows.bindToggle(findViewById(rowId), label, sub, checked, onChange);
    }

    private void bindAction(int rowId, String label, String sub, boolean chevron, Runnable click) {
        SettingsRows.bindAction(findViewById(rowId), label, sub, chevron, click);
    }

    private void bindActionChevron(int rowId, String label, String sub, Runnable click) {
        bindAction(rowId, label, sub, true, click);
    }

    private void bindActionButton(int rowId, String label, String sub, String buttonLabel, Runnable buttonClick) {
        SettingsRows.bindActionButton(findViewById(rowId), label, sub, buttonLabel, buttonClick);
    }
}
