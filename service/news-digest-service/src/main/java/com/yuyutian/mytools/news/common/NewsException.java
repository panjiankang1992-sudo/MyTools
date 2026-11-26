package com.yuyutian.mytools.news.common;

/** 携带统一错误码的业务异常。 */
public class NewsException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;

    /** 使用错误码创建异常，不附加敏感上下文。 */
    public NewsException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    /** 获取业务错误码。 */
    public ErrorCode code() { return code; }
}
