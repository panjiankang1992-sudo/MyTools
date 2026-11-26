package com.yuyutian.mytools.toolbox.service.admin.identity;

/**
 * 上游身份调用的失败分类。
 * 分类决定工具箱对外的错误码与是否重试，禁止把失败细节（含令牌、账号）写入消息。
 */
public enum AdminIdentityFailure {

    /** 上游明确判定账号或密码不正确，或令牌无效。 */
    REJECTED,
    /** 当前账号有效但不是管理员。 */
    NOT_ADMIN,
    /** 上游暂时不可用、超时或返回了不安全/无法解析的结果。 */
    UNAVAILABLE
}
