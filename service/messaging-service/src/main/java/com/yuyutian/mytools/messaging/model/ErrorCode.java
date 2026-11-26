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
    ATTACHMENT_DOWNLOAD_INVALID("MESSAGING_010", "Attachment cannot be downloaded through the HTTP pipeline"),
    EMAIL_INGRESS_DISABLED("MESSAGING_011", "Email ingress adapter is disabled"),
    EMAIL_INGRESS_FAILED("MESSAGING_012", "Email ingress poll failed"),
    INBOUND_REPLY_REJECTED("MESSAGING_013", "Inbound reply was rejected by the channel provider"),
    INBOUND_REPLY_DEFERRED("MESSAGING_014", "Inbound reply was deferred by the channel provider"),
    OUTBOUND_INVALID("MESSAGING_015", "Outbound request is invalid"),
    DESTINATION_NOT_FOUND("MESSAGING_016", "Verified destination was not found"),
    OUTBOUND_CONFLICT("MESSAGING_017", "Outbound state or idempotency conflict"),
    OUTBOUND_DISABLED("MESSAGING_018", "Outbound service is disabled or not configured"),
    OUTBOUND_NOT_FOUND("MESSAGING_019", "Outbound delivery was not found"),
    OUTBOUND_LEASE_INVALID("MESSAGING_020", "Active outbound execution lease is required"),
    CHANNEL_UNAVAILABLE("MESSAGING_021", "Scheduled delivery is unavailable for this channel"),
    OUTBOUND_PROVIDER_REJECTED("MESSAGING_022", "Provider rejected the delivery"),
    OUTBOUND_RESULT_UNKNOWN("MESSAGING_023", "Provider result requires reconciliation"),
    OUTBOUND_RETENTION_DISABLED("MESSAGING_024", "Outbound retention is disabled"),
    OUTBOUND_RETENTION_FAILED("MESSAGING_025", "Outbound retention operation failed"),
    OUTBOUND_CAPACITY_EXCEEDED("MESSAGING_026", "Outbound storage admission capacity is exhausted"),
    OUTBOUND_CAPACITY_INVALID("MESSAGING_027", "Outbound capacity accounting is invalid"),
    OUTBOUND_CONTENT_EXPIRED("MESSAGING_028", "Outbound content retention expired");

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
