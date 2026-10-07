package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The install step is the part that differs most between devices, so what is tested here is the
 * decision making around it: which status code means what, whether the system will accept an
 * install at all, and the shape of the intent that hands the APK over.
 */
@RunWith(RobolectricTestRunner.class)
public class ApkInstallerTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
    }

    @Test
    public void errorForStatus_mapsInstallerCodesToExplanations() {
        assertEquals(UpdateError.INSTALL_BLOCKED,
                ApkInstaller.errorForStatus(PackageInstaller.STATUS_FAILURE_BLOCKED));
        assertEquals("the user backing out is not an error to shout about",
                UpdateError.CANCELLED, ApkInstaller.errorForStatus(PackageInstaller.STATUS_FAILURE_ABORTED));
        assertEquals(UpdateError.SIGNATURE,
                ApkInstaller.errorForStatus(PackageInstaller.STATUS_FAILURE_CONFLICT));
        assertEquals(UpdateError.STORAGE,
                ApkInstaller.errorForStatus(PackageInstaller.STATUS_FAILURE_STORAGE));
        assertEquals(UpdateError.CHECKSUM,
                ApkInstaller.errorForStatus(PackageInstaller.STATUS_FAILURE_INVALID));
        assertEquals(UpdateError.INSTALL_FAILED,
                ApkInstaller.errorForStatus(PackageInstaller.STATUS_FAILURE));
        assertEquals("an unknown code must still produce a usable message",
                UpdateError.INSTALL_FAILED, ApkInstaller.errorForStatus(Integer.MIN_VALUE));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.LOLLIPOP)
    public void canRequestInstall_isAlwaysTrueBeforeAndroid8() {
        // Pre-8 devices use a device-wide "unknown sources" toggle that apps cannot read.
        assertTrue(ApkInstaller.canRequestInstall(mContext));
        assertNull("the permission screen does not exist there", ApkInstaller.permissionIntent(mContext));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.O)
    public void permissionIntent_pointsAtThisAppsScreen() {
        Intent intent = ApkInstaller.permissionIntent(mContext);
        assertNotNull(intent);
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.getAction());
        assertEquals("package:" + mContext.getPackageName(), String.valueOf(intent.getData()));
    }

    @Test
    public void appDetailsIntent_isAvailableAsAFallback() {
        Intent intent = ApkInstaller.appDetailsIntent(mContext);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
        assertEquals("package:" + mContext.getPackageName(), String.valueOf(intent.getData()));
    }

    @Test
    public void openInstaller_handsTheInstallerAReadableContentUri() throws IOException {
        File apk = new File(mContext.getExternalCacheDir() != null
                ? mContext.getExternalCacheDir() : mContext.getCacheDir(), "update.apk");
        try (FileOutputStream out = new FileOutputStream(apk)) {
            out.write(new byte[]{1, 2, 3});
        }

        assertTrue("the download location must be covered by the provider's paths",
                ApkInstaller.openInstaller(mContext, apk));

        Intent started = shadowOf((Application) mContext).getNextStartedActivity();
        assertNotNull("the system installer must be invoked", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("application/vnd.android.package-archive", started.getType());
        Uri data = started.getData();
        assertNotNull(data);
        assertEquals("content", data.getScheme());
        assertEquals("the installer is another app, so the URI must be granted",
                mContext.getPackageName() + ".fileprovider", data.getAuthority());
        assertTrue("without NEW_TASK this throws when started from the application context",
                (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("without the read grant the installer cannot open the file",
                (started.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void openInstaller_refusesAFileOutsideTheSharedPaths() throws IOException {
        File outside = new File(System.getProperty("java.io.tmpdir"), "litetv-outside.apk");
        try (FileOutputStream out = new FileOutputStream(outside)) {
            out.write(new byte[]{1});
        }
        // The provider only covers the cache directories, so this must fail rather than leak a path.
        assertFalse(ApkInstaller.openInstaller(mContext, outside));
        //noinspection ResultOfMethodCallIgnored
        outside.delete();
    }
}
