package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UiScaleTest {
    @Test
    public void autoDoublesOnLargeScreensOnly() {
        assertEquals(2f, MainActivity.effectiveUiScale(0, 600), 0);
        assertEquals(2f, MainActivity.effectiveUiScale(0, 1200), 0);
        assertEquals(1f, MainActivity.effectiveUiScale(0, 411), 0);
    }

    @Test
    public void chosenScaleWins() {
        assertEquals(1.5f, MainActivity.effectiveUiScale(1.5f, 1200), 0);
        assertEquals(1f, MainActivity.effectiveUiScale(1f, 1200), 0);
        assertEquals(3f, MainActivity.effectiveUiScale(3f, 411), 0);
    }
}
