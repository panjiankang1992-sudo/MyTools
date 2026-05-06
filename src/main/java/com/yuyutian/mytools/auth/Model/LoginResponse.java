package com.yuyutian.mytools.auth.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户登录响应数据。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /** 用户ID（雪花算法生成） */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 用户角色 */
    private String role;

    /** JWT访问令牌 */
    private String accessToken;

    /** 令牌过期时间（秒） */
    private Long expiresIn;
}