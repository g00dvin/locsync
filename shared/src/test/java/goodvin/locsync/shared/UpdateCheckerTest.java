package goodvin.locsync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckerTest {
    @Test
    public void comparesVersions() {
        assertTrue(UpdateChecker.isNewer("v3.7.0", "v3.6.2"));
        assertTrue(UpdateChecker.isNewer("v3.10.0", "v3.9.9"));
        assertTrue(UpdateChecker.isNewer("v4.0", "v3.9.9"));
        assertFalse(UpdateChecker.isNewer("v3.6.2", "v3.6.2"));
        assertFalse(UpdateChecker.isNewer("v3.6.1", "v3.6.2"));
    }

    @Test
    public void nonReleaseVersionsNeverUpdate() {
        assertFalse(UpdateChecker.isNewer("v3.7.0", "main-abc1234"));
        assertFalse(UpdateChecker.isNewer("nightly", "v3.6.2"));
        assertNull(UpdateChecker.parseVersion("v3.7.0-beta"));
    }

    @Test
    public void picksThisAppsReleaseApk() throws Exception {
        String json = "{\"tag_name\":\"v3.7.0\",\"assets\":["
                + "{\"name\":\"locsync-server-v3.7.0.apk\",\"browser_download_url\":\"https://x/server.apk\"},"
                + "{\"name\":\"locsync-client-v3.7.0-debug.apk\",\"browser_download_url\":\"https://x/debug.apk\"},"
                + "{\"name\":\"locsync-client-v3.7.0.apk\",\"browser_download_url\":\"https://x/client.apk\"}]}";
        UpdateChecker.Release r = UpdateChecker.parseRelease(json, "locsync-client-");
        assertEquals("v3.7.0", r.tag());
        assertEquals("https://x/client.apk", r.apkUrl());
        assertNull(UpdateChecker.parseRelease("{\"tag_name\":\"v3.7.0\",\"assets\":[]}", "locsync-client-"));
    }
}
