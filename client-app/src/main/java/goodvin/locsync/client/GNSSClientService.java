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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import goodvin.locsync.shared.AppLog;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import goodvin.locsync.proto.LocationProto;
import goodvin.locsync.shared.AndroidSystemStatsReader;
import goodvin.locsync.shared.Metrics;
import goodvin.locsync.shared.MetricsCsvWriter;
import goodvin.locsync.shared.MetricsSnapshot;
import goodvin.locsync.shared.Protocol;
import goodvin.locsync.shared.SystemStatsReader;

public class GNSSClientService extends Service implements ConnectionManager.ConnectionListener {
    private static final String TAG = "GNSSClientService";
    private static final String CHANNEL_ID = "GNSSClientChannel";
    private static final int NOTIFICATION_ID = 1;

    private static GNSSClientService instance = null;

    private ConnectionManager connectionManager;
    private MockLocationManager mockLocationManager;
    private NotificationManager notificationManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Location lastReceivedLocation;
    private static long lastUpdateTime;
    private int lastBroadcastSatelliteCount = -1;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastResponseTime = 0;

    private final LocationKalmanFilter kalman = new LocationKalmanFilter(2.0, 1.0);
    private static final long OUTPUT_INTERVAL_MS = 100;   // 10 Hz
    private static final long GPS_LOSS_CAP_MS = 2500;
    private volatile long lastFixElapsedMs = 0;           // SystemClock.elapsedRealtime of last real fix
    // Head-unit elapsedRealtime the filter state refers to: the fix time, i.e. arrival minus the
    // fix's age when latency compensation is on. Output ticks extrapolate from here to "now".
    private long stateElapsedMs = 0;
    private static final long MAX_LATENCY_MS = 3000;        // ignore absurd ages (clock glitches)
    private static final double MAX_HORIZON_S = 3.0;        // never extrapolate further than this
    private long lastFedFixTimestampMs = Long.MIN_VALUE;  // LocationUpdate.timestamp last fed to the filter (dedup keepalive resends)
    private volatile double lastAltitude = 0;
    private FilterConfig filterConfig = new FilterConfig();  // re-read from Preferences on every fix
    // Last injected output, for the Monitor screen's filter card (NaN = nothing injected yet).
    private double outAccuracy = Double.NaN, outSpeedAcc = Double.NaN, outBearingAcc = Double.NaN;
    private double lastLatencyMs = Double.NaN, lastHorizonMs = Double.NaN;
    // Monitor-only diagnostics, independent of what the "Honest accuracy" setting injects: the
    // filter's own speed/bearing accuracy, and what the phone reported for the last fix.
    private double filtSpeedAcc = Double.NaN, filtBearingAcc = Double.NaN;
    private double inSpeedAcc = Double.NaN, inBearingAcc = Double.NaN;
    private TrackRecorder trackRecorder;    // non-null while "Record track" is on
    private final Runnable smoothingTick = this::smoothingTick;

    private volatile DatagramSocket udpSocket;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Metrics metrics = new Metrics();
    private SystemStatsReader statsReader;
    private MetricsCsvWriter csvWriter;
    private volatile boolean metricsPrimed = false;
    private long lastTickWallMs = 0;
    private static final long METRICS_INTERVAL_MS = 1000;
    private static final String METRICS_TAG = "METRICS";
    private final Runnable metricsTick = this::sampleMetrics;
    private final java.text.SimpleDateFormat metricsTs =
            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
    private static final long HELLO_INTERVAL_MS = 1000;
    private static final long CONNECTED_TIMEOUT_MS = 3000;
    private static final String BROADCAST_ADDR = "255.255.255.255";
    private final Runnable helloTick = this::sendHelloTick;

    private static final String WIDGET_SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    private static final String WIDGET_PACKAGE = "dezz.status.widget";

    public static boolean isServiceEnabled(Context context) {
        return Preferences.serviceEnabled(context);
    }

    public static boolean isServiceRunning() {
        return instance != null;
    }

