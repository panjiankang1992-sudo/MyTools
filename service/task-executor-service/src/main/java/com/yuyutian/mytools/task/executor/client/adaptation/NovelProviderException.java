package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;

/** 无异常链、正文和请求头的本地改编前置条件异常。 */
public final class NovelProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode error;
    private final NovelProviderProtocolDiagnostics.Reason protocolReason;

    /** 仅使用白名单代码创建异常。 */
    public NovelProviderException(ErrorCode error) {
        this(error, NovelProviderProtocolDiagnostics.Reason.INVALID_SHAPE);
    }

    NovelProviderException(ErrorCode error, NovelProviderProtocolDiagnostics.Reason protocolReason) {
        super(error.code(), null, false, false);
        this.error = error;
        this.protocolReason = protocolReason;
    }

    NovelProviderProtocolDiagnostics.Reason protocolReason() { return protocolReason; }

    /** 返回稳定错误类型。 */
    public ErrorCode error() { return error; }
}
