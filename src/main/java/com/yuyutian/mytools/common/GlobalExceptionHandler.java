package com.yuyutian.mytools.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler.
 * Unified handling of all exceptions thrown by Controller.
 * Returns internationally formatted error responses.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    /**
     * Get internationalized message by message key.
     */
    private String getMessage(String messageKey) {
        try {
            return messageSource.getMessage(messageKey, null, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            log.warn("Failed to get i18n message for key: {}", messageKey);
            return messageKey;
        }
    }

    /**
     * Handle business exception.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("Business exception: code={}, messageKey={}, path={}", ex.getCode(), ex.getMessageKey(), request.getRequestURI());
        Result<Void> result = Result.error(ex.getCode(), getMessage(ex.getMessageKey()), ex.getFieldErrors());
        return ResponseEntity.status(ex.getHttpStatus()).body(result);
    }

    /**
     * Handle validation exception.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String messageKey = error.getDefaultMessage();
            String displayMessage = isChinese(messageKey) ? messageKey : getMessage(messageKey);
            fieldErrors.put(fieldName, displayMessage);
        });
        log.warn("Validation exception: errors={}, path={}", fieldErrors, request.getRequestURI());
        Result<Map<String, String>> result = Result.error(
                "50002",
                getMessage("sys.validation.failed"),
                fieldErrors
        );
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * Handle authentication exception.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication exception: message={}, path={}", ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ErrorCode.AUTH_002.getCode(), getMessage(ErrorCode.AUTH_002.getMessageKey()), null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    /**
     * Handle access denied exception.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(
            AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied: message={}, path={}", ex.getMessage(), request.getRequestURI());
        Result<Void> result = Result.error(ErrorCode.AUTH_003.getCode(), getMessage(ErrorCode.AUTH_003.getMessageKey()), null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
    }

    /**
     * Handle all other uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: type={}, message={}, path={}", ex.getClass().getName(), ex.getMessage(), request.getRequestURI(), ex);
        Result<Void> result = Result.error(ErrorCode.SYS_001.getCode(), getMessage(ErrorCode.SYS_001.getMessageKey()), null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    /**
     * Check if string contains Chinese characters.
     */
    private boolean isChinese(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.matches(".*[\\u4e00-\\u9fa5].*");
    }
}
