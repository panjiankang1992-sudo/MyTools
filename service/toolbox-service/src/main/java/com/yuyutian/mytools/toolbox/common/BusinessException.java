package com.yuyutian.mytools.toolbox.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务异常：只携带错误码、messageKey 与安全 details，禁止硬编码提示文案。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String messageKey;
    private final transient Map<String, Object> details;

    /**
     * 使用错误码默认 messageKey 构造异常。
     *
     * @param errorCode 稳定错误码
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.messageKey(), Map.of());
    }

    /**
     * 使用错误码默认 messageKey 与安全 details 构造异常。
     *
     * @param errorCode 稳定错误码
     * @param details   安全 details，只允许字段路径、限额与索引
     */
    public BusinessException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.messageKey(), details);
    }

    /**
     * 完整构造异常；messageKey 由调用方显式指定时必须是稳定的英文键。
     *
     * @param errorCode  稳定错误码
     * @param messageKey 前端用于解析提示的稳定键
     * @param details    安全 details，只允许字段路径、限额与索引
     */
    public BusinessException(ErrorCode errorCode, String messageKey, Map<String, Object> details) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.messageKey = messageKey == null ? errorCode.messageKey() : messageKey;
        // 复制为不可变有序映射，避免调用方后续修改影响响应内容。
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details == null ? Map.of() : details));
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码枚举
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 返回前端提示键。
     *
     * @return messageKey
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * 返回安全 details。
     *
     * @return 不可变 details
     */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * 构造字段级参数错误，details 只包含字段路径与原因。
     *
     * @param field  字段路径
     * @param reason 安全的机器可读原因
     * @return 参数无效异常
     */
    public static BusinessException invalid(String field, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (field != null) {
            details.put("field", field);
        }
        if (reason != null) {
            details.put("reason", reason);
        }
        return new BusinessException(ErrorCode.TOOLBOX_001, details);
    }

    /**
     * 构造带字段限制信息的参数错误。
     *
     * @param field  字段路径
     * @param reason 安全的机器可读原因
     * @param extras 附加安全键值，例如 limit 与 actual
     * @return 参数无效异常
     */
    public static BusinessException invalid(String field, String reason, Map<String, Object> extras) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (field != null) {
            details.put("field", field);
        }
        if (reason != null) {
            details.put("reason", reason);
        }
        if (extras != null) {
            details.putAll(extras);
        }
        return new BusinessException(ErrorCode.TOOLBOX_001, details);
    }
}
