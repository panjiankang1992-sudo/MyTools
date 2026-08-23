package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 消息服务内部接口令牌校验器。
 */
@Component
public class InternalRequestAuthorizer {

    private final MessagingProperties properties;

    /**
     * 创建内部接口令牌校验器。
     */
    public InternalRequestAuthorizer(MessagingProperties properties) {
        this.properties = properties;
    }

    /**
     * 使用常量时间比较校验 Bearer 令牌。
     */
    public void requireAuthorized(String authorization) {
        String expected = properties.internalToken();
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (expected == null || expected.isBlank() || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ErrorCode.INTERNAL_UNAUTHORIZED.message());
        }
    }
}
