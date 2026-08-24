package com.coolxer.securecommunication;

import java.util.concurrent.CompletableFuture;

/** High-level protocol v1 client. Business requests are never retried by the SDK. */
public interface SecureClient extends AutoCloseable {
    void enroll(String token) throws SecureError;
    void initialize() throws SecureError;
    void initialize(ExecutionOptions options) throws SecureError;
    CompletableFuture<Void> initializeAsync();
    CompletableFuture<Void> initializeAsync(ExecutionOptions options);
    SecureResponse request(SecureRequest request) throws SecureError;
    SecureResponse request(SecureRequest request, ExecutionOptions options) throws SecureError;
    CompletableFuture<SecureResponse> requestAsync(SecureRequest request);
    CompletableFuture<SecureResponse> requestAsync(SecureRequest request, ExecutionOptions options);
    void closeSession();
    @Override default void close() { closeSession(); }
}
