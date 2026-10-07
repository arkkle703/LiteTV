package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The release manifest is external input published by our own release job, but it arrives over the
 * network, so parsing is strict: anything that cannot be checked (missing checksum, a URL outside
 * GitHub, an ABI we do not build) must be rejected rather than half-trusted.
 */
public class UpdateManifestTest {

    private static final String SHA = "00112233445566778899aabbccddeeff"
            + "00112233445566778899aabbccddeeff";

    private static String json(String versionCode, String variants) {
        return "{\"versionCode\":" + versionCode
                + ",\"versionName\":\"1.5.0\""
                + ",\"releaseUrl\":\"https://github.com/vibe4free/LiteTV/releases/tag/v1.5.0\""
                + ",\"changelog\":[\"first\",\"second\"]"
                + ",\"variants\":{" + variants + "}}";
    }

    private static String variant(String abi, String url, String sha, long size) {
        return "\"" + abi + "\":{\"url\":\"" + url + "\",\"sha256\":\"" + sha + "\",\"size\":" + size + "}";
    }

    private static String githubUrl(String abi) {
        return "https://github.com/vibe4free/LiteTV/releases/download/v1.5.0/LiteTV-v1.5.0-" + abi + ".apk";
    }

    private static UpdateManifest valid() {
        return UpdateManifest.parse(json("7", variant("arm64-v8a", githubUrl("arm64-v8a"), SHA, 3316072)));
    }

    @Test
    public void parse_readsVersionsAndChangelog() {
        UpdateManifest manifest = valid();
        assertEquals(7, manifest.versionCode);
        assertEquals("1.5.0", manifest.versionName);
        assertEquals(2, manifest.changelogLines().size());
        assertEquals("first", manifest.changelogLines().get(0));
    }

    @Test
    public void parse_acceptsAManifestWithoutChangelogOrReleasePage() {
        UpdateManifest manifest = UpdateManifest.parse(
                "{\"versionCode\":7,\"versionName\":\"1.5.0\",\"variants\":{"
                        + variant("x86", githubUrl("x86"), SHA, 100) + "}}");
        assertTrue(manifest.changelogLines().isEmpty());
        assertNull(manifest.releaseUrl);
    }

    @Test
    public void isNewerThan_comparesVersionCodes() {
        UpdateManifest manifest = valid();
        assertTrue(manifest.isNewerThan(6));
        assertFalse("the same version is not an update", manifest.isNewerThan(7));
        assertFalse("a downgrade is not an update", manifest.isNewerThan(8));
    }

    @Test
    public void variantFor_prefersTheDevicesFirstSupportedAbi() {
        UpdateManifest manifest = UpdateManifest.parse(json("7",
                variant("arm64-v8a", githubUrl("arm64-v8a"), SHA, 1) + ","
                        + variant("armeabi-v7a", githubUrl("armeabi-v7a"), SHA, 2)));
        assertEquals(githubUrl("arm64-v8a"),
                manifest.variantFor(new String[]{"arm64-v8a", "armeabi-v7a"}).url);
        assertEquals(githubUrl("armeabi-v7a"),
                manifest.variantFor(new String[]{"armeabi-v7a", "armeabi"}).url);
    }

    @Test
    public void variantFor_fallsThroughWhenThePreferredAbiHasNoBuild() {
        UpdateManifest manifest = UpdateManifest.parse(json("7",
                variant("x86", githubUrl("x86"), SHA, 1)));
        UpdateManifest.Variant variant =
                manifest.variantFor(new String[]{"arm64-v8a", "armeabi-v7a", "x86"});
        assertNotNull("a device must be able to fall back to a compatible build", variant);
        assertEquals(githubUrl("x86"), variant.url);
    }

    @Test
    public void variantFor_returnsNullWhenNothingMatches() {
        UpdateManifest manifest = valid();
        assertNull(manifest.variantFor(new String[]{"mips", "mips64"}));
        assertNull(manifest.variantFor(null));
    }

    @Test
    public void abiKeyFor_mapsEveryArchitectureWeShip() {
        assertEquals("arm64-v8a", UpdateManifest.abiKeyFor("arm64-v8a"));
        assertEquals("armeabi-v7a", UpdateManifest.abiKeyFor("armeabi-v7a"));
        assertEquals("armeabi-v7a", UpdateManifest.abiKeyFor("armeabi"));
        assertEquals("x86_64", UpdateManifest.abiKeyFor("x86_64"));
        assertEquals("x86", UpdateManifest.abiKeyFor("x86"));
        assertNull(UpdateManifest.abiKeyFor("riscv64"));
        assertNull(UpdateManifest.abiKeyFor(null));
    }

    @Test
    public void parse_rejectsUnusableManifests() {
        assertRejected("empty", null);
        assertRejected("empty", "   ");
        assertRejected("not json", "<html>404</html>");
        assertRejected("no versionCode", "{\"versionName\":\"1.5.0\",\"variants\":{"
                + variant("x86", githubUrl("x86"), SHA, 1) + "}}");
        assertRejected("versionCode 0", json("0", variant("x86", githubUrl("x86"), SHA, 1)));
        assertRejected("no versionName", "{\"versionCode\":7,\"variants\":{"
                + variant("x86", githubUrl("x86"), SHA, 1) + "}}");
        assertRejected("no variants", json("7", ""));
        assertRejected("unknown abi", json("7", variant("mips", githubUrl("x86"), SHA, 1)));
        assertRejected("plain http", json("7", variant("x86", "http://github.com/x.apk", SHA, 1)));
        assertRejected("not github", json("7", variant("x86", "https://evil.example.com/x.apk", SHA, 1)));
        assertRejected("no checksum", json("7", variant("x86", githubUrl("x86"), "", 1)));
        assertRejected("short checksum", json("7", variant("x86", githubUrl("x86"), "abc", 1)));
        assertRejected("non-hex checksum",
                json("7", variant("x86", githubUrl("x86"), "zz112233445566778899aabbccddeeff"
                        + "00112233445566778899aabbccddeeff", 1)));
        assertRejected("no size", json("7", variant("x86", githubUrl("x86"), SHA, 0)));
    }

    private static void assertRejected(String reason, String manifestJson) {
        try {
            UpdateManifest.parse(manifestJson);
            fail("manifest should have been rejected: " + reason);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
