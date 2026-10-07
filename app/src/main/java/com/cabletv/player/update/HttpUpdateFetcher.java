package com.cabletv.player.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the update manifest over HTTPS from GitHub.
 *
 * <p>Redirects are followed by hand ({@code followRedirects(false)}) rather than by OkHttp, so
 * every hop can be checked against {@link UpdateUrlPolicy} — GitHub answers release downloads with
 * a redirect to a content host, and that hop is exactly where a tampered redirect would take the
 * updater somewhere it should not go.
 */
public class HttpUpdateFetcher implements UpdateFetcher {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_REDIRECTS = 5;
    /** The manifest is a few KB; anything larger is not one. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final OkHttpClient mClient;

    public HttpUpdateFetcher() {
        mClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    @Override
    public String fetch(String url) throws IOException {
        String current = url;
        requireAllowed(current);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Request request = new Request.Builder()
                    .url(current)
                    .header("Accept", "application/json")
                    .build();
            Response response = null;
            try {
                response = mClient.newCall(request).execute();
                int code = response.code();
                if (isRedirect(code)) {
                    current = UpdateUrlPolicy.resolve(current, response.header("Location"));
                    requireAllowed(current);
                    continue;
                }
                if (code != 200) {
                    throw new HttpStatusException(code, current);
                }
                if (response.body() == null) {
                    throw new IOException("empty update response from " + current);
                }
                return readBounded(response.body().byteStream());
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
        throw new IOException("too many redirects for " + url);
    }

    private static void requireAllowed(String url) throws IOException {
        if (!UpdateUrlPolicy.isAllowed(url)) {
            throw new IOException("refusing to fetch an update from outside GitHub: " + url);
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("update manifest is larger than " + MAX_BODY_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), UTF_8);
    }
}
