package com.yuyutian.mytools.rss.web;

import com.yuyutian.mytools.rss.common.ErrorCode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在 JSON 解码前限制实际字节数，分块请求也不能绕过限制。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class RssBodySizeFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** 只处理 RSS 请求体，不影响其他模块上传。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/internal/v1/rss/")) || !(request.getMethod().equals("POST") || request.getMethod().equals("PUT"));
    }
    /** 缓冲至多限制加一字节，再将有界输入交给 MVC。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) { reject(response); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            /** 返回有界输入流。 */
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    /** 读取一个字节。 */
                    @Override public int read() { return input.read(); }
                    /** 返回同步读取状态。 */
                    @Override public boolean isFinished() { return input.available() == 0; }
                    /** 输入已全部缓冲，因此总是可读。 */
                    @Override public boolean isReady() { return true; }
                    /** 此同步 JSON 入口不支持异步回调。 */
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            /** MVC 或诊断工具可以读取同一有界正文。 */
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + ErrorCode.RSS_006.name() + "\",\"message\":\"" + ErrorCode.RSS_006.name() + "\"}");
    }
}
