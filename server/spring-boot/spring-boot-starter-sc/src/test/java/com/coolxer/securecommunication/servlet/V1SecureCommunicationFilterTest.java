package com.coolxer.securecommunication.servlet;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class V1SecureCommunicationFilterTest {
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
