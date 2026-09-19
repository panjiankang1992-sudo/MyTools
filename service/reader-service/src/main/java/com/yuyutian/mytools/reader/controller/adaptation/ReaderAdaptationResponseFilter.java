package com.yuyutian.mytools.reader.controller.adaptation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 改编及章节响应含私有文本，成功和错误响应均禁止共享或持久缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReaderAdaptationResponseFilter extends OncePerRequestFilter {
    /** 仅覆盖本功能路径，不改变既有 Reader 音频等资源的缓存策略。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/reader-state/chapter-adaptations/")
                && !path.startsWith("/api/v1/reader-state/adaptation-style-templates")
                && !path.startsWith("/api/v1/reader-admin/adaptation-style-templates")
                && !path.startsWith("/api/v1/reader-state/features/reader-adaptation")
                && !path.startsWith("/api/internal/v1/chapter-adaptations/")
                && !path.startsWith("/api/internal/v1/ebook-bindings/")
                && !path.startsWith("/api/internal/v1/provider-probes/")
                && !(path.startsWith("/api/v1/reader-state/shelves/")
                && (path.contains("/chapter-adaptation-capability") || path.contains("/chapters")));
    }

    /** 在授权和业务处理前设置缓存与内容嗅探保护。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