    public static ConnectionManager.ConnectionState getConnectionState() {
        return instance != null && instance.connectionManager != null ? instance.connectionManager.getCurrentState() : ConnectionManager.ConnectionState.DISCONNECTED;
    }

    public static String getServerAddress() {
        return instance != null && instance.connectionManager != null ? instance.connectionManager.getServerAddress() : null;
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            startTransport();
        }

        @Override
        public void onLost(@NonNull Network network) {
            stopTransport("WiFi disconnected");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        AppLog.setDebug(Preferences.debugLoggingEnabled(this));
        notificationManager = getSystemService(NotificationManager.class);
        mockLocationManager = new MockLocationManager(this);
        connectionManager = new ConnectionManager(this, this);

        registerWiFiStateReceiver();
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, createNotification(false));
        }

        instance = this;
    }

    private void registerWiFiStateReceiver() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;

        super.onDestroy();

        stopTransport("Service destroyed");
        executor.shutdown();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    // ConnectionManager.ConnectionListener implementation
    @Override
    public void onConnectionStateChanged(ConnectionManager.ConnectionState state, String message, String serverAddress) {
        AppLog.d(TAG, "Connection state: " + state + " - " + message);

        updateNotification();

        // Notify activity about connection status change
        sendBroadcast(new Intent("goodvin.locsync.CONNECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("state", state.toString())
                .putExtra("serverAddress", serverAddress));
    }

    private void startTransport() {
        if (running.getAndSet(true)) {
            return;
        }

        DatagramSocket sock;
        try {
            sock = new DatagramSocket();
        } catch (IOException e) {
            Log.e(TAG, "Failed to open UDP socket", e);
            running.set(false);   // allow a later onAvailable() to retry
            return;
        }
        udpSocket = sock;
        try {
            sock.setBroadcast(true);
        } catch (SocketException e) {
            Log.w(TAG, "Failed to enable broadcast on socket", e);
        }
        if (!running.get()) {
            // stopTransport() raced in during socket open — close and bail
            AppLog.i(TAG, "Transport stopped during socket open; closing");
            sock.close();
            udpSocket = null;
            return;
        }

        String server = connectionManager.getSendTarget();
        connectionManager.setState(ConnectionManager.ConnectionState.CONNECTING, "Connecting to server...", server);

        if (!MockLocationManager.isMockLocationEnabled(getContentResolver())) {
            Log.w(TAG, "Mock locations not enabled - please enable in Developer Options");
            broadcastMockLocationStatus(getString(R.string.mock_location_enable_message), true);
        }
        try {
            mockLocationManager.startMockLocationProvider();
        } catch (SecurityException e) {
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }

        executor.execute(this::receiveLoop);
        mainHandler.post(helloTick);

        stateElapsedMs = 0;
        filterConfig = Preferences.filterConfig(this);
        mainHandler.post(smoothingTick);

        lastTickWallMs = 0;
        mainHandler.post(this::startMetricsSampler);
    }

    private void stopTransport(String reason) {
        if (!running.getAndSet(false)) {
            return;
        }
        AppLog.i(TAG, "Stopping transport: " + reason);
        mainHandler.removeCallbacks(helloTick);
        mainHandler.removeCallbacks(smoothingTick);
        mainHandler.removeCallbacks(metricsTick);
        metricsPrimed = false;
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
        lastFixElapsedMs = 0;
        stateElapsedMs = 0;
        kalman.reset();
        outAccuracy = outSpeedAcc = outBearingAcc = Double.NaN;
        filtSpeedAcc = filtBearingAcc = inSpeedAcc = inBearingAcc = Double.NaN;
        if (trackRecorder != null) {
            trackRecorder.close();
            trackRecorder = null;
        }
        lastLatencyMs = lastHorizonMs = Double.NaN;
        connectionManager.clearLearnedServerAddress();
        lastBroadcastSatelliteCount = -1;
        broadcastSatelliteStatusToWidget(0);

        if (instance == null) {
            mockLocationManager.shutdown();
        } else {
            mockLocationManager.stopMockLocationProvider(5000);
        }

        connectionManager.setState(ConnectionManager.ConnectionState.DISCONNECTED, reason, null);
        sendBroadcast(new Intent("goodvin.locsync.CONNECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("state", ConnectionManager.ConnectionState.DISCONNECTED.toString()));
    }

    private void sendHelloTick() {
        if (!running.get()) {
            return;
        }
        String target = connectionManager.getSendTarget(); // null in Auto mode until discovered
        final DatagramSocket sock = udpSocket;
        if (sock != null) {
            final String dest = (target != null) ? target : BROADCAST_ADDR;
            executor.execute(() -> {
                try {
                    byte[] hello = Protocol.buildPacket(Protocol.TYPE_HELLO, null);
                    sock.send(new DatagramPacket(hello, hello.length,
                            InetAddress.getByName(dest), Protocol.PORT));
                } catch (IOException e) {
                    Log.w(TAG, "Failed to send HELLO to " + dest, e);
                }
            });
        }
        // Recency check: drop to CONNECTING if no RESPONSE within the window; in Auto mode also
        // forget the learned server so the next ticks broadcast to re-discover.
        if (connectionManager.isConnected()
                && System.currentTimeMillis() - lastResponseTime > CONNECTED_TIMEOUT_MS) {
            connectionManager.setState(ConnectionManager.ConnectionState.CONNECTING,
                    "Waiting for server...", connectionManager.getServerAddress());
            if (connectionManager.isAutoDiscover()) {
                connectionManager.clearLearnedServerAddress();
            }
        }
        mainHandler.postDelayed(helloTick, HELLO_INTERVAL_MS);
    }

    private void receiveLoop() {
        DatagramSocket sock = udpSocket;
        if (sock == null) {
            return;
        }
        byte[] buffer = new byte[Protocol.MAX_PACKET_BYTES];
        while (running.get() && !sock.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                sock.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (running.get() && !sock.isClosed()) {
                    AppLog.v(TAG, "UDP receive error: " + e.getMessage());
                    try {
                        Thread.sleep(200);   // avoid hot-spin on repeated errors
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        Protocol.Header header;
        try {
            header = Protocol.parse(packet.getData(), packet.getLength());
        } catch (IllegalArgumentException e) {
            return;
        }

        if (header.type == Protocol.TYPE_VERSION_MISMATCH
                || !Protocol.isSupportedVersion(header.version)) {
            broadcastMockLocationStatus(getString(R.string.version_mismatch), true);
            return;
        }
        if (header.type != Protocol.TYPE_RESPONSE) {
            return;
        }
        metrics.recordPacketRecv(packet.getLength(), SystemClock.elapsedRealtime());

        try {
            LocationProto.ServerResponse response = LocationProto.ServerResponse.parseFrom(
                    java.util.Arrays.copyOfRange(packet.getData(),
                            header.payloadOffset, header.payloadOffset + header.payloadLength));

            lastResponseTime = System.currentTimeMillis();
            String srcAddr = packet.getAddress().getHostAddress();
            if (connectionManager.isAutoDiscover()) {
                connectionManager.setLearnedServerAddress(srcAddr);
            }
            if (!connectionManager.isConnected()) {
                connectionManager.setState(ConnectionManager.ConnectionState.CONNECTED,
                        "Receiving from server", srcAddr);
            }

            if (response.hasLocationUpdate()) {
                handleLocationUpdate(response);
            } else {
                AppLog.i(TAG, "Server status: " + response.getStatus());
                Intent intent = new Intent("goodvin.locsync.LOCATION_UPDATE");
                intent.setPackage(getPackageName());
                intent.putExtra("satellites", response.getSatellites());
                sendBroadcast(intent);
            }
            broadcastSatelliteStatusToWidget(response.getSatellites());
        } catch (IOException e) {
            Log.w(TAG, "Failed to parse ServerResponse", e);
        }
    }

    private void handleLocationUpdate(LocationProto.ServerResponse response) {
        try {
            LocationProto.LocationUpdate locationUpdate = response.getLocationUpdate();
            metrics.recordFixAgeMs(locationUpdate.getLocationAge() * 1000.0);
            // Create Android Location object
            Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(locationUpdate.getLatitude());
            location.setLongitude(locationUpdate.getLongitude());
            location.setTime(locationUpdate.getTimestamp());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            location.setAltitude(locationUpdate.getAltitude());
            location.setAccuracy(locationUpdate.getAccuracy());
            location.setBearing(locationUpdate.getBearing());
            location.setSpeed(locationUpdate.getSpeed());

            AppLog.i(TAG, "Received location update: " + location);

            // Update internal state
            lastReceivedLocation = location;
            lastUpdateTime = System.currentTimeMillis();

            // Update notification with new location data
            updateNotification();

            // Broadcast location update to activity
            Intent intent = new Intent("goodvin.locsync.LOCATION_UPDATE");
            intent.putExtra("location", location);
            intent.putExtra("satellites", response.getSatellites());
            intent.putExtra("provider", locationUpdate.getProvider());
            intent.putExtra("locationAge", locationUpdate.getLocationAge());
            intent.setPackage(getPackageName());
            sendBroadcast(intent);

            // Feed the fix into the Kalman filter on the main thread; the smoothing loop
            // (also main-thread) reads/predicts the same filter, so all access is confined
            // to one thread and no synchronization is needed.
            final double lat = locationUpdate.getLatitude();
            final double lon = locationUpdate.getLongitude();
            final float spd = locationUpdate.getSpeed();
            final float brg = locationUpdate.getBearing();
            final float acc = locationUpdate.getAccuracy();
            final float spdAcc = locationUpdate.getSpeedAccuracy();
            final float brgAcc = locationUpdate.getBearingAccuracy();
            final boolean hasSpdAcc = locationUpdate.hasSpeedAccuracy();
            final boolean hasBrgAcc = locationUpdate.hasBearingAccuracy();
            final boolean hasSpd = locationUpdate.hasSpeed();
            final boolean hasBrg = locationUpdate.hasBearing();
            final long fixTs = locationUpdate.getTimestamp();
            final double alt = locationUpdate.getAltitude();
            final float ageS = locationUpdate.getLocationAge();
            final String provider = locationUpdate.getProvider();
            final int sats = response.getSatellites();
            mainHandler.post(() -> {
                try {
                    // Skip keepalive resends of a fix already fed to the filter. The server re-sends the
                    // last response ~1 Hz to keep the connection live; re-feeding that same stale fix (and,
                    // in a tunnel, its phantom speed=0) is what pinned velocity and froze the icon.
                    if (fixTs == lastFedFixTimestampMs) {
                        return;
                    }
                    long prevFixTs = lastFedFixTimestampMs;
                    lastFedFixTimestampMs = fixTs;
                    metrics.recordFix();
                    inSpeedAcc = hasSpdAcc ? spdAcc : Double.NaN;
                    inBearingAcc = hasBrgAcc ? brgAcc : Double.NaN;
                    filterConfig = Preferences.filterConfig(this);
                    kalman.setTurnModel(filterConfig.turnModel);
                    kalman.setGating(filterConfig.gating, filterConfig.gateThreshold);
                    kalman.setStandstill(filterConfig.standstillHold, filterConfig.standstillSpeed);
                    kalman.setProcessNoise(filterConfig.processNoise, filterConfig.adaptiveNoise);

                    long nowElapsed = SystemClock.elapsedRealtime();
                    // When the fix was taken, on our clock: it is already ageS old on arrival (phone-side
                    // fix age), plus any user-configured extra latency (provider/transport delay).
                    long latencyMs = 0;
                    if (filterConfig.latencyCompensation) {
                        latencyMs = Math.round(Math.max(0, ageS) * 1000 + filterConfig.extraLatencyMs);
                        latencyMs = Math.max(0, Math.min(latencyMs, MAX_LATENCY_MS));
                    }
                    long measElapsed = nowElapsed - latencyMs;
                    lastLatencyMs = latencyMs;

                    boolean resumingAfterGap =
                            lastFixElapsedMs == 0 || (nowElapsed - lastFixElapsedMs) > GPS_LOSS_CAP_MS;
                    if (resumingAfterGap) {
                        // Fresh start, or the first real fix after a GPS gap (e.g. tunnel exit): re-anchor so
                        // the estimate snaps to the new position instead of lurching from stale state.
                        kalman.reset();
                    } else if (kalman.isInitialized() && stateElapsedMs > 0) {
                        // Step the state from the previous fix time to this one. The phone's fix timestamps
                        // give the exact interval; fall back to our clock if they look wrong. Cap dt so a
                        // long GPS gap can't feed a huge predict step (bounds process-noise growth).
                        double dt = (fixTs - prevFixTs) / 1000.0;
                        if (!(dt > 0 && dt <= 5.0)) {
                            dt = (measElapsed - stateElapsedMs) / 1000.0;
                        }
                        kalman.predict(Math.max(0, Math.min(dt, 5.0)));
                    }
                    kalman.update(lat, lon, spd, brg, acc, spdAcc, brgAcc, hasSpd, hasBrg);
                    lastAltitude = alt;
                    lastFixElapsedMs = nowElapsed;
                    stateElapsedMs = measElapsed;

                    updateTrackRecorder();
                    if (trackRecorder != null) {
                        trackRecorder.fix(nowElapsed, fixTs, provider, sats, lat, lon, alt, acc, hasSpd, spd, hasBrg, brg,
                                spdAcc, brgAcc, ageS, latencyMs, kalman);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating Kalman filter", e);
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }
    }

    private void smoothingTick() {
        long tickWall = SystemClock.elapsedRealtime();
        if (lastTickWallMs != 0) {
            metrics.recordTickJitterMs(Math.abs((tickWall - lastTickWallMs) - OUTPUT_INTERVAL_MS));
        }
        lastTickWallMs = tickWall;
        if (!running.get()) {
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean gpsLost = lastFixElapsedMs == 0 || (nowElapsed - lastFixElapsedMs) > GPS_LOSS_CAP_MS;

        if (kalman.isInitialized() && !gpsLost) {
            // Project the fix-time state to now (covers both the time since the last fix and, with
            // latency compensation, the fix's own age) without disturbing the filter.
            double horizonS = Math.max(0, Math.min((nowElapsed - stateElapsedMs) / 1000.0, MAX_HORIZON_S));
            lastHorizonMs = horizonS * 1000.0;
            injectSmoothed(kalman.extrapolate(horizonS));
        }
        // When GPS is lost, we simply stop advancing/injecting (freeze) until fixes resume.

        mainHandler.postDelayed(smoothingTick, OUTPUT_INTERVAL_MS);
    }

    private void injectSmoothed(LocationKalmanFilter.Estimate est) {
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(est.latitude);
        loc.setLongitude(est.longitude);
        loc.setAltitude(lastAltitude);
        double speed = est.speed;
        boolean moving = speed >= filterConfig.standstillSpeed;
        filtSpeedAcc = est.speedAccuracy;
        filtBearingAcc = moving ? est.bearingAccuracyDeg : Double.NaN;
        if (filterConfig.reportUncertainty) {
            outAccuracy = est.accuracy68;
            outSpeedAcc = est.speedAccuracy;
            outBearingAcc = moving ? est.bearingAccuracyDeg : Double.NaN;
        } else {
            outAccuracy = est.accuracy;
            outSpeedAcc = Double.NaN;
            outBearingAcc = Double.NaN;
        }
        loc.setAccuracy((float) outAccuracy);
        if (moving) {
            loc.setSpeed((float) speed);
            loc.setBearing((float) est.bearingDeg);
            if (filterConfig.reportUncertainty) {
                loc.setSpeedAccuracyMetersPerSecond((float) outSpeedAcc);
                loc.setBearingAccuracyDegrees((float) outBearingAcc);
            }
        }
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        lastReceivedLocation = loc;
        lastUpdateTime = System.currentTimeMillis();
        try {
            mockLocationManager.setMockLocation(loc);
            if (trackRecorder != null) {
                // after setMockLocation: records what was actually injected (incl. static jitter)
                trackRecorder.out(SystemClock.elapsedRealtime(), loc.getLatitude(), loc.getLongitude(), moving, loc.getSpeed(), loc.getBearing(),
                        outAccuracy, outSpeedAcc, outBearingAcc, lastHorizonMs);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }
    }

    private void startMetricsSampler() {
        if (statsReader == null) statsReader = new AndroidSystemStatsReader(this);
        if (csvWriter == null) {
            csvWriter = new MetricsCsvWriter(new java.io.File(getCacheDir(), "logs"), "client", 5_000_000);
        }
        mainHandler.removeCallbacks(metricsTick);
        metricsPrimed = false;
        mainHandler.postDelayed(metricsTick, METRICS_INTERVAL_MS);
    }

    private void sampleMetrics() {
        try {
            // Compute + broadcast the snapshot every tick so the Monitor screen always shows live
            // link-health data; the metrics toggle only gates persistence (CSV + logcat).
            {
                MetricsSnapshot s = metrics.snapshot(statsReader.read());
                if (!metricsPrimed) {
                    metricsPrimed = true;
                } else {
                    String ts = metricsTs.format(new java.util.Date());
                    long uptimeS = SystemClock.elapsedRealtime() / 1000;
                    if (Preferences.metricsEnabled(this)) {
                        csvWriter.append(MetricsSnapshot.csvHeader(), s.toCsvRow(ts, uptimeS));
                        Log.i(METRICS_TAG, s.toLogLine());
                    }
                    sendBroadcast(new Intent("goodvin.locsync.METRICS")
                            .setPackage(getPackageName())
                            .putExtra("text", s.toDisplayString())
                            .putExtra("pktSentPerSec", s.pktSentPerSec)
                            .putExtra("pktRecvPerSec", s.pktRecvPerSec)
                            .putExtra("bytesSentPerSec", s.bytesSentPerSec)
                            .putExtra("bytesRecvPerSec", s.bytesRecvPerSec)
                            .putExtra("maxGapMs", s.maxGapMs)
                            .putExtra("ageMeanMs", s.ageMeanMs)
                            .putExtra("ageP95Ms", s.ageP95Ms)
                            .putExtra("cpuPct", s.cpuPct)
                            .putExtra("fixesPerSec", s.fixesPerSec));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "metrics sampling failed", e);
        }
        if (trackRecorder != null) {
            trackRecorder.flush();
        }
        broadcastFilterStats();
        mainHandler.postDelayed(metricsTick, METRICS_INTERVAL_MS);
    }

    /** Opens/closes the track file to follow the "Record track" setting (checked on every fix). */
    private void updateTrackRecorder() {
        boolean wanted = Preferences.trackRecording(this);
        if (wanted && trackRecorder == null) {
            trackRecorder = new TrackRecorder(new java.io.File(getCacheDir(), "logs"));
            AppLog.i(TAG, "Track recording started");
        } else if (!wanted && trackRecorder != null) {
            trackRecorder.close();
            trackRecorder = null;
            AppLog.i(TAG, "Track recording stopped");
        }
    }

    /**
     * Sends the filter's live state to the Monitor screen as ready-to-show label/value rows, so new
     * diagnostics only need to be added here.
     */
    private void broadcastFilterStats() {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        addStat(labels, values, R.string.filter_out_accuracy, outAccuracy, "±%.1f m");
        labels.add(getString(R.string.filter_accuracy_mode));
        values.add(getString(filterConfig.reportUncertainty
                ? R.string.filter_accuracy_mode_68 : R.string.filter_accuracy_mode_legacy));
        addStat(labels, values, R.string.filter_out_speed_acc, filtSpeedAcc, "±%.2f m/s");
        addStat(labels, values, R.string.filter_out_bearing_acc, filtBearingAcc, "±%.1f°");
        addStat(labels, values, R.string.filter_in_speed_acc, inSpeedAcc, "±%.2f m/s");
        addStat(labels, values, R.string.filter_in_bearing_acc, inBearingAcc, "±%.1f°");
        addStat(labels, values, R.string.filter_latency, lastLatencyMs, "%.0f ms");
        addStat(labels, values, R.string.filter_horizon, lastHorizonMs, "%.0f ms");
        addStat(labels, values, R.string.filter_turn_rate,
                kalman.isInitialized() && filterConfig.turnModel ? kalman.getTurnRateDegPerSec() : Double.NaN,
                "%+.1f°/s");
        labels.add(getString(R.string.filter_motion));
        values.add(!kalman.isInitialized() ? "—" : getString(kalman.isStationary()
                ? R.string.filter_motion_stopped : R.string.filter_motion_moving));
        addStat(labels, values, R.string.filter_nis, kalman.getLastNis(), "%.1f");
        addStat(labels, values, R.string.filter_nis_avg,
                kalman.isInitialized() ? kalman.getNisAverage() : Double.NaN, "%.1f");
        addStat(labels, values, R.string.filter_process_noise,
                kalman.isInitialized() ? kalman.getProcessNoise() : Double.NaN, "%.2f m/s²");
        addStat(labels, values, R.string.filter_outliers, kalman.getOutlierCount(), "%.0f");
        addStat(labels, values, R.string.filter_reinits, kalman.getReinitCount(), "%.0f");
        sendBroadcast(new Intent("goodvin.locsync.FILTER_STATS")
                .setPackage(getPackageName())
                .putExtra("labels", labels.toArray(new String[0]))
                .putExtra("values", values.toArray(new String[0])));
    }

    private void addStat(java.util.List<String> labels, java.util.List<String> values,
                         int labelRes, double v, String format) {
        labels.add(getString(labelRes));
        values.add(Double.isNaN(v) ? "—" : String.format(java.util.Locale.US, format, v));
    }

    private void broadcastMockLocationStatus(String message, boolean error) {
        Intent intent = new Intent("goodvin.locsync.MOCK_LOCATION_STATUS");
        intent.setPackage(getPackageName());
        intent.putExtra("message", message);
        intent.putExtra("error", error);
        sendBroadcast(intent);
    }

    /**
     * Broadcasts the satellite count to the external widget app, but only when the count changes.
     * Uses an explicit package target — required for receivers registered with RECEIVER_NOT_EXPORTED.
     */
    private void broadcastSatelliteStatusToWidget(int count) {
        if (count == lastBroadcastSatelliteCount) {
            return;
        }
        lastBroadcastSatelliteCount = count;
        Intent intent = new Intent(WIDGET_SATELLITE_STATUS_ACTION);
        intent.setPackage(WIDGET_PACKAGE);
        intent.putExtra("count", count);
        sendBroadcast(intent);
    }

    public static long getLastUpdateTime() {
        return lastUpdateTime;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(String.format(getString(R.string.notification_channel_description), getString(R.string.app_name)));

        notificationManager.createNotificationChannel(channel);
    }

    private Notification createNotification(boolean isConnected) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isConnected ?
                String.format(getString(R.string.notification_title_connected), getString(R.string.app_name)) :
                String.format(getString(R.string.notification_title_disconnected), getString(R.string.app_name));

        String text = isConnected ?
                (lastReceivedLocation != null ?
                        String.format(getString(R.string.notification_text_connected),
                                (System.currentTimeMillis() - lastUpdateTime) / 1000.0) :
                        getString(R.string.notification_text_connected_no_age)) :
                getString(R.string.notification_text_disconnected);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        boolean isConnected = connectionManager != null && connectionManager.isConnected();

        notificationManager.notify(NOTIFICATION_ID, createNotification(isConnected));
    }
}
