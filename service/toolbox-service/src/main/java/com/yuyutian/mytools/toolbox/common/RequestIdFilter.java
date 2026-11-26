package com.yuyutian.mytools.toolbox.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求标识过滤器：为每个请求生成或沿用安全的 requestId，写入响应头与请求上下文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 请求标识响应头名称。 */
    public static final String HEADER = "X-Request-Id";

    /** 只接受长度受限的安全字符，防止响应头注入。 */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        // 仅沿用形如 UUID 的安全标识，其余一律重新生成，避免外部注入任意头部值。
        String requestId = incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        RequestIdContext.set(requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程复用前必须清理，防止 requestId 串号。
            RequestIdContext.clear();
        }
    }
}
