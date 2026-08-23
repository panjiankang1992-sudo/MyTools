package com.yuyutian.mytools.pikpak.controller;

import static com.yuyutian.mytools.pikpak.common.ErrorCode.*;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将内部异常转换为稳定错误码且不回显敏感输入。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** 处理参数错误。 @param exception 异常 @return 错误响应 */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception exception) {
        String code = exception instanceof IllegalArgumentException && exception.getMessage() != null
            && exception.getMessage().matches("PIKPAK_[0-9]{3}") ? exception.getMessage() : REQUEST_INVALID.code();
        HttpStatus status = INTERNAL_UNAUTHORIZED.code().equals(code) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("errorCode", code));
    }

    /** 处理状态冲突。 @param exception 异常 @return 错误响应 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        String code = exception.getMessage() != null && exception.getMessage().matches("PIKPAK_[0-9]{3}")
            ? exception.getMessage() : INTERNAL_FAILURE.code();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("errorCode", code));
    }
}
