package com.coolxer.securecommunication.servlet;

import com.coolxer.securecommunication.core.DefaultSecurityPolicy;
import com.coolxer.securecommunication.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class V1SecureCommunicationFilterTest {
    @Test
    void explicitTlsPolicyRejectsHttp() throws Exception {
        DefaultSecurityPolicy policy = new DefaultSecurityPolicy(
                Set.of(ProtocolConstants.INTERNATIONAL_SUITE), true,
                Duration.ofMinutes(5), Duration.ofMinutes(10),
                1_400_000, 1_048_576, 1_048_576, Clock.systemUTC());
        V1SecureCommunicationFilter filter = new V1SecureCommunicationFilter(
                "/sc/v1/message", null, policy, null);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/sc/v1/message");
        request.setContentType(ProtocolConstants.ENVELOPE_MEDIA_TYPE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("SC_TLS_REQUIRED");
    }

    @Test
    void corsPreflightReachesTheMvcCorsProcessor() throws Exception {
        V1SecureCommunicationFilter filter = new V1SecureCommunicationFilter(
                "/sc/v1/message", null, null, null);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "OPTIONS", "/sc/v1/message");
        request.addHeader("Origin", "http://localhost:8888");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.isCommitted()).isFalse();
    }
}
