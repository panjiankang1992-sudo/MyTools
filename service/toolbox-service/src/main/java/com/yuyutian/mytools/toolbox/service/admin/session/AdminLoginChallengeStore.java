package com.yuyutian.mytools.toolbox.service.admin.session;

/**
 * 登录预认证挑战存储。
 * 登录表单打开时领取一次性 nonce，并与预认证 Cookie 绑定；提交时一次性消费，防止重放。
 * 实现由 `JdbcAdminLoginChallengeStore` 提供，使挑战在重启与多实例下都不丢失。
 */
public interface AdminLoginChallengeStore {

    /** 预认证 Cookie 名称。 */
    String PRE_AUTH_COOKIE = "__Host-toolbox_admin_login";

    /**
     * 创建一个预认证挑战。
     *
     * @return 预认证 Cookie 明文值与 CSRF nonce 明文值
     */
    IssuedChallenge issue();

    /**
     * 一次性消费挑战；Cookie 值、nonce 与有效期都必须匹配，且只能成功一次。
     *
     * @param cookieValue 预认证 Cookie 明文值
     * @param nonce       请求头携带的 nonce
     * @return 消费成功时为 true
     */
    boolean consume(String cookieValue, String nonce);

    /**
     * 新签发的预认证挑战。
     *
     * @param cookieValue 预认证 Cookie 明文值
     * @param nonce       CSRF nonce 明文值
     */
    record IssuedChallenge(String cookieValue, String nonce) {
    }
}
