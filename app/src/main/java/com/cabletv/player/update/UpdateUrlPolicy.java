package com.cabletv.player.update;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Every URL the updater touches has to be HTTPS on GitHub. The check runs on the manifest URL, on
 * each redirect hop, and again on the APK URL taken out of the manifest — an update path that can
 * be pointed at an arbitrary host is an attack path, and a redirect or a hand-edited manifest is
 * the easiest way in.
 *
 * <p>Hosts are matched by suffix rather than by an exact list because GitHub serves release assets
 * from hosts it rotates (objects.githubusercontent.com, release-assets.githubusercontent.com, …).
 * The signature check on the downloaded APK is the real defence; this policy keeps the traffic
 * from leaving GitHub in the first place.
 */
public final class UpdateUrlPolicy {

    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_SUFFIX = ".github.com";
    private static final String GITHUB_CONTENT_SUFFIX = ".githubusercontent.com";

    private UpdateUrlPolicy() {
    }

    /** True when the URL is HTTPS and its host belongs to GitHub. */
    public static boolean isAllowed(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            return host.equals(GITHUB_HOST)
                    || host.endsWith(GITHUB_SUFFIX)
                    || host.endsWith(GITHUB_CONTENT_SUFFIX);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Resolves a (possibly relative) redirect target against the URL it came from. Throws when the
     * result is unusable, so callers never end up fetching something they did not validate.
     */
    public static String resolve(String base, String location) throws IOException {
        if (location == null || location.isEmpty()) {
            throw new IOException("redirect without a target");
        }
        try {
            return new URI(base).resolve(location).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("unusable redirect target: " + location, e);
        }
    }
}
