package com.yuyutian.mytools.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Unified response format.
 * All API responses use this format.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** Error code, "0000" means success */
    private String code;

    /** Response message (i18n key or display text) */
    private String message;

    /** Response data */
    private T data;

    /** Field-level errors (used in validation errors) */
    private Map<String, String> fieldErrors;

    /** Trace ID for log correlation */
    private String traceId;

    /** Response timestamp */
    private LocalDateTime timestamp;

    public static <T> Result<T> success(T data) {
        return new Result<>("0000", "操作成功", data, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> success() {
        return new Result<>("0000", "操作成功", null, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> success(String message) {
        return new Result<>("0000", message, null, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>("0000", message, data, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(String code, String message, Map<String, String> fieldErrors) {
        return new Result<>(String.valueOf(parseCode(code)), message, null, fieldErrors, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(String code, String message) {
        return new Result<>(String.valueOf(parseCode(code)), message, null, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(String.valueOf(errorCode.getHttpStatus().value()), errorCode.getMessageKey(), null, null, generateTraceId(), LocalDateTime.now());
    }

    public static <T> Result<T> error(BusinessException e) {
        return new Result<>(String.valueOf(e.getHttpStatus().value()), e.getMessageKey(), null, e.getFieldErrors(), generateTraceId(), LocalDateTime.now());
    }

    private static int parseCode(String code) {
        try {
            return Integer.parseInt(code);
        } catch (Exception e) {
            return 50000;
        }
    }

    private static String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
