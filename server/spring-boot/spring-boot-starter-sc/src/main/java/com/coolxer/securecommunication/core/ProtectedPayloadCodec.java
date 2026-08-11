package com.coolxer.securecommunication.core;

import com.coolxer.securecommunication.protocol.AadFactory;
import com.coolxer.securecommunication.protocol.ProtectedPayload;
import com.coolxer.securecommunication.protocol.SecureErrorCode;
import com.coolxer.securecommunication.protocol.SecureProtocolException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict codec for the complete logical request encrypted as one plaintext value. */
public final class ProtectedPayloadCodec {
    private static final Set<String> FIELDS = Set.of(
            "method", "path", "contentType", "headers", "body");
    private static final Pattern HEADER_NAME = Pattern.compile("[a-z0-9-]{1,64}");
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]*");
    private static final int MAX_HEADERS = 32;
    private static final int MAX_HEADER_VALUE_BYTES = 8_192;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ProtectedPayload decode(byte[] encoded, int maximumBodyBytes)
            throws SecureProtocolException {
        try {
            JsonNode root = mapper.readTree(encoded);
            if (root == null || !root.isObject()
                    || !fieldNames(root).equals(FIELDS)
                    || !root.get("method").isTextual()
                    || !root.get("path").isTextual()
                    || !root.get("contentType").isTextual()
                    || !root.get("headers").isObject()
                    || !root.get("body").isTextual()
                    || root.get("headers").size() > MAX_HEADERS) {
                throw invalid();
            }
            Map<String, String> headers = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> values = root.get("headers").fields();
            while (values.hasNext()) {
                Map.Entry<String, JsonNode> value = values.next();
                String name = value.getKey();
                if (!HEADER_NAME.matcher(name).matches() || !value.getValue().isTextual()) {
                    throw invalid();
                }
                String text = value.getValue().textValue();
                if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0
                        || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > MAX_HEADER_VALUE_BYTES) {
                    throw invalid();
                }
                headers.put(name, text);
            }
            String body = root.get("body").textValue();
            if (!BASE64URL.matcher(body).matches()) {
                throw invalid();
            }
            byte[] bytes = Base64.getUrlDecoder().decode(body);
            if (bytes.length > maximumBodyBytes) {
                throw new SecureProtocolException(SecureErrorCode.PAYLOAD_TOO_LARGE);
            }
            String method = root.get("method").textValue();
            if (!method.equals(method.toUpperCase(java.util.Locale.ROOT))
                    || !method.matches("[A-Z]{3,16}")) {
                throw invalid();
            }
            String path = root.get("path").textValue();
            if (!AadFactory.normalizePathAndQuery(path).equals(path)) {
                throw invalid();
            }
            String contentType = root.get("contentType").textValue();
            if (!AadFactory.normalizeContentType(contentType).equals(contentType)) {
                throw invalid();
            }
            return new ProtectedPayload(method, path, contentType, headers, bytes);
        } catch (SecureProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static SecureProtocolException invalid() {
        return new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
    }
}
