package goodvin.locsync.server;

import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.GnssStatus;
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
import java.net.InetSocketAddress;
import java.time.Duration;

import goodvin.locsync.proto.LocationProto;

/**
 * Renders the real server screens with demo data into docs/screenshots for the README.
 * Opt-in (excluded from normal test runs):
 * ./gradlew :server-app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest'
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "ru-w400dp-h860dp-port-xhdpi")
public class ScreenshotsTest {

    private ActivityController<MainActivity> controller;

    @After
    public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        setStatic(GNSSServerService.class, "instance", null);
        setStatic(GNSSServerService.class, "running", false);
    }

    @Test
    public void captureScreens() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS);
        GNSSServerService.setServiceEnabled(app, false);   // don't start the real service
        Preferences.setLiveMonitoring(app, true);

        // A running service with a connected head unit and a fix, without starting its socket or GPS.
        GNSSServerService service = Robolectric.buildService(GNSSServerService.class).get();
        GnssStatus.Builder sats = new GnssStatus.Builder();
        float[] cn0 = {44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 31, 30, 28, 27, 26, 25};
        for (int i = 0; i < 32; i++) {
            boolean used = i < cn0.length;
            sats.addSatellite(i % 2 == 0 ? GnssStatus.CONSTELLATION_GPS : GnssStatus.CONSTELLATION_GLONASS,
                    i + 1, used ? cn0[i] : 18, 30, 90, true, true, used, false, 0, false, 0);
        }
        setField(service, "gnssStatus", sats.build());
        setField(service, "satellitesUsed", cn0.length);
        setField(service, "isGnssActive", true);
        setField(service, "clientAddr", new InetSocketAddress("192.168.43.120", 8887));
        LocationProto.ServerResponse.Builder resp = (LocationProto.ServerResponse.Builder) getField(service, "lastServerResponse");
        resp.setStatus(LocationProto.Status.TRANSMITTING_LOCATION).setSatellites(cn0.length)
                .setLocationUpdate(LocationProto.LocationUpdate.newBuilder()
                        .setTimestamp(System.currentTimeMillis()).setLatitude(55.756044).setLongitude(37.622023)
                        .setAltitude(156).setAccuracy(3.8f).setSpeed(15.9f).setBearing(28.5f)
                        .setProvider("fused").setLocationAge(0.12f));
        setStatic(GNSSServerService.class, "instance", service);
        setStatic(GNSSServerService.class, "running", true);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        idle(Duration.ofMinutes(12).plusSeconds(34));

        goodvin.locsync.shared.AppLog.i("GNSSServerService", "Location updates started: fused, interval 1000 ms");
        goodvin.locsync.shared.AppLog.i("GNSSServerService", "New client connected: /192.168.43.120:8887");
        for (int i = 0; i < 40; i++) {                  // fill the Monitor sparklines
            setField(service, "satellitesUsed", cn0.length - 2 + (i / 7) % 3);
            feed(app, i);
            idle(Duration.ofSeconds(1));
        }

        setField(service, "satellitesUsed", cn0.length);
        File dir = new File(System.getProperty("screenshots.dir", "build/screenshots"));
        dir.mkdirs();
        capture(activity, new File(dir, "server-home.png"), true);
        activity.findViewById(R.id.btnRight).performClick();
        idle(Duration.ofMillis(1100));   // the log renders on the next 1 s tick
        capture(activity, new File(dir, "server-monitor.png"), true);
        activity.findViewById(R.id.btnLeft).performClick();   // back to home
        idle(Duration.ofMillis(300));
        activity.findViewById(R.id.btnLeft).performClick();   // settings
        idle(Duration.ofMillis(300));
        capture(activity, new File(dir, "server-settings.png"), true);
    }

    private static void feed(Application app, int i) {
        app.sendBroadcast(new Intent("goodvin.locsync.METRICS").setPackage(app.getPackageName())
                .putExtra("pktSentPerSec", 1.0)
                .putExtra("bytesSentPerSec", 96.0)
                .putExtra("maxGapMs", 1004.0)
                .putExtra("ageMeanMs", 60.0 + 30 * Math.abs(Math.sin(i / 3.0)))
                .putExtra("ageP95Ms", 120.0)
                .putExtra("cpuPct", 1.8)
                .putExtra("fixesPerSec", 1.0));
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

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
