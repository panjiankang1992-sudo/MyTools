package com.yuyutian.mytools.rss.common;

/** 仅携带稳定业务错误码的领域异常。 */
public final class RssException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    /** 创建业务异常。 */
    public RssException(ErrorCode code) { super(code.name()); this.code = code; }
    /** 返回统一错误码。 */
    public ErrorCode code() { return code; }
}
