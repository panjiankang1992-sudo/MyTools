package com.yuyutian.mytools.toolbox.controller.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.model.SessionResponse;
import com.yuyutian.mytools.toolbox.service.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名会话接口：签发或复用访客会话，原始 token 只经 cookie 下发。
 * 请求体必须为空或空对象；任何字段都视为未知字段并拒绝，避免客户端自选 owner 或 token。
 */
@RestController
@RequestMapping("/public/v1/session")
public class SessionController {

    /** 会话接口允许的最大请求体字节数，超过即按硬限制拒绝。 */
    private static final int MAX_BODY_BYTES = 1024;

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    /**
     * 构造控制器。
     *
     * @param sessionService 会话服务
     * @param objectMapper   JSON 解析器
     */
    public SessionController(SessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 签发或复用会话。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @return 到期时间与 CSRF token
     */
    @PostMapping
    public ApiResponse<SessionResponse> create(HttpServletRequest request, HttpServletResponse response) {
        rejectNonEmptyBody(request);
        return ApiResponse.ok(sessionService.issueOrReuse(request, response));
    }

    /**
     * 校验请求体为空或空对象，直接读取原始字节以兼容未声明 Content-Type 的客户端。
     *
     * @param request 当前请求
     */
    private void rejectNonEmptyBody(HttpServletRequest request) {
        byte[] raw;
        try {
            raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException exception) {
            throw BusinessException.invalid(null, "unreadableBody");
        }
        if (raw.length > MAX_BODY_BYTES) {
            throw new BusinessException(ErrorCode.TOOLBOX_005,
                    Map.of("limitName", "maxSessionBodyBytes", "limit", (long) MAX_BODY_BYTES, "actual", (long) raw.length));
        }
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        // 空体与空对象是唯一允许的两种形式。
        if (text.isEmpty() || "{}".equals(text)) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(text);
        } catch (IOException exception) {
            throw BusinessException.invalid(null, "malformedJson");
        }
        if (node.isObject() && !node.isEmpty()) {
            Iterator<String> fields = node.fieldNames();
            throw BusinessException.invalid(fields.hasNext() ? fields.next() : null, "unknownField");
        }
        if (!node.isObject()) {
            throw BusinessException.invalid(null, "invalidBody");
        }
    }
}
