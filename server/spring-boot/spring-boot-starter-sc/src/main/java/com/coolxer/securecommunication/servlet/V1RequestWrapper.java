package com.coolxer.securecommunication.servlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

final class V1RequestWrapper extends HttpServletRequestWrapper {
    private final byte[] body;
    private final String method;
    private final String requestUri;
    private final String queryString;
    private final String contentType;
    private final Map<String, String> protectedHeaders;
    private final Map<String, String[]> parameters;

    V1RequestWrapper(
            HttpServletRequest request,
            byte[] body,
            String method,
            String requestUri,
            String queryString,
            String contentType,
            Map<String, String> protectedHeaders) {
        super(request);
        this.body = body.clone();
        this.method = method;
        this.requestUri = requestUri;
        this.queryString = queryString;
        this.contentType = contentType;
        this.protectedHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<>(protectedHeaders));
        this.parameters = parseQuery(queryString);
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getRequestURI() {
        return requestUri;
    }

    @Override
    public String getServletPath() {
        return requestUri;
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public String getHeader(String name) {
        String protectedValue = protectedHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
        return protectedValue != null ? protectedValue : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String protectedValue = protectedHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
        return protectedValue != null
                ? Collections.enumeration(List.of(protectedValue))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Enumeration<String> outer = super.getHeaderNames();
        while (outer != null && outer.hasMoreElements()) {
            names.add(outer.nextElement());
        }
        names.addAll(protectedHeaders.keySet());
        return Collections.enumeration(names);
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameters.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return new Vector<>(parameters.keySet()).elements();
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = parameters.get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                if (listener == null) {
                    throw new IllegalArgumentException("ReadListener must not be null");
                }
            }
        };
    }

    private static Map<String, String[]> parseQuery(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> collected = new LinkedHashMap<>();
        for (String pair : query.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String name = decode(parts[0]);
            String value = parts.length == 2 ? decode(parts[1]) : "";
            collected.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        Map<String, String[]> result = new LinkedHashMap<>();
        collected.forEach((name, values) ->
                result.put(name, values.toArray(String[]::new)));
        return Collections.unmodifiableMap(result);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
