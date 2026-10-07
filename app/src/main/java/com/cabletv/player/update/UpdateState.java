package com.cabletv.player.update;

/**
 * Where the updater currently stands. Everything the settings screen and the web page show is
 * derived from these states, so a check, a download and an install cannot disagree about what is
 * going on.
 *
 * <p>The download and install states are only produced from the phase that implements them on; the
 * full set lives here so the state machine is described in one place.
 */
public enum UpdateState {
    /** Nothing has been checked in this process yet. */
    IDLE,
    /** A check is running. */
    CHECKING,
    /** GitHub's latest manifest is not newer than the installed build. */
    UP_TO_DATE,
    /** A newer build exists and a matching APK was found for this device. */
    UPDATE_AVAILABLE,
    /** The APK for this device is being fetched. */
    DOWNLOADING,
    /** The APK is downloaded and verified, waiting for the user to start the install. */
    READY,
    /** The system installer has been handed the APK. */
    INSTALLING,
    /** Android is blocking installs from this app until the user grants the per-app permission. */
    NEEDS_PERMISSION,
    /** The last attempt failed; {@link UpdateStatus#error} says why. */
    ERROR
}
