package com.yuyutian.mytools.user.mapper;

import com.yuyutian.mytools.user.Model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
    int insert(User user);

    /**
     * 根据用户名查询用户。
     *
     * @param username 用户名
     * @return 用户对象
     */
    User findByUsername(String username);

    /**
     * 检查用户名是否存在。
     *
     * @param username 用户名
     * @return 记录数
     */
    int existsByUsername(String username);

    /**
     * 检查邮箱是否存在。
     *
     * @param email 邮箱
     * @return 记录数
     */
    int existsByEmail(String email);

    /**
     * 根据邮箱查询用户。
     *
     * @param email 邮箱
     * @return 用户对象
     */
    User findByEmail(String email);

    /**
     * 根据ID查询用户。
     *
     * @param id 用户ID
     * @return 用户对象
     */
    User findById(Long id);

    /**
     * 更新最后登录时间。
     *
     * @param id 用户ID
     * @param lastLoginTime 最后登录时间
     * @return 影响行数
     */
    int updateLastLoginTime(Long id, java.time.LocalDateTime lastLoginTime);

    /**
     * 更新邮箱。
     *
     * @param id 用户ID
     * @param email 新邮箱
     * @return 影响行数
     */
    int updateEmail(Long id, String email);

    /**
     * 更新手机号。
     *
     * @param id 用户ID
     * @param phone 新手机号
     * @return 影响行数
     */
    int updatePhone(Long id, String phone);

    /**
     * 更新密码。
     *
     * @param id 用户ID
     * @param password 加密后的密码
     * @return 影响行数
     */
    int updatePassword(Long id, String password);

    /**
     * 更新用户状态。
     *
     * @param id 用户ID
     * @param status 新状态
     * @return 影响行数
     */
    int updateStatus(Long id, String status);

    /**
     * 删除用户。
     *
     * @param id 用户ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 更新用户角色。
     *
     * @param id 用户ID
     * @param role 新角色
     * @return 影响行数
     */
    int updateRole(Long id, String role);

    /**
     * 统计用户总数（支持关键字和状态过滤）。
     *
     * @param keyword 关键字（用户名或邮箱模糊匹配）
     * @param status 状态过滤
     * @return 记录数
     */
    long count(@Param("keyword") String keyword, @Param("status") String status);

    /**
     * 分页查询用户列表。
     *
     * @param keyword 关键字
     * @param status 状态
     * @param offset 偏移量
     * @param limit 每页数量
     * @return 用户列表
     */
    List<User> selectPage(@Param("keyword") String keyword, @Param("status") String status,
                         @Param("offset") int offset, @Param("limit") int limit);
}