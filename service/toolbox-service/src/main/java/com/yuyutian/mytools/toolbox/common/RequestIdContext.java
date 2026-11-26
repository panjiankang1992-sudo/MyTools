package com.yuyutian.mytools.toolbox.common;

/**
 * 请求级 requestId 上下文：由过滤器写入，控制器与异常处理器读取。
 * 使用显式清理避免线程池复用导致串号。
 */
public final class RequestIdContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestIdContext() {
    }

    /**
     * 写入当前线程的 requestId。
     *
     * @param requestId 已校验安全的 requestId
     */
    public static void set(String requestId) {
        CURRENT.set(requestId);
    }

    /**
     * 返回当前线程的 requestId，未设置时返回 null。
     *
     * @return requestId 或 null
     */
    public static String get() {
        return CURRENT.get();
    }

    /**
     * 清理当前线程的 requestId。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
