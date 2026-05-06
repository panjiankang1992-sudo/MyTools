package com.yuyutian.mytools.user.service;

import com.yuyutian.mytools.common.PageResult;
import com.yuyutian.mytools.user.Model.*;

/**
 * 用户服务接口。
 *
 * @author mytools
 * @since 2026-04-22
 */
public interface UserService {

    /**
     * 获取用户信息。
     *
     * @param userId 用户ID
     * @return 用户信息响应
     */
    UserInfoResponse getUserInfo(Long userId);

    /**
     * 更新用户信息。
     *
     * @param userId 用户ID
     * @param request 更新请求
     * @return 更新后的用户信息
     */
    UserInfoResponse updateUserInfo(Long userId, UpdateUserInfoRequest request);

    /**
     * 修改密码。
     *
     * @param userId 用户ID
     * @param request 修改密码请求
     */
    void changePassword(Long userId, ChangePasswordRequest request);

    /**
     * 更新用户状态（管理员）。
     *
     * @param targetUserId 目标用户ID
     * @param status 新状态
     * @param adminUserId 管理员用户ID
     * @return 更新后的用户ID和状态
     */
    UserStatusResponse updateUserStatus(Long targetUserId, String status, Long adminUserId);

    /**
     * 删除用户（管理员）。
     *
     * @param targetUserId 目标用户ID
     * @param adminUserId 管理员用户ID
     */
    void deleteUser(Long targetUserId, Long adminUserId);

    /**
     * 解锁用户账户（管理员）。
     * 用于解除因连续登录失败而被锁定的账户。
     *
     * @param targetUserId 目标用户ID
     * @param adminUserId 管理员用户ID
     */
    void unlockUser(Long targetUserId, Long adminUserId);

    /**
     * 分页查询用户列表（管理员）。
     *
     * @param keyword 关键字（用户名或邮箱模糊匹配）
     * @param status 状态过滤
     * @param page 页码（从1开始）
     * @param pageSize 每页记录数
     * @return 分页用户列表
     */
    PageResult<UserInfoResponse> getUserPage(String keyword, String status, int page, int pageSize);

    /**
     * 创建用户（管理员）。
     *
     * @param request 创建用户请求
     * @param adminUserId 管理员用户ID
     * @return 创建的用户响应
     */
    CreateUserResponse createUser(CreateUserRequest request, Long adminUserId);

    /**
     * 更新用户（管理员）。
     *
     * @param targetUserId 目标用户ID
     * @param request 更新用户请求
     * @param adminUserId 管理员用户ID
     * @return 更新后的用户信息
     */
    UserInfoResponse updateUser(Long targetUserId, UpdateUserRequest request, Long adminUserId);

    /**
     * 为用户分配角色（管理员）。
     *
     * @param targetUserId 目标用户ID
     * @param roleCode 角色编码
     * @param adminUserId 管理员用户ID
     */
    void assignRole(Long targetUserId, String roleCode, Long adminUserId);
}