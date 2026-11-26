package com.yuyutian.mytools.rss.config;

import com.yuyutian.mytools.rss.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 服务只接受内部认证与可信 owner，匿名健康检查不输出数据库详情。 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {
    private final RssProperties properties;
    /** 注入内部访问配置。 */
    public InternalTokenFilter(RssProperties properties) { this.properties = properties; }
    /** 验证令牌和规范路径，拒绝缺失、过短令牌及伪造身份。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI();
        if (path.equals("/actuator/health")) { chain.doFilter(request, response); return; }
        String expected = properties.internalToken();
        String supplied = request.getHeader("Authorization");
        long owner;
        try { owner = Long.parseLong(request.getHeader("X-RSS-Owner")); }
        catch (NumberFormatException exception) { owner = 0; }
        boolean canonical = path.startsWith("/internal/v1/rss/") && !path.contains("%") && !path.contains(";")
                && !path.contains("\\") && !path.contains("//") && !path.contains("/./") && !path.contains("/../");
        if (!canonical || owner <= 0 || expected == null || expected.length() < 32 || supplied == null
                || !MessageDigest.isEqual(("Bearer " + expected).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"" + ErrorCode.RSS_010.name() + "\",\"message\":\"" + ErrorCode.RSS_010.name() + "\"}"); return;
        }
        request.setAttribute("rssOwner", owner);
        chain.doFilter(request, response);
    }
}
