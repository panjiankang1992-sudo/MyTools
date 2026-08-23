package com.yuyutian.mytools.reader.model;

/**
 * 阅读服务统一错误码。
 */
public enum ErrorCode {
    SEARCH_NOT_FOUND("READER_001", "Search request was not found");

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
