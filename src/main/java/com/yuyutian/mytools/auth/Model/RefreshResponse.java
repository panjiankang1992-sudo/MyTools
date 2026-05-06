package com.yuyutian.mytools.auth.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token刷新响应数据。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefreshResponse {

    /** 新JWT访问令牌 */
    private String accessToken;

    /** 令牌过期时间（秒） */
    private Long expiresIn;
}