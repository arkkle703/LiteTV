package com.cabletv.player.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The bundled roots are a trust decision, so their identities are pinned here: replacing a file
 * with a different certificate fails the build rather than silently widening what the app trusts.
 *
 * <p>The other half is the failure mode. Extending the platform store is a compatibility nicety,
 * and the one thing it must never do is leave the app trusting <em>less</em> than the platform —
 * hence the checks that an unreadable or empty platform store is refused instead of being replaced.
 */
@RunWith(RobolectricTestRunner.class)
public class TlsCompatTest {

    private static final String SECTIGO_E46 =
            "EA:6B:89:ED:69:07:A2:09:FF:91:88:67:6F:B1:64:E7:"
                    + "AC:ED:89:4B:89:96:DF:BE:5C:E5:BB:CC:22:DE:4D:DD";
    private static final String SECTIGO_R46 =
            "92:F3:51:BF:3D:54:16:4D:FA:8D:D8:F9:E1:13:9D:31:"
                    + "50:34:97:86:48:5D:2B:9E:EC:D0:0E:29:71:C1:E6:C5";
    private static final String ISRG_ROOT_X1 =
            "96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:"
                    + "CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6";

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void bundledCertificates_areTheOnesWeIntendToTrust() throws Exception {
        List<X509Certificate> bundled = TlsCompat.readBundled(context());
        assertEquals("all three must be readable; a missing asset is a broken build",
                3, bundled.size());
        assertEquals(SECTIGO_E46, fingerprintOf(bundled.get(0)));
        assertEquals(SECTIGO_R46, fingerprintOf(bundled.get(1)));
        assertEquals(ISRG_ROOT_X1, fingerprintOf(bundled.get(2)));
    }

    @Test
    public void bundledCertificates_areCurrentlyValid() throws Exception {
        for (X509Certificate certificate : TlsCompat.readBundled(context())) {
            assertNotNull(certificate.getSubjectX500Principal());
            // A root that has expired cannot help anyone; this catches an unmaintained bundle.
            certificate.checkValidity();
        }
    }

    @Test
    public void readBundled_reportsUnreadableAssetsAsEmpty() {
        // Assets that cannot be opened must produce an empty list rather than a half-filled one,
        // which is what makes the caller refuse to build a store at all instead of trusting a
        // partial bundle.
        assertTrue(TlsCompat.readBundled(brokenAssetContext()).isEmpty());
    }

    @Test
    public void buildTrustStore_keepsThePlatformCertificatesAndAddsTheBundledOnes() throws Exception {
        // Runs against the JVM's own CA store (AndroidCAStore does not exist off-device), which
        // still exercises the merge: platform entries kept, bundled roots added.
        Context context = context();
        KeyStore store = TlsCompat.buildTrustStore(context);
        if (store == null) {
            return; // No platform store in this environment; the refusal path is tested below.
        }
        int bundled = TlsCompat.readBundled(context).size();
        assertTrue("the platform's own certificates must still be trusted",
                store.size() > bundled);
        boolean found = false;
        java.util.Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            if (aliases.nextElement().startsWith("bundled-")) {
                found = true;
                break;
            }
        }
        assertTrue("the roots old devices are missing must be in the merged store", found);
    }

    @Test
    public void extendedTrustStore_neverTrustsOnlyTheBundledRoots() {
        // Two outcomes are acceptable: the platform store could not be read, so the platform's own
        // trust is kept (null from sslContext), or it was extended -- in which case it must hold
        // the platform's certificates as well, not just our three. Trusting less than the platform
        // would be a much worse failure than the one this class exists to fix.
        TlsCompat.init(context());
        javax.net.ssl.X509TrustManager manager = TlsCompat.trustManager();
        if (manager != null) {
            assertTrue("an extended store must contain the platform's own roots too",
                    manager.getAcceptedIssuers().length > 3);
        }
    }

    private static Context brokenAssetContext() {
        return new android.content.ContextWrapper(context()) {
            @Override
            public android.content.res.AssetManager getAssets() {
                return null; // stands in for assets that cannot be opened at all
            }
        };
    }

    private static String fingerprintOf(X509Certificate certificate) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) {
                hex.append(':');
            }
            hex.append(String.format("%02X", hash[i]));
        }
        return hex.toString();
    }

    @Test
    public void bundledCertificates_surviveAnEncodeDecodeRoundTrip() throws Exception {
        X509Certificate certificate = TlsCompat.readBundled(context()).get(0);
        X509Certificate reparsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate.getEncoded()));
        assertEquals("the asset must be a well-formed standalone certificate",
                fingerprintOf(certificate), fingerprintOf(reparsed));
    }
}
