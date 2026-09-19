package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;

/** 章节改编领域异常，只携带稳定错误码，不保留用户正文。 */
public final class ChapterAdaptationException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final ErrorCode errorCode;

    /** 以稳定错误码创建异常。 */
    public ChapterAdaptationException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /** 返回用于公共响应的稳定错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
