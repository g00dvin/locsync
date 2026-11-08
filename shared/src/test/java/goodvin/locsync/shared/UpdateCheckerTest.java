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
    public void readsTheTagFromTheLatestRedirect() {
        assertEquals("v3.7.2", UpdateChecker.tagFromLocation("https://github.com/g00dvin/locsync/releases/tag/v3.7.2"));
        assertEquals("v4.0", UpdateChecker.tagFromLocation("https://github.com/g00dvin/locsync/releases/tag/v4.0"));
        assertNull(UpdateChecker.tagFromLocation("https://github.com/g00dvin/locsync/releases/tag/v4.0-rc1"));
        assertNull(UpdateChecker.tagFromLocation("https://github.com/g00dvin/locsync/releases/tag/../../x"));
        assertNull(UpdateChecker.tagFromLocation("https://github.com/g00dvin/locsync/releases"));
        assertNull(UpdateChecker.tagFromLocation(null));
    }

    // Trimmed from api.github.com/repos/g00dvin/locsync/releases/latest (2026-11-08).
    private static final String API_JSON = "{\"html_url\":\"https://github.com/g00dvin/locsync/releases/tag/v3.10.2\","
            + "\"tag_name\":\"v3.10.2\",\"name\":\"Release v3.10.2\",\"assets\":["
            + "{\"name\":\"locsync-client-v3.10.2.apk\",\"browser_download_url\":"
            + "\"https://github.com/g00dvin/locsync/releases/download/v3.10.2/locsync-client-v3.10.2.apk\"},"
            + "{\"name\":\"locsync-server-v3.10.2.apk\",\"browser_download_url\":"
            + "\"https://github.com/g00dvin/locsync/releases/download/v3.10.2/locsync-server-v3.10.2.apk\"}],\"body\":\"\"}";

    @Test
    public void readsTheReleaseFromTheApiFallback() {
        UpdateChecker.Release r = UpdateChecker.releaseFromApiJson(API_JSON, "locsync-server-");
        assertEquals("v3.10.2", r.tag());
        assertEquals(UpdateChecker.apkUrl("v3.10.2", "locsync-server-"), r.apkUrl());
        // Pretty-printed JSON reads the same.
        assertEquals("v3.10.2", UpdateChecker.releaseFromApiJson(
                API_JSON.replace("\"tag_name\":", "\"tag_name\": "), "locsync-client-").tag());
    }

    @Test
    public void apiFallbackNeedsThisAppsApkAndAPlainVersion() {
        assertNull(UpdateChecker.releaseFromApiJson(
                API_JSON.replace("\"name\":\"locsync-server-v3.10.2.apk\"", "\"name\":\"other.apk\""), "locsync-server-"));
        assertNull(UpdateChecker.releaseFromApiJson(API_JSON.replace("v3.10.2", "v4.0-rc1"), "locsync-server-"));
        assertNull(UpdateChecker.releaseFromApiJson("{\"message\":\"Not Found\"}", "locsync-server-"));
    }

    @Test
    public void buildsTheAssetUrl() {
        assertEquals("https://github.com/g00dvin/locsync/releases/download/v3.7.2/locsync-client-v3.7.2.apk",
                UpdateChecker.apkUrl("v3.7.2", "locsync-client-"));
    }
}
