package com.yuyutian.mytools.reader.model;

/**
 * 阅读服务统一错误码。
 */
public enum ErrorCode {
    SEARCH_NOT_FOUND("READER_001", "Search request was not found"),
    DISCOVERY_NOT_FOUND("READER_002", "Source discovery request was not found"),
    INTERNAL_UNAUTHORIZED("READER_003", "Internal service token is invalid"),
    HEALTH_CHECK_NOT_FOUND("READER_004", "Source health check was not found"),
    HEALTH_SOURCE_LIMIT("READER_005", "Enabled source count exceeds health check limit");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回默认英文错误说明。
     *
     * @return 错误说明
     */
    public String message() {
        return message;
    }
}
