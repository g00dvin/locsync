package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LinkQualityTest {
    @Test
    public void longestWaitOfTheLastMinute() {
        LinkQuality q = new LinkQuality();
        assertEquals(0, q.maxGapMs(5000));                 // nothing yet
        for (long t = 1000; t <= 10_000; t += 1000) q.onFix(t);
        assertEquals(1000, q.maxGapMs(10_500));
        assertEquals(4000, q.maxGapMs(14_000));            // the wait still running counts
        q.onFix(25_000);                                    // a 15 s hole
        q.onFix(26_000);
        assertEquals(15_000, q.maxGapMs(26_500));
        for (long t = 27_000; t <= 90_000; t += 1000) q.onFix(t);
        assertEquals(1000, q.maxGapMs(90_000));            // the hole left the window
        q.reset();
        assertEquals(0, q.maxGapMs(90_000));
        for (long t = 0; t < 400_000; t += 250) q.onFix(t); // the ring wraps
        assertEquals(250, q.maxGapMs(399_750));
    }
}
