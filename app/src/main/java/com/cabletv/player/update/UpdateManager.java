package com.cabletv.player.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.cabletv.player.config.AppConfig;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place that knows how to check for updates. A single long-lived instance owns the current
 * {@link UpdateStatus}, so the settings screen, the web page and the start-up check all read and
 * write the same state instead of racing each other.
 *
 * <p>Work runs on its own single-thread executor (named "update"), not on the EPG executor: the
 * update path downloads files and must never queue ahead of EPG parsing.
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";
    /** Long enough not to compete with playback start-up, short enough to feel automatic. */
    private static final long STARTUP_CHECK_DELAY_MS = 8_000L;

    /** Notified on the main thread whenever the status changes. */
    public interface Listener {
        void onUpdateStatusChanged(UpdateStatus status);
    }

    private static volatile UpdateManager sInstance;

    private final ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final UpdateChecker mChecker;
    private final UpdateDownloader mDownloader;
    private final AtomicBoolean mCancelDownload = new AtomicBoolean(false);
    /** Set when the web page asked for the whole flow, cleared once the installer is shown. */
    private volatile boolean mInstallWhenReady;

    private volatile UpdateStatus mStatus = UpdateStatus.idle();
    /** Bumped on every publish, so a slow check can tell that its result is already stale. */
    private final AtomicLong mStateVersion = new AtomicLong();

    /** Visible for tests, which inject a checker backed by a stub fetcher. */
    UpdateManager(UpdateChecker checker) {
        this(checker, new UpdateDownloader());
    }

    UpdateManager(UpdateChecker checker, UpdateDownloader downloader) {
        mChecker = checker;
        mDownloader = downloader;
        mExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "update");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static UpdateManager get() {
        UpdateManager instance = sInstance;
        if (instance == null) {
            synchronized (UpdateManager.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new UpdateManager(new UpdateChecker(new HttpUpdateFetcher()));
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    public UpdateStatus getStatus() {
        return mStatus;
    }

    public void addListener(Listener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * The automatic path used at start-up: honours the user's switch and the daily window, and
     * fails silently — a check the user did not ask for must never interrupt them.
     */
    public void scheduleStartupCheck(Context context) {
        // A verified download outlives the process, so it is offered before anything is asked of
        // the network: the television is usually restarted between checking and installing.
        restorePendingDownload(context);
        if (!AppConfig.isUpdateAutoCheckEnabled()) {
            Log.d(TAG, "Automatic update check is disabled");
            return;
        }
        if (!UpdateThrottle.isDue(AppConfig.getUpdateLastCheckMs(), System.currentTimeMillis(),
                UpdateThrottle.AUTO_CHECK_INTERVAL_MS)) {
            Log.d(TAG, "Last update check is still recent");
            return;
        }
        final Context appContext = context.getApplicationContext();
        mMainHandler.postDelayed(() -> enqueue(appContext), STARTUP_CHECK_DELAY_MS);
    }

    /** The user asked for this one, so it ignores the daily window. */
    public void checkNow(Context context) {
        final Context appContext = context.getApplicationContext();
        mExecutor.execute(() -> runCheck(appContext));
    }

    private void enqueue(Context appContext) {
        // Re-check the switch: the user may have turned it off during the delay.
        if (!AppConfig.isUpdateAutoCheckEnabled()) {
            return;
        }
        mExecutor.execute(() -> runCheck(appContext));
    }

    private void runCheck(Context appContext) {
        performCheck(appContext);
    }

    /**
     * Runs a check on the calling thread and returns its result. The web page uses this: a browser
     * press wants an answer in the same request, while the start-up check stays asynchronous.
     */
    public UpdateStatus checkNowAndWait(Context appContext) {
        return performCheck(appContext.getApplicationContext());
    }

    /**
     * The single place a check happens, so the web request and the background check cannot run on
     * top of each other. Blocking; the caller is either the update thread or a web worker.
     */
    private synchronized UpdateStatus performCheck(Context appContext) {
        UpdateStatus previous = mStatus;
        long versionAtStart = mStateVersion.get();
        publish(UpdateStatus.checking());
        UpdateStatus status;
        try {
            status = mChecker.check(AppVersion.code(appContext), android.os.Build.SUPPORTED_ABIS);
        } catch (RuntimeException e) {
            // The updater runs on its own thread, and an exception there would otherwise take the
            // whole process down (the default handler terminates the app). A failed check is a
            // status, not a crash.
            Log.e(TAG, "Update check threw", e);
            status = UpdateStatus.error(UpdateError.UNKNOWN, System.currentTimeMillis());
        }
        status = keepVerifiedDownload(status, previous);
        status = restoreVerifiedDownload(status, appContext);
        // Stamp the attempt even when it failed: a failing check must not retry on every launch.
        AppConfig.setUpdateLastCheckMs(System.currentTimeMillis());
        AppConfig.setUpdateLastError(status.error == UpdateError.NONE ? "" : status.error.name());
        if (status.state == UpdateState.ERROR) {
            Log.w(TAG, "Update check failed: " + status.error);
        } else {
            Log.i(TAG, "Update check: " + status.state
                    + (status.latestVersionName != null ? " (latest " + status.latestVersionName + ")" : ""));
        }
        if (mStateVersion.get() != versionAtStart) {
            // A download finished, or an install was handed over, while this check was in flight.
            // That newer state is the truth; publishing this result would roll it back (this is
            // exactly how a finished install ended up looking like "installing").
            Log.i(TAG, "Update check finished late; keeping the newer state " + mStatus.state);
            return status;
        }
        publish(status);
        return status;
    }

    private void publish(final UpdateStatus status) {
        mStatus = status;
        if (mListeners.isEmpty()) {
            return;
        }
        mMainHandler.post(() -> {
            for (Listener listener : mListeners) {
                listener.onUpdateStatusChanged(status);
            }
        });
    }

    // --- Download -------------------------------------------------------------------------

    /**
     * Fetches and verifies the APK for the update that was just found. Nothing is installed from
     * here; the caller decides when to hand the verified file to the system installer.
     */
    public void downloadUpdate(Context context) {
        UpdateStatus current = mStatus;
        if (!current.isUpdateAvailable()) {
            Log.w(TAG, "Ignoring a download request without a known update");
            return;
        }
        final Context appContext = context.getApplicationContext();
        final UpdateManifest.Variant variant = current.variant;
        final int expectedVersionCode = current.latestVersionCode;
        mCancelDownload.set(false);
        publish(current.withProgress(0L, variant.size));
        mExecutor.execute(() -> runDownload(appContext, variant, expectedVersionCode));
    }

    /** Stops the running download; the partial file is kept so a retry resumes. */
    public void cancelDownload() {
        mCancelDownload.set(true);
    }

    /**
     * What the web page's single "update now" button means: fetch the APK, and once it is verified,
     * put the system's own confirmation on the television. The install still has to be accepted on
     * the television itself, which is the point — a phone on the same network cannot install
     * anything on the TV without someone there agreeing.
     */
    public void downloadAndInstall(Context context) {
        UpdateStatus current = mStatus;
        if (current.isReadyToInstall()) {
            // Already downloaded and verified (possibly waiting for the install permission).
            installUpdate(context);
            return;
        }
        mInstallWhenReady = true;
        downloadUpdate(context);
    }

    private void runDownload(Context context, UpdateManifest.Variant variant, int expectedVersionCode) {
        UpdateStatus before = mStatus;
        try {            File apk = mDownloader.download(context, variant.url, variant.size, variant.sha256,
                    this::publishProgress, mCancelDownload);
            ApkVerifier.Result result = ApkVerifier.verify(context, apk, expectedVersionCode);
            if (result != ApkVerifier.Result.OK) {
                UpdateDownloader.clear(context);
                Log.w(TAG, "Rejected the downloaded APK: " + result);
                fail(before, errorFor(result));
                return;
            }
            Log.i(TAG, "APK verified and ready to install: " + apk);
            AppConfig.setUpdateLastError("");
            AppConfig.setUpdatePendingVersion(expectedVersionCode);
            AppConfig.setUpdatePendingName(before.latestVersionName);
            publish(before.toReady(apk));
            if (mInstallWhenReady) {
                // The web page asked for the whole flow; carry on to the installer prompt.
                mInstallWhenReady = false;
                installUpdate(context);
            }
        } catch (UpdateDownloader.DownloadException e) {
            Log.w(TAG, "Download failed: " + e.error + " (" + e.getMessage() + ")");
            mInstallWhenReady = false;
            if (e.error == UpdateError.CANCELLED) {
                // Not a failure: the update is still on offer and the partial file is kept.
                publish(before.backToAvailable(UpdateError.CANCELLED));
                return;
            }
            fail(before, e.error);
        } catch (RuntimeException e) {
            // Same reasoning as the check: a broken download must not kill the app.
            Log.e(TAG, "Download threw", e);
            fail(before, UpdateError.UNKNOWN);
        }
    }

    /**
     * A check that finds the same version must not throw away an already verified download: the
     * download is the slow part, and the user would be told to fetch something they already have.
     * The remembered state is kept instead, so the screen still offers the install (or still asks
     * for the install permission) rather than starting over.
     */
    static UpdateStatus keepVerifiedDownload(UpdateStatus fresh, UpdateStatus previous) {
        if (fresh.state != UpdateState.UPDATE_AVAILABLE || previous == null
                || !isVerifiedWaiting(previous)
                || previous.latestVersionCode != fresh.latestVersionCode
                || previous.apkFile == null || !previous.apkFile.isFile()) {
            return fresh;
        }
        return previous;
    }

    /**
     * True for the states that mean "a verified APK is waiting for the user": ready to install, or
     * held back for permission. INSTALLING is deliberately excluded — it describes a handover that
     * is over by the time a later check returns, and keeping it would resurrect a state the screen
     * would then sit in.
     */
    private static boolean isVerifiedWaiting(UpdateStatus status) {
        return (status.state == UpdateState.READY || status.state == UpdateState.NEEDS_PERMISSION)
                && status.isReadyToInstall();
    }

    /**
     * True when a remembered download is for a version that is already installed (or older): the
     * marker outlived the install it was meant for, and offering it again would ask the user to
     * install what they are running.
     */
    static boolean isPendingStale(int pendingVersionCode, int installedVersionCode) {
        return pendingVersionCode <= installedVersionCode;
    }

    /** Puts a downloaded-but-uninstalled update back on offer without touching the network. */
    private void restorePendingDownload(Context context) {
        int pending = AppConfig.getUpdatePendingVersion();
        if (pending <= 0) {
            return;
        }
        boolean stale = isPendingStale(pending, AppVersion.code(context));
        File apk = UpdateDownloader.targetFile(context);
        if (stale || !apk.isFile()) {
            // Either the update has just been installed (the marker outlived the download) or the
            // cache was cleared. Offering it again would ask the user to install the version they
            // are already running, so the claim is dropped and the leftover file goes with it.
            Log.d(TAG, "Dropping a stale pending update (" + pending + ", installed "
                    + AppVersion.code(context) + ")");
            AppConfig.setUpdatePendingVersion(0);
            AppConfig.setUpdatePendingName("");
            if (stale) {
                UpdateDownloader.clear(context);
            }
            return;
        }
        publish(UpdateStatus.readyFromCache(pending, AppConfig.getUpdatePendingName(), apk));
    }

    /**
     * The same idea as {@link #keepVerifiedDownload} but across a restart: a verified APK is still
     * in the cache, so the offer stays "install" instead of "download" even though this process
     * has no memory of downloading it.
     */
    static UpdateStatus restoreVerifiedDownload(UpdateStatus fresh, Context context) {
        if (fresh.state != UpdateState.UPDATE_AVAILABLE
                || AppConfig.getUpdatePendingVersion() != fresh.latestVersionCode) {
            return fresh;
        }
        File apk = UpdateDownloader.targetFile(context);
        if (!apk.isFile()) {
            // The cache was cleared (or the system reclaimed it); forget the claim.
            AppConfig.setUpdatePendingVersion(0);
            return fresh;
        }
        return fresh.toReady(apk);
    }

    private void publishProgress(long downloaded, long total) {
        UpdateStatus current = mStatus;
        if (current.state == UpdateState.DOWNLOADING) {
            publish(current.withProgress(downloaded, total));
        }
    }

    private void fail(UpdateStatus before, UpdateError error) {
        AppConfig.setUpdateLastError(error.name());
        publish(before.toError(error));
    }

    // --- Install ---------------------------------------------------------------------------

    /**
     * Hands the verified APK to the system installer. Android shows its own confirmation; this only
     * decides whether we are allowed to ask, and then reports whatever the installer says back.
     */
    public void installUpdate(Context context) {
        UpdateStatus current = mStatus;
        if (current.apkFile == null) {
            Log.w(TAG, "Ignoring an install request without a verified APK");
            return;
        }
        if (!ApkInstaller.canRequestInstall(context)) {
            // Nothing is downloaded twice and nothing is lost: the file is verified and waiting.
            publish(current.toNeedsPermission());
            return;
        }
        publish(current.toInstalling());
        ApkInstaller.install(context, current.apkFile, this::onInstallStatus);
    }

    private void onInstallStatus(ApkInstaller.InstallStatus status) {
        UpdateStatus current = mStatus;
        switch (status) {
            case FAILED:
                UpdateError error = ApkInstaller.lastError();
                Log.w(TAG, "Install failed: " + error);
                AppConfig.setUpdateLastError(error.name());
                publish(current.readyAgain(error));
                break;
            case SUCCEEDED:
                Log.i(TAG, "Update installed; the system restarts the app");
                publish(current.readyAgain(UpdateError.NONE));
                break;
            case HANDED_OVER:
                // The installer UI is on screen but this path reports nothing back, so the app
                // cannot know whether the user went through with it. Leaving the state at
                // "installing" would strand the screen on "waiting for the installer" if they
                // cancel; going back to "ready" is right either way, because a successful install
                // kills this process anyway.
                Log.i(TAG, "Install handed to the system installer; no result will follow");
                publish(current.readyAgain(UpdateError.NONE));
                break;
            default:
                // PENDING_USER_ACTION means the session is waiting for the user's answer, which
                // arrives as another status broadcast.
                break;
        }
    }

    private static UpdateError errorFor(ApkVerifier.Result result) {
        switch (result) {
            case WRONG_SIGNATURE:
            case WRONG_PACKAGE:
            case WRONG_VERSION:
                return UpdateError.SIGNATURE;
            default:
                return UpdateError.CHECKSUM;
        }
    }
}
