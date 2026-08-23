package com.yuyutian.mytools.messaging.model;

/**
 * 消息服务统一错误码。
 */
public enum ErrorCode {
    INTERNAL_UNAUTHORIZED("MESSAGING_001", "Internal service token is invalid"),
    DELIVERY_NOT_FOUND("MESSAGING_002", "Delivery request was not found"),
    PROVIDER_NOT_CONFIGURED("MESSAGING_003", "Delivery provider is not configured"),
    DELIVERY_STATE_INVALID("MESSAGING_004", "Delivery state does not allow this operation"),
    INBOUND_NOT_FOUND("MESSAGING_005", "Inbound message was not found"),
    DELIVERY_INVALID("MESSAGING_006", "Delivery request is invalid"),
    ONEBOT_INGRESS_DISABLED("MESSAGING_007", "OneBot ingress adapter is disabled"),
    ONEBOT_PAYLOAD_INVALID("MESSAGING_008", "OneBot event payload is invalid"),
    ATTACHMENT_DOWNLOAD_NOT_FOUND("MESSAGING_009", "Attachment download was not found"),
    ATTACHMENT_DOWNLOAD_INVALID("MESSAGING_010", "Attachment cannot be downloaded through the HTTP pipeline");

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
