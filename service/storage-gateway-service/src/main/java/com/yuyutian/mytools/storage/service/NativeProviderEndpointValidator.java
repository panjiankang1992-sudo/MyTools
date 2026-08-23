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
        return validate(value, true);
    }

    /**
     * 校验 S3 或兼容服务地址。
     *
     * @param value 配置地址
     * @return 规范化地址
     */
    public static URI s3(String value) {
        return validate(value, false);
    }

    private static URI validate(String value, boolean directoryEndpoint) {
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
            if (directoryEndpoint && !normalized.endsWith("/")) {
                normalized += "/";
            }
            return URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(ErrorCode.PROVIDER_INVALID.code(), exception);
        }
    }
}
