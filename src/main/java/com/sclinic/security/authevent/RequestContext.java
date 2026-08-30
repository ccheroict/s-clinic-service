package com.sclinic.security.authevent;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's network context, captured for security forensics.
 *
 * @param ip        client address, honouring a reverse proxy header when present
 * @param userAgent client user agent, truncated to keep log rows bounded
 */
public record RequestContext(String ip, String userAgent) {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    public static RequestContext from(HttpServletRequest request) {
        if (request == null) {
            return new RequestContext(null, null);
        }
        return new RequestContext(clientIp(request), userAgent(request));
    }

    /**
     * Trusts {@code X-Forwarded-For} only for its first entry. The clinic runs
     * behind a single reverse proxy; if that changes, this is the one place to
     * revisit.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return first.trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return null;
        }
        return agent.length() > MAX_USER_AGENT_LENGTH
                ? agent.substring(0, MAX_USER_AGENT_LENGTH)
                : agent;
    }
}
