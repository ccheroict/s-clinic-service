package com.sclinic.security.authevent;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Works out the caller's network context.
 *
 * <p>A bean rather than a static helper because deciding whether to believe
 * {@code X-Forwarded-For} needs configuration, and the previous static method had
 * no way to reach any. It took the first entry of the header whenever one was
 * present, which means the recorded address was the client's to choose: send
 * {@code X-Forwarded-For: 10.0.0.1} and that is what lands in the audit trail,
 * hashed into the chain alongside facts the server actually established.
 */
@Component
@RequiredArgsConstructor
public class RequestContextResolver {

    /**
     * An IPv6 address with a zone identifier is the longest legitimate value, at
     * well under this. The cap exists because the header is attacker-controlled
     * and the value is stored and hashed; without it a request can write a
     * megabyte into every audit row it touches.
     */
    private static final int MAX_IP_LENGTH = 64;

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final ForwardedHeaderProperties properties;

    public RequestContext resolve(HttpServletRequest request) {
        if (request == null) {
            return new RequestContext(null, null);
        }
        return new RequestContext(clientIp(request), userAgent(request));
    }

    /**
     * The peer address, or the forwarded entry written by the clinic's own proxy
     * when the deployment is known to have one.
     *
     * <p>With {@code trustedProxyCount = N}, the entry taken is the Nth from the
     * right. A proxy appends the address it received the request from, so the
     * tail of the list is written by infrastructure under the clinic's control
     * and the head by the client. Counting from the right means a client that
     * prepends extra entries shifts only the part that was already untrusted.
     */
    private String clientIp(HttpServletRequest request) {
        int trusted = properties.trustedProxyCount();

        if (trusted <= 0) {
            return truncate(request.getRemoteAddr(), MAX_IP_LENGTH);
        }

        String forwarded = request.getHeader("X-Forwarded-For");

        if (forwarded == null || forwarded.isBlank()) {
            // Configured to expect a proxy and did not get a header from one.
            // The peer address is the honest answer; inventing nothing is better
            // than guessing, and if this is unexpected it is a deployment fault
            // to notice rather than a value to fabricate.
            return truncate(request.getRemoteAddr(), MAX_IP_LENGTH);
        }

        String[] entries = forwarded.split(",");
        // The hop our own proxy recorded. When the client sent fewer entries than
        // the configured proxy count — because it sent none and only our proxies
        // appended — index 0 is still the leftmost real one.
        int index = Math.max(0, entries.length - trusted);

        if (index >= entries.length) {
            return truncate(request.getRemoteAddr(), MAX_IP_LENGTH);
        }

        String candidate = entries[index].trim();
        return candidate.isEmpty()
                ? truncate(request.getRemoteAddr(), MAX_IP_LENGTH)
                : truncate(candidate, MAX_IP_LENGTH);
    }

    private String userAgent(HttpServletRequest request) {
        return truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
