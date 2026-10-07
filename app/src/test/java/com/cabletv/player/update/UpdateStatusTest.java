package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/**
 * The status object carries the update across steps (found → downloading → ready), so these cases
 * pin down what each transition keeps: the manifest, the chosen APK and the version being offered.
 */
public class UpdateStatusTest {

    private static final String SHA = "00112233445566778899aabbccddeeff"
            + "00112233445566778899aabbccddeeff";

    private static UpdateManifest manifest() {
        return UpdateManifest.parse("{\"versionCode\":7,\"versionName\":\"1.5.0\","
                + "\"changelog\":[\"note\"],"
                + "\"variants\":{\"x86\":{\"url\":\"https://github.com/vibe4free/LiteTV/releases/"
                + "download/v1.5.0/LiteTV.apk\",\"sha256\":\"" + SHA + "\",\"size\":1000}}}");
    }

    private static UpdateStatus available() {
        UpdateManifest manifest = manifest();
        return UpdateStatus.available(manifest, manifest.variantFor(new String[]{"x86"}), 42L);
    }

    @Test
    public void available_keepsTheVersionAndTheChosenApk() {
        UpdateStatus status = available();
        assertTrue(status.isUpdateAvailable());
        assertEquals(7, status.latestVersionCode);
        assertEquals("1.5.0", status.latestVersionName);
        assertEquals(1, status.changelog.size());
        assertNotNull(status.variant);
        assertEquals(42L, status.checkedAtMs);
    }

    @Test
    public void installTransitions_keepTheVerifiedFile() {
        File apk = new File("/tmp/update.apk");
        UpdateStatus ready = available().withProgress(1000L, 1000L).toReady(apk);

        assertEquals(UpdateState.INSTALLING, ready.toInstalling().state);
        assertEquals("the installer needs the file it was handed", apk, ready.toInstalling().apkFile);

        UpdateStatus needsPermission = ready.toNeedsPermission();
        assertEquals(UpdateState.NEEDS_PERMISSION, needsPermission.state);
        assertEquals("granting the permission must not require a re-download", apk, needsPermission.apkFile);

        UpdateStatus refused = ready.readyAgain(UpdateError.INSTALL_BLOCKED);
        assertEquals("a blocked install leaves the verified file in place", UpdateState.READY, refused.state);
        assertEquals(apk, refused.apkFile);
        assertEquals(UpdateError.INSTALL_BLOCKED, refused.error);
    }

    @Test
    public void withProgress_keepsTheManifestAndTheApkChoice() {
        assertFalse(UpdateStatus.idle().isUpdateAvailable());
        UpdateStatus status = available().withProgress(250L, 1000L);
        assertEquals(UpdateState.DOWNLOADING, status.state);
        assertNotNull("a download must not forget which build it is fetching", status.variant);
        assertEquals(7, status.latestVersionCode);
        assertEquals(0.25f, status.progressFraction(), 0.001f);
    }

    @Test
    public void progressFraction_survivesAnUnknownTotal() {
        assertEquals(0f, available().withProgress(500L, 0L).progressFraction(), 0.001f);
    }

    @Test
    public void toReady_keepsTheVerifiedFile() {
        File apk = new File("/tmp/update.apk");
        UpdateStatus status = available().withProgress(1000L, 1000L).toReady(apk);
        assertEquals(UpdateState.READY, status.state);
        assertEquals(apk, status.apkFile);
        assertEquals(1f, status.progressFraction(), 0.001f);
    }

    @Test
    public void backToAvailable_keepsOfferingTheUpdateAfterACancel() {
        UpdateStatus status = available().withProgress(400L, 1000L)
                .backToAvailable(UpdateError.CANCELLED);
        assertTrue("a cancelled download is not a dead end", status.isUpdateAvailable());
        assertEquals(UpdateError.CANCELLED, status.error);
        assertEquals("progress is kept so a retry can resume", 400L, status.downloadedBytes);
    }

    @Test
    public void toError_keepsTheOfferWhenAnApkWasAlreadyChosen() {
        UpdateStatus withApk = available().toError(UpdateError.NETWORK);
        assertTrue("a failed download must stay retryable", withApk.isUpdateAvailable());
        assertEquals(UpdateError.NETWORK, withApk.error);

        UpdateStatus withoutApk = UpdateStatus.error(UpdateError.PARSE, 1L)
                .toError(UpdateError.NETWORK);
        assertEquals("nothing to retry without a chosen APK", UpdateState.ERROR, withoutApk.state);
    }

    @Test
    public void idleAndUpToDate_haveNoUpdateOnOffer() {
        assertFalse(UpdateStatus.idle().isUpdateAvailable());
        assertFalse(UpdateStatus.upToDate(manifest(), 1L).isUpdateAvailable());
        assertNull(UpdateStatus.upToDate(manifest(), 1L).variant);
        assertEquals(1, UpdateStatus.upToDate(manifest(), 1L).changelog.size());
        assertEquals(UpdateState.CHECKING, UpdateStatus.checking().state);
    }
}
