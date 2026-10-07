package com.cabletv.player.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.cabletv.player.config.AppConfig;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private volatile UpdateStatus mStatus = UpdateStatus.idle();

    /** Visible for tests, which inject a checker backed by a stub fetcher. */
    UpdateManager(UpdateChecker checker) {
        mChecker = checker;
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
        publish(UpdateStatus.checking());
        UpdateStatus status = mChecker.check(AppVersion.code(appContext), android.os.Build.SUPPORTED_ABIS);
        // Stamp the attempt even when it failed: a failing check must not retry on every launch.
        AppConfig.setUpdateLastCheckMs(System.currentTimeMillis());
        AppConfig.setUpdateLastError(status.error == UpdateError.NONE ? "" : status.error.name());
        if (status.state == UpdateState.ERROR) {
            Log.w(TAG, "Update check failed: " + status.error);
        } else {
            Log.i(TAG, "Update check: " + status.state
                    + (status.latestVersionName != null ? " (latest " + status.latestVersionName + ")" : ""));
        }
        publish(status);
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
}
