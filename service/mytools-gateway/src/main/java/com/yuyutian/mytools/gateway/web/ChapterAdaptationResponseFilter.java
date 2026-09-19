package com.yuyutian.mytools.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 认证失败和业务错误同样禁止缓存，并在 MVC 分配请求字符串之前限制改编输入。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class ChapterAdaptationResponseFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/app/v1/reader/chapter-adaptations")
                && !path.startsWith("/api/app/v1/reader/adaptation-style-templates")
                && !path.startsWith("/api/app/v1/features/reader-adaptation")
                && !path.matches("/api/app/v1/reader/shelves/[^/]+/(chapter-adaptation-capability|chapters)(/.*)?");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store, private"); response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!"POST".equals(request.getMethod()) && !"DELETE".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        try {
            String contentType = request.getContentType();
            var media = contentType == null ? null : org.springframework.http.MediaType.parseMediaType(contentType);
            if (media != null && media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset())) { reject(response); return; }
        } catch (IllegalArgumentException exception) { reject(response); return; }
        if (request.getContentLengthLong() > 32768) { reject(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(32769);
        if (bytes.length > 32768) { reject(response); return; }
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); }
        catch (java.nio.charset.CharacterCodingException exception) { reject(response); return; }
        chain.doFilter(new BoundedRequest(request, bytes), response);
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(400); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"GATEWAY_003\",\"message\":\"Gateway request is invalid\"}");
    }
    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        private BoundedRequest(HttpServletRequest request, byte[] bytes) { super(request); this.bytes = bytes; }
        /** 固定 UTF-8，避免声明其他字符集重新解释已检查的字节。 */
        @Override public String getCharacterEncoding() { return "UTF-8"; }
        /** 返回已验证的请求长度。 */
        @Override public int getContentLength() { return bytes.length; }
        /** 返回已验证的请求长度。 */
        @Override public long getContentLengthLong() { return bytes.length; }
        /** MVC 仅能读取已缓存的有界字节。 */
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                /** 读取一个已验证字节。 */
                @Override public int read() { return input.read(); }
                /** 返回剩余字节状态。 */
                @Override public boolean isFinished() { return input.available() == 0; }
                /** 同步内存流始终就绪。 */
                @Override public boolean isReady() { return true; }
                /** 此同步 MVC 入口不接受异步流回调。 */
                @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous request only"); }
            };
        }
        /** 字符读取复用相同的 UTF-8 字节边界。 */
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
