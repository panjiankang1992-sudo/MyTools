package com.yuyutian.mytools.toolbox.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.AdminProperties;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求体大小限制过滤器：对事件和翻译批量接口在进入反序列化前限制字节数，超限返回 TOOLBOX_005。
 * 该限制同样覆盖没有 Content-Length 的分块请求，避免无界读入内存。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    /** 需要限制请求体大小的接口路径（不含上下文路径）。 */
    // 上下文改为根之后，Servlet 路径包含显式的 /public/v1 前缀。
    private static final String LIMITED_PATH = "/public/v1/events";
    private static final String LIMITED_TRANSLATION_PATH = "/public/v1/translations";

    /**
     * 管理端 API 前缀。
     *
     * 设计第 6 节要求管理接口也有体积限制（`TOOLBOX_ADMIN_007`），
     * 而此前这里**只覆盖两个公开路径**，管理端**没有上限**——
     * 一个超大的请求体会被无界读入。
     */
    private static final String ADMIN_PREFIX = "/admin/v1/";

    private final ToolboxProperties properties;
    private final AdminProperties adminProperties;
    private final ObjectMapper objectMapper;

    /**
     * 构造过滤器。
     *
     * @param properties      门面配置
     * @param adminProperties 管理端配置（提供管理端体积上限）
     * @param objectMapper    JSON 序列化器，用于直接返回错误信封
     */
    public BodySizeLimitFilter(ToolboxProperties properties, AdminProperties adminProperties,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.adminProperties = adminProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean translation = LIMITED_TRANSLATION_PATH.equals(request.getServletPath());
        // 管理端：任何**有请求体**的写方法都要限（登录、保存草稿、发布、导入都带体）。
        boolean admin = request.getServletPath() != null
                && request.getServletPath().startsWith(ADMIN_PREFIX)
                && !"GET".equalsIgnoreCase(request.getMethod())
                && !"HEAD".equalsIgnoreCase(request.getMethod());
        boolean limited = LIMITED_PATH.equals(request.getServletPath()) || translation;
        if (!admin && (!"POST".equalsIgnoreCase(request.getMethod()) || !limited)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 翻译二十项及其 JSON 转义均允许，但拒绝反序列化前的无界请求体。
        long limit = admin ? adminProperties.maxBodyBytes()
                : (translation ? 32768 : properties.events().maxBodyBytes());
        if (limit > 0 && request.getContentLengthLong() > limit) {
            writeTooLarge(response, limit, request.getContentLengthLong(), admin);
            return;
        }
        // 最多多读一个字节用于判断是否超限，内存占用被限制在 limit + 1。
        byte[] body;
        try (InputStream input = request.getInputStream()) {
            body = input.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
        }
        if (limit > 0 && body.length > limit) {
            writeTooLarge(response, limit, body.length, admin);
            return;
        }
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /**
     * 返回请求体超限错误，details 只包含限额与实际字节数。
     *
     * @param response HTTP 响应
     * @param limit    配置上限
     * @param actual   实际或声明字节数
     * @param admin    是否管理端路径——决定用哪个错误码
     * @throws IOException 写入响应失败
     */
    private void writeTooLarge(HttpServletResponse response, long limit, long actual, boolean admin) throws IOException {
        // 公开侧与管理侧各有自己的错误码：运维看到 007 就知道是管理端被限，
        // 不必再去翻是哪个链路。
        ErrorCode code = admin ? ErrorCode.TOOLBOX_ADMIN_007 : ErrorCode.TOOLBOX_005;
        BusinessException exception = new BusinessException(code,
                Map.of("limitName", "maxBodyBytes", "limit", limit, "actual", actual));
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(exception));
    }
}
