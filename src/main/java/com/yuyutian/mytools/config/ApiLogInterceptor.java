package com.yuyutian.mytools.config;

import com.yuyutian.mytools.apilog.model.ApiLog;
import com.yuyutian.mytools.apilog.service.ApiLogService;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

/**
 * API日志拦截器。
 * 记录所有API请求的日志。
 *
 * @author mytools
 * @since 2026-05-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiLogInterceptor implements HandlerInterceptor {

    private static final String START_TIME = "apiLogStartTime";

    private final ApiLogService apiLogService;
    private final JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 记录开始时间
        request.setAttribute(START_TIME, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            // 获取开始时间
            Long startTime = (Long) request.getAttribute(START_TIME);
            if (startTime == null) {
                return;
            }

            // 计算耗时
            long durationMs = System.currentTimeMillis() - startTime;

            // 获取用户信息
            Long userId = null;
            String username = null;
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    String token = authHeader.substring(7);
                    userId = jwtUtils.getUserIdFromToken(token);
                    username = jwtUtils.getUsernameFromToken(token);
                } catch (Exception e) {
                    // token解析失败，忽略
                }
            }

            // 确定模块
            String module = extractModule(request.getRequestURI());

            // 构建日志对象
            ApiLog apiLog = new ApiLog();
            apiLog.setUserId(userId);
            apiLog.setUsername(username);
            apiLog.setModule(module);
            apiLog.setApiPath(request.getRequestURI());
            apiLog.setMethod(request.getMethod());
            apiLog.setRequestMethod(handler.getClass().getSimpleName().replace("Controller", ""));
            apiLog.setSuccess(ex == null && response.getStatus() < 400);
            apiLog.setErrorMessage(ex != null ? ex.getMessage() : null);
            apiLog.setDurationMs(durationMs);
            apiLog.setIpAddress(getClientIp(request));
            apiLog.setRequestTime(LocalDateTime.now().minusNanos(durationMs * 1_000_000));
            apiLog.setResponseTime(LocalDateTime.now());

            // 异步保存日志
            apiLogService.saveLog(apiLog);

        } catch (Exception e) {
            log.error("API日志记录失败", e);
        }
    }

    /**
     * 从请求路径提取模块名称。
     */
    private String extractModule(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "UNKNOWN";
        }

        // 移除 /api/ 前缀
        String path = uri;
        if (path.startsWith("/api/")) {
            path = path.substring(5);
        }

        // 取第一个路径段作为模块名
        int slashIndex = path.indexOf('/');
        if (slashIndex > 0) {
            return path.substring(0, slashIndex).toUpperCase();
        }

        // 处理没有斜杠的情况
        return path.isEmpty() ? "UNKNOWN" : path.toUpperCase();
    }

    /**
     * 获取客户端真实IP。
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // 多个代理的情况，取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }
}
