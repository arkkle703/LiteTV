package com.cabletv.player.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The marker that remembers a downloaded update is written before the install and can outlive it.
 * A fresh install then finds a marker for the version it is already running, which must not be
 * presented as "an update is ready" — that is what a real upgrade showed on a device.
 */
public class UpdatePendingStaleTest {

    @Test
    public void pendingForTheInstalledVersionIsStale() {
        assertTrue("the update was installed; the marker is left over",
                UpdateManager.isPendingStale(7, 7));
    }

    @Test
    public void pendingForAnOlderVersionIsStale() {
        assertTrue("a downgrade marker is meaningless",
                UpdateManager.isPendingStale(6, 7));
    }

    @Test
    public void pendingForANewerVersionIsStillWanted() {
        assertFalse(UpdateManager.isPendingStale(8, 7));
    }
}
