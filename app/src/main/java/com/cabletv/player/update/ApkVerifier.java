package com.cabletv.player.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * Checks that a downloaded APK is really the next build of this app before anyone is offered an
 * install button.
 *
 * <p>The checksum from the manifest already proves the file is the one the release published. This
 * goes one step further and proves the file belongs to <em>this</em> app: same package name, the
 * version the manifest announced, and the same signing certificate as the installed APK. That last
 * one is the defence that survives a tampered manifest or a hostile download host, and it is also
 * the check that guarantees an in-place upgrade will actually succeed.
 */
public final class ApkVerifier {

    public enum Result {
        OK,
        /** Unreadable, truncated or not an APK at all. */
        NOT_AN_APK,
        /** A valid APK, but for a different application. */
        WRONG_PACKAGE,
        /** Not the version the manifest announced (which also catches a downgrade attempt). */
        WRONG_VERSION,
        /** Signed with a different key than the installed app. */
        WRONG_SIGNATURE
    }

    private ApkVerifier() {
    }

    public static Result verify(Context context, File apk, int expectedVersionCode) {
        if (apk == null || !apk.isFile()) {
            return Result.NOT_AN_APK;
        }
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();

        PackageInfo archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlags());
        if (archive == null || archive.packageName == null) {
            return Result.NOT_AN_APK;
        }
        if (!packageName.equals(archive.packageName)) {
            return Result.WRONG_PACKAGE;
        }
        if (archive.versionCode != expectedVersionCode) {
            return Result.WRONG_VERSION;
        }

        PackageInfo installed;
        try {
            installed = packageManager.getPackageInfo(packageName, signatureFlags());
        } catch (PackageManager.NameNotFoundException e) {
            // Cannot happen for our own package, and if it does we cannot vouch for the download.
            return Result.WRONG_SIGNATURE;
        }
        if (!sharesASigner(signaturesOf(archive), signaturesOf(installed))) {
            return Result.WRONG_SIGNATURE;
        }
        return Result.OK;
    }

    /**
     * True when both APKs carry a certificate in common. Compared by certificate digest, so a
     * rotation of the signing key (which keeps the old certificate in the history) still matches.
     */
    static boolean sharesASigner(Signature[] archive, Signature[] installed) {
        Set<String> expected = digestsOf(installed);
        if (expected.isEmpty()) {
            // Without a reference certificate nothing can be vouched for; fail closed.
            return false;
        }
        Set<String> actual = digestsOf(archive);
        for (String digest : actual) {
            if (expected.contains(digest)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> digestsOf(Signature[] signatures) {
        Set<String> digests = new HashSet<>();
        if (signatures == null) {
            return digests;
        }
        for (Signature signature : signatures) {
            if (signature == null) {
                continue;
            }
            digests.add(sha256Of(signature.toByteArray()));
        }
        return digests;
    }

    private static Signature[] signaturesOf(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    /** GET_SIGNING_CERTIFICATES on Android 9+, the older flag below it. */
    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    private static String sha256Of(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return UpdateDownloader.toHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
