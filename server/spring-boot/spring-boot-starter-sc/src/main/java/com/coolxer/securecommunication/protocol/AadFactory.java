package com.coolxer.securecommunication.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AadFactory {
    private AadFactory() {
    }

    public static byte[] create(
            Direction direction, SecureEnvelope envelope, RequestMetadata metadata)
            throws SecureProtocolException {
        String method = requireLine(metadata.method()).toUpperCase(Locale.ROOT);
        String path = normalizePathAndQuery(metadata.pathAndQuery());
        String contentType = normalizeContentType(metadata.contentType());
        if (!contentType.equals(envelope.getCty())) {
            throw new SecureProtocolException(SecureErrorCode.ROUTE_MISMATCH);
        }
        String aad = String.join("\n",
                "SC1",
                direction.value(),
                requireLine(envelope.getSuite()),
                requireLine(envelope.getKid()),
                requireLine(envelope.getSid()),
                Long.toString(envelope.getTs()),
                Long.toString(envelope.getSeq()),
                requireLine(envelope.getRid()),
                method,
                path,
                contentType);
        if (direction == Direction.RESPONSE) {
            aad += "\n" + envelope.getSt();
        }
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    public static String normalizeContentType(String value) throws SecureProtocolException {
        String normalized = value == null || value.isBlank()
                ? "application/octet-stream"
                : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || !normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        return normalized;
    }

    public static String normalizePathAndQuery(String value) throws SecureProtocolException {
        String checked = requireLine(value);
        if (!checked.startsWith("/") || checked.contains("#")
                || checked.contains("://") || checked.indexOf(' ') >= 0
                || checked.indexOf('?') != checked.lastIndexOf('?')) {
            throw new SecureProtocolException(SecureErrorCode.ROUTE_MISMATCH);
        }
        String[] parts = checked.split("\\?", 2);
        String path = uppercasePercentHex(parts[0]);
        if (parts.length == 1 || parts[1].isEmpty()) {
            return path;
        }
        List<String> pairs = new ArrayList<>();
        for (String pair : parts[1].split("&", -1)) {
            if (!pair.isEmpty()) {
                pairs.add(uppercasePercentHex(pair));
            }
        }
        pairs.sort(Comparator.comparing(AadFactory::queryName)
                .thenComparing(AadFactory::queryValue));
        return pairs.isEmpty() ? path : path + "?" + String.join("&", pairs);
    }

    private static String queryName(String pair) {
        int index = pair.indexOf('=');
        return index < 0 ? pair : pair.substring(0, index);
    }

    private static String queryValue(String pair) {
        int index = pair.indexOf('=');
        return index < 0 ? "" : pair.substring(index + 1);
    }

    private static String uppercasePercentHex(String value) throws SecureProtocolException {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '%') {
                if (i + 2 >= value.length()
                        || Character.digit(value.charAt(i + 1), 16) < 0
                        || Character.digit(value.charAt(i + 2), 16) < 0) {
                    throw new SecureProtocolException(SecureErrorCode.ROUTE_MISMATCH);
                }
                result.append('%')
                        .append(Character.toUpperCase(value.charAt(++i)))
                        .append(Character.toUpperCase(value.charAt(++i)));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String requireLine(String value) throws SecureProtocolException {
        if (value == null || value.isEmpty() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new SecureProtocolException(SecureErrorCode.INVALID_ENVELOPE);
        }
        return value;
    }
}
