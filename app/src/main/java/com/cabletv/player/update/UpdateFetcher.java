package com.cabletv.player.update;

import java.io.IOException;

/**
 * Fetches a small text document. An interface so the checker can be unit-tested without a network,
 * and so a different transport can be swapped in later without touching the update logic.
 */
public interface UpdateFetcher {

    /** Blocking. Returns the body, or throws on any failure. */
    String fetch(String url) throws IOException;

    /** Thrown when the server answered with a status other than 200. */
    class HttpStatusException extends IOException {
        public final int code;

        public HttpStatusException(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }
}
