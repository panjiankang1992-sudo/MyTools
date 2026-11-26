package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ErrorCode;

/** 新版投递的稳定业务错误，不携带远端异常正文。 */
public class OutboundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    /** 创建可安全返回客户端的业务异常。 */
    public OutboundException(ErrorCode code) { super(code.message()); this.code = code; }
    /** 返回统一错误码。 */
    public ErrorCode code() { return code; }
}
