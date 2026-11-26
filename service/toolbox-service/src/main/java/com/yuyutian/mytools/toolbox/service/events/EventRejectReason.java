package com.yuyutian.mytools.toolbox.service.events;

/**
 * 单条事件的稳定拒绝或重复原因码，前端据此提示，不使用自由文本。
 */
public enum EventRejectReason {

    /** 缺少事件标识。 */
    MISSING_EVENT_ID("missing_event_id"),
    /** 事件标识过长或非法。 */
    INVALID_EVENT_ID("invalid_event_id"),
    /** 事件类型不在公开枚举内，包含被禁止的 pdf_success。 */
    INVALID_EVENT_TYPE("invalid_event_type"),
    /** 工具分类非法。 */
    INVALID_CATEGORY("invalid_category"),
    /** 事件结构版本不是 1。 */
    INVALID_SCHEMA_VERSION("invalid_schema_version"),
    /** 工具标识不存在。 */
    UNKNOWN_TOOL("unknown_tool"),
    /** 工具标识已停用。 */
    TOOL_DISABLED("tool_disabled"),
    /** 工具与给定分类不匹配。 */
    TOOL_CATEGORY_MISMATCH("tool_category_mismatch"),
    /** 该事件类型必须携带工具标识。 */
    MISSING_TOOL_ID("missing_tool_id"),
    /** 成功事件只接受四个 worksheet.* 工具。 */
    WORKSHEET_TOOL_REQUIRED("worksheet_tool_required"),
    /** 该事件类型必须携带操作标识。 */
    MISSING_OPERATION_ID("missing_operation_id"),
    /** 操作标识过长或非法。 */
    INVALID_OPERATION_ID("invalid_operation_id"),
    /** 事件时间格式非法。 */
    INVALID_OCCURRED_AT("invalid_occurred_at"),
    /** 事件时间超出允许范围。 */
    OCCURRED_AT_OUT_OF_RANGE("occurred_at_out_of_range"),
    /** 事件标识已上报过。 */
    DUPLICATE_EVENT("duplicate_event"),
    /** 同一操作的成功事件已计入。 */
    DUPLICATE_SUCCESS("duplicate_success");

    private final String code;

    EventRejectReason(String code) {
        this.code = code;
    }

    /**
     * 返回稳定的原因码。
     *
     * @return 原因码
     */
    public String code() {
        return code;
    }
}
