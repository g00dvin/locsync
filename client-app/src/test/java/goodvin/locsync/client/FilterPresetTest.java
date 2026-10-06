package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FilterPresetTest {
    @Test
    public void defaultsAreBalanced() {
        // Must match the Preferences defaults, so a fresh install shows "Balanced".
        assertEquals(FilterPreset.BALANCED, FilterPreset.match(2.0f, 0.85f, 9.21f, 0.5f));
    }

    @Test
    public void eachPresetMatchesItself() {
        for (FilterPreset p : FilterPreset.values()) {
            assertEquals(p, FilterPreset.match(p.processNoise, p.turnResponsiveness, p.gateThreshold, p.standstillSpeed));
        }
    }

    @Test
    public void handTunedIsCustom() {
        assertNull(FilterPreset.match(2.0f, 0.85f, 9.21f, 0.6f));
    }

    @Test
    public void cycleWrapsAndCustomGoesToBalanced() {
        assertEquals(FilterPreset.BALANCED, FilterPreset.next(FilterPreset.SMOOTH));
        assertEquals(FilterPreset.RESPONSIVE, FilterPreset.next(FilterPreset.BALANCED));
        assertEquals(FilterPreset.SMOOTH, FilterPreset.next(FilterPreset.RESPONSIVE));
        assertEquals(FilterPreset.BALANCED, FilterPreset.next(null));
    }
}
