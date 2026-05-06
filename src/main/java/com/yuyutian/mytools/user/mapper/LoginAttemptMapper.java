package com.yuyutian.mytools.user.mapper;

import org.apache.ibatis.annotations.Mapper;

/**
 * 登录尝试记录数据访问层（防暴力破解）。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Mapper
public interface LoginAttemptMapper {

    /**
     * 插入登录尝试记录。
     *
     * @param attempt 登录尝试记录
     * @return 影响行数
     */
    int insert(LoginAttempt attempt);

    /**
     * 根据用户名查询登录尝试记录。
     *
     * @param username 用户名
     * @return 登录尝试记录
     */
    LoginAttempt findByUsername(String username);

    /**
     * 根据用户ID查询登录尝试记录。
     *
     * @param userId 用户ID
     * @return 登录尝试记录
     */
    LoginAttempt findByUserId(Long userId);

    /**
     * 更新登录尝试记录。
     *
     * @param attempt 登录尝试记录
     * @return 影响行数
     */
    int update(LoginAttempt attempt);

    /**
     * 删除登录尝试记录（解锁时使用）。
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    int deleteByUserId(Long userId);
}
