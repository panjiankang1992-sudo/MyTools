package com.mytools.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一响应格式类。
 * 所有API响应都使用此格式。
 *
 * @param <T> 响应数据类型
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** 错误码，200表示成功 */
    private int code;

    /** 响应消息 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 追踪ID，用于日志关联 */
    private String traceId;

    /** 响应时间戳 */
    private LocalDateTime timestamp;

    /**
     * 创建成功响应。
     *
     * @param data 响应数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 创建成功响应（无数据）。
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 创建成功响应（带消息）。
     *
     * @param message 成功消息
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(String message) {
        return new Result<>(200, message, null, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 创建成功响应（带消息和数据）。
     *
     * @param message 成功消息
     * @param data 响应数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 创建成功响应（带HTTP状态码）。
     *
     * @param httpStatus HTTP状态码
     * @param message 成功消息
     * @param data 响应数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(int httpStatus, String message, T data) {
        return new Result<>(httpStatus, message, data, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 创建错误响应。
     *
     * @param errorCode 错误码
     * @param message 错误消息
     * @param <T> 数据类型
     * @return 错误响应
     */
    public static <T> Result<T> error(String errorCode, String message) {
        return new Result<>(parseCode(errorCode), message, null, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 创建错误响应（使用ErrorCode枚举）。
     *
     * @param errorCode 错误码枚举
     * @param <T> 数据类型
     * @return 错误响应
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getHttpStatus().value(), errorCode.getMessage(), null, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 创建错误响应（使用BusinessException）。
     *
     * @param e 业务异常
     * @param <T> 数据类型
     * @return 错误响应
     */
    public static <T> Result<T> error(BusinessException e) {
        return new Result<>(e.getHttpStatus().value(), e.getMessage(), null, generateTraceId(), LocalDateTime.now());
    }

    /**
     * 将错误码字符串解析为HTTP状态码数值。
     */
    private static int parseCode(String errorCode) {
        try {
            String[] parts = errorCode.split("_");
            if (parts.length >= 2) {
                String category = parts[0];
                int num = Integer.parseInt(parts[1]);
                return switch (category) {
                    case "USER" -> 40000 + num;
                    case "AUTH" -> 50000 + num;
                    case "SYS" -> 60000 + num;
                    default -> 50000 + num;
                };
            }
            return 50000;
        } catch (Exception e) {
            return 50000;
        }
    }

    /**
     * 生成追踪ID。
     */
    private static String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
