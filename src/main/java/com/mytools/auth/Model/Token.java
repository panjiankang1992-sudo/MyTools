package com.mytools.auth.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 认证令牌实体类。
 * 用于管理用户JWT令牌的黑名单和失效管理。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Alias("Token")
public class Token {

    /** 令牌记录ID */
    private Long id;

    /** 用户ID */
    private Long userId;

    /** JWT令牌 */
    private String token;

    /** 设备信息 */
    private String deviceInfo;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 创建时间 */
    private LocalDateTime createTime;
}