package com.yuyutian.mytools.user.mapper;

import lombok.Data;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 登录尝试记录实体类（防暴力破解）。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@Alias("LoginAttempt")
public class LoginAttempt {

    /** 主键ID */
    private Long id;

    /** 用户ID（未找到用户时为空） */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 连续失败次数 */
    private Integer failedCount;

    /** 锁定时间（不为空表示已锁定） */
    private LocalDateTime lockTime;

    /** 最后尝试时间 */
    private LocalDateTime lastAttemptTime;

    /** 计数过期时间（TTL，15分钟后重置） */
    private LocalDateTime expireTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
