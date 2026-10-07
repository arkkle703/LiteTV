package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.Signature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The verifier is the last gate before a downloaded file is offered for install, so the cases that
 * matter are the refusals. Signature comparison is tested directly: building two APKs with
 * different keys is not something a JVM unit test can do, but the comparison itself is the part
 * that decides.
 */
@RunWith(RobolectricTestRunner.class)
public class ApkVerifierTest {

    private static Signature signatureOf(String seed) {
        byte[] bytes = new byte[64];
        byte[] seedBytes = seed.getBytes();
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = seedBytes[i % seedBytes.length];
        }
        return new Signature(bytes);
    }

    @Test
    public void verify_refusesAFileThatIsNotAnApk() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File notAnApk = new File(context.getCacheDir(), "not-an-apk.apk");
        try (FileOutputStream out = new FileOutputStream(notAnApk)) {
            out.write("this is not an APK".getBytes());
        }
        assertEquals(ApkVerifier.Result.NOT_AN_APK, ApkVerifier.verify(context, notAnApk, 7));
        assertEquals("a missing file must not be treated as usable",
                ApkVerifier.Result.NOT_AN_APK, ApkVerifier.verify(context, new File(context.getCacheDir(), "gone.apk"), 7));
        assertEquals(ApkVerifier.Result.NOT_AN_APK, ApkVerifier.verify(context, null, 7));
        //noinspection ResultOfMethodCallIgnored
        notAnApk.delete();
    }

    @Test
    public void sharesASigner_matchesTheInstalledCertificate() {
        Signature installed = signatureOf("release-key");
        Signature sameKey = signatureOf("release-key");
        Signature otherKey = signatureOf("some-other-key");

        assertTrue(ApkVerifier.sharesASigner(new Signature[]{sameKey}, new Signature[]{installed}));
        assertFalse("a different signing key must be refused",
                ApkVerifier.sharesASigner(new Signature[]{otherKey}, new Signature[]{installed}));
    }

    @Test
    public void sharesASigner_matchesWhenOneOfSeveralCertificatesIsShared() {
        // Signing key rotation keeps the old certificate in the history.
        Signature rotated = signatureOf("new-key");
        Signature previous = signatureOf("release-key");
        assertTrue(ApkVerifier.sharesASigner(
                new Signature[]{rotated, previous}, new Signature[]{signatureOf("release-key")}));
    }

    @Test
    public void sharesASigner_failsClosed() {
        Signature installed = signatureOf("release-key");
        assertFalse("an unsigned download must be refused",
                ApkVerifier.sharesASigner(new Signature[0], new Signature[]{installed}));
        assertFalse(ApkVerifier.sharesASigner(null, new Signature[]{installed}));
        assertFalse("without a reference certificate nothing can be vouched for",
                ApkVerifier.sharesASigner(new Signature[]{installed}, null));
        assertFalse(ApkVerifier.sharesASigner(new Signature[]{installed}, new Signature[0]));
    }

    @Test
    public void digestsOf_ignoresNullEntries() {
        assertEquals(1, ApkVerifier.digestsOf(new Signature[]{null, signatureOf("a")}).size());
        assertEquals(0, ApkVerifier.digestsOf(null).size());
    }
}
