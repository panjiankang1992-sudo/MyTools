package com.yuyutian.mytools.localfile.service.tagging;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import lombok.Getter;

/**
 * 打标签服务异常。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Getter
public class TaggerException extends BusinessException {

    public TaggerException(ErrorCode errorCode) {
        super(errorCode);
    }

    public TaggerException(ErrorCode errorCode, String detail) {
        super(errorCode.getCode(), errorCode.getMessageKey() + ": " + detail, errorCode.getHttpStatus());
    }

    public TaggerException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getCode(), errorCode.getMessageKey(), errorCode.getHttpStatus());
        initCause(cause);
    }

    public TaggerException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getCode(), errorCode.getMessageKey() + ": " + detail, errorCode.getHttpStatus());
        initCause(cause);
    }
}
