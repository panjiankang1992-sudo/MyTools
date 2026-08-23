package com.yuyutian.mytools.storage.model;

/**
 * 存储网关统一错误码。
 */
public enum ErrorCode {
    ROOT_NOT_FOUND("STORAGE_001"),
    IDEMPOTENCY_CONFLICT("STORAGE_002"),
    UPLOAD_TOO_LARGE("STORAGE_003"),
    PATH_INVALID("STORAGE_004"),
    UPLOAD_CONFLICT("STORAGE_005"),
    CONTENT_MISMATCH("STORAGE_006"),
    IO_FAILURE("STORAGE_007"),
    TARGET_CONFLICT("STORAGE_008"),
    UPLOAD_NOT_FOUND("STORAGE_009"),
    INTERNAL_UNAUTHORIZED("STORAGE_010");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }
}
