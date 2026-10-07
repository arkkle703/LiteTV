package com.cabletv.player.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The daily window is what keeps a silent background check from turning into a request on every
 * launch. The interesting cases are the ones a naive subtraction gets wrong: never checked, and a
 * clock that moved backwards.
 */
public class UpdateThrottleTest {

    private static final long DAY = UpdateThrottle.AUTO_CHECK_INTERVAL_MS;

    @Test
    public void isDue_whenNeverChecked() {
        assertTrue(UpdateThrottle.isDue(0L, 1_000L, DAY));
    }

    @Test
    public void isDue_onlyAfterTheIntervalElapsed() {
        long now = 1_000_000_000L;
        assertFalse(UpdateThrottle.isDue(now - 1_000L, now, DAY));
        assertFalse(UpdateThrottle.isDue(now - DAY + 1, now, DAY));
        assertTrue("exactly one interval later counts as due", UpdateThrottle.isDue(now - DAY, now, DAY));
        assertTrue(UpdateThrottle.isDue(now - DAY - 1, now, DAY));
    }

    @Test
    public void isDue_whenTheClockMovedBackwards() {
        long now = 1_000_000_000L;
        assertTrue("a stored check from the future must not block checking forever",
                UpdateThrottle.isDue(now + DAY, now, DAY));
    }
}
