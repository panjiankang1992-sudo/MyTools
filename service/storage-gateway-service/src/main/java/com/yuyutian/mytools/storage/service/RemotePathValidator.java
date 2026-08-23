package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;

import java.util.Arrays;

/**
 * 统一校验 Provider 内相对路径。
 */
public final class RemotePathValidator {
    private RemotePathValidator() {
    }

    /**
     * 校验并返回去除首尾空白的相对路径。
     *
     * @param value 输入路径
     * @param allowEmpty 是否允许空路径
     * @return 安全相对路径
     */
    public static String validate(String value, boolean allowEmpty) {
        String path = value == null ? "" : value.trim();
        if ((path.isEmpty() && !allowEmpty) || path.length() > 2048 || path.startsWith("/")
                || path.contains(":") || path.contains("\\")
                || Arrays.asList(path.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        return path;
    }
}
