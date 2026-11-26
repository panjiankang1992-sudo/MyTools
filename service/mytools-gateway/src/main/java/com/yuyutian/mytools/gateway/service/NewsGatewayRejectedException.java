package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.common.ErrorCode;
import org.springframework.http.HttpStatus;

/** 新闻明确业务拒绝只传播白名单错误码，不传播下游原始正文。 */
public class NewsGatewayRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    private final HttpStatus status;

    /** 创建受限的新闻业务异常。 */
    public NewsGatewayRejectedException(ErrorCode code, HttpStatus status) {
        super(code.name());
        this.code = code;
        this.status = status;
    }

    /** 返回公共错误码。 */
    public ErrorCode code() { return code; }

    /** 返回公共 HTTP 状态。 */
    public HttpStatus status() { return status; }
}
