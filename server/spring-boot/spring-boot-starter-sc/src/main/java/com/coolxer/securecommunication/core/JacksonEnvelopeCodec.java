package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.SecureEnvelope;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JacksonEnvelopeCodec implements EnvelopeCodec {
    private final ObjectMapper mapper;

    public JacksonEnvelopeCodec() {
        this(new ObjectMapper());
    }

    public JacksonEnvelopeCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public SecureEnvelope decode(byte[] encoded) throws SecureProtocolException {
        try {
            return mapper.readValue(encoded, SecureEnvelope.class);
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE, exception);
        }
    }

    @Override
    public byte[] encode(SecureEnvelope envelope) throws SecureProtocolException {
        try {
            return mapper.writeValueAsBytes(envelope);
        } catch (Exception exception) {
            throw new SecureProtocolException(SecureErrorCode.INTERNAL_ERROR, exception);
        }
    }
}
