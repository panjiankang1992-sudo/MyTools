package com.yuyutian.mytools.toolbox.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 过滤器阶段写失败响应的公共工具。
 * 复用公开门面的错误信封，保证前端只需处理一种结构。
 */
public final class FilterResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FilterResponses() {
    }

    /**
     * 写出与管理接口一致的失败信封。
     *
     * @param response  HTTP 响应
     * @param errorCode 稳定错误码
     * @param reason    安全的机器可读原因
     * @throws IOException 写出失败时抛出
     */
    public static void writeError(HttpServletResponse response, ErrorCode errorCode, String reason) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        ApiErrorResponse body = ApiErrorResponse.of(errorCode);
        if (reason != null) {
            body = new ApiErrorResponse(body.code(), body.messageKey(), Map.of("reason", reason),
                    body.requestId() == null ? "" : body.requestId());
        }
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
