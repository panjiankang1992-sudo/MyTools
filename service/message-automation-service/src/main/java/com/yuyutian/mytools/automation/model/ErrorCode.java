package com.yuyutian.mytools.automation.model;

/**
 * 消息自动化服务统一错误码。
 */
public enum ErrorCode {
    INTERNAL_UNAUTHORIZED("AUTOMATION_001", "Internal service token is invalid"),
    NO_ACTION_INPUT("AUTOMATION_002", "Message contains no valid action input"),
    DOWNLOAD_CREATE_FAILED("AUTOMATION_003", "Download request creation failed");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回稳定错误码。
     */
    public String code() {
        return code;
    }

    /**
     * 返回默认英文说明。
     */
    public String message() {
        return message;
    }
}
