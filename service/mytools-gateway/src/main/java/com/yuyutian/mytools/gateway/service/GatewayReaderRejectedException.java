package com.yuyutian.mytools.gateway.service;

import org.springframework.http.HttpStatusCode;

/**
 * Reader 已明确拒绝请求且返回稳定错误码异常。
 */
public class GatewayReaderRejectedException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String errorCode;

    /**
     * 创建 Reader 稳定拒绝异常。
     *
     * @param statusCode Reader 返回的客户端错误状态
     * @param errorCode 经 Gateway 格式校验后的 Reader 错误码
     */
    public GatewayReaderRejectedException(HttpStatusCode statusCode, String errorCode) {
        super(errorCode);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    /**
     * 返回应透传给 App 的 HTTP 状态。
     *
     * @return Reader 返回的客户端错误状态
     */
    public HttpStatusCode statusCode() {
        return statusCode;
    }

    /**
     * 返回经 Gateway 校验的稳定 Reader 错误码。
     *
     * @return Reader 错误码
     */
    public String errorCode() {
        return errorCode;
    }
}
