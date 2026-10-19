package com.sclinic.security.authevent;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which address gets recorded against a login attempt.
 *
 * <p>This matters because the value is written to {@code auth_event} and hashed
 * into the audit chain, where it sits next to facts the server established. The
 * code this replaced read the first entry of {@code X-Forwarded-For} whenever one
 * was present, which made the recorded address the client's to choose: send
 * {@code X-Forwarded-For: 10.0.0.1} and that is what the trail says.
 */
class RequestContextResolverTest {

    private static final String PEER = "203.0.113.9";

    private RequestContextResolver resolverTrusting(int proxies) {
        return new RequestContextResolver(new ForwardedHeaderProperties(proxies));
    }

    private MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PEER);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Nested
    class WithNoProxyConfigured {

        @Test
        void theHeaderIsIgnoredEntirely() {
            RequestContext context = resolverTrusting(0).resolve(request("10.0.0.1"));

            // The default, and the only safe default: a deployment where nobody
            // has thought about proxies records the peer address, which may be
            // uninformative but is at least not supplied by the caller.
            assertThat(context.ip()).isEqualTo(PEER);
        }

        @Test
        void aForgedChainOfEntriesChangesNothing() {
            RequestContext context = resolverTrusting(0)
                    .resolve(request("1.1.1.1, 2.2.2.2, 3.3.3.3"));

            assertThat(context.ip()).isEqualTo(PEER);
        }

        @Test
        void aNegativeConfiguredCountIsTreatedAsZero() {
            // Rather than reading entries from the wrong end of the list.
            RequestContext context = resolverTrusting(-5).resolve(request("10.0.0.1"));

            assertThat(context.ip()).isEqualTo(PEER);
        }
    }

    @Nested
    class WithOneProxyConfigured {

        @Test
        void theEntryOurProxyAppendedIsUsed() {
            // One proxy, and the client sent nothing: the proxy appended the
            // address it saw, which is the real client.
            RequestContext context = resolverTrusting(1).resolve(request("198.51.100.7"));

            assertThat(context.ip()).isEqualTo("198.51.100.7");
        }

        @Test
        void entriesTheClientPrependedAreSkipped() {
            // The client sent "1.1.1.1" hoping to be recorded as that; our proxy
            // appended the address it actually received the request from. Counting
            // from the right takes the proxy's entry.
            RequestContext context = resolverTrusting(1)
                    .resolve(request("1.1.1.1, 198.51.100.7"));

            assertThat(context.ip()).isEqualTo("198.51.100.7");
        }

        @Test
        void alongerForgedChainStillYieldsTheRightmostEntry() {
            RequestContext context = resolverTrusting(1)
                    .resolve(request("1.1.1.1, 2.2.2.2, 3.3.3.3, 198.51.100.7"));

            assertThat(context.ip()).isEqualTo("198.51.100.7");
        }

        @Test
        void aMissingHeaderFallsBackToThePeerAddress() {
            // Configured to expect a proxy and got no header from one. The peer
            // address is the honest answer; a deployment fault should look like
            // one rather than be papered over with an invented value.
            RequestContext context = resolverTrusting(1).resolve(request(null));

            assertThat(context.ip()).isEqualTo(PEER);
        }

        @Test
        void aBlankEntryFallsBackToThePeerAddress() {
            assertThat(resolverTrusting(1).resolve(request("  ")).ip()).isEqualTo(PEER);
            assertThat(resolverTrusting(1).resolve(request("1.1.1.1,  ")).ip()).isEqualTo(PEER);
        }
    }

    @Nested
    class WithTwoProxiesConfigured {

        @Test
        void theEntryTwoFromTheRightIsUsed() {
            // Two proxies in the path, so the two rightmost entries were written
            // by our own infrastructure and the client is the one before them.
            RequestContext context = resolverTrusting(2)
                    .resolve(request("198.51.100.7, 10.0.0.2"));

            assertThat(context.ip()).isEqualTo("198.51.100.7");
        }

        @Test
        void aClientSendingFewerEntriesThanExpectedDoesNotReadOffTheList() {
            // Misconfiguration, or a request that reached the app through a
            // shorter path. Taking index 0 is the closest thing to correct and
            // cannot throw.
            RequestContext context = resolverTrusting(2).resolve(request("198.51.100.7"));

            assertThat(context.ip()).isEqualTo("198.51.100.7");
        }
    }

    @Nested
    class Bounds {

        @Test
        void anOverlongForwardedValueIsTruncated() {
            String huge = "9".repeat(5000);
            RequestContext context = resolverTrusting(1).resolve(request(huge));

            // The header is attacker-controlled and the value is stored on every
            // audit row the request touches. Without a cap, one request writes a
            // megabyte per entry.
            assertThat(context.ip()).hasSize(64);
        }

        @Test
        void anOverlongUserAgentIsTruncated() {
            MockHttpServletRequest request = request(null);
            request.addHeader("User-Agent", "U".repeat(5000));

            assertThat(resolverTrusting(0).resolve(request).userAgent()).hasSize(512);
        }

        @Test
        void aMissingUserAgentIsNull() {
            assertThat(resolverTrusting(0).resolve(request(null)).userAgent()).isNull();
        }

        @Test
        void noRequestAtAllYieldsAnEmptyContext() {
            // Scheduled jobs and startup seeding write audit entries with no
            // request behind them.
            RequestContext context = resolverTrusting(0).resolve(null);

            assertThat(context.ip()).isNull();
            assertThat(context.userAgent()).isNull();
        }
    }
}
