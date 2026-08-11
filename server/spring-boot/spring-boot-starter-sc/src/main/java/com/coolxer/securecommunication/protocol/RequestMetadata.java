package com.coolxer.securecommunication.protocol;

public record RequestMetadata(String method, String pathAndQuery, String contentType) {
}
