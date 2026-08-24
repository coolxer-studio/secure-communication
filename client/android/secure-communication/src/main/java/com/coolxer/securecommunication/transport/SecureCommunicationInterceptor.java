package com.coolxer.securecommunication.internal.transport;

import com.coolxer.securecommunication.SecureError;
import com.coolxer.securecommunication.SecureRequest;
import com.coolxer.securecommunication.internal.protocol.SecureEnvelopeCodec;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public final class SecureCommunicationInterceptor implements Interceptor {
    private static final MediaType ENVELOPE_MEDIA_TYPE =
            MediaType.get(SecureEnvelopeCodec.ENVELOPE_MEDIA_TYPE);

    private final HttpUrl endpoint;
    private final SecureEnvelopeCodec codec;

    public SecureCommunicationInterceptor(HttpUrl endpoint, SecureEnvelopeCodec codec) {
        if (endpoint == null) {
            throw new IllegalArgumentException("protocol v1 endpoint is required");
        }
        this.endpoint = endpoint;
        this.codec = codec;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        SecureRequest logical = original.tag(SecureRequest.class);
        if (logical == null) {
            logical = fromRequest(original);
        }
        try {
            SecureEnvelopeCodec.EncodedRequest encoded = codec.encode(logical);
            Request transport = original.newBuilder()
                    .url(endpoint)
                    .method("POST", RequestBody.create(encoded.getBody(), ENVELOPE_MEDIA_TYPE))
                    .headers(new okhttp3.Headers.Builder()
                            .add("Content-Type", SecureEnvelopeCodec.ENVELOPE_MEDIA_TYPE)
                            .add("Accept", SecureEnvelopeCodec.ENVELOPE_MEDIA_TYPE)
                            .build())
                    .build();
            Response response = chain.proceed(transport);
            ResponseBody responseBody = response.body();
            String encodedResponse = responseBody == null ? "" : responseBody.string();
            String responseType = response.header("Content-Type", "");
            if (!responseType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(SecureEnvelopeCodec.ENVELOPE_MEDIA_TYPE)) {
                throw new SecureTransportException(parseServerError(response, encodedResponse));
            }
            SecureEnvelopeCodec.DecodedResponse decoded = codec.decode(
                    encodedResponse,
                    encoded.getSequence(),
                    encoded.getRequestId());
            JSONObject protectedResponse = new JSONObject(
                    new String(decoded.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            if (protectedResponse.length() != 2
                    || !protectedResponse.has("contentType")
                    || !protectedResponse.has("body")) {
                throw new SecureError("SC_INVALID_ENVELOPE", "Protected response is invalid");
            }
            String logicalContentType = protectedResponse.getString("contentType")
                    .split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (!logicalContentType.matches(
                    "[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
                throw new SecureError("SC_INVALID_ENVELOPE", "Protected response is invalid");
            }
            String logicalBody = protectedResponse.getString("body");
            if (!logicalBody.matches("[A-Za-z0-9_-]*")) {
                throw new SecureError("SC_INVALID_ENVELOPE", "Protected response is invalid");
            }
            byte[] logicalBytes = android.util.Base64.decode(logicalBody,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
            return response.newBuilder()
                    .code(decoded.getStatus())
                    .header("Content-Type", logicalContentType)
                    .body(ResponseBody.create(
                            logicalBytes, MediaType.parse(logicalContentType)))
                    .build();
        } catch (SecureError error) {
            throw new SecureTransportException(error);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new SecureTransportException(new SecureError(
                    "SC_INVALID_ENVELOPE", "Protected response is invalid",
                    0, null, error));
        }
    }

    private static SecureRequest fromRequest(Request request) throws IOException {
        byte[] body = new byte[0];
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            body = buffer.readByteArray();
        }
        String path = request.url().encodedPath();
        if (request.url().encodedQuery() != null) {
            path += "?" + request.url().encodedQuery();
        }
        String contentType = request.body() == null || request.body().contentType() == null
                ? "application/octet-stream"
                : request.body().contentType().toString();
        return new SecureRequest(request.method(), path, contentType,
                java.util.Collections.emptyMap(), body, null);
    }

    private static SecureError parseServerError(Response response, String body) {
        try {
            JSONObject json = new JSONObject(body);
            return new SecureError(
                    json.optString("code", "SC_TRANSPORT_FAILED"),
                    json.optString("message", "Secure transport failed"),
                    response.code(),
                    json.optString("traceId", null),
                    null);
        } catch (Exception ignored) {
            return new SecureError(
                    "SC_TRANSPORT_FAILED", "Secure transport failed",
                    response.code(), response.header("X-Trace-Id"), null);
        }
    }
}
