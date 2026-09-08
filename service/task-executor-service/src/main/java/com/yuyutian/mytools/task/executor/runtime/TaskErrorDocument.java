package com.yuyutian.mytools.task.executor.runtime;

/**
 * 脚本向 Executor 声明的稳定业务错误。
 *
 * @param code 稳定错误码
 * @param category 错误类别
 * @param retryable 是否允许自动重试
 * @param message 有界错误摘要
 */
public record TaskErrorDocument(String code, Category category, Boolean retryable, String message) {

    /**
     * 校验错误文档字段及类别与重试语义一致性。
     *
     * @return 是否有效
     */
    public boolean valid() {
        return code != null && code.matches("^[A-Z][A-Z0-9_]{2,127}$") && category != null
                && retryable != null && retryable == category.retryable()
                && (message == null || message.length() <= 2048);
    }

    /**
     * 稳定业务错误类别。
     */
    public enum Category {
        TRANSIENT(true),
        RATE_LIMITED(true),
        RESOURCE_EXHAUSTED(true),
        TIMEOUT(true),
        PERMANENT(false),
        VALIDATION(false),
        AUTHENTICATION(false),
        AUTHORIZATION(false),
        NOT_FOUND(false),
        CONFLICT(false);

        private final boolean retryable;

        Category(boolean retryable) {
            this.retryable = retryable;
        }

        /**
         * 返回该错误类别是否允许自动重试。
         *
         * @return 是否可重试
         */
        public boolean retryable() {
            return retryable;
        }
    }
}
