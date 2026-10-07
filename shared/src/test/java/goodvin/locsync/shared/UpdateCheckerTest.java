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

    @Test
    public void buildsTheAssetUrl() {
        assertEquals("https://github.com/g00dvin/locsync/releases/download/v3.7.2/locsync-client-v3.7.2.apk",
                UpdateChecker.apkUrl("v3.7.2", "locsync-client-"));
    }
}
