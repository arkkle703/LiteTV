package com.cabletv.player.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a verified APK to the system installer. Everything here is about the two ways Android TV
 * devices differ from phones: the per-app "install unknown apps" switch may be missing from the TV
 * settings, and the install itself must stay operable with a remote.
 *
 * <p>Two paths are used, and they exist for different reasons:
 *
 * <ul>
 *   <li>A {@link PackageInstaller} session is tried first because it reports back. That is the only
 *       way to tell "the system blocked this" from "the user cancelled" from "the APK is bad", and
 *       guessing is exactly what makes an updater frustrating on a TV with no visible error.</li>
 *   <li>{@code ACTION_VIEW} with the package-archive MIME type is the path that is known to work
 *       across TV firmware (it is what large sideloaded TV apps use), so it is the fallback when a
 *       session cannot even be created — a failure mode seen on older boxes.</li>
 * </ul>
 *
 * <p>Neither path installs anything silently: the system always shows its own confirmation, which
 * is reachable with a D-pad.
 */
public final class ApkInstaller {

    private static final String TAG = "ApkInstaller";
    private static final String ACTION_INSTALL_STATUS = "com.cabletv.player.update.INSTALL_STATUS";
    private static final String SESSION_NAME = "litetv-update";

    /** The outcome the UI should show. */
    public enum InstallStatus {
        /** The system dialog is on screen; the answer arrives through the callback later. */
        PENDING_USER_ACTION,
        /** Installed (the process is usually killed right after, so this is mostly for the log). */
        SUCCEEDED,
        /** Refused, with the reason available through {@link #lastError()}. */
        FAILED,
        /** Handed over through ACTION_VIEW, which gives no result. */
        HANDED_OVER
    }

    public interface Callback {
        void onInstallStatus(InstallStatus status);
    }

    /**
     * The broadcast receiver only lives as long as one install; the installer is a singleton and
     * installs are serialised, so a single slot is enough and avoids leaking a receiver per press.
     */
    private static BroadcastReceiver sReceiver;
    private static Callback sCallback;
    private static UpdateError sLastError = UpdateError.NONE;
    /** The APK of the install in flight, kept so the installer UI can be offered as a fallback. */
    private static File sPendingApk;
    private static boolean sTriedInstallerUi;

    private ApkInstaller() {
    }

    /**
     * Whether Android will let this app start an install at all. Below Android 8 the answer is a
     * device-wide "unknown sources" setting that we cannot read, so it is assumed to be on — the
     * installer reports back if it is not.
     */
    public static boolean canRequestInstall(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (RuntimeException e) {
            // Some vendor builds throw here instead of answering; let the installer decide.
            Log.w(TAG, "Cannot read the install permission state", e);
            return true;
        }
    }

    /**
     * The system screen where the user grants this app the right to install. Null on devices where
     * the concept does not exist (Android 7 and older).
     */
    public static Intent permissionIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    /** The app's own page in system settings, the fallback when the specific screen is missing. */
    public static Intent appDetailsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    /** Error of the last {@link InstallStatus#FAILED} result. */
    public static UpdateError lastError() {
        return sLastError;
    }

    /**
     * Starts an install. The callback runs on the main thread and may be called twice for a session
     * install: once with {@link InstallStatus#PENDING_USER_ACTION} and once with the final answer.
     */
    public static void install(Context context, File apk, Callback callback) {
        Context appContext = context.getApplicationContext();
        sCallback = callback;
        sLastError = UpdateError.NONE;
        sPendingApk = apk;
        sTriedInstallerUi = false;
        try {
            installThroughSession(appContext, apk);
        } catch (Exception e) {
            // Session installs are unavailable on some firmware; the plain install intent works
            // there, at the price of getting no result back.
            Log.w(TAG, "PackageInstaller session failed, falling back to the install intent", e);
            sTriedInstallerUi = true;
            if (openInstaller(appContext, apk)) {
                callback.onInstallStatus(InstallStatus.HANDED_OVER);
            } else {
                sLastError = UpdateError.INSTALL_FAILED;
                callback.onInstallStatus(InstallStatus.FAILED);
            }
        }
    }

    private static void installThroughSession(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            OutputStream out = session.openWrite(SESSION_NAME, 0L, apk.length());
            try {
                copy(apk, out);
                session.fsync(out);
            } finally {
                closeQuietly(out);
            }
        } catch (IOException e) {
            session.abandon();
            throw e;
        } finally {
            closeQuietly(session);
        }

