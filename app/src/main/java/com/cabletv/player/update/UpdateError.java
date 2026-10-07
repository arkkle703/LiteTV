package com.cabletv.player.update;

/**
 * Why an update check failed, in terms the UI can translate. Keeping the reason as an enum rather
 * than a raw HTTP code or exception message means the screens stay localised and the stored
 * "last error" stays readable after the fact.
 */
public enum UpdateError {
    /** Last attempt succeeded. */
    NONE,
    /** Could not reach GitHub at all (no network, DNS, TLS, timeout). */
    NETWORK,
    /**
     * GitHub answered, but not with the manifest. A 404 is normal until a release ships the
     * manifest asset, so it gets its own value instead of looking like a server outage.
     */
    HTTP_NOT_FOUND,
    /** GitHub answered with any other error status. */
    HTTP,
    /** The manifest was fetched but is not usable (malformed JSON, missing or unsafe fields). */
    PARSE,
    /** The manifest has no APK for this device's CPU architecture. */
    NO_BUILD_FOR_DEVICE,
    UNKNOWN
}
