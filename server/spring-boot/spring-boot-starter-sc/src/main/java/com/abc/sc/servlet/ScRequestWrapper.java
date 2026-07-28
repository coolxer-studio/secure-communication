package com.abc.sc.servlet;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.Charset;

public class ScRequestWrapper extends HttpServletRequestWrapper {
    private String uri;
    private HashMap<String, String[]> parameter;
    private byte[] body;

    public ScRequestWrapper(HttpServletRequest request, String uri, HashMap<String, String[]> parameters, String body) {
        super(request);
        this.uri = uri;
        parameter = new HashMap<String, String[]>();
        parameter.putAll(request.getParameterMap());
        parameter.putAll(parameters);
        this.body = body.getBytes(Charset.forName("UTF-8"));
    }

    // 重写uri路径
    @Override
    public String getRequestURI() {
        return this.uri;
    }

    // 重写参数
    @Override
    public String getParameter(String name) {
        String[] values = parameter.get(name);
        return values == null ? null : values[0];
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Map getParameterMap() {
        return parameter;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Enumeration getParameterNames() {
        return new Vector(parameter.keySet()).elements();
    }

    @Override
    public String[] getParameterValues(String name) {
        return parameter.get(name);
    }

    // 重写body（inputStream的内容只能读一次，如果不重写，controller中获取的body就是空）
    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {

        final ByteArrayInputStream bais = new ByteArrayInputStream(body);

        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setReadListener(ReadListener arg0) {
            }
        };
    }

}
