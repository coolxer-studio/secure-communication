package com.coolxer.securecommunication;

import com.coolxer.securecommunication.internal.protocol.SecureEnvelopeCodec;
import com.coolxer.securecommunication.internal.transport.SecureCommunicationInterceptor;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;
import java.util.Collections;
import java.util.List;

final class SecureCommunicationClient {
    private final HttpUrl baseUrl;
    private final OkHttpClient httpClient;

    SecureCommunicationClient(
            HttpUrl baseUrl, OkHttpClient hostClient, SecureEnvelopeCodec codec) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        this.baseUrl = baseUrl;
        HttpUrl endpoint = baseUrl.resolve("/sc/v1/message");
        if (endpoint == null) {
            throw new IllegalArgumentException("Unable to resolve v1 message endpoint");
        }
        this.httpClient = hostClient.newBuilder()
                .addInterceptor(new SecureCommunicationInterceptor(endpoint, codec))
                .retryOnConnectionFailure(false)
                .connectionSpecs(connectionSpecs(baseUrl))
                .build();
    }

    static List<ConnectionSpec> connectionSpecs(HttpUrl baseUrl) {
        if ("http".equals(baseUrl.scheme())) {
            return Collections.singletonList(ConnectionSpec.CLEARTEXT);
        }
        ConnectionSpec tls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .build();
        return Collections.singletonList(tls);
    }

    Call newCall(SecureRequest request) {
        Request transportPlaceholder = new Request.Builder()
                .url(baseUrl)
                .post(okhttp3.RequestBody.create(new byte[0], null))
                .tag(SecureRequest.class, request)
                .build();
        return httpClient.newCall(transportPlaceholder);
    }

}
