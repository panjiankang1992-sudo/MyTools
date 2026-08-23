package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Executor 调用 Reader Service 内部接口的统一令牌校验器。
 */
@Component
public class InternalRequestAuthorizer {

    private final ReaderProperties properties;

    /**
     * 创建内部请求校验器。
     *
     * @param properties 阅读服务配置
     */
    public InternalRequestAuthorizer(ReaderProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验 Bearer 内部令牌。
     *
     * @param authorization 授权头
     */
    public void requireAuthorized(String authorization) {
        String expected = properties.internalToken();
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (expected == null || expected.isBlank() || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    ErrorCode.INTERNAL_UNAUTHORIZED.message());
        }
    }
}
