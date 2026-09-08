package com.yuyutian.mytools.task.scheduler.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 调度服务统一异常处理器。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理结构化业务异常。
     *
     * @param exception 业务异常
     * @return 结构化错误响应
     */
    @ExceptionHandler(SchedulerException.class)
    public ResponseEntity<ErrorResponse> handleSchedulerException(SchedulerException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(
                exception.errorCode().name(), exception.getMessage(), Instant.now()));
    }

    /**
     * 处理请求校验异常。
     *
     * @param exception 校验异常
     * @return 结构化错误响应
     */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
                ErrorCode.INVALID_REQUEST.name(), exception.getMessage(), Instant.now()));
    }
}
