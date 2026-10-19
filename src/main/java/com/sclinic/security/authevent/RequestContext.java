package com.sclinic.security.authevent;

/**
 * The caller's network context, captured for security forensics.
 *
 * <p>Built by {@link RequestContextResolver}. There is deliberately no static
 * factory taking an {@code HttpServletRequest}: deciding whether to believe
 * {@code X-Forwarded-For} requires knowing how many proxies are in front of the
 * application, a static method has nowhere to read that from, and the version
 * that existed here before simply trusted the header. Keeping a convenient
 * unsafe path alongside the safe one guarantees the unsafe one gets used.
 *
 * @param ip        client address as far as it can be established
 * @param userAgent client user agent, truncated to keep rows bounded
 */
public record RequestContext(String ip, String userAgent) {

    /** For work with no request behind it: startup seeding, scheduled jobs. */
    public static RequestContext none() {
        return new RequestContext(null, null);
    }
}
