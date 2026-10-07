package com.cabletv.player.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * The version of the APK that is actually installed. Read from the package manager rather than from
 * {@code BuildConfig}: the updater compares "what is on this device" against the release manifest,
 * and on this project the version is injected from an untracked gradle.properties at build time.
 */
public final class AppVersion {

    private static final String TAG = "AppVersion";

    private AppVersion() {
    }

    public static int code(Context context) {
        PackageInfo info = infoOf(context);
        return info != null ? info.versionCode : 0;
    }

    public static String name(Context context) {
        PackageInfo info = infoOf(context);
        if (info == null || info.versionName == null) {
            return "";
        }
        return info.versionName;
    }

    private static PackageInfo infoOf(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Cannot read the installed version", e);
            return null;
        }
    }
}
