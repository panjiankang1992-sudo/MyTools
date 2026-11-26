package com.yuyutian.mytools.toolbox.service.admin.identity;

/**
 * 上游身份调用失败时抛出的受控异常。
 * 只携带失败分类，不携带账号、密码或令牌，避免通过异常信息泄漏凭据。
 */
public class AdminIdentityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient AdminIdentityFailure failure;

    /**
     * 构造受控异常。
     *
     * @param failure 失败分类
     */
    public AdminIdentityException(AdminIdentityFailure failure) {
        super("admin identity " + failure.name());
        this.failure = failure;
    }

    /**
     * 返回失败分类。
     *
     * @return 失败分类
     */
    public AdminIdentityFailure failure() {
        return failure;
    }
}
