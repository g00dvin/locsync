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

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import goodvin.locsync.shared.AppLog;
import goodvin.locsync.shared.LinkState;
import goodvin.locsync.shared.LogExporter;
import goodvin.locsync.shared.MetricsCsvWriter;
import goodvin.locsync.shared.PowerOrbView;
import goodvin.locsync.shared.SatelliteBarsView;
import goodvin.locsync.shared.SparklineView;
import goodvin.locsync.shared.VersionGetter;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSClientActivity";
    private static final int VIEW_CONNECT = 0, VIEW_MONITOR = 1, VIEW_SETTINGS = 2;

    // Head units have large, low-density screens where dp-sized UI reads tiny. Scale the whole UI
    // (dp + sp uniformly) by raising the effective density on large screens; phones are untouched.
    private static final float LARGE_SCREEN_UI_SCALE = 2.0f;
    private static final int LARGE_SCREEN_MIN_SW_DP = 600;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    // Top bar
    private ViewFlipper viewFlipper;
    private ImageView btnLeft, btnRight;
    private TextView titleText, subtitleText;
    private androidx.activity.OnBackPressedCallback backCallback;

    // Connect
    private PowerOrbView powerOrb;
    private TextView statusLine, statusSub, signalDetail, bannerText;
    private View connectBanner;
    private SatelliteBarsView satBars;
    private View statCard1, statCard2, statCard3;

    // Monitor
    private TextView monLocation, monAltAcc, logText;
    private View logDot;
    private SparklineView sparkAgeView, sparkPktView, sparkSatView;
    private TextView sparkAgeVal, sparkPktVal, sparkSatVal;

    private boolean liveMonitoring = false;   // real-time Monitor updates, off by default

    private final Handler uiHandler = new Handler();
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private String appVersion = "<unknown>";
    private String warningMessage = null;   // version mismatch / mock-provider error, shown in banner
    private boolean mockError = false;
    private Runnable bannerAction = null;    // what tapping the connect banner does (depends on the issue)
    private long connectedSinceElapsed = 0;

    // Latest values for the connect/monitor readouts.
    private int lastSatellites = 0;
    private Location lastLocation = null;
    private String lastProvider = null;
    private float lastLocationAge = 0;
    private double mPktRecv = Double.NaN, mPktSent = Double.NaN, mBytesRecv = Double.NaN,
            mBytesSent = Double.NaN, mMaxGap = Double.NaN, mAgeMean = Double.NaN,
            mAgeP95 = Double.NaN, mCpu = Double.NaN, mFixes = Double.NaN;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = !result.containsValue(false);
                if (allGranted) {
                    Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                    checkMockLocationSettings();
                } else {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                    refreshPermissions();
                }
            });

    private final ActivityResultLauncher<Intent> mockLocationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result ->
                    refreshPermissions());

    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.CONNECTION_CHANGED".equals(intent.getAction())) {
                refreshState();
            }
        }
    };

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.LOCATION_UPDATE".equals(intent.getAction())) {
                lastSatellites = intent.getIntExtra("satellites", 0);
                Location location = IntentCompat.getParcelableExtra(intent, "location", Location.class);
                if (location != null) {
                    lastLocation = location;
                    lastProvider = intent.getStringExtra("provider");
                    lastLocationAge = intent.getFloatExtra("locationAge", 0);
                }
                updateConnectReadouts();
                if (liveMonitoring) {
                    sparkSatView.push(lastSatellites);
                    updateMonitorLocation();
                }
            }
        }
    };

    private final BroadcastReceiver mockLocationStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.MOCK_LOCATION_STATUS".equals(intent.getAction())) {
                warningMessage = intent.getStringExtra("message");
                mockError = intent.getBooleanExtra("error", true);
                refreshState();
            }
        }
    };

    private final BroadcastReceiver metricsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.METRICS".equals(intent.getAction())) {
                mPktRecv = intent.getDoubleExtra("pktRecvPerSec", Double.NaN);
                mPktSent = intent.getDoubleExtra("pktSentPerSec", Double.NaN);
                mBytesRecv = intent.getDoubleExtra("bytesRecvPerSec", Double.NaN);
                mBytesSent = intent.getDoubleExtra("bytesSentPerSec", Double.NaN);
                mMaxGap = intent.getDoubleExtra("maxGapMs", Double.NaN);
                mAgeMean = intent.getDoubleExtra("ageMeanMs", Double.NaN);
                mAgeP95 = intent.getDoubleExtra("ageP95Ms", Double.NaN);
                mCpu = intent.getDoubleExtra("cpuPct", Double.NaN);
                mFixes = intent.getDoubleExtra("fixesPerSec", Double.NaN);
                updateConnectReadouts();
                if (liveMonitoring) {
                    if (!Double.isNaN(mAgeMean)) sparkAgeView.push((float) mAgeMean);
                    if (!Double.isNaN(mPktRecv)) sparkPktView.push((float) mPktRecv);
                    updateMonitorMetrics();
                }
            }
        }
    };

    private final BroadcastReceiver filterStatsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.FILTER_STATS".equals(intent.getAction()) && liveMonitoring) {
                renderFilterStats(intent.getStringArrayExtra("labels"), intent.getStringArrayExtra("values"));
            }
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        android.content.res.Configuration config =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        if (config.smallestScreenWidthDp >= LARGE_SCREEN_MIN_SW_DP) {
            config.densityDpi = Math.round(config.densityDpi * LARGE_SCREEN_UI_SCALE);
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        setContentView(R.layout.activity_main);

        appVersion = VersionGetter.getAppVersionName(this);
        AppLog.setDebug(Preferences.debugLoggingEnabled(this));
        // Manual server-address UI was removed; the client always auto-discovers.
        Preferences.setAutoDiscover(this, true);
        liveMonitoring = Preferences.liveMonitoring(this);

        bindTopBar();
        bindConnect();
        bindMonitor();
        bindSettings();
        registerReceivers();

        showView(VIEW_CONNECT);
        refreshPermissions();
        refreshState();
        startUIUpdates();

        if (GNSSClientService.isServiceEnabled(this) && !GNSSClientService.isServiceRunning()) {
            startGNSSService();
        }
        if (GNSSClientService.isServiceEnabled(this) && Preferences.autostartWifiBoot(this)) {
            AutostartScheduler.schedule(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(connectionReceiver);
        unregisterReceiver(locationReceiver);
        unregisterReceiver(mockLocationStatusReceiver);
        unregisterReceiver(metricsReceiver);
        unregisterReceiver(filterStatsReceiver);
        uiHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Recompute from the service rather than assuming a state; also re-check mock-app selection.
        refreshPermissions();
        refreshState();
        refreshA11yAutostartRow();
    }

    // --- binding ---

    private void bindTopBar() {
        viewFlipper = findViewById(R.id.viewFlipper);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        titleText = findViewById(R.id.titleText);
        subtitleText = findViewById(R.id.subtitleText);
        btnLeft.setOnClickListener(v -> {
            if (viewFlipper.getDisplayedChild() == VIEW_CONNECT) {
                showView(VIEW_SETTINGS);
            } else {
                showView(VIEW_CONNECT);
            }
        });
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
        setText(statCard2, R.id.statLabel, getString(R.string.stat_recv));
        setText(statCard2, R.id.statUnit, getString(R.string.unit_pkts));
        setText(statCard3, R.id.statLabel, getString(R.string.uptime_label));

        powerOrb.setOnClickListener(v -> togglePower());
        connectBanner.setOnClickListener(v -> {
            if (bannerAction != null) bannerAction.run();
        });
        findViewById(R.id.bannerDismiss).setOnClickListener(v ->
                connectBanner.setVisibility(View.GONE));
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
        findViewById(R.id.btnExportLogs).setOnClickListener(v -> exportLogs("locsync-client"));
        findViewById(R.id.btnExportCsv).setOnClickListener(v -> shareMetricsCsv());
    }

    private void bindSettings() {
        // Permissions
        bindActionButton(R.id.rowPermissions, "",
                getString(R.string.permission_fine_location) + " · " + getString(R.string.permission_coarse_location),
                getString(R.string.request_permissions_short), this::requestPermissions);

        // Automation
        bindToggle(R.id.rowAutostart, getString(R.string.autostart_wifi_boot),
                getString(R.string.autostart_wifi_boot_sub), Preferences.autostartWifiBoot(this),
                checked -> {
                    Preferences.setAutostartWifiBoot(this, checked);
                    if (checked) {
                        if (GNSSClientService.isServiceEnabled(this)) AutostartScheduler.schedule(this);
                    } else {
                        AutostartScheduler.cancel(this);
                    }
                });
        bindActionChevron(R.id.rowA11yAutostart, getString(R.string.a11y_autostart), null,
                this::openAccessibilitySettings);
        bindToggle(R.id.rowStaticJitter, getString(R.string.static_jitter), null,
                Preferences.staticJitterEnabled(this),
                checked -> Preferences.setStaticJitterEnabled(this, checked));

        // Smoothing
        bindToggle(R.id.rowReportUncertainty, getString(R.string.filter_report_uncertainty),
                getString(R.string.filter_report_uncertainty_sub), Preferences.filterReportUncertainty(this),
                checked -> Preferences.setFilterReportUncertainty(this, checked));
        bindToggle(R.id.rowLatencyComp, getString(R.string.filter_latency_comp),
                getString(R.string.filter_latency_comp_sub), Preferences.filterLatencyCompensation(this),
                checked -> Preferences.setFilterLatencyCompensation(this, checked));
        bindNumberInput(R.id.rowExtraLatency, getString(R.string.filter_extra_latency),
                Preferences.filterExtraLatencyMs(this), 0, 1000,
                v -> Preferences.setFilterExtraLatencyMs(this, (float) v));
        bindToggle(R.id.rowTurnModel, getString(R.string.filter_turn_model),
                getString(R.string.filter_turn_model_sub), Preferences.filterTurnModel(this),
                checked -> Preferences.setFilterTurnModel(this, checked));
        bindToggle(R.id.rowGating, getString(R.string.filter_gating),
                getString(R.string.filter_gating_sub), Preferences.filterGating(this),
                checked -> Preferences.setFilterGating(this, checked));
        bindNumberInput(R.id.rowGateThreshold, getString(R.string.filter_gate_threshold),
                Preferences.filterGateThreshold(this), 4, 100,
                v -> Preferences.setFilterGateThreshold(this, (float) v));
        bindToggle(R.id.rowStandstillHold, getString(R.string.filter_standstill),
                getString(R.string.filter_standstill_sub), Preferences.filterStandstillHold(this),
                checked -> Preferences.setFilterStandstillHold(this, checked));
        bindNumberInput(R.id.rowStandstillSpeed, getString(R.string.filter_standstill_speed),
                Preferences.filterStandstillSpeed(this), 0.1, 3,
                v -> Preferences.setFilterStandstillSpeed(this, (float) v));

        // Diagnostics
        bindToggle(R.id.rowDebug, getString(R.string.debug_logging), null,
                Preferences.debugLoggingEnabled(this),
                checked -> {
                    Preferences.setDebugLoggingEnabled(this, checked);
                    AppLog.setDebug(checked);
                });
        bindToggle(R.id.rowMetrics, getString(R.string.metrics_enabled), null,
                Preferences.metricsEnabled(this),
                checked -> Preferences.setMetricsEnabled(this, checked));
        bindActionChevron(R.id.rowExportMetrics, getString(R.string.export_metrics),
                lastMetricsFileName(), this::shareMetricsCsv);

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

    private void refreshA11yAutostartRow() {
        View row = findViewById(R.id.rowA11yAutostart);
        TextView sub = row.findViewById(R.id.row_sub);
        sub.setText(AutostartAccessibilityService.isEnabled(this)
                ? R.string.a11y_autostart_on : R.string.a11y_autostart_off);
        sub.setVisibility(View.VISIBLE);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            // Some head-unit ROMs strip the accessibility screen; fall back to the settings root.
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void registerReceivers() {
        registerReceiver(connectionReceiver, new IntentFilter("goodvin.locsync.CONNECTION_CHANGED"), RECEIVER_NOT_EXPORTED);
        registerReceiver(locationReceiver, new IntentFilter("goodvin.locsync.LOCATION_UPDATE"), RECEIVER_NOT_EXPORTED);
        registerReceiver(mockLocationStatusReceiver, new IntentFilter("goodvin.locsync.MOCK_LOCATION_STATUS"), RECEIVER_NOT_EXPORTED);
        registerReceiver(metricsReceiver, new IntentFilter("goodvin.locsync.METRICS"), RECEIVER_NOT_EXPORTED);
        registerReceiver(filterStatsReceiver, new IntentFilter("goodvin.locsync.FILTER_STATS"), RECEIVER_NOT_EXPORTED);
    }

    // --- power / state ---

    private void togglePower() {
        if (GNSSClientService.isServiceRunning()) {
            stopGNSSService();
        } else {
            startGNSSService();
        }
        refreshState();
    }

    private LinkState currentState() {
        boolean running = GNSSClientService.isServiceRunning();
        boolean connected = GNSSClientService.getConnectionState() == ConnectionManager.ConnectionState.CONNECTED;
        return LinkState.of(running, connected);
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
                statusLine.setText(R.string.status_client_connected);
                statusLine.setTextColor(getColor(R.color.ls_accent_400));
                statusSub.setText(R.string.sub_client_connected);
            }
            case WAITING -> {
                statusLine.setText(R.string.status_client_connecting);
                statusLine.setTextColor(getColor(R.color.ls_accent_300));
                statusSub.setText(R.string.sub_client_waiting);
            }
            default -> {
                statusLine.setText(R.string.status_hint_start);
                statusLine.setTextColor(getColor(R.color.ls_text_muted));
                statusSub.setText(R.string.subtitle_client);
            }
        }
        subtitleText.setText(statusSub.getText());
        updateBanner(state);
        updateConnectReadouts();
    }

    private void updateBanner(LinkState state) {
        String msg = null;
        Runnable action = null;

        List<String> missing = missingPermissionNames();
        if (!missing.isEmpty()) {
            // First launch / revoked: name the exact permissions and let a tap request them.
            msg = String.format(getString(R.string.missing_permissions), String.join(", ", missing));
            action = this::requestPermissions;
        } else if (warningMessage != null && mockError) {
            // Version mismatch or a mock-provider failure reported by the service.
            msg = warningMessage;
            action = this::openMockLocationSettings;
        } else if (!MockLocationManager.isSelectedMockApp(this)) {
            msg = getString(R.string.mock_app_not_selected);
            action = this::openMockLocationSettings;
        }

        bannerAction = action;
        if (msg != null) {
            bannerText.setText(msg);
            connectBanner.setVisibility(View.VISIBLE);
        } else {
            connectBanner.setVisibility(View.GONE);
        }
    }

    private List<String> missingPermissionNames() {
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(getPermissionName(p));
            }
        }
        return missing;
    }

    private void openMockLocationSettings() {
        try {
            mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Log.w(TAG, "Cannot open settings", e);
            }
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

        if (connected) {
            setStat(statCard1, String.valueOf(lastSatellites), textColor);
            setStat(statCard2, fmt1(mPktRecv), textColor);
            setStat(statCard3, uptime(), textColor);
            // Client only knows the satellite count (no per-satellite C/N0 over the protocol),
            // so the signal card shows the count and a uniform count meter.
            signalDetail.setText(String.format(getString(R.string.signal_sats), lastSatellites));
            satBars.setData(lastSatellites, null);
        } else {
            String none = getString(R.string.value_none);
            setStat(statCard1, none, dimColor);
            setStat(statCard2, none, dimColor);
            setStat(statCard3, none, dimColor);
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
        if (!connected || lastLocation == null) {
            monLocation.setText(R.string.value_none);
            monAltAcc.setText("");
            String none = getString(R.string.value_none);
            setText(R.id.kvProvider, R.id.kvValue, none);
            setText(R.id.kvSatellites, R.id.kvValue, none);
            setText(R.id.kvSpeed, R.id.kvValue, none);
            setText(R.id.kvBearing, R.id.kvValue, none);
            setText(R.id.kvAge, R.id.kvValue, none);
            setText(R.id.kvFixes, R.id.kvValue, none);
            return;
        }
        Location loc = lastLocation;
        monLocation.setText(String.format(getString(R.string.location_format), loc.getLatitude(), loc.getLongitude()));
        StringBuilder alt = new StringBuilder();
        if (loc.hasAltitude()) alt.append(String.format(getString(R.string.altitude_format), loc.getAltitude()));
        if (loc.hasAccuracy()) alt.append(String.format(getString(R.string.location_accuracy_format), loc.getAccuracy()));
        monAltAcc.setText(alt.toString().trim());
        setText(R.id.kvProvider, R.id.kvValue, lastProvider != null ? lastProvider : getString(R.string.unknown));
        setText(R.id.kvSatellites, R.id.kvValue, String.valueOf(lastSatellites));
        setText(R.id.kvSpeed, R.id.kvValue, loc.hasSpeed() ? String.format(getString(R.string.speed_format), loc.getSpeed()) : getString(R.string.value_none));
        setText(R.id.kvBearing, R.id.kvValue, loc.hasBearing() ? String.format(getString(R.string.bearing_format), loc.getBearing()) : getString(R.string.value_none));
        setText(R.id.kvAge, R.id.kvValue, String.format(getString(R.string.age_format), lastLocationAge));
        setText(R.id.kvFixes, R.id.kvValue, Double.isNaN(mFixes) ? getString(R.string.value_none) : String.format(getString(R.string.fixes_format), mFixes));
    }

    private void updateMonitorMetrics() {
        boolean connected = currentState() == LinkState.CONNECTED;
        setText(R.id.lhPackets, R.id.metricValue, connected ? fmt1(mPktRecv) : none());
        setText(R.id.lhBytes, R.id.metricValue, connected ? fmt0(mBytesRecv) : none());
        setText(R.id.lhMaxGap, R.id.metricValue, connected ? fmt0(mMaxGap) : none());
        setText(R.id.lhAgeMean, R.id.metricValue, connected ? fmt0(mAgeMean) : none());
        setText(R.id.lhAgeP95, R.id.metricValue, connected ? fmt0(mAgeP95) : none());
        setText(R.id.lhCpu, R.id.metricValue, fmt0(mCpu));
        sparkAgeVal.setText(fmt0(mAgeMean));
        sparkPktVal.setText(fmt1(mPktRecv));
        sparkSatVal.setText(String.valueOf(lastSatellites));
    }

    /** Fills the Monitor filter card with the rows the service sent (reusing cells when possible). */
    private void renderFilterStats(String[] labels, String[] values) {
        if (labels == null || values == null || labels.length != values.length) return;
        android.view.ViewGroup rows = findViewById(R.id.monFilterRows);
        if (rows.getChildCount() != labels.length) {
            rows.removeAllViews();
            android.view.LayoutInflater inflater = getLayoutInflater();
            for (int i = 0; i < labels.length; i++) {
                View cell = inflater.inflate(R.layout.ls_kv_cell, rows, false);
                cell.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                rows.addView(cell);
            }
        }
        for (int i = 0; i < labels.length; i++) {
            View cell = rows.getChildAt(i);
            setText(cell, R.id.kvKey, labels[i]);
            setText(cell, R.id.kvValue, values[i]);
        }
        findViewById(R.id.monFilterCard).setVisibility(View.VISIBLE);
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

    // --- periodic tick ---

    private void startUIUpdates() {
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (currentState() == LinkState.CONNECTED) {
                    setStat(statCard3, uptime(), getColor(R.color.ls_text));
                }
                blinkLogDot();
                if (liveMonitoring && viewFlipper.getDisplayedChild() == VIEW_MONITOR) {
                    renderLog();
                }
                uiHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private ObjectAnimator dotAnimator;
    private void blinkLogDot() {
        boolean running = GNSSClientService.isServiceRunning();
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
        updateBanner(currentState());
    }

    private String getPermissionName(String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION -> getString(R.string.permission_coarse_location);
            default -> permission.substring(permission.lastIndexOf('.') + 1);
        };
    }

    private void requestPermissions() {
        List<String> toRequest = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (toRequest.isEmpty()) {
            checkMockLocationSettings();
        } else {
            permissionLauncher.launch(toRequest.toArray(new String[0]));
        }
    }

    private void checkMockLocationSettings() {
        Toast.makeText(this, getString(R.string.mock_location_enable_message), Toast.LENGTH_LONG).show();
        try {
            mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // --- service control (behaviour preserved) ---

    private void startGNSSService() {
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        startForegroundService(serviceIntent);
        Preferences.setServiceEnabled(this, true);
        if (Preferences.autostartWifiBoot(this)) {
            AutostartScheduler.schedule(this);
        }
        ensureBatteryOptimizationExemption();
    }

    private void stopGNSSService() {
        // Pause only: stop the running instance but keep the service "enabled" and the Wi-Fi/boot
        // autostart job armed, so it comes back on the next trigger (Wi-Fi connect, reboot, relaunch).
        // Autostart is disabled instead via the Automation toggle.
        stopService(new Intent(this, GNSSClientService.class));
        connectedSinceElapsed = 0;
    }

    @SuppressLint("BatteryLife")
    private void ensureBatteryOptimizationExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.w(TAG, "Battery optimization exemption request failed", e);
        }
    }

    // --- exports (behaviour preserved) ---

    private String lastMetricsFileName() {
        File csv = MetricsCsvWriter.fileFor(new File(getCacheDir(), "logs"), "client");
        return (csv.exists() && csv.length() > 0) ? csv.getName() : "";
    }

    private void exportLogs(String appName) {
        Toast.makeText(this, goodvin.locsync.logexporter.R.string.export_logs_in_progress, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File logFile = LogExporter.exportLogs(this, appName);
                LogExporter.cleanupOldLogs(this, appName);
                runOnUiThread(() -> {
                    if (logFile != null) {
                        shareFile(logFile, "text/plain", getString(goodvin.locsync.logexporter.R.string.share_logs));
                        Toast.makeText(this, goodvin.locsync.logexporter.R.string.export_logs_success, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, goodvin.locsync.logexporter.R.string.export_logs_no_logs, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error exporting logs", e);
                runOnUiThread(() -> Toast.makeText(this,
                        String.format(getString(goodvin.locsync.logexporter.R.string.export_logs_error), e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void shareMetricsCsv() {
        File csv = MetricsCsvWriter.fileFor(new File(getCacheDir(), "logs"), "client");
        if (!csv.exists() || csv.length() == 0) {
            Toast.makeText(this, R.string.metrics_export_none, Toast.LENGTH_SHORT).show();
            return;
        }
        shareFile(csv, "text/csv", getString(R.string.export_metrics));
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

    private void bindToggle(int rowId, String label, String sub, boolean checked,
                            java.util.function.Consumer<Boolean> onChange) {
        View row = findViewById(rowId);
        setText(row, R.id.row_label, label);
        TextView subView = row.findViewById(R.id.row_sub);
        if (sub != null) {
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        CompoundButton sw = row.findViewById(R.id.row_switch);
        sw.setChecked(checked);
        row.setOnClickListener(v -> {
            boolean next = !sw.isChecked();
            sw.setChecked(next);
            onChange.accept(next);
        });
    }

    /**
     * Numeric setting: saves every valid edit immediately (the service re-reads settings on each
     * fix); out-of-range or unparsable input is flagged and not saved.
     */
    private void bindNumberInput(int rowId, String label, double value, double min, double max,
                                 java.util.function.DoubleConsumer onChange) {
        View row = findViewById(rowId);
        setText(row, R.id.row_label, String.format(Locale.US, "%s (%s–%s)", label, fmtNum(min), fmtNum(max)));
        EditText input = row.findViewById(R.id.row_input);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(fmtNum(value));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                double v;
                try {
                    v = Double.parseDouble(s.toString().trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    v = Double.NaN;
                }
                if (Double.isNaN(v) || v < min || v > max) {
                    input.setError(String.format(Locale.US, "%s–%s", fmtNum(min), fmtNum(max)));
                } else {
                    input.setError(null);
                    onChange.accept(v);
                }
            }
        });
    }

    private static String fmtNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
    }

    private void bindAction(int rowId, String label, String sub, boolean chevron, Runnable click) {
        View row = findViewById(rowId);
        setText(row, R.id.row_label, label);
        TextView subView = row.findViewById(R.id.row_sub);
        if (sub != null && !sub.isEmpty()) {
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        if (chevron) row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
        if (click != null) row.setOnClickListener(v -> click.run());
    }

    private void bindActionChevron(int rowId, String label, String sub, Runnable click) {
        bindAction(rowId, label, sub, true, click);
    }

    private void bindActionButton(int rowId, String label, String sub, String buttonLabel, Runnable buttonClick) {
        View row = findViewById(rowId);
        if (!label.isEmpty()) setText(row, R.id.row_label, label);
        TextView subView = row.findViewById(R.id.row_sub);
        if (sub != null && !sub.isEmpty()) {
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        TextView button = row.findViewById(R.id.row_button);
        button.setText(buttonLabel);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> buttonClick.run());
    }
}
