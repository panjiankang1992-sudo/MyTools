package com.mytools.user.mapper;

import com.mytools.user.Model.User;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

/**
 * 用户数据访问层。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Mapper
public interface UserMapper {

    /**
     * 插入用户记录。
     *
     * @param user 用户对象
     * @return 影响行数
     */
    @Insert("INSERT INTO t_user (id, username, password, email, phone, role, status, register_time, create_time, update_time) " +
            "VALUES (#{id}, #{username}, #{password}, #{email}, #{phone}, #{role}, #{status}, #{registerTime}, #{createTime}, #{updateTime})")
    int insert(User user);

    /**
     * 根据用户名查询用户。
     *
     * @param username 用户名
     * @return 用户对象
     */
    @Select("SELECT id, username, password, email, phone, role, status, register_time, last_login_time, create_time, update_time " +
            "FROM t_user WHERE username = #{username}")
    User findByUsername(String username);

    /**
     * 检查用户名是否存在。
     *
     * @param username 用户名
     * @return 记录数
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE username = #{username}")
    int existsByUsername(String username);

    /**
     * 检查邮箱是否存在。
     *
     * @param email 邮箱
     * @return 记录数
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE email = #{email}")
    int existsByEmail(String email);

    /**
     * 根据邮箱查询用户。
     *
     * @param email 邮箱
     * @return 用户对象
     */
    @Select("SELECT id, username, password, email, phone, role, status, register_time, last_login_time, create_time, update_time " +
            "FROM t_user WHERE email = #{email}")
    User findByEmail(String email);

    /**
     * 根据ID查询用户。
     *
     * @param id 用户ID
     * @return 用户对象
     */
    @Select("SELECT id, username, password, email, phone, role, status, register_time, last_login_time, create_time, update_time " +
            "FROM t_user WHERE id = #{id}")
    User findById(Long id);

    /**
     * 更新最后登录时间。
     *
     * @param id 用户ID
     * @param lastLoginTime 最后登录时间
     * @return 影响行数
     */
    @Update("UPDATE t_user SET last_login_time = #{lastLoginTime} WHERE id = #{id}")
    int updateLastLoginTime(@Param("id") Long id, @Param("lastLoginTime") LocalDateTime lastLoginTime);

    /**
     * 更新邮箱。
     *
     * @param id 用户ID
     * @param email 新邮箱
     * @return 影响行数
     */
    @Update("UPDATE t_user SET email = #{email}, update_time = NOW() WHERE id = #{id}")
    int updateEmail(@Param("id") Long id, @Param("email") String email);

    /**
     * 更新手机号。
     *
     * @param id 用户ID
     * @param phone 新手机号
     * @return 影响行数
     */
    @Update("UPDATE t_user SET phone = #{phone}, update_time = NOW() WHERE id = #{id}")
    int updatePhone(@Param("id") Long id, @Param("phone") String phone);

    /**
     * 更新密码。
     *
     * @param id 用户ID
     * @param password 加密后的密码
     * @return 影响行数
     */
    @Update("UPDATE t_user SET password = #{password}, update_time = NOW() WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /**
     * 更新用户状态。
     *
     * @param id 用户ID
     * @param status 新状态
     * @return 影响行数
     */
    @Update("UPDATE t_user SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 删除用户。
     *
     * @param id 用户ID
     * @return 影响行数
     */
    @Delete("DELETE FROM t_user WHERE id = #{id}")
    int deleteById(Long id);
}