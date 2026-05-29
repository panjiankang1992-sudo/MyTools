package com.yuyutian.mytools.auth.mapper;

import com.yuyutian.mytools.auth.Model.EmailVerificationCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 邮箱验证码数据访问层。
 *
 * @author mytools
 * @since 2026-05-29
 */
@Mapper
public interface EmailVerificationCodeMapper {

    /**
     * 插入验证码记录。
     *
     * @param code 验证码实体
     * @return 影响行数
     */
    int insert(EmailVerificationCode code);

    /**
     * 查询最近一条未使用验证码。
     *
     * @param username 用户名
     * @param email 邮箱
     * @param phone 手机号
     * @param purpose 验证码用途
     * @return 验证码实体
     */
    EmailVerificationCode findLatestUnused(@Param("username") String username,
                                           @Param("email") String email,
                                           @Param("phone") String phone,
                                           @Param("purpose") String purpose);

    /**
     * 查询邮箱最近一条未使用验证码。
     *
     * @param email 邮箱
     * @param purpose 验证码用途
     * @return 验证码实体
     */
    EmailVerificationCode findLatestUnusedByEmail(@Param("email") String email,
                                                  @Param("purpose") String purpose);

    /**
     * 将邮箱下未使用验证码置为失效。
     *
     * @param email 邮箱
     * @param purpose 验证码用途
     * @param updateTime 更新时间
     * @return 影响行数
     */
    int invalidateUnusedByEmail(@Param("email") String email,
                                @Param("purpose") String purpose,
                                @Param("updateTime") LocalDateTime updateTime);

    /**
     * 标记验证码已使用。
     *
     * @param id 验证码记录ID
     * @param usedTime 使用时间
     * @return 影响行数
     */
    int markUsed(@Param("id") Long id, @Param("usedTime") LocalDateTime usedTime);

    /**
     * 标记验证码已过期。
     *
     * @param id 验证码记录ID
     * @param updateTime 更新时间
     * @return 影响行数
     */
    int markExpired(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);
}
