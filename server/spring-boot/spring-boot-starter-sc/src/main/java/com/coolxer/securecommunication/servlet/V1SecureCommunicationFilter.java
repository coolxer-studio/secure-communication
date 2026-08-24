package com.coolxer.securecommunication.servlet;

import com.coolxer.securecommunication.core.SecureMessageService;
import com.coolxer.securecommunication.core.ProtectedPayloadCodec;
import com.coolxer.securecommunication.core.ProtectedResponseCodec;
import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.coolxer.securecommunication.spi.SecurityPolicy;
import com.coolxer.securecommunication.spi.LogicalRouteAuthorizer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class V1SecureCommunicationFilter implements Filter {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(V1SecureCommunicationFilter.class);

    private final String prefix;
    private final SecureMessageService messages;
    private final SecurityPolicy policy;
    private final LogicalRouteAuthorizer routes;
    private final ProtectedPayloadCodec protectedPayloadCodec = new ProtectedPayloadCodec();
    private final ProtectedResponseCodec protectedResponseCodec = new ProtectedResponseCodec();

    public V1SecureCommunicationFilter(
            String prefix, SecureMessageService messages, SecurityPolicy policy,
            LogicalRouteAuthorizer routes) {
        if (prefix == null || prefix.isBlank() || !prefix.startsWith("/")) {
            throw new IllegalArgumentException("v1 message endpoint must start with '/'");
        }
        this.prefix = prefix.endsWith("/")
                ? prefix.substring(0, prefix.length() - 1)
                : prefix;
        this.messages = messages;
        this.policy = policy;
        this.routes = routes;
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain chain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)
                || !matches(request.getRequestURI())) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }
        // Browser preflight must reach Spring MVC's CORS processor. The actual
        // POST is still handled below and remains fail-closed and encrypted.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        String traceId = UUID.randomUUID().toString();
        String suite = null;
        String requestId = "-";
        String sessionSummary = "-";
        long startedAt = System.nanoTime();
        try {
            requireTransport(request);
            requireEnvelopeContentType(request);
            if (!"POST".equalsIgnoreCase(request.getMethod())
                    || !request.getRequestURI().equals(prefix)) {
                throw new SecureProtocolException(SecureErrorCode.ROUTE_MISMATCH);
            }
            byte[] encoded = readBounded(request, policy.maxEnvelopeBytes());
            SecureMessageService.OpenedRequest opened =
                    messages.openRequest(encoded);
            suite = opened.envelope().getSuite();
            requestId = opened.envelope().getRid();
            sessionSummary = summarizeSession(opened.envelope().getSid());

            com.coolxer.securecommunication.protocol.ProtectedPayload protectedPayload =
                    protectedPayloadCodec.decode(
                            opened.plaintext(), policy.maxBodyBytes());
            String[] logicalTarget = protectedPayload.path().split("\\?", 2);
            if (!routes.isAllowed(protectedPayload.method(), logicalTarget[0])) {
                throw new SecureProtocolException(SecureErrorCode.ROUTE_MISMATCH);
            }
            java.util.Map<String, String> protectedHeaders =
                    new java.util.LinkedHashMap<>(protectedPayload.headers());
            protectedHeaders.put("x-sc-request-id", opened.envelope().getRid());
            request.setAttribute("sc.transportTrust",
                    "sc1-authenticated/" + opened.envelope().getSuite());
            request.setAttribute("sc.sessionId", opened.envelope().getSid());
            V1RequestWrapper plaintextRequest = new V1RequestWrapper(
                    request,
                    protectedPayload.body(),
                    protectedPayload.method(),
                    logicalTarget[0],
                    logicalTarget.length == 2 ? logicalTarget[1] : null,
                    protectedPayload.contentType(),
                    protectedHeaders);
            BufferedResponseWrapper plaintextResponse = new BufferedResponseWrapper(response);
            chain.doFilter(plaintextRequest, plaintextResponse);

            byte[] plaintext = protectedResponseCodec.encode(
                    plaintextResponse.getContentType(), plaintextResponse.body());
            byte[] sealed = messages.sealResponse(
                    opened, plaintext, plaintextResponse.getContentType(),
                    plaintextResponse.getStatus());
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("Location", null);
            response.setContentType(ProtocolConstants.ENVELOPE_MEDIA_TYPE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentLength(sealed.length);
            response.getOutputStream().write(sealed);
            response.getOutputStream().flush();
        } catch (SecureProtocolException exception) {
            writeError(response, exception.errorCode(), traceId);
            LOGGER.warn(
                    "secure_communication_failure version=1 requestId={} session={} suite={} error={} durationMs={}",
                    safe(requestId), sessionSummary, safe(suite), exception.errorCode().code(),
                    elapsedMillis(startedAt));
        } catch (Exception exception) {
            writeError(response, SecureErrorCode.INTERNAL_ERROR, traceId);
            LOGGER.error(
                    "secure_communication_failure version=1 requestId={} session={} suite={} error={} durationMs={}",
                    safe(requestId), sessionSummary, safe(suite),
                    SecureErrorCode.INTERNAL_ERROR.code(), elapsedMillis(startedAt),
                    exception);
        }
    }

    private void requireTransport(HttpServletRequest request)
            throws SecureProtocolException {
        if (policy.requireTls() && !request.isSecure()) {
            throw new SecureProtocolException(SecureErrorCode.TLS_REQUIRED);
        }
    }

    private static void requireEnvelopeContentType(HttpServletRequest request)
            throws SecureProtocolException {
        String contentType = request.getContentType();
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if (!ProtocolConstants.ENVELOPE_MEDIA_TYPE.equalsIgnoreCase(mediaType)) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
    }

    private static byte[] readBounded(HttpServletRequest request, int maximum)
            throws IOException, SecureProtocolException {
        if (request.getContentLengthLong() > maximum) {
            throw new SecureProtocolException(SecureErrorCode.PAYLOAD_TOO_LARGE);
        }
        byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
        if (bytes.length > maximum) {
            throw new SecureProtocolException(SecureErrorCode.PAYLOAD_TOO_LARGE);
        }
        return bytes;
    }

    private boolean matches(String requestUri) {
        return requestUri.equals(prefix) || requestUri.startsWith(prefix + "/");
    }

    private static void writeError(
            HttpServletResponse response, SecureErrorCode error, String traceId)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(error.httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Trace-Id", traceId);
        String json = "{\"code\":\"" + error.code()
                + "\",\"message\":\"" + error.message()
                + "\",\"traceId\":\"" + traceId + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    private static String safe(String value) {
        return value == null ? "-" : value;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String summarizeSession(String sessionId) {
        if (sessionId == null) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
