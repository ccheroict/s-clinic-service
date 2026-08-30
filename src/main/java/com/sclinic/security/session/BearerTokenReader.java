package com.sclinic.security.session;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the bearer token from a request.
 *
 * <p>Shared by the authentication filter and by the auth endpoints, which accept
 * interim-scope tokens the filter deliberately refuses to authenticate.
 */
public final class BearerTokenReader {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private BearerTokenReader() {
    }

    /** Returns the raw token, or null when the header is absent or malformed. */
    public static String read(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader(HEADER);
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
