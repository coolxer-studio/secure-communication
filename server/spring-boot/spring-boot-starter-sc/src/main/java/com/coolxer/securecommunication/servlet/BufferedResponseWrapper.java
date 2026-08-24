package com.coolxer.securecommunication.servlet;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class BufferedResponseWrapper extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    BufferedResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException("getWriter() was already called");
        }
        if (outputStream == null) {
            outputStream = new ServletOutputStream() {
                @Override
                public void write(int value) {
                    body.write(value);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    if (listener == null) {
                        throw new IllegalArgumentException("WriteListener must not be null");
                    }
                }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream() was already called");
        }
        if (writer == null) {
            Charset charset;
            try {
                charset = Charset.forName(getCharacterEncoding());
            } catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }
            writer = new PrintWriter(new OutputStreamWriter(body, charset));
        }
        return writer;
    }

    byte[] body() throws IOException {
        flushBuffer();
        return body.toByteArray();
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (outputStream != null) {
            outputStream.flush();
        }
    }

    @Override
    public void resetBuffer() {
        body.reset();
    }

    @Override
    public void reset() {
        super.reset();
        body.reset();
    }
}
