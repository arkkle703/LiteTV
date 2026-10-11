package com.cabletv.player.util;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS trust that also works on Android releases whose certificate store predates the chains the
 * world uses now.
 *
 * <p>Android 5 to 7 ship a frozen list of certificate authorities. Several hosts we depend on
 * (GitHub for updates, and any EPG or logo server behind Let's Encrypt) chain to roots that were
 * added to Android later, and an old device can neither find the anchor nor fetch the missing
 * intermediate: the handshake fails with "Trust anchor for certification path not found". Older
 * releases are exactly what an IPTV player meets in the wild, so the app carries the few public
 * roots that matter and adds them to the platform's own list for its own connections.
 *
 * <p>This is deliberately narrow: the connections the app itself makes are given this trust set,
 * nothing else on the device changes, and hostname verification stays on. If the platform store
 * cannot be read the whole thing is abandoned ({@link #sslContext()} returns null) rather than
 * falling back to a store holding only the bundled roots — trusting less than the platform would
 * be a far worse failure than the one being fixed.
 */
public final class TlsCompat {

    private static final String TAG = "TlsCompat";

    /**
     * Public CA certificates, in PEM form, that older Android stores are missing. See
     * {@code TlsCompatTest}, which pins their fingerprints so a swapped file cannot slip through.
     */
    private static final String[] BUNDLED_CERTIFICATES = {
            "tls/sectigo-e46.pem",   // Sectigo Public Server Authentication Root E46 (GitHub, ECDSA)
            "tls/sectigo-r46.pem",   // Sectigo Public Server Authentication Root R46 (GitHub, RSA)
            "tls/isrg-root-x1.pem",  // ISRG Root X1 (Let's Encrypt)
    };

    private static volatile Context sAppContext;
    private static volatile SSLContext sContext;
    private static volatile X509TrustManager sTrustManager;
    private static volatile boolean sAttempted;

    private TlsCompat() {
    }

    /** Called once from Application.onCreate; without it the rest of this class stays inert. */
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    /** The trust set for our own HTTPS connections, or null to keep the platform's own. */
    public static SSLContext sslContext() {
        ensureBuilt();
        return sContext;
    }

    /** OkHttp needs the trust manager alongside the socket factory to validate certificates. */
    public static X509TrustManager trustManager() {
        ensureBuilt();
        return sTrustManager;
    }

    /**
     * Applies the trust set to one connection. Used where the code talks to playlist and EPG
     * servers through {@link HttpsURLConnection}.
     */
    public static void applyTo(HttpsURLConnection connection) {
        if (connection == null) {
            return;
        }
        SSLContext context = sslContext();
        if (context == null) {
            return;
        }
        connection.setSSLSocketFactory(context.getSocketFactory());
    }

    private static synchronized void ensureBuilt() {
        if (sAttempted) {
            return;
        }
        // One attempt per process: a failure here is not something a retry can fix, and callers
        // must not pay for it on every request.
        sAttempted = true;
        try {
            Context context = sAppContext;
            if (context == null) {
                Log.w(TAG, "TlsCompat.init was never called; keeping the platform trust store");
                return;
            }
            KeyStore merged = buildTrustStore(context);
            if (merged == null) {
                return;
            }
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(merged);
            X509TrustManager trustManager = null;
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) {
                    trustManager = (X509TrustManager) manager;
                    break;
                }
            }
            if (trustManager == null) {
                Log.w(TAG, "No X509 trust manager; keeping the platform trust store");
                return;
            }
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, null);
            sTrustManager = trustManager;
            sContext = sslContext;
            Log.i(TAG, "Trust store extended with " + BUNDLED_CERTIFICATES.length
                    + " bundled roots for older devices");
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            // Never let a compatibility nicety break networking: the platform default still works
            // on any device that does not need this.
            Log.w(TAG, "Cannot extend the trust store; using the platform's own", e);
            sContext = null;
            sTrustManager = null;
        }
    }

    /**
     * The platform's trusted certificates plus the bundled ones, or null when the platform store
     * cannot be read or comes back empty (in which case adding our own would trust almost nothing).
     */
    static KeyStore buildTrustStore(Context context) throws GeneralSecurityException, IOException {
        List<X509Certificate> bundled = readBundled(context);
        if (bundled.isEmpty()) {
            Log.w(TAG, "No bundled certificates could be read");
            return null;
        }
        KeyStore system = platformTrustStore();
        if (system == null) {
            return null;
        }
        List<Certificate> platformCertificates = certificatesOf(system);
        if (platformCertificates.isEmpty()) {
            Log.w(TAG, "The platform trust store is empty; refusing to replace it");
            return null;
        }
        KeyStore merged = KeyStore.getInstance(KeyStore.getDefaultType());
        merged.load(null, null);
        int index = 0;
        for (Certificate certificate : platformCertificates) {
            merged.setCertificateEntry("platform-" + index++, certificate);
        }
        for (X509Certificate certificate : bundled) {
            // Bundled entries are only added when the platform does not already have them, so a
            // device with an up-to-date store keeps using exactly what the system trusts.
            if (!platformCertificates.contains(certificate)) {
                merged.setCertificateEntry("bundled-" + index++, certificate);
            }
        }
        return merged;
    }

    /** The device's own trust store. {@code AndroidCAStore} is how Android exposes it. */
    private static KeyStore platformTrustStore() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidCAStore");
            store.load(null, null);
            return store;
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            Log.w(TAG, "Cannot open the platform trust store", e);
            return null;
        }
    }

    private static List<Certificate> certificatesOf(KeyStore store) throws GeneralSecurityException {
        List<Certificate> certificates = new ArrayList<>();
        java.util.Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = store.getCertificate(aliases.nextElement());
            if (certificate != null) {
                certificates.add(certificate);
            }
        }
        return certificates;
    }

    /** Reads the bundled PEM files; unreadable entries are reported and skipped. */
    static List<X509Certificate> readBundled(Context context) {
        List<X509Certificate> certificates = new ArrayList<>();
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            Log.w(TAG, "No X.509 certificate factory", e);
            return Collections.emptyList();
        }
        for (String asset : BUNDLED_CERTIFICATES) {
            InputStream in = null;
            try {
                in = context.getAssets().open(asset);
                certificates.add((X509Certificate) factory.generateCertificate(in));
            } catch (IOException | CertificateException | RuntimeException e) {
                Log.w(TAG, "Cannot read bundled certificate " + asset, e);
            } finally {
                closeQuietly(in);
            }
        }
        return certificates;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Reading a bundled asset cannot fail in a way that matters here.
        }
    }
}
