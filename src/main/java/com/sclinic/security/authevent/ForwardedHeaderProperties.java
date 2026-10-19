package com.sclinic.security.authevent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of {@code X-Forwarded-For} to believe.
 *
 * <p>The address recorded against a login attempt, and hashed into the audit
 * chain, is only worth something if the client cannot choose it. Anyone can send
 * {@code X-Forwarded-For: 10.0.0.1}, so a server that reads the header without
 * knowing how many proxies sit in front of it is recording an address the
 * attacker supplied — in an audit trail, next to facts that are real.
 *
 * @param trustedProxyCount how many reverse proxies the clinic runs in front of
 *                          this application.
 *                          <p>Zero, the default, means the header is ignored
 *                          entirely and the peer address is used. That is the
 *                          right default because it is the only one that is safe
 *                          when nobody has thought about it: running behind a
 *                          proxy with this unset records the proxy's address,
 *                          which is useless but honest, whereas trusting the
 *                          header unset records whatever the client typed, which
 *                          is worse than useless.
 *                          <p>With N greater than zero, the address is taken N
 *                          entries from the <em>right</em> of the list. Each
 *                          proxy appends the address it received the request
 *                          from, so the rightmost entries were written by your
 *                          own infrastructure and the leftmost by whoever felt
 *                          like it.
 */
@ConfigurationProperties(prefix = "sclinic.network")
public record ForwardedHeaderProperties(int trustedProxyCount) {

    public ForwardedHeaderProperties {
        if (trustedProxyCount < 0) {
            trustedProxyCount = 0;
        }
    }
}
