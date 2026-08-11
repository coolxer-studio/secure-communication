package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.AadFactory;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encodes response metadata and body inside the authenticated ciphertext. */
public final class ProtectedResponseCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public byte[] encode(String contentType, byte[] body) throws SecureProtocolException {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("contentType", AadFactory.normalizeContentType(contentType));
            value.put("body", Base64.getUrlEncoder().withoutPadding().encodeToString(body));
            return mapper.writeValueAsBytes(value);
        } catch (SecureProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecureProtocolException(
                    com.coolxer.securecommunication.protocol.SecureErrorCode.INTERNAL_ERROR,
                    exception);
        }
    }
}
