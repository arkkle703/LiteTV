package com.cabletv.player.update;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * An immutable snapshot of what the updater knows. Screens render one of these and never hold
 * pieces of updater state themselves, so a check or a download that finishes while the settings
 * screen is open can simply hand over a new snapshot.
 *
 * <p>The transitions are instance methods ({@link #withProgress}, {@link #toReady}, …) because each
 * one carries the manifest and the chosen APK forward: a download must not lose track of which
 * build it is fetching.
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
    /** Bytes fetched so far while {@link UpdateState#DOWNLOADING}. */
    public final long downloadedBytes;
    /** Expected size in bytes, 0 when unknown. */
    public final long totalBytes;
    /** The verified APK once {@link UpdateState#READY}. */
    public final File apkFile;
    public final long checkedAtMs;

    private final UpdateManifest manifest;

    private UpdateStatus(UpdateState state, UpdateError error, UpdateManifest manifest,
                         UpdateManifest.Variant variant, long downloadedBytes, long totalBytes,
                         File apkFile, long checkedAtMs) {
        this.state = state;
        this.error = error;
        this.manifest = manifest;
        this.latestVersionCode = manifest != null ? manifest.versionCode : 0;
        this.latestVersionName = manifest != null ? manifest.versionName : null;
        this.changelog = manifest != null ? manifest.changelogLines() : Collections.<String>emptyList();
        this.releaseUrl = manifest != null ? manifest.releaseUrl : null;
        this.variant = variant;
        this.downloadedBytes = downloadedBytes;
        this.totalBytes = totalBytes;
        this.apkFile = apkFile;
        this.checkedAtMs = checkedAtMs;
    }

    public static UpdateStatus idle() {
        return new UpdateStatus(UpdateState.IDLE, UpdateError.NONE, null, null, 0L, 0L, null, 0L);
    }

    public static UpdateStatus checking() {
        return new UpdateStatus(UpdateState.CHECKING, UpdateError.NONE, null, null, 0L, 0L, null, 0L);
    }

    public static UpdateStatus upToDate(UpdateManifest manifest, long nowMs) {
        return new UpdateStatus(UpdateState.UP_TO_DATE, UpdateError.NONE, manifest, null, 0L, 0L, null, nowMs);
    }

    public static UpdateStatus available(UpdateManifest manifest, UpdateManifest.Variant variant, long nowMs) {
        return new UpdateStatus(UpdateState.UPDATE_AVAILABLE, UpdateError.NONE, manifest, variant, 0L, 0L, null, nowMs);
    }

    public static UpdateStatus error(UpdateError error, UpdateManifest manifest, long nowMs) {
        return new UpdateStatus(UpdateState.ERROR, error, manifest, null, 0L, 0L, null, nowMs);
    }

    public static UpdateStatus error(UpdateError error, long nowMs) {
        return error(error, null, nowMs);
    }

    public boolean isUpdateAvailable() {
        return state == UpdateState.UPDATE_AVAILABLE && variant != null;
    }

    /** Download progress; keeps the manifest and APK choice. */
    public UpdateStatus withProgress(long downloaded, long total) {
        long size = total > 0 ? total : totalBytes;
        return new UpdateStatus(UpdateState.DOWNLOADING, UpdateError.NONE, manifest, variant,
                downloaded, size, null, checkedAtMs);
    }

    /** The APK is on disk and passed verification. */
    public UpdateStatus toReady(File apk) {
        return new UpdateStatus(UpdateState.READY, UpdateError.NONE, manifest, variant,
                totalBytes, totalBytes, apk, checkedAtMs);
    }

    /**
     * Back to "an update is available", with the reason it stopped. Used for a cancelled download:
     * the APK is still waiting to be fetched, and the partial file is kept for a resume.
     */
    public UpdateStatus backToAvailable(UpdateError reason) {
        return new UpdateStatus(UpdateState.UPDATE_AVAILABLE, reason, manifest, variant,
                downloadedBytes, totalBytes, null, checkedAtMs);
    }

    /** A failed download step; the chosen APK stays known so the user can retry. */
    public UpdateStatus toError(UpdateError reason) {
        boolean keepOffer = variant != null;
        return new UpdateStatus(keepOffer ? UpdateState.UPDATE_AVAILABLE : UpdateState.ERROR,
                reason, manifest, variant, downloadedBytes, totalBytes, null, checkedAtMs);
    }

    /** Fraction downloaded, 0..1, or 0 when the total is unknown. */
    public float progressFraction() {
        if (totalBytes <= 0L) {
            return 0f;
        }
        return Math.min(1f, (float) downloadedBytes / (float) totalBytes);
    }
}
