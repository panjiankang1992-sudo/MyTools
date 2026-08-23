package com.yuyutian.mytools.storage.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 存储网关稳定错误响应转换器。
 */
@RestControllerAdvice
public class StorageExceptionHandler {

    /**
     * 转换参数与状态冲突异常。
     *
     * @param exception 异常
     * @return 错误响应
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException exception) {
        String code = exception.getMessage() != null && exception.getMessage().matches("STORAGE_\\d{3}")
                ? exception.getMessage() : "STORAGE_007";
        HttpStatus status = switch (code) {
            case "STORAGE_001", "STORAGE_009", "STORAGE_011" -> HttpStatus.NOT_FOUND;
            case "STORAGE_010" -> HttpStatus.UNAUTHORIZED;
            case "STORAGE_002", "STORAGE_005", "STORAGE_008", "STORAGE_012" -> HttpStatus.CONFLICT;
            case "STORAGE_014" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", code, "message", "Storage operation failed"));
    }
}
