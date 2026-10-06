package goodvin.locsync.client;

import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;

/**
 * Renders the real client screens with demo data into docs/screenshots for the README.
 * Opt-in (excluded from normal test runs):
 * ./gradlew :client-app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest'
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "ru-w1200dp-h1600dp-port-mdpi")
public class ScreenshotsTest {

    private ActivityController<MainActivity> controller;

    @After
    public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        setStatic(GNSSClientService.class, "instance", null);
    }

    @Test
    public void captureScreens() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
        AppOpsManager appOps = (AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(),
                app.getPackageName(), AppOpsManager.MODE_ALLOWED);
        Preferences.setServiceEnabled(app, false);   // don't start the real service
        Preferences.setLiveMonitoring(app, true);

        // A "running" service connected to the phone, without starting its sockets.
        GNSSClientService service = Robolectric.buildService(GNSSClientService.class).get();
        ConnectionManager cm = new ConnectionManager(app, (s, m, a) -> { });
        cm.setState(ConnectionManager.ConnectionState.CONNECTED, "", "192.168.43.1");
        setField(service, "connectionManager", cm);
        setStatic(GNSSClientService.class, "instance", service);

        goodvin.locsync.shared.AppLog.i("GNSSClientService", "Server found at 192.168.43.1");
        goodvin.locsync.shared.AppLog.i("GNSSClientService", "Mock location provider enabled");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        idle(Duration.ofMinutes(12).plusSeconds(34));   // "online 12:34"

        for (int i = 0; i < 40; i++) {                  // fill the Monitor sparklines
            feed(app, activity, i);
            idle(Duration.ofSeconds(1));
        }
        feed(app, activity, 40);
        idle(Duration.ofMillis(300));

        File dir = new File(System.getProperty("screenshots.dir", "build/screenshots"));
        dir.mkdirs();
        capture(activity, new File(dir, "client-home.png"), true);
        activity.findViewById(R.id.btnRight).performClick();
        idle(Duration.ofMillis(1100));   // the log renders on the next 1 s tick
        capture(activity, new File(dir, "client-monitor.png"), true);
        activity.findViewById(R.id.btnLeft).performClick();   // back to home
        idle(Duration.ofMillis(300));
        activity.findViewById(R.id.btnLeft).performClick();   // settings
        idle(Duration.ofMillis(300));
        capture(activity, new File(dir, "client-settings.png"), true);
    }

    private static void feed(Application app, MainActivity activity, int i) throws Exception {
        setStatic(GNSSClientService.class, "lastUpdateTime", System.currentTimeMillis());

        Location loc = new Location("fused");
        loc.setLatitude(55.751244 + i * 0.00012);
        loc.setLongitude(37.618423 + i * 0.00009);
        loc.setAltitude(156);
        loc.setAccuracy(3.8f);
        loc.setSpeed(16.4f + (float) Math.sin(i / 4.0));
        loc.setBearing(28.5f);
        app.sendBroadcast(new Intent("goodvin.locsync.LOCATION_UPDATE").setPackage(app.getPackageName())
                .putExtra("location", loc)
                .putExtra("satellites", 18 + (i / 7) % 3)
                .putExtra("provider", "fused")
                .putExtra("locationAge", 0.25f));

        app.sendBroadcast(new Intent("goodvin.locsync.METRICS").setPackage(app.getPackageName())
                .putExtra("pktSentPerSec", 0.0)
                .putExtra("pktRecvPerSec", 1.0)
                .putExtra("bytesSentPerSec", 0.0)
                .putExtra("bytesRecvPerSec", 96.0)
                .putExtra("maxGapMs", 1012.0)
                .putExtra("ageMeanMs", 70.0 + 25 * Math.abs(Math.sin(i / 3.0)))
                .putExtra("ageP95Ms", 140.0)
                .putExtra("cpuPct", 2.4)
                .putExtra("fixesPerSec", 1.0));

        int[] labels = {R.string.filter_out_accuracy, R.string.filter_out_speed_acc,
                R.string.filter_out_bearing_acc, R.string.filter_latency, R.string.filter_network_delay,
                R.string.filter_horizon, R.string.filter_turn_rate, R.string.filter_motion,
                R.string.filter_nis_avg, R.string.filter_outliers};
        String[] values = {"±2.9 m", "±0.21 m/s", "±1.4°", "310 ms", "42 ms", "180 ms", "+0.8°/s",
                activity.getString(R.string.filter_motion_moving), "1.9", "0"};
        String[] labelText = new String[labels.length];
        for (int k = 0; k < labels.length; k++) labelText[k] = activity.getString(labels[k]);
        app.sendBroadcast(new Intent("goodvin.locsync.FILTER_STATS").setPackage(app.getPackageName())
                .putExtra("labels", labelText)
                .putExtra("values", values));
    }

    private static void idle(Duration d) {
        shadowOf(Looper.getMainLooper()).idleFor(d);
    }

    /** Draws the window; {@code fullScroll} renders the whole visible ScrollView content, not just the viewport. */
    private static void capture(MainActivity activity, File out, boolean fullScroll) throws Exception {
        View root = activity.getWindow().getDecorView();
        int w = root.getWidth(), h = root.getHeight();
        int extra = 0;
        ScrollView scroll = fullScroll ? findVisibleScroll(root) : null;
        if (fullScroll) {
            if (scroll != null) {
                extra = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
            } else {
                // Non-scrolling page (home): grow the window until its content fits.
                android.widget.ViewFlipper flipper = activity.findViewById(R.id.viewFlipper);
                View page = flipper.getCurrentView();
                page.measure(View.MeasureSpec.makeMeasureSpec(page.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                extra = Math.max(0, page.getMeasuredHeight() - flipper.getHeight());
            }
            if (extra > 0) {
                int newH = h + extra;
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(newH, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, w, newH);
                h = newH;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        System.out.println("Saved " + out + " (" + w + "x" + h + ")");
    }

    private static ScrollView findVisibleScroll(View v) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof ScrollView && v.isShown() && v.getHeight() > 0) return (ScrollView) v;
        if (v instanceof android.view.ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = findVisibleScroll(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setStatic(Class<?> cls, String name, Object value) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
