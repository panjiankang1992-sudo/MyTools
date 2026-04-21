package com.mytools.user.service;

import com.mytools.user.Model.*;

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
}