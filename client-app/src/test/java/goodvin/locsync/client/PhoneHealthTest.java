package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PhoneHealthTest {
    @Test
    public void normalPhoneIsOk() {
        assertEquals(PhoneHealth.OK, PhoneHealth.of(78, false, 34f, 0));
        assertEquals(PhoneHealth.OK, PhoneHealth.of(10, true, 34f, 1));   // low but charging
        assertEquals(PhoneHealth.OK, PhoneHealth.of(50, false, Float.NaN, 0));
    }

    @Test
    public void heatWinsOverBattery() {
        assertEquals(PhoneHealth.HOT, PhoneHealth.of(10, false, 46f, 0));
        assertEquals(PhoneHealth.HOT, PhoneHealth.of(80, true, 38f, 3));   // system says severe
    }

    @Test
    public void lowBatteryWhenNotCharging() {
        assertEquals(PhoneHealth.LOW_BATTERY, PhoneHealth.of(15, false, 30f, 0));
        assertEquals(PhoneHealth.OK, PhoneHealth.of(16, false, 30f, 0));
    }
}
