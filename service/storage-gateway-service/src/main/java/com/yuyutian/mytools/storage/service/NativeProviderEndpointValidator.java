package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;

import java.net.URI;

/**
 * 校验原生远端 Provider 的服务端地址。
 */
public final class NativeProviderEndpointValidator {
    private NativeProviderEndpointValidator() {
    }

    /**
     * 校验 WebDAV 地址并规范化目录结尾。
     *
     * @param value 配置地址
     * @return 规范化地址
     */
    public static URI webDav(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
        }
        try {
            URI uri = URI.create(value.trim());
            boolean loopbackHttp = "http".equals(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
            if (!("https".equals(uri.getScheme()) || loopbackHttp) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code());
            }
            String normalized = uri.toString();
            return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code(), exception);
        }
    }
}
