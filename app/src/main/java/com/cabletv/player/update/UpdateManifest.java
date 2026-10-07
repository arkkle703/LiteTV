package com.cabletv.player.update;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The {@code update.json} asset that each release publishes under a fixed name, so
 * {@code releases/latest/download/update.json} always resolves to the newest one without going
 * through the GitHub API (anonymous API calls are limited to 60 per hour per IP, which shared
 * connections in China burn through quickly).
 *
 * <p>Parsing is strict on purpose: a manifest that is missing a checksum or points somewhere other
 * than GitHub is rejected outright rather than half-trusted.
 */
public class UpdateManifest {

    /** One downloadable APK. */
    public static class Variant {
        public String url;
        public String sha256;
        public long size;
    }

    public int versionCode;
    public String versionName;
    public int minVersionCode;
    public List<String> changelog;
    public String releaseUrl;
    public Map<String, Variant> variants;

    /** Parses and validates; throws {@link IllegalArgumentException} when the manifest is unusable. */
    public static UpdateManifest parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("empty update manifest");
        }
        UpdateManifest manifest;
        try {
            manifest = new Gson().fromJson(json, UpdateManifest.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("malformed update manifest", e);
        }
        if (manifest == null) {
            throw new IllegalArgumentException("malformed update manifest");
        }
        manifest.validate();
        return manifest;
    }

    private void validate() {
        if (versionCode <= 0) {
            throw new IllegalArgumentException("update manifest has no versionCode");
        }
        if (versionName == null || versionName.trim().isEmpty()) {
            throw new IllegalArgumentException("update manifest has no versionName");
        }
        if (releaseUrl != null && !releaseUrl.isEmpty() && !UpdateUrlPolicy.isAllowed(releaseUrl)) {
            throw new IllegalArgumentException("release page is not on GitHub: " + releaseUrl);
        }
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("update manifest has no APKs");
        }
        for (Map.Entry<String, Variant> entry : variants.entrySet()) {
            Variant variant = entry.getValue();
            String abi = entry.getKey();
            if (!isKnownAbi(abi)) {
                throw new IllegalArgumentException("unknown ABI in update manifest: " + abi);
            }
            if (variant == null || !UpdateUrlPolicy.isAllowed(variant.url)) {
                throw new IllegalArgumentException("APK for " + abi + " is not on GitHub");
            }
            if (!isSha256(variant.sha256)) {
                throw new IllegalArgumentException("APK for " + abi + " has no usable checksum");
            }
            if (variant.size <= 0) {
                throw new IllegalArgumentException("APK for " + abi + " has no size");
            }
        }
    }

    /** True when this manifest describes a build newer than the one installed. */
    public boolean isNewerThan(int installedVersionCode) {
        return versionCode > installedVersionCode;
    }

    /** Changelog lines, never null. */
    public List<String> changelogLines() {
        return changelog != null ? changelog : Collections.<String>emptyList();
    }

    /**
     * Picks the APK for this device by walking {@code Build.SUPPORTED_ABIS} in order — the first
     * architecture the device lists is also the one it prefers. Returns null when the manifest has
     * nothing for this device.
     */
    public Variant variantFor(String[] supportedAbis) {
        if (supportedAbis == null) {
            return null;
        }
        for (String abi : supportedAbis) {
            String key = abiKeyFor(abi);
            if (key != null && variants != null) {
                Variant variant = variants.get(key);
                if (variant != null) {
                    return variant;
                }
            }
        }
        return null;
    }

    /** Maps a device ABI onto the key used in the manifest; null for architectures we do not build. */
    public static String abiKeyFor(String abi) {
        if (abi == null) {
            return null;
        }
        switch (abi) {
            case "arm64-v8a":
                return "arm64-v8a";
            // 32-bit ARM devices report either name; the v7a build is the one that runs.
            case "armeabi-v7a":
            case "armeabi":
                return "armeabi-v7a";
            case "x86_64":
                return "x86_64";
            case "x86":
                return "x86";
            default:
                return null;
        }
    }

    private static boolean isKnownAbi(String abi) {
        return "arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)
                || "x86_64".equals(abi) || "x86".equals(abi);
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
