package com.yuyutian.mytools.toolbox.common;

/**
 * 成功响应信封：code 固定为 OK，requestId 与响应头 X-Request-Id 一致。
 *
 * @param code      成功标记，固定为 OK
 * @param data      业务数据
 * @param requestId 请求标识
 * @param <T>       业务数据类型
 */
public record ApiResponse<T>(String code, T data, String requestId) {

    /** 成功标记常量。 */
    public static final String OK = "OK";

    /**
     * 构造成功响应，requestId 取当前请求上下文。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功信封
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK, data, RequestIdContext.get());
    }
}
