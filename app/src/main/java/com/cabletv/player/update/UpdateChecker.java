package com.cabletv.player.update;

/**
 * Turns "the release manifest on GitHub" into a {@link UpdateStatus}. Deliberately free of Android
 * types so the whole decision path — version comparison, ABI pick, failure classification — is
 * plain JVM code that tests can drive with a stub {@link UpdateFetcher}.
 */
public class UpdateChecker {

    /**
     * A fixed asset name, which is what makes the {@code releases/latest/download/} form work: the
     * APK assets carry the version in their names, so only this file keeps a stable URL. Fetching
     * this instead of the GitHub API avoids the 60-per-hour anonymous rate limit.
     */
    public static final String MANIFEST_URL =
            "https://github.com/vibe4free/LiteTV/releases/latest/download/update.json";

    private final UpdateFetcher mFetcher;

    public UpdateChecker(UpdateFetcher fetcher) {
        mFetcher = fetcher;
    }

    /**
     * Blocking; call off the main thread. Never throws — every failure is classified into a status
     * so the caller has one thing to render.
     */
    public UpdateStatus check(int installedVersionCode, String[] supportedAbis) {
        long now = System.currentTimeMillis();
        String json;
        try {
            json = mFetcher.fetch(MANIFEST_URL);
        } catch (UpdateFetcher.HttpStatusException e) {
            return UpdateStatus.error(e.code == 404 ? UpdateError.HTTP_NOT_FOUND : UpdateError.HTTP, now);
        } catch (java.io.IOException e) {
            return UpdateStatus.error(UpdateError.NETWORK, now);
        }

        UpdateManifest manifest;
        try {
            manifest = UpdateManifest.parse(json);
        } catch (RuntimeException e) {
            // Covers malformed JSON and, deliberately, anything the parser throws while building
            // the object: a manifest we cannot read is a parse failure, never a crash. Release
            // builds once died here because R8 had made the model class uninstantiable.
            return UpdateStatus.error(UpdateError.PARSE, now);
        }

        if (!manifest.isNewerThan(installedVersionCode)) {
            return UpdateStatus.upToDate(manifest, now);
        }
        UpdateManifest.Variant variant = manifest.variantFor(supportedAbis);
        if (variant == null) {
            return UpdateStatus.error(UpdateError.NO_BUILD_FOR_DEVICE, manifest, now);
        }
        return UpdateStatus.available(manifest, variant, now);
    }
}
