package com.cabletv.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/**
 * The updater is only allowed to talk to GitHub over TLS. These cases are the reason the policy is
 * its own class: every redirect hop and the APK URL from the manifest go through it, so a lookalike
 * host must not slip through.
 */
public class UpdateUrlPolicyTest {

    @Test
    public void allowsGithubOverHttps() {
        assertTrue(UpdateUrlPolicy.isAllowed(
                "https://github.com/vibe4free/LiteTV/releases/latest/download/update.json"));
        assertTrue(UpdateUrlPolicy.isAllowed(
                "https://github.com/vibe4free/LiteTV/releases/download/v1.5.0/LiteTV-v1.5.0-arm64-v8a.apk"));
        assertTrue("release downloads redirect to a content host",
                UpdateUrlPolicy.isAllowed("https://objects.githubusercontent.com/github-production/x"));
        assertTrue(UpdateUrlPolicy.isAllowed("https://release-assets.githubusercontent.com/x"));
    }

    @Test
    public void rejectsEverythingElse() {
        assertFalse("plain http", UpdateUrlPolicy.isAllowed("http://github.com/x"));
        assertFalse("another host", UpdateUrlPolicy.isAllowed("https://evil.example.com/x.apk"));
        assertFalse("suffix trick", UpdateUrlPolicy.isAllowed("https://github.com.evil.example.com/x"));
        assertFalse("prefix trick", UpdateUrlPolicy.isAllowed("https://evilgithub.com/x"));
        assertFalse("userinfo trick",
                UpdateUrlPolicy.isAllowed("https://github.com@evil.example.com/x.apk"));
        assertFalse("no scheme", UpdateUrlPolicy.isAllowed("github.com/x"));
        assertFalse("no host", UpdateUrlPolicy.isAllowed("https:///x"));
        assertFalse("garbage", UpdateUrlPolicy.isAllowed("not a url"));
        assertFalse("null", UpdateUrlPolicy.isAllowed(null));
    }

    @Test
    public void resolve_handlesAbsoluteAndRelativeRedirects() throws IOException {
        assertEquals("https://objects.githubusercontent.com/b",
                UpdateUrlPolicy.resolve("https://github.com/a", "https://objects.githubusercontent.com/b"));
        assertEquals("https://github.com/b",
                UpdateUrlPolicy.resolve("https://github.com/releases/latest", "/b"));
    }

    @Test
    public void resolve_rejectsUnusableTargets() {
        assertResolveFails(null);
        assertResolveFails("");
        assertResolveFails("http://[invalid");
    }

    private static void assertResolveFails(String location) {
        try {
            UpdateUrlPolicy.resolve("https://github.com/a", location);
            fail("should have rejected the redirect target: " + location);
        } catch (IOException expected) {
            // expected
        }
    }
}
