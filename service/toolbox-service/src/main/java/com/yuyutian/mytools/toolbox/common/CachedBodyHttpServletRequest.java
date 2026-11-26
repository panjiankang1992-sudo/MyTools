package com.yuyutian.mytools.toolbox.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 已缓存请求体的请求包装：限制过滤器读入的字节可以重复读取，供 JSON 反序列化使用。
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    /**
     * 构造包装请求。
     *
     * @param request 原始请求
     * @param body    已读入并校验大小的请求体
     */
    CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedServletInputStream(new ByteArrayInputStream(body));
    }

    @Override
    public BufferedReader getReader() throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        String encoding = getCharacterEncoding();
        if (encoding != null && !encoding.isBlank()) {
            charset = Charset.forName(encoding);
        }
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    /** 基于字节数组的 Servlet 输入流。 */
    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream source;

        private CachedServletInputStream(ByteArrayInputStream source) {
            this.source = source;
        }

        @Override
        public int read() {
            return source.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return source.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return source.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("asyncReadNotSupported");
        }
    }
}
