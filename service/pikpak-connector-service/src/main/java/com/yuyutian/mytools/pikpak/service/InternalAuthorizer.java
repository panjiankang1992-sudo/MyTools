package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.common.ErrorCode.INTERNAL_UNAUTHORIZED;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** PikPak 内部接口令牌校验器。 */
@Service
public class InternalAuthorizer {
    private final String expected;

    /** 创建校验器。 @param expected 内部令牌 */
    public InternalAuthorizer(@Value("${pikpak.internal-token:}") String expected) {
        this.expected = expected;
    }

    /** 校验 Bearer Token。 @param authorization 授权头 */
    public void require(String authorization) {
        String supplied = authorization != null && authorization.startsWith("Bearer ")
            ? authorization.substring(7) : "";
        if (expected.isBlank() || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(INTERNAL_UNAUTHORIZED.code());
        }
    }
}
