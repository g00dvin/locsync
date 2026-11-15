package goodvin.locsync.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FixSourceTest {
    @Test
    public void gpsFillsInWhileFusedIsQuietOrCoarse() {
        FixSource s = new FixSource();
        s.reset(0);
        assertFalse(s.fusedStalled(3000));
        assertTrue(s.fusedStalled(3001));                   // nothing from Fused after the start
        assertTrue(s.accept(true, true, 4000));             // a good Fused fix
        assertFalse(s.fusedStalled(5000));
        assertFalse(s.accept(false, true, 5000));           // GPS beside a healthy Fused: dropped
        assertTrue(s.accept(true, true, 5000));
        assertFalse(s.accept(false, true, 8000));           // Fused quiet for exactly the limit: not yet
        assertTrue(s.fusedStalled(8001));
        assertTrue(s.accept(false, true, 9000));            // ... and now GPS fills in
        assertFalse(s.accept(true, false, 9500));           // a coarse Fused fix is never sent
        assertTrue(s.accept(false, true, 10_000));
        assertTrue(s.accept(true, true, 10_500));           // Fused is back
        assertFalse(s.accept(false, true, 11_000));
    }

    // Network positions and cold-start guesses hop around by hundreds of metres: the last good fix stands.
    @Test
    public void coarseFixesAreNeverSent() {
        FixSource s = new FixSource();
        s.reset(0);
        assertFalse(s.accept(true, false, 1000));
        assertFalse(s.accept(true, false, 8000));
        assertFalse(s.accept(false, false, 9000));          // GPS too
        assertTrue(s.fusedStalled(9000));                   // coarse Fused doesn't count as alive
        assertTrue(s.accept(false, true, 10_000));
    }
}
