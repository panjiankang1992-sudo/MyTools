package com.yuyutian.mytools.toolbox.service.admin.identity;

/**
 * 上游 MyTools 身份适配层。
 * 工具箱不保存密码、不复制 JWT 签名密钥，只通过受保护的服务端通道复用上游账号与权限。
 * 每个管理读取、保存、发布请求都必须调用 {@link #validate}，不得把浏览器字段当作授权依据。
 */
public interface AdminIdentityClient {

    /**
     * 使用账号与密码向上游登录，取得该次登录的令牌。
     *
     * @param account  用户名或邮箱
     * @param password 密码；只在本次调用中使用，不缓存、不记录日志
     * @return 上游登录结果
     * @throws AdminIdentityException 上游拒绝、不可用或返回不安全结果时抛出
     */
    UpstreamLogin login(String account, String password);

    /**
     * 校验访问令牌对应的账号当前状态与角色。
     * 该调用读取上游数据库的当前状态，而不是仅信任令牌内的历史角色。
     *
     * @param accessToken 上游访问令牌
     * @return 账号权威状态
     * @throws AdminIdentityException 上游不可用或返回不安全结果时抛出
     */
    AdminIdentity validate(String accessToken);

    /**
     * 注销该次上游会话。退出流程应尽力调用，但上游失败不得导致本地仍保持登录。
     *
     * @param accessToken 上游访问令牌
     */
    void logout(String accessToken);

    /**
     * 用刷新令牌换一组新的上游凭据（A08）。
     *
     * **上游契约**（从 MyTools 的 OpenAPI 核实，不是推断）：
     * `POST /api/auth/refresh`，**刷新令牌放在 `Authorization: Bearer` 头里**，
     * 无请求体；响应为 `{accessToken, refreshToken, expiresIn}`。
     *
     * @param refreshToken 刷新令牌；只加密保存于服务端
     * @return 新的上游凭据
     * @throws AdminIdentityException 刷新被拒或上游不可用
     */
    default UpstreamLogin refresh(String refreshToken) {
        // 默认实现只说"这里不支持刷新"。
        //
        // 为什么不强制每个实现都写：这个接口在测试里被大量以匿名类形式实现，
        // 它们关心的是登录／校验／注销，**与刷新无关**；
        // 强制它们都实现一个用不到的抽象方法，只会让那些测试为了编译而写空壳。
        // 真实的 HTTP 实现**覆盖**了它。
        throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
    }

    /**
     * 上游登录结果。
     *
     * @param accessToken  访问令牌；只加密保存于服务端
     * @param refreshToken 刷新令牌；只加密保存于服务端
     * @param expiresIn    访问令牌有效秒数，来自上游响应而不是本地推算
     */
    record UpstreamLogin(String accessToken, String refreshToken, long expiresIn) {
    }
}
