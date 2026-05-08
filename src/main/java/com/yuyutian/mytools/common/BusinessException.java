package com.yuyutian.mytools.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Business exception.
 * Contains error code, message key for i18n, HTTP status, and field-level errors.
 */
@Getter
public class BusinessException extends RuntimeException {
    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;
    private final Map<String, String> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessageKey());
        this.code = errorCode.getCode();
        this.messageKey = errorCode.getMessageKey();
        this.httpStatus = errorCode.getHttpStatus();
        this.fieldErrors = null;
    }

    public BusinessException(ErrorCode errorCode, Map<String, String> fieldErrors) {
        super(errorCode.getMessageKey());
        this.code = errorCode.getCode();
        this.messageKey = errorCode.getMessageKey();
        this.httpStatus = errorCode.getHttpStatus();
        this.fieldErrors = fieldErrors;
    }

    public BusinessException(String code, String messageKey, HttpStatus httpStatus) {
        super(messageKey);
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
        this.fieldErrors = null;
    }
}
