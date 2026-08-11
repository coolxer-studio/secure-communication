package com.coolxer.securecommunication;

import com.coolxer.securecommunication.protocol.SecureEnvelopeCodec;
import com.coolxer.securecommunication.transport.SecureCommunicationInterceptor;
import com.coolxer.securecommunication.transport.SecureTransportException;

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

public final class SecureCommunicationClient {
    private final HttpUrl baseUrl;
    private final OkHttpClient httpClient;

    public SecureCommunicationClient(
            HttpUrl baseUrl, OkHttpClient hostClient, SecureEnvelopeCodec codec) {
        if (baseUrl == null || !"https".equals(baseUrl.scheme())) {
            throw new IllegalArgumentException("baseUrl must use HTTPS");
        }
        this.baseUrl = baseUrl;
        HttpUrl endpoint = baseUrl.resolve("/sc/v1/message");
        if (endpoint == null) {
            throw new IllegalArgumentException("Unable to resolve v1 message endpoint");
        }
        ConnectionSpec tls13 = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .build();
        this.httpClient = hostClient.newBuilder()
                .addInterceptor(new SecureCommunicationInterceptor(endpoint, codec))
                .retryOnConnectionFailure(false)
                .connectionSpecs(Collections.singletonList(tls13))
                .build();
    }

    public Call newCall(SecureRequest request) {
        Request transportPlaceholder = new Request.Builder()
                .url(baseUrl)
                .post(okhttp3.RequestBody.create(new byte[0], null))
                .tag(SecureRequest.class, request)
                .build();
        return httpClient.newCall(transportPlaceholder);
    }

    public Call execute(SecureRequest request, SecureCallback callback) {
        Call call = newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                if (exception instanceof SecureTransportException) {
                    callback.onFailure(
                            ((SecureTransportException) exception).getSecureError());
                } else {
                    callback.onFailure(new SecureError(
                            "SC_NETWORK_FAILED", "Network request failed",
                            0, null, exception));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    byte[] bytes = body == null ? new byte[0] : body.bytes();
                    callback.onResponse(new SecureResponse(
                            response.code(),
                            response.header("Content-Type", "application/octet-stream"),
                            bytes));
                }
            }
        });
        return call;
    }

    public interface SecureCallback {
        void onResponse(SecureResponse response);

        void onFailure(SecureError error);
    }
}
