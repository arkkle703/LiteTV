package com.cabletv.player.update;

/**
 * Decides whether an automatic check is due. Kept separate from the manager so the rule (and its
 * edge cases — never checked, clock moved backwards) is testable without touching storage.
 */
public final class UpdateThrottle {

    /** How often the background check may run. An update is not urgent enough to nag more often. */
    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private UpdateThrottle() {
    }

    /** True when the last check is old enough (or never happened). */
    public static boolean isDue(long lastCheckMs, long nowMs, long intervalMs) {
        if (lastCheckMs <= 0L) {
            return true;
        }
        if (nowMs < lastCheckMs) {
            // Clock moved backwards (or a stale stored value): check rather than wait forever.
            return true;
        }
        return nowMs - lastCheckMs >= intervalMs;
    }
}
