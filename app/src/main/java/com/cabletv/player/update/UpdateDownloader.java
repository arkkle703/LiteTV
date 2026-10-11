package com.cabletv.player.update;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the release APK into the app's cache, resuming a partial file when it can.
 *
 * <p>One file name, one location ({@code <cache>/update.apk}) with the partial download next to it
 * as {@code update.apk.part}: the cache needs no storage permission on any supported Android, and a
 * single well-known name is what lets a retry pick up where the last attempt stopped.
 *
 * <p>Redirects are followed by hand so each hop can be checked, and the finished file is verified
 * against the size and checksum from the manifest before it is handed on. That check is what makes
 * the download trustworthy — the transport is a plain HTTP client, the manifest is the only thing
 * that says what the bytes should be.
 */
public class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";
    private static final String TARGET_NAME = "update.apk";
    private static final String PART_SUFFIX = ".part";
    /** Nothing legitimate is this big; a runaway or hostile response must not fill the cache. */
    private static final long MAX_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 5;
    /** Progress callbacks are for a status line, not for every 32 KB chunk. */
    private static final long PROGRESS_INTERVAL_MS = 250L;

    /** Called from the download thread; the total is 0 when the size is unknown. */
    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    /** A download that did not produce a usable file, with the reason the UI should show. */
    public static class DownloadException extends IOException {
        public final UpdateError error;

        public DownloadException(UpdateError error, String message) {
            this(error, message, null);
        }

        public DownloadException(UpdateError error, String message, Throwable cause) {
            super(message, cause);
            this.error = error;
        }
    }

    private final String mRequiredScheme;
    private final OkHttpClient mClient;

    public UpdateDownloader() {
        this("https");
    }

    /** The scheme is a parameter so tests can drive a local server; production requires HTTPS. */
    UpdateDownloader(String requiredScheme) {
        mRequiredScheme = requiredScheme;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false);
        // Same reason as the manifest fetch: the APK is served from GitHub's content hosts, which
        // old devices cannot validate without the bundled roots.
        javax.net.ssl.SSLContext tls = com.cabletv.player.util.TlsCompat.sslContext();
        if (tls != null) {
            builder.sslSocketFactory(tls.getSocketFactory(), com.cabletv.player.util.TlsCompat.trustManager());
        }
        mClient = builder.build();
    }

    /** Where the download lives. External cache first: it is bigger and never needs a permission. */
    public static File targetFile(Context context) {
        File dir = context.getExternalCacheDir();
        if (dir == null) {
            dir = context.getCacheDir();
        }
        return new File(dir, TARGET_NAME);
    }

    /**
     * Blocking; call off the main thread. Returns the verified file, or throws.
     *
     * @param expectedSize   size from the manifest; 0 when unknown
     * @param expectedSha256 lowercase hex checksum from the manifest
     * @param cancel         polled while streaming; cancelling keeps the partial file for a resume
     */
    public File download(Context context, String url, long expectedSize, String expectedSha256,
                         Progress progress, AtomicBoolean cancel) throws DownloadException {
        File target = targetFile(context);
        File part = new File(target.getParentFile(), TARGET_NAME + PART_SUFFIX);

        // A previous attempt may have completed; re-downloading would be pure waste.
        if (target.isFile() && isIntact(target, expectedSize, expectedSha256)) {
            return target;
        }
        deleteQuietly(target);

        long resumeFrom = part.isFile() ? part.length() : 0L;
        if (resumeFrom > 0L && expectedSize > 0L && resumeFrom >= expectedSize) {
            // The partial file cannot be a prefix of this download; start over.
            deleteQuietly(part);
            resumeFrom = 0L;
        }

        boolean completed = stream(url, part, resumeFrom, expectedSize, progress, cancel);
        if (!completed) {
            // The server refused the range (or answered 200 to it); one clean retry, then give up.
            completed = stream(url, part, 0L, expectedSize, progress, cancel);
        }
        if (!completed) {
            throw new DownloadException(UpdateError.NETWORK, "the download could not be resumed");
        }

        if (!part.renameTo(target)) {
            throw new DownloadException(UpdateError.STORAGE, "cannot move the download into place");
        }
        if (expectedSize > 0L && target.length() != expectedSize) {
            deleteQuietly(target);
            throw new DownloadException(UpdateError.CHECKSUM,
                    "downloaded " + target.length() + " bytes, expected " + expectedSize);
        }
        if (!expectedSha256.equalsIgnoreCase(sha256Of(target))) {
            // A file that does not match the manifest is not ours; never offer it for install.
            deleteQuietly(target);
            throw new DownloadException(UpdateError.CHECKSUM, "checksum mismatch");
        }
        Log.i(TAG, "Downloaded and verified " + target.length() + " bytes");
        return target;
    }

    /** Deletes both the finished download and any partial file. */
    public static void clear(Context context) {
        File target = targetFile(context);
        deleteQuietly(target);
        deleteQuietly(new File(target.getParentFile(), TARGET_NAME + PART_SUFFIX));
    }

    /**
     * One HTTP attempt.
     *
     * @return true when the file was streamed to completion, false when the range request was
     *         refused and the caller should retry from scratch
     */
    private boolean stream(String url, File part, long resumeFrom, long expectedSize,
                           Progress progress, AtomicBoolean cancel) throws DownloadException {
        boolean append = resumeFrom > 0L;
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requireScheme(current);
            Request.Builder request = new Request.Builder().url(current);
            if (append) {
                request.header("Range", "bytes=" + resumeFrom + "-");
            }
            Response response = null;
            try {
                response = mClient.newCall(request.build()).execute();
                int code = response.code();
                if (isRedirect(code)) {
                    current = resolve(current, response.header("Location"));
                    continue;
                }
                if (code == 416) {
                    // The server does not have the bytes we asked for: restart.
                    deleteQuietly(part);
                    return false;
                }
                if (append && code == 200) {
                    // Range ignored: appending would corrupt the file.
                    deleteQuietly(part);
                    return false;
                }
                if (code != 200 && code != 206) {
                    throw new DownloadException(UpdateError.HTTP, "HTTP " + code + " for " + current);
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new DownloadException(UpdateError.NETWORK, "empty response from " + current);
                }
                long total = expectedSize > 0L
                        ? expectedSize
                        : (resumeFrom + Math.max(0L, body.contentLength()));
                write(body.byteStream(), part, append, resumeFrom, total, progress, cancel);
                return true;
            } catch (IOException e) {
                if (e instanceof DownloadException) {
                    throw (DownloadException) e;
                }
                throw new DownloadException(UpdateError.NETWORK, "download failed: " + current, e);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
        throw new DownloadException(UpdateError.NETWORK, "too many redirects for " + url);
    }

    private void write(InputStream in, File part, boolean append, long alreadyBytes, long total,
                       Progress progress, AtomicBoolean cancel) throws DownloadException {
        long downloaded = alreadyBytes;
        long lastReport = 0L;
        byte[] buffer = new byte[32 * 1024];
        FileOutputStream out;
        try {
            out = new FileOutputStream(part, append);
        } catch (IOException e) {
            throw new DownloadException(UpdateError.STORAGE, "cannot open " + part, e);
        }
        try {
            int read;
            while (true) {
                if (cancel != null && cancel.get()) {
                    // Checked before reading: a cancel that arrives while the last chunk is being
                    // written must still stop the download instead of finishing it.
                    // Keep the partial file: cancelling is not a failure, it is a pause.
                    throw new DownloadException(UpdateError.CANCELLED, "download cancelled");
                }
                try {
                    read = in.read(buffer);
                } catch (IOException e) {
                    // Reading is the network side; the file is left in place for a resume.
                    throw new DownloadException(UpdateError.NETWORK, "connection lost", e);
                }
                if (read == -1) {
                    break;
                }
                downloaded += read;
                if (downloaded > MAX_BYTES) {
                    throw new DownloadException(UpdateError.CHECKSUM, "download is implausibly large");
                }
                try {
                    out.write(buffer, 0, read);
                } catch (IOException e) {
                    throw new DownloadException(UpdateError.STORAGE, "cannot write " + part, e);
                }
                long now = System.currentTimeMillis();
                if (progress != null && now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    progress.onProgress(downloaded, total);
                }
            }
            try {
                out.flush();
            } catch (IOException e) {
                throw new DownloadException(UpdateError.STORAGE, "cannot flush " + part, e);
            }
        } finally {
            closeQuietly(out);
        }
        if (progress != null) {
            progress.onProgress(downloaded, total > 0L ? total : downloaded);
        }
    }

    private void requireScheme(String url) throws DownloadException {
        if (url == null || !url.toLowerCase().startsWith(mRequiredScheme + "://")) {
            throw new DownloadException(UpdateError.HTTP, "refusing a " + mRequiredScheme + " download from " + url);
        }
    }

    private static String resolve(String base, String location) throws DownloadException {
        try {
            return UpdateUrlPolicy.resolve(base, location);
        } catch (IOException e) {
            throw new DownloadException(UpdateError.NETWORK, "unusable redirect target", e);
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static boolean isIntact(File file, long expectedSize, String expectedSha256) {
        if (expectedSize > 0L && file.length() != expectedSize) {
            return false;
        }
        return expectedSha256.equalsIgnoreCase(sha256Of(file));
    }

    /** Lowercase hex SHA-256 of a file, or "" when it cannot be read. */
    public static String sha256Of(File file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot hash " + file, e);
            return "";
        } finally {
            closeQuietly(in);
        }
        return toHex(digest.digest());
    }

    static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Cannot delete " + file);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing a stream we are done with cannot change the outcome.
        }
    }
}
