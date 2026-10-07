package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;

/**
 * The checker turns "whatever GitHub answered" into exactly one status. These tests drive it with a
 * stub fetcher, which is why the network sits behind {@link UpdateFetcher}: no test here needs a
 * connection, and every failure branch is reachable.
 */
public class UpdateCheckerTest {

    private static final String SHA = "00112233445566778899aabbccddeeff"
            + "00112233445566778899aabbccddeeff";
    private static final String[] ARM64_DEVICE = {"arm64-v8a", "armeabi-v7a", "armeabi"};

    private static String manifestJson(int versionCode, String abi) {
        return "{\"versionCode\":" + versionCode + ",\"versionName\":\"" + versionCode + ".0.0\","
                + "\"changelog\":[\"note\"],"
                + "\"variants\":{\"" + abi + "\":{\"url\":\"https://github.com/vibe4free/LiteTV/"
                + "releases/download/v" + versionCode + "/LiteTV.apk\","
                + "\"sha256\":\"" + SHA + "\",\"size\":1234}}}";
    }

    @Test
    public void check_reportsANewerBuildForThisDevice() {
        UpdateStatus status = new UpdateChecker(fetcherReturning(manifestJson(7, "arm64-v8a")))
                .check(6, ARM64_DEVICE);
        assertEquals(UpdateState.UPDATE_AVAILABLE, status.state);
        assertEquals(7, status.latestVersionCode);
        assertEquals("7.0.0", status.latestVersionName);
        assertEquals(1, status.changelog.size());
        assertNotNull("the APK for this device must be resolved before offering the update", status.variant);
        assertEquals(1234, status.variant.size);
    }

    @Test
    public void check_isUpToDateWhenTheInstalledBuildIsNotOlder() {
        UpdateChecker checker = new UpdateChecker(fetcherReturning(manifestJson(7, "arm64-v8a")));
        assertEquals(UpdateState.UP_TO_DATE, checker.check(7, ARM64_DEVICE).state);
        assertEquals("an older manifest must never offer a downgrade",
                UpdateState.UP_TO_DATE, checker.check(8, ARM64_DEVICE).state);
    }

    @Test
    public void check_reportsWhenTheManifestHasNoBuildForThisDevice() {
        UpdateStatus status = new UpdateChecker(fetcherReturning(manifestJson(7, "x86")))
                .check(6, ARM64_DEVICE);
        assertEquals(UpdateState.ERROR, status.state);
        assertEquals(UpdateError.NO_BUILD_FOR_DEVICE, status.error);
        assertEquals("the version is still worth showing", 7, status.latestVersionCode);
        assertNull(status.variant);
    }

    @Test
    public void check_classifiesFailures() {
        assertEquals(UpdateError.NETWORK,
                new UpdateChecker(failingWith(new IOException("offline"))).check(6, ARM64_DEVICE).error);
        assertEquals(UpdateError.HTTP_NOT_FOUND,
                new UpdateChecker(failingWith(new UpdateFetcher.HttpStatusException(404, "x")))
                        .check(6, ARM64_DEVICE).error);
        assertEquals(UpdateError.HTTP,
                new UpdateChecker(failingWith(new UpdateFetcher.HttpStatusException(500, "x")))
                        .check(6, ARM64_DEVICE).error);
        assertEquals(UpdateError.PARSE,
                new UpdateChecker(fetcherReturning("<html>nope</html>")).check(6, ARM64_DEVICE).error);
    }

    @Test
    public void check_fetchesTheFixedManifestAsset() {
        RecordingFetcher fetcher = new RecordingFetcher(manifestJson(6, "arm64-v8a"));
        new UpdateChecker(fetcher).check(6, ARM64_DEVICE);
        assertEquals("the fixed asset name is what makes releases/latest/download/ work",
                "https://github.com/vibe4free/LiteTV/releases/latest/download/update.json",
                fetcher.requestedUrl);
    }

    private static UpdateFetcher fetcherReturning(final String body) {
        return new RecordingFetcher(body);
    }

    private static UpdateFetcher failingWith(final IOException failure) {
        return new UpdateFetcher() {
            @Override
            public String fetch(String url) throws IOException {
                throw failure;
            }
        };
    }

    private static class RecordingFetcher implements UpdateFetcher {
        private final String body;
        String requestedUrl;

        RecordingFetcher(String body) {
            this.body = body;
        }

        @Override
        public String fetch(String url) {
            requestedUrl = url;
            return body;
        }
    }
}
