package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import com.cabletv.player.config.AppConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;

/**
 * A television app is restarted often, and the downloaded APK outlives the process. A check after
 * a restart must still offer "install" rather than asking for the same download again.
 */
@RunWith(RobolectricTestRunner.class)
public class UpdatePendingVersionTest {

    private static final String SHA = "00112233445566778899aabbccddeeff"
            + "00112233445566778899aabbccddeeff";

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        com.orhanobut.hawk.Hawk.init(mContext).build();
        AppConfig.setUpdatePendingVersion(0);
        UpdateDownloader.clear(mContext);
    }

    private static UpdateStatus available(int versionCode) {
        UpdateManifest manifest = UpdateManifest.parse("{\"versionCode\":" + versionCode
                + ",\"versionName\":\"1.5.0\",\"variants\":{\"x86\":{\"url\":\"https://github.com/"
                + "vibe4free/LiteTV/releases/download/v1.5.0/LiteTV.apk\",\"sha256\":\"" + SHA
                + "\",\"size\":1000}}}");
        return UpdateStatus.available(manifest, manifest.variantFor(new String[]{"x86"}), 1L);
    }

    private File writeApk() throws Exception {
        File apk = UpdateDownloader.targetFile(mContext);
        try (FileOutputStream out = new FileOutputStream(apk)) {
            out.write(new byte[]{1, 2, 3});
        }
        return apk;
    }

    @Test
    public void readyFromCache_carriesTheVersionWithoutAManifest() throws Exception {
        File apk = writeApk();
        UpdateStatus restored = UpdateStatus.readyFromCache(7, "1.5.0", apk);
        assertEquals(UpdateState.READY, restored.state);
        assertEquals(7, restored.latestVersionCode);
        assertEquals("1.5.0", restored.latestVersionName);
        assertEquals("the screen must offer the install button", true, restored.isReadyToInstall());
        assertEquals("the changelog is unknown until the next check", 0, restored.changelog.size());
    }

    @Test
    public void restoreVerifiedDownload_offersInstallAfterARestart() throws Exception {
        writeApk();
        AppConfig.setUpdatePendingVersion(7);

        UpdateStatus restored = UpdateManager.restoreVerifiedDownload(available(7), mContext);

        assertEquals(UpdateState.READY, restored.state);
        assertEquals("the file that was verified before the restart", 7, restored.latestVersionCode);
    }

    @Test
    public void restoreVerifiedDownload_forgetsAVersionWithNoFile() {
        // The marker without the file means the cache was cleared; asking for a download is right.
        AppConfig.setUpdatePendingVersion(7);
        UpdateStatus restored = UpdateManager.restoreVerifiedDownload(available(7), mContext);
        assertEquals(UpdateState.UPDATE_AVAILABLE, restored.state);
        assertEquals("a stale marker must not survive", 0, AppConfig.getUpdatePendingVersion());
    }

    @Test
    public void restoreVerifiedDownload_ignoresAnotherVersion() throws Exception {
        writeApk();
        AppConfig.setUpdatePendingVersion(6);
        assertEquals(UpdateState.UPDATE_AVAILABLE,
                UpdateManager.restoreVerifiedDownload(available(7), mContext).state);
    }

    @Test
    public void restoreVerifiedDownload_leavesUpToDateAlone() throws Exception {
        writeApk();
        AppConfig.setUpdatePendingVersion(7);
        UpdateManifest manifest = UpdateManifest.parse("{\"versionCode\":7,\"versionName\":\"1.5.0\","
                + "\"variants\":{\"x86\":{\"url\":\"https://github.com/vibe4free/LiteTV/releases/"
                + "download/v1.5.0/LiteTV.apk\",\"sha256\":\"" + SHA + "\",\"size\":1000}}}");
        assertEquals(UpdateState.UP_TO_DATE,
                UpdateManager.restoreVerifiedDownload(UpdateStatus.upToDate(manifest, 1L), mContext).state);
    }
}
