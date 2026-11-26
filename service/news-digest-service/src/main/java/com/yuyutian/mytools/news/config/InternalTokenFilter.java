package com.yuyutian.mytools.news.config;

import com.yuyutian.mytools.news.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 隔离用户代理和来源管理接口，空令牌始终拒绝访问。 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {
    private final NewsProperties properties;
    private final RegionProperties region;
    private final SchedulingProperties scheduling;

    /** 创建内部接口认证过滤器。 */
    @Autowired
    public InternalTokenFilter(NewsProperties properties, RegionProperties region, SchedulingProperties scheduling) {
        this.properties = properties; this.region = region; this.scheduling = scheduling;
        if (scheduling.workerToken() != null && !scheduling.workerToken().isBlank()
                && (scheduling.workerToken().length() < 32 || scheduling.workerToken().equals(properties.adminToken())
                || scheduling.workerToken().equals(properties.internalToken()) || scheduling.workerToken().equals(region.reportToken())
                || scheduling.workerToken().equals(scheduling.businessToken()))) {
            throw new IllegalArgumentException("News job token must be distinct and at least 32 characters");
        }
        if (region.reportToken() != null && !region.reportToken().isBlank()
                && (region.reportToken().length() < 32 || region.reportToken().equals(properties.adminToken())
                || region.reportToken().equals(properties.internalToken()))) {
            throw new IllegalArgumentException("Region report token must be distinct and at least 32 characters");
        }
    }

    /** 为地区单元测试保留关闭自动编排的构造方式。 */
    public InternalTokenFilter(NewsProperties properties, RegionProperties region) {
        this(properties, region, new SchedulingProperties(false, "http://127.0.0.1:23410", "", ""));
    }

    /** 为旧版鉴权测试保留关闭节点上报的构造方式。 */
    public InternalTokenFilter(NewsProperties properties) { this(properties, new RegionProperties("", "", 50, true)); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        // 拒绝矩阵参数和编码路径，避免过滤器与控制器对管理路径产生不同解释。
        boolean canonical = !path.contains(";") && !path.contains("%") && !path.contains("\\")
                && !path.contains("//") && !path.contains("/./") && !path.contains("/../");
        if (path.equals("/actuator/health")) {
            // 唯一匿名入口只提供健康状态。
            chain.doFilter(request, response);
            return;
        }
        boolean admin = path.startsWith("/actuator/") || path.equals("/internal/admin") || path.startsWith("/internal/admin/");
        boolean worker = path.equals("/internal/worker") || path.startsWith("/internal/worker/");
        boolean job = path.equals("/internal/jobs") || path.startsWith("/internal/jobs/");
        String expected = job ? scheduling.workerToken() : worker ? region.reportToken() : admin ? properties.adminToken() : properties.internalToken();
        String supplied = request.getHeader("Authorization");
        if (!canonical || expected == null || expected.isBlank() || supplied == null
                || !MessageDigest.isEqual(("Bearer " + expected).getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            // 常量时间比较后返回固定错误，不泄漏期望令牌。
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"" + ErrorCode.NEWS_001.name()
                    + "\",\"message\":\"" + ErrorCode.NEWS_001.message() + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
