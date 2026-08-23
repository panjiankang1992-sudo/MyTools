package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.config.StorageProperties;
import com.yuyutian.mytools.storage.model.ErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 集中校验 Storage Gateway 内部接口令牌。
 */
@Service
public class InternalAuthorizer {
    private final StorageProperties properties;

    /**
     * 创建内部鉴权器。
     *
     * @param properties 存储配置
     */
    public InternalAuthorizer(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验 Bearer Token。
     *
     * @param authorization 授权头
     */
    public void require(String authorization) {
        String expected = properties.internalToken();
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (expected == null || expected.isBlank() || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(ErrorCode.INTERNAL_UNAUTHORIZED.code());
        }
    }
}
