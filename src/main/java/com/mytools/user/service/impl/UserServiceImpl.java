package com.mytools.user.service.impl;

import com.mytools.auth.mapper.TokenMapper;
import com.mytools.auth.Model.Token;
import com.mytools.common.BusinessException;
import com.mytools.common.ErrorCode;
import com.mytools.user.Model.*;
import com.mytools.user.mapper.UserMapper;
import com.mytools.user.mapper.UserRoleMapper;
import com.mytools.user.service.UserService;
import com.mytools.utils.PasswordUtils;
import com.mytools.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户服务实现类。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final TokenMapper tokenMapper;
    private final UserRoleMapper userRoleMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    public UserServiceImpl(UserMapper userMapper, TokenMapper tokenMapper,
                           UserRoleMapper userRoleMapper, SnowflakeIdGenerator snowflakeIdGenerator) {
        this.userMapper = userMapper;
        this.tokenMapper = tokenMapper;
        this.userRoleMapper = userRoleMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * 获取用户信息。
     * 查询用户并映射到响应对象（排除密码字段）。
     */
    @Override
    public UserInfoResponse getUserInfo(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        UserInfoResponse response = new UserInfoResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setRegisterTime(user.getRegisterTime());
        response.setLastLoginTime(user.getLastLoginTime());
        return response;
    }

    /**
     * 更新用户信息。
     * 验证邮箱唯一性后更新。
     */
    @Override
    @Transactional
    public UserInfoResponse updateUserInfo(Long userId, UpdateUserInfoRequest request) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 如果更新邮箱，检查唯一性
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            // 检查邮箱是否已被其他用户使用
            User existingUser = userMapper.findByEmail(request.getEmail());
            if (existingUser != null && !existingUser.getId().equals(userId)) {
                throw new BusinessException(ErrorCode.USER_007);
            }
            userMapper.updateEmail(userId, request.getEmail());
        }

        // 更新手机号
        if (request.getPhone() != null) {
            userMapper.updatePhone(userId, request.getPhone());
        }

        // 返回最新用户信息
        return getUserInfo(userId);
    }

    /**
     * 修改密码。
     * 验证旧密码后更新为新密码。
     */
    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 验证旧密码
        if (!PasswordUtils.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.USER_008);
        }

        // BCrypt加密新密码
        String encodedPassword = PasswordUtils.encode(request.getNewPassword());
        userMapper.updatePassword(userId, encodedPassword);

        log.info("用户密码修改成功: userId={}", userId);
    }

    /**
     * 更新用户状态（管理员操作）。
     * 验证管理员权限和目标用户存在性。
     */
    @Override
    @Transactional
    public UserStatusResponse updateUserStatus(Long targetUserId, String status, Long adminUserId) {
        // 验证目标用户存在
        User targetUser = userMapper.findById(targetUserId);
        if (targetUser == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 验证管理员用户存在
        User adminUser = userMapper.findById(adminUserId);
        if (adminUser == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 验证管理员角色
        if (!"ADMIN".equals(adminUser.getRole())) {
            throw new BusinessException(ErrorCode.AUTH_003);
        }

        // 验证状态值
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new BusinessException(ErrorCode.USER_009);
        }

        // 更新状态
        userMapper.updateStatus(targetUserId, status);

        log.info("管理员{}修改用户{}状态为{}", adminUserId, targetUserId, status);

        UserStatusResponse response = new UserStatusResponse();
        response.setUserId(targetUserId);
        response.setStatus(status);
        return response;
    }

    /**
     * 删除用户（管理员操作）。
     * 验证管理员权限，删除用户及其关联数据。
     */
    @Override
    @Transactional
    public void deleteUser(Long targetUserId, Long adminUserId) {
        // 验证目标用户存在
        User targetUser = userMapper.findById(targetUserId);
        if (targetUser == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 验证管理员用户存在
        User adminUser = userMapper.findById(adminUserId);
        if (adminUser == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 验证管理员角色
        if (!"ADMIN".equals(adminUser.getRole())) {
            throw new BusinessException(ErrorCode.AUTH_003);
        }

        // 删除用户令牌
        tokenMapper.deleteByUserId(targetUserId);

        // 删除用户角色关联
        userRoleMapper.deleteByUserId(targetUserId);

        // 删除用户
        userMapper.deleteById(targetUserId);

        log.info("管理员{}删除了用户{}", adminUserId, targetUserId);
    }
}