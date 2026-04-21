package com.mytools.auth.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注册响应数据。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponse {

    /** 用户ID（雪花算法生成） */
    private Long userId;

    /** 用户名 */
    private String username;

    /** JWT访问令牌 */
    private String token;

    /** 令牌过期时间（秒） */
    private Long expiresIn;
}