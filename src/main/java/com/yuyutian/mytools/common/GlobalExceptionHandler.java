package com.yuyutian.mytools.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器。
 * 统一处理所有Controller抛出的异常，返回格式一致的错误响应。
 * 不泄露内部实现细节到客户端。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     *
     * @param ex 业务异常
     * @param request HTTP请求
     * @return 错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("业务异常: code={}, message={}, path={}", ex.getErrorCode(), ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus()).body(result);
    }

    /**
     * 处理验证异常。
     *
     * @param ex 验证异常
     * @param request HTTP请求
     * @return 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("验证异常: errors={}, path={}", errors, request.getRequestURI());
        Result<Map<String, String>> result = new Result<>(
                HttpStatus.BAD_REQUEST.value(),
                "参数校验失败",
                errors,
                generateTraceId(),
                LocalDateTime.now()
        );
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * 处理认证异常（Spring Security）。
     *
     * @param ex 认证异常
     * @param request HTTP请求
     * @return 错误响应
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {
        log.warn("认证异常: message={}, path={}", ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ErrorCode.AUTH_002);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    /**
     * 处理访问拒绝异常（权限不足）。
     *
     * @param ex 访问拒绝异常
     * @param request HTTP请求
     * @return 错误响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(
            AccessDeniedException ex, HttpServletRequest request) {
        log.warn("访问拒绝: message={}, path={}", ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ErrorCode.AUTH_003);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
    }

    /**
     * 处理媒体文件未找到异常。
     *
     * @param ex 媒体未找到异常
     * @param request HTTP请求
     * @return 错误响应
     */
    @ExceptionHandler(MediaNotFoundException.class)
    public ResponseEntity<Result<Void>> handleMediaNotFoundException(
            MediaNotFoundException ex, HttpServletRequest request) {
        log.warn("媒体未找到: message={}, path={}", ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ErrorCode.MOMENTS_004);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
    }

    /**
     * 处理所有其他未捕获的异常。
     * 不泄露内部错误详情到客户端。
     *
     * @param ex 异常
     * @param request HTTP请求
     * @return 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("未处理异常: type={}, message={}, path={}", ex.getClass().getName(), ex.getMessage(), request.getRequestURI(), ex);
        Result<Void> result = Result.error(ErrorCode.SYS_001);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    /**
     * 生成追踪ID。
     */
    private String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
