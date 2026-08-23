package com.yuyutian.mytools.asset.model;

/**
 * 资产注册服务统一错误码。
 */
public enum ErrorCode {
    INTERNAL_UNAUTHORIZED("ASSET_001", "Internal service token is invalid"),
    ASSET_NOT_FOUND("ASSET_002", "Asset was not found"),
    IDEMPOTENCY_CONFLICT("ASSET_003", "Idempotency key is bound to different asset data"),
    VERSION_CONFLICT("ASSET_004", "Asset version does not match"),
    ARTIFACT_CYCLE("ASSET_005", "Asset cannot derive from itself"),
    INPUT_INVALID("ASSET_006", "Asset request contains an invalid value");

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
