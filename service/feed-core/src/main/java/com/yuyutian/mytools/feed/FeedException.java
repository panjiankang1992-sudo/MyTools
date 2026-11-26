package com.yuyutian.mytools.feed;

/** 无业务依赖的订阅处理错误，调用方负责映射统一业务错误码。 */
public final class FeedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** 区分地址拒绝、响应超限、格式异常和传输失败。 */
    public enum Kind { URL, LIMIT, FORMAT, TRANSPORT }
    private final Kind kind;
    /** 保存稳定错误类别，不包含 URL 或原始响应。 */
    public FeedException(Kind kind) { super(kind.name()); this.kind = kind; }
    /** 返回稳定错误类别。 */
    public Kind kind() { return kind; }
}
