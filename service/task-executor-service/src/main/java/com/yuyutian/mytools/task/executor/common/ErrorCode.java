package com.yuyutian.mytools.task.executor.common;

/** 与 Reader 共用的改编协议错误码，仅传递固定分类，不包含外部诊断正文。 */
public enum ErrorCode {
    NOT_FOUND("READER_030"), INTENT_CONFLICT("READER_033"), SOURCE_CHANGED("READER_035"), IDEMPOTENCY_CONFLICT("READER_036"),
    UNAVAILABLE("READER_039"), UNAUTHORIZED("READER_040"), PROTOCOL("READER_041"),
    TOO_LARGE("READER_042"), CONSTRAINTS("READER_043"), FENCED("READER_044"), DISABLED("READER_046"),
    DISPATCH("READER_048"), CONTEXT("READER_049"), BIND_PENDING("READER_050"), CONSENT("READER_052"),
    CONTENT_REJECTED("READER_053"), UNKNOWN("READER_054"), REQUEST_REJECTED("READER_055"),
    HISTORY_DELETED("READER_056"), DELETING("READER_057"), DEADLINE("READER_058"),
    AUTHORITY_UNAVAILABLE("READER_059"), REQUEST_INVALID("READER_060"), PERSISTENCE_UNAVAILABLE("READER_061");

    private final String code;

    ErrorCode(String code) { this.code = code; }

    /** 返回可写入 Reader 账本的稳定代码。 */
    public String code() { return code; }

    /** 只识别白名单服务代码，外部任意代码不能进入异常或指标。 */
    public static ErrorCode fromReader(String value) {
        for (ErrorCode error : values()) if (error.code.equals(value)) return error;
        return AUTHORITY_UNAVAILABLE;
    }
}
