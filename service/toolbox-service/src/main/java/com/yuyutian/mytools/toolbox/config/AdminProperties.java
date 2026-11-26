package com.yuyutian.mytools.toolbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 管理员门面配置：上游身份地址、内部凭据与本地管理会话上限。
 * 所有键使用英文；内部凭据只从运行环境注入，默认值为空且不包含任何可用凭据。
 *
 * @param enabled           是否启用管理员功能；关闭时全部管理接口返回不可用
 * @param upstreamBaseUrl   上游 MyTools 认证服务的固定基地址，必须是 http/https 的绝对地址
 * @param internalToken     调用上游内部校验接口的凭据，只从环境变量注入，禁止写入仓库
 * @param upstreamTimeoutMs 单次上游调用的超时毫秒数，超时按依赖不可用处理
 * @param idleMinutes       管理会话空闲上限，单位分钟
 * @param absoluteHours     管理会话绝对上限，单位小时
 * @param sessionCookieName 管理会话 Cookie 名称，生产默认带 __Host- 前缀
 * @param cookieSecure      是否只在 HTTPS 下发送管理 Cookie
 * @param csrfHeaderName    管理写请求使用的 CSRF 头名称，与匿名 Guest 头严格区分
 * @param encryptionKey     上游令牌的加密密钥，Base64 编码的 16／24／32 字节；只从环境注入
 */
@ConfigurationProperties(prefix = "toolbox.admin")
public record AdminProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String upstreamBaseUrl,
        @DefaultValue("") String internalToken,
        @DefaultValue("4000") long upstreamTimeoutMs,
        @DefaultValue("30") long idleMinutes,
        @DefaultValue("8") long absoluteHours,
        @DefaultValue("__Host-toolbox_admin") String sessionCookieName,
        /**
         * 登录预认证 Cookie 名称。
         *
         * **必须与 `cookieSecure` 保持一致**：`__Host-` 前缀的 Cookie 按规范**必须带 `Secure`**，
         * 否则浏览器会直接拒收——那样 nonce 永远绑定不上，登录会以 403 失败。
         * 明文部署（本地联调、内网 HTTP）必须改用不带前缀的名称。
         */
        @DefaultValue("__Host-toolbox_admin_login") String loginCookieName,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("X-Toolbox-Admin-Csrf") String csrfHeaderName,
        @DefaultValue("") String encryptionKey,
        /**
         * 管理端写请求的请求体上限（字节）。
         *
         * 整册教材草稿约 800 条，实测 JSON 在数百 KB 量级；取 4 MB 既留出充足余量，
         * 又能挡住无界请求体。**这个值是运维可调的**：若将来有更大的单册需求，
         * 调大这里即可，不需要改代码。
         */
        @DefaultValue("4194304") long maxBodyBytes,
        /**
         * 登录失败次数上限；窗口内超过即限流。0 或负数表示关闭限流。
         *
         * 默认 10 次 / 5 分钟：够正常用户打错几次密码，又能挡住脚本穷举。
         */
        @DefaultValue("10") long loginMaxFailures,
        /** 登录限流的统计窗口（秒）。 */
        @DefaultValue("300") long loginWindowSeconds) {

    /**
     * 校验 Cookie 配置自洽。
     *
     * `__Host-` 前缀的 Cookie 按规范必须带 `Secure`；若配置为明文（`cookieSecure=false`）
     * 却仍使用该前缀，浏览器会**直接拒收**，表现为登录时 403「CSRF 无效」——很难排查。
     * 因此启动时直接失败，而不是留到运行时以隐晦的方式暴露。
     *
     * @throws IllegalStateException 配置自相矛盾时
     */
    public void validateCookieConfiguration() {
        if (!cookieSecure && (sessionCookieName.startsWith("__Host-") || loginCookieName.startsWith("__Host-"))) {
            throw new IllegalStateException(
                    "admin cookie configuration inconsistent: __Host- prefixed cookies require Secure; "
                            + "set toolbox.admin.cookie-secure=true or use non-prefixed cookie names "
                            + "(sessionCookieName=" + sessionCookieName + ", loginCookieName=" + loginCookieName + ")");
        }
    }


}
