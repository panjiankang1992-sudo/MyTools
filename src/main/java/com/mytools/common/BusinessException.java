package com.mytools.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常基类。
 * 所有业务相关的异常都继承此类，包含错误码和HTTP状态码信息。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    /**
     * 通过错误码枚举构造业务异常。
     *
     * @param errorCode 错误码枚举
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 通过错误码枚举和自定义消息构造业务异常。
     *
     * @param errorCode 错误码枚举
     * @param customMessage 自定义错误消息
     */
    public BusinessException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 通过错误码字符串和HTTP状态码构造业务异常。
     *
     * @param errorCode 错误码字符串
     * @param message 错误消息
     * @param httpStatus HTTP状态码
     */
    public BusinessException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
