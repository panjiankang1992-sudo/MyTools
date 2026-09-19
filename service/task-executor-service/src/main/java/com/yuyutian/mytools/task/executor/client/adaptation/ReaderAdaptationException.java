package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;

/** Reader 固定路由调用失败，仅保留白名单分类，不含远端 message 或底层异常链。 */
public final class ReaderAdaptationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode error;
    private final int httpStatus;

    /** 构造脱敏异常，不复制远端诊断。 */
    public ReaderAdaptationException(ErrorCode error, int httpStatus) {
        super(error.code(), null, false, false); this.error = error; this.httpStatus = httpStatus;
    }
    /** 返回稳定分类。 */
    public ErrorCode error() { return error; }
    /** 返回响应状态；零表示没有完整 HTTP 响应。 */
    public int httpStatus() { return httpStatus; }
}
