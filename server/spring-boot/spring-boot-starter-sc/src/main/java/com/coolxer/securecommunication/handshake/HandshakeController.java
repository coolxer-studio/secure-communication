package com.coolxer.securecommunication.handshake;

import com.coolxer.securecommunication.protocol.ProtocolConstants;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import jakarta.servlet.http.HttpServletRequest;
import com.coolxer.securecommunication.spi.SecurityPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnBean(HandshakeService.class)
public final class HandshakeController {
    private final HandshakeService handshakes;
    private final SecurityPolicy policy;

    public HandshakeController(HandshakeService handshakes, SecurityPolicy policy) {
        this.handshakes = handshakes;
        this.policy = policy;
    }

    @PostMapping(
            value = ProtocolConstants.HANDSHAKE_ENDPOINT,
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<?> start(
            @RequestBody HandshakeRequest body, HttpServletRequest request) {
        try {
            requireTls(request);
            return ResponseEntity.ok(handshakes.start(
                    body, request.getHeader("Origin"), request.getRemoteAddr()));
        } catch (SecureProtocolException exception) {
            return error(exception.errorCode());
        }
    }

    @PostMapping(
            value = ProtocolConstants.HANDSHAKE_FINISH_ENDPOINT,
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<?> finish(
            @RequestBody HandshakeFinishRequest body, HttpServletRequest request) {
        try {
            requireTls(request);
            return ResponseEntity.ok(handshakes.finish(body));
        } catch (SecureProtocolException exception) {
            return error(exception.errorCode());
        }
    }

    private void requireTls(HttpServletRequest request)
            throws SecureProtocolException {
        if (policy.requireTls() && !request.isSecure()) {
            throw new SecureProtocolException(SecureErrorCode.TLS_REQUIRED);
        }
    }

    private static ResponseEntity<Map<String, String>> error(SecureErrorCode code) {
        String traceId = UUID.randomUUID().toString();
        return ResponseEntity.status(code.httpStatus()).body(Map.of(
                "code", code.code(), "message", code.message(), "traceId", traceId));
    }
}
