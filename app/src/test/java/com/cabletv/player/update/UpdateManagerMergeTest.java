package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A background check runs whenever it likes, including right after a download finished. Finding
 * the same version again must not discard the verified file: the user would be asked to fetch
 * something already sitting in the cache.
 */
public class UpdateManagerMergeTest {

    private static final String SHA = "00112233445566778899aabbccddeeff"
            + "00112233445566778899aabbccddeeff";

    private static UpdateManifest manifest(int versionCode) {
        return UpdateManifest.parse("{\"versionCode\":" + versionCode + ",\"versionName\":\"1.5.0\","
                + "\"variants\":{\"x86\":{\"url\":\"https://github.com/vibe4free/LiteTV/releases/"
                + "download/v1.5.0/LiteTV.apk\",\"sha256\":\"" + SHA + "\",\"size\":1000}}}");
    }

    private static UpdateStatus available(int versionCode) {
        UpdateManifest manifest = manifest(versionCode);
        return UpdateStatus.available(manifest, manifest.variantFor(new String[]{"x86"}), 1L);
    }

    private File existingApk() throws IOException {
        File apk = Files.createTempFile("litetv-merge", ".apk").toFile();
        try (FileOutputStream out = new FileOutputStream(apk)) {
            out.write(new byte[]{1, 2, 3});
        }
        return apk;
    }

    @Test
    public void keepVerifiedDownload_keepsTheReadyFileForTheSameVersion() throws Exception {
        File apk = existingApk();
        UpdateStatus ready = available(7).withProgress(1000L, 1000L).toReady(apk);
        // A fresh check of the same version would otherwise say "update available" again.
        assertSame(ready, UpdateManager.keepVerifiedDownload(available(7), ready));
        apk.delete();
    }

    @Test
    public void keepVerifiedDownload_keepsThePermissionDetourToo() throws Exception {
        File apk = existingApk();
        UpdateStatus needsPermission = available(7).withProgress(1000L, 1000L).toReady(apk).toNeedsPermission();
        assertSame(needsPermission, UpdateManager.keepVerifiedDownload(available(7), needsPermission));
        apk.delete();
    }

    @Test
    public void keepVerifiedDownload_startsOverForADifferentVersion() throws Exception {
        File apk = existingApk();
        UpdateStatus ready = available(7).withProgress(1000L, 1000L).toReady(apk);
        UpdateStatus fresh = available(8);
        assertSame("a newer release must be offered even though an older one is on disk",
                fresh, UpdateManager.keepVerifiedDownload(fresh, ready));
        apk.delete();
    }

    @Test
    public void keepVerifiedDownload_ignoresAnUndownloadedOrMissingFile() throws Exception {
        File apk = existingApk();
        UpdateStatus ready = available(7).withProgress(1000L, 1000L).toReady(apk);
        UpdateStatus fresh = available(7);

        assertSame("nothing downloaded yet", fresh,
                UpdateManager.keepVerifiedDownload(fresh, available(7)));
        apk.delete();
        assertSame("the file is gone; the download must happen again", fresh,
                UpdateManager.keepVerifiedDownload(fresh, ready));
    }

    @Test
    public void keepVerifiedDownload_doesNotResurrectATransientInstallingState() throws Exception {
        // A check that started before the installer was launched must not bring INSTALLING back:
        // the screen would then sit at "waiting for the installer" after the handover is over.
        File apk = existingApk();
        UpdateStatus installing = available(7).withProgress(1000L, 1000L).toReady(apk).toInstalling();
        UpdateStatus fresh = available(7);
        assertSame(fresh, UpdateManager.keepVerifiedDownload(fresh, installing));
        assertEquals(UpdateState.UPDATE_AVAILABLE,
                UpdateManager.keepVerifiedDownload(fresh, installing).state);
        apk.delete();
    }

    @Test
    public void keepVerifiedDownload_leavesOtherStatesAlone() throws Exception {
        UpdateStatus upToDate = UpdateStatus.upToDate(manifest(7), 1L);
        File apk = existingApk();
        UpdateStatus ready = available(7).withProgress(1000L, 1000L).toReady(apk);
        assertSame(upToDate, UpdateManager.keepVerifiedDownload(upToDate, ready));
        assertEquals(UpdateState.UP_TO_DATE, UpdateManager.keepVerifiedDownload(upToDate, ready).state);
        apk.delete();
    }
}
