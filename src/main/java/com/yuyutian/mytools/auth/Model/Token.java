package com.yuyutian.mytools.auth.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 认证令牌实体类。
 * 用于管理用户Access Token和Refresh Token的存储、失效和版本控制。
 *
 * @author mytools
 * @since 2026-04-27
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

    /** Access Token */
    private String accessToken;

    /** Refresh Token */
    private String refreshToken;

    /** Token类型 */
    private String tokenType;

    /** Access Token过期时间戳（毫秒） */
    private Long expireTime;

    /** Refresh Token过期时间戳（毫秒） */
    private Long refreshExpireTime;

    /** 版本号（乐观锁） */
    private Integer version;

    /** 状态：ACTIVE / INVALID */
    private String status;

    /** Token名称（用户自定义） */
    private String tokenName;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
