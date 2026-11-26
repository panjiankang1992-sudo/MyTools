package com.yuyutian.mytools.toolbox.common;

import java.util.Map;

/**
 * 失败响应信封：只包含稳定错误码、messageKey、安全 details 与 requestId。
 *
 * @param code      稳定错误码，例如 TOOLBOX_001
 * @param messageKey 前端提示键
 * @param details   安全 details，只允许字段路径、限额与索引
 * @param requestId 请求标识
 */
public record ApiErrorResponse(String code, String messageKey, Map<String, Object> details, String requestId) {

    /**
     * 由业务异常构造失败信封。
     *
     * @param exception 业务异常
     * @return 失败信封
     */
    public static ApiErrorResponse of(BusinessException exception) {
        return new ApiErrorResponse(
                exception.errorCode().name(),
                exception.messageKey(),
                exception.details(),
                RequestIdContext.get());
    }

    /**
     * 由错误码构造不带 details 的失败信封。
     *
     * @param errorCode 稳定错误码
     * @return 失败信封
     */
    public static ApiErrorResponse of(ErrorCode errorCode) {
        return new ApiErrorResponse(errorCode.name(), errorCode.messageKey(), Map.of(), RequestIdContext.get());
    }
}