        registerStatusReceiver(context);
        Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
        // FLAG_MUTABLE: the installer fills in the status extras, which an immutable intent blocks.
        android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(
                context, sessionId, status,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_MUTABLE);
        session.commit(pending.getIntentSender());
    }

    private static void copy(File apk, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(apk);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * The install intent every sideloaded TV app ends up using: a content URI the installer can
     * read once, with the package-archive type.
     */
    static boolean openInstaller(Context context, File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(installUri(context, apk), "application/vnd.android.package-archive");
            // NEW_TASK because this runs from the application context; the read grant because on
            // Android 7+ the installer reads the file through our provider rather than directly.
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0));
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            // Includes ActivityNotFoundException on firmware with no installer UI and
            // IllegalArgumentException when the file is outside the provider's paths.
            Log.w(TAG, "No installer could be opened for " + apk, e);
            return false;
        }
    }

    /**
     * How the installer should be handed the file.
     *
     * <p>Android 7 and newer require a content URI (a {@code file://} one throws), so the provider
     * serves it and the read grant travels with the intent. Below 7 those APIs do not exist and the
     * installer reads the path itself — and some vendor firmware (MIUI on Android 6, which is what
     * a MiTV runs) resolves <em>only</em> {@code file://} install intents and refuses the content
     * form outright, so the classic form is the one that works there.
     */
    static Uri installUri(Context context, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apk);
        }
        // The installer runs as another user id; without this it cannot open the downloaded file.
        if (!apk.setReadable(true, false)) {
            Log.w(TAG, "Could not make the download readable: " + apk);
        }
        return Uri.fromFile(apk);
    }

    private static synchronized void registerStatusReceiver(Context context) {
        unregisterStatusReceiver(context);
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                handleStatus(receiverContext, intent);
            }
        };
        // The broadcast comes from the system, and an app targeting Android 14 must say whether a
        // dynamically registered receiver is exported; this one only ever hears the installer.
        ContextCompat.registerReceiver(context, sReceiver,
                new IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private static synchronized void unregisterStatusReceiver(Context context) {
        if (sReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(sReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered; nothing to do.
        }
        sReceiver = null;
    }

    private static void handleStatus(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "Installer status " + status + " (" + message + ")");

        Callback callback = sCallback;
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // The session does not install silently: the system wants its own confirmation, and
                // it hands us the intent to show. Without starting it the install would sit here
                // forever with nothing on screen (only a device test caught this).
                startConfirmation(context, intent);
                notifyCallback(callback, InstallStatus.PENDING_USER_ACTION);
                return;
            case PackageInstaller.STATUS_SUCCESS:
                unregisterStatusReceiver(context);
                sCallback = null;
                notifyCallback(callback, InstallStatus.SUCCEEDED);
                return;
            default:
                sLastError = errorForStatus(status, message);
                // Some firmware refuses a session install but is perfectly happy to install the
                // same file through its own installer UI, where the user gets to approve it (this
                // is what MIUI does: "INSTALL_FAILED_USER_RESTRICTED"). Offer that once instead of
                // dead-ending on a message the user cannot act on.
                if (canFallBackToInstaller(sLastError) && !sTriedInstallerUi) {
                    sTriedInstallerUi = true;
                    Log.i(TAG, "Retrying through the installer UI");
                    if (openInstaller(context, sPendingApk)) {
                        unregisterStatusReceiver(context);
                        sCallback = null;
                        notifyCallback(callback, InstallStatus.HANDED_OVER);
                        return;
                    }
                }
                unregisterStatusReceiver(context);
                sCallback = null;
                notifyCallback(callback, InstallStatus.FAILED);
        }
    }

    /**
     * Whether a failed session install is worth retrying through the installer UI. A user who
     * cancelled is asked once, not twice, and a file the installer called invalid is not shown
     * again at all.
     */
    static boolean canFallBackToInstaller(UpdateError error) {
        switch (error) {
            case INSTALL_RESTRICTED:
            case INSTALL_BLOCKED:
            case INSTALL_FAILED:
            case UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    private static void notifyCallback(Callback callback, InstallStatus status) {
        if (callback != null) {
            callback.onInstallStatus(status);
        }
        if (status == InstallStatus.SUCCEEDED || status == InstallStatus.FAILED) {
            sPendingApk = null;
            sTriedInstallerUi = false;
        }
    }

    /** Shows the installer's confirmation dialog, which the session flow expects us to launch. */
    private static void startConfirmation(Context context, Intent statusIntent) {
        Intent confirm = statusIntent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm == null) {
            Log.w(TAG, "The installer asked for confirmation but sent no intent");
            return;
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(confirm);
        } catch (Exception e) {
            Log.w(TAG, "Cannot show the installer confirmation", e);
        }
    }

    /** Turns an installer status code into something the update screen can explain. */
    static UpdateError errorForStatus(int status) {
        return errorForStatus(status, null);
    }

    static UpdateError errorForStatus(int status, String message) {
        // The installer's own message is the reliable part on vendor firmware: MIUI reports its
        // permission problem as a plain STATUS_FAILURE_INCOMPATIBLE, and only the text says why.
        String text = message == null ? "" : message;
        if (text.contains("USER_RESTRICTED") || text.contains("RESTRICTED")) {
            return UpdateError.INSTALL_RESTRICTED;
        }
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return UpdateError.INSTALL_BLOCKED;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return UpdateError.CANCELLED;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return UpdateError.SIGNATURE;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return UpdateError.STORAGE;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return UpdateError.CHECKSUM;
            default:
                return UpdateError.INSTALL_FAILED;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // A stream we are finished with cannot change the outcome.
        }
    }
}
