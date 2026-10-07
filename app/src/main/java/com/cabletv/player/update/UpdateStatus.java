package com.cabletv.player.update;

import java.util.Collections;
import java.util.List;

/**
 * An immutable snapshot of what the updater knows. Screens render one of these and never hold
 * pieces of updater state themselves, so a check that finishes while the settings screen is open
 * can simply hand over a new snapshot.
 */
public final class UpdateStatus {

    public final UpdateState state;
    public final UpdateError error;
    public final int latestVersionCode;
    public final String latestVersionName;
    public final List<String> changelog;
    public final String releaseUrl;
    /** The APK chosen for this device; only set once a newer build with a matching ABI was found. */
    public final UpdateManifest.Variant variant;
    public final long checkedAtMs;

    private UpdateStatus(UpdateState state, UpdateError error, UpdateManifest manifest,
                         UpdateManifest.Variant variant, long checkedAtMs) {
        this.state = state;
        this.error = error;
        this.latestVersionCode = manifest != null ? manifest.versionCode : 0;
        this.latestVersionName = manifest != null ? manifest.versionName : null;
        this.changelog = manifest != null ? manifest.changelogLines() : Collections.<String>emptyList();
        this.releaseUrl = manifest != null ? manifest.releaseUrl : null;
        this.variant = variant;
        this.checkedAtMs = checkedAtMs;
    }

    public static UpdateStatus idle() {
        return new UpdateStatus(UpdateState.IDLE, UpdateError.NONE, null, null, 0L);
    }

    public static UpdateStatus checking() {
        return new UpdateStatus(UpdateState.CHECKING, UpdateError.NONE, null, null, 0L);
    }

    public static UpdateStatus upToDate(UpdateManifest manifest, long nowMs) {
        return new UpdateStatus(UpdateState.UP_TO_DATE, UpdateError.NONE, manifest, null, nowMs);
    }

    public static UpdateStatus available(UpdateManifest manifest, UpdateManifest.Variant variant, long nowMs) {
        return new UpdateStatus(UpdateState.UPDATE_AVAILABLE, UpdateError.NONE, manifest, variant, nowMs);
    }

    public static UpdateStatus error(UpdateError error, UpdateManifest manifest, long nowMs) {
        return new UpdateStatus(UpdateState.ERROR, error, manifest, null, nowMs);
    }

    public static UpdateStatus error(UpdateError error, long nowMs) {
        return error(error, null, nowMs);
    }

    public boolean isUpdateAvailable() {
        return state == UpdateState.UPDATE_AVAILABLE && variant != null;
    }
}
