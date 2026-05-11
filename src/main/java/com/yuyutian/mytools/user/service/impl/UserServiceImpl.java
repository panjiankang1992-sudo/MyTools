package com.yuyutian.mytools.user.service.impl;

import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.PageResult;
import com.yuyutian.mytools.role.mapper.RoleMapper;
import com.yuyutian.mytools.user.Model.*;
import com.yuyutian.mytools.user.mapper.LoginAttemptMapper;
import com.yuyutian.mytools.user.mapper.UserMapper;
import com.yuyutian.mytools.user.mapper.RoleFinderMapper;
import com.yuyutian.mytools.user.mapper.UserRoleMapper;
import com.yuyutian.mytools.user.service.UserService;
import com.yuyutian.mytools.utils.PasswordUtils;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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
    private final RoleFinderMapper roleFinderMapper;
    private final UserRoleMapper userRoleMapper;
    private final LoginAttemptMapper loginAttemptMapper;
    private final RoleMapper roleMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    public UserServiceImpl(UserMapper userMapper, TokenMapper tokenMapper,
                           RoleFinderMapper roleFinderMapper, UserRoleMapper userRoleMapper,
                           LoginAttemptMapper loginAttemptMapper,
                           RoleMapper roleMapper, SnowflakeIdGenerator snowflakeIdGenerator) {
        this.userMapper = userMapper;
        this.tokenMapper = tokenMapper;
        this.roleFinderMapper = roleFinderMapper;
        this.userRoleMapper = userRoleMapper;
        this.loginAttemptMapper = loginAttemptMapper;
        this.roleMapper = roleMapper;
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
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setGender(user.getGender());
        response.setBirthday(user.getBirthday());
        response.setAddress(user.getAddress());
        response.setHobbies(user.getHobbies());
        response.setSignature(user.getSignature());
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
        }

        // 使用 updateProfile 更新所有字段
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setNickname(request.getNickname());
        updateUser.setAvatar(request.getAvatar());
        updateUser.setEmail(request.getEmail());
        updateUser.setPhone(request.getPhone());
        updateUser.setGender(request.getGender());
        updateUser.setBirthday(request.getBirthday());
        updateUser.setAddress(request.getAddress());
        updateUser.setHobbies(request.getHobbies());
        updateUser.setSignature(request.getSignature());
        userMapper.updateProfile(updateUser);

        log.info("用户{}更新了个人信息", userId);

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

    /**
     * 解锁用户账户（管理员操作）。
     * 解除因连续登录失败而被锁定的账户。
     */
    @Override
    @Transactional
    public void unlockUser(Long targetUserId, Long adminUserId) {
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

        // 删除登录尝试记录（解锁）
        loginAttemptMapper.deleteByUserId(targetUserId);

        log.info("管理员{}解锁了用户{}", adminUserId, targetUserId);
    }

    /**
     * 分页查询用户列表（管理员）。
     */
    @Override
    public PageResult<UserInfoResponse> getUserPage(String keyword, String status, int page, int pageSize) {
        // 计算偏移量
        int offset = (page - 1) * pageSize;

        // 查询总数
        long total = userMapper.count(keyword, status);

        // 查询分页数据
        List<User> users = userMapper.selectPage(keyword, status, offset, pageSize);

        // 转换为响应对象
        List<UserInfoResponse> list = users.stream().map(this::convertToUserInfoResponse).collect(Collectors.toList());

        return new PageResult<>(total, page, pageSize, list);
    }

    private UserInfoResponse convertToUserInfoResponse(User user) {
        UserInfoResponse response = new UserInfoResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setGender(user.getGender());
        response.setBirthday(user.getBirthday());
        response.setAddress(user.getAddress());
        response.setHobbies(user.getHobbies());
        response.setSignature(user.getSignature());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setRegisterTime(user.getRegisterTime());
        response.setLastLoginTime(user.getLastLoginTime());
        return response;
    }

    /**
     * 创建用户（管理员操作）。
     */
    @Override
    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request, Long adminUserId) {
        // 验证管理员用户存在
        User adminUser = userMapper.findById(adminUserId);
        if (adminUser == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }
        // 验证管理员角色
        if (!"ADMIN".equals(adminUser.getRole())) {
            throw new BusinessException(ErrorCode.AUTH_003);
        }

        // 检查用户名唯一性
        if (userMapper.existsByUsername(request.getUsername()) > 0) {
            throw new BusinessException(ErrorCode.USER_002);
        }
        // 检查邮箱唯一性
        if (userMapper.existsByEmail(request.getEmail()) > 0) {
            throw new BusinessException(ErrorCode.USER_007);
        }

        // BCrypt加密密码
        String encodedPassword = PasswordUtils.encode(request.getPassword());

        // 生成雪花ID
        long userId = snowflakeIdGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();

        // 构建用户对象
        User user = new User();
        user.setId(userId);
        user.setUsername(request.getUsername());
        user.setPassword(encodedPassword);
        user.setNickname(request.getNickname()); // 设置昵称
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setGender(request.getGender() != null ? request.getGender() : 0);
        user.setRole(request.getRole() != null ? request.getRole() : "USER");
        user.setStatus(request.getStatus() != null ? request.getStatus() : "ACTIVE");
        user.setRegisterTime(now);
        user.setCreateTime(now);
        user.setUpdateTime(now);

        // 保存用户记录
        userMapper.insert(user);

        log.info("管理员{}创建了新用户{}", adminUserId, userId);

        CreateUserResponse response = new CreateUserResponse();
        response.setUserId(userId);
        response.setUsername(request.getUsername());
        return response;
    }

    /**
     * 更新用户（管理员操作）。
     */
    @Override
    @Transactional
    public UserInfoResponse updateUser(Long targetUserId, UpdateUserRequest request, Long adminUserId) {
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

        // 如果更新邮箱，检查唯一性
        if (request.getEmail() != null && !request.getEmail().equals(targetUser.getEmail())) {
            User existingUser = userMapper.findByEmail(request.getEmail());
            if (existingUser != null && !existingUser.getId().equals(targetUserId)) {
                throw new BusinessException(ErrorCode.USER_007);
            }
        }

        // 使用 updateProfile 更新所有字段
        User updateUser = new User();
        updateUser.setId(targetUserId);
        updateUser.setNickname(request.getNickname());
        updateUser.setEmail(request.getEmail());
        updateUser.setPhone(request.getPhone());
        userMapper.updateProfile(updateUser);

        log.info("管理员{}更新了用户{}", adminUserId, targetUserId);

        // 返回最新用户信息
        return getUserInfo(targetUserId);
    }

    /**
     * 为用户分配角色（管理员操作）。
     */
    @Override
    @Transactional
    public void assignRole(Long targetUserId, String roleCode, Long adminUserId) {
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

        // 通过角色编码查询角色
        com.yuyutian.mytools.role.model.Role role = roleMapper.findByRoleCode(roleCode);
        if (role == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 删除旧的角色关联
        userRoleMapper.deleteByUserId(targetUserId);

        // 创建新的角色关联
        var userRole = new com.yuyutian.mytools.user.Model.UserRole();
        userRole.setId(snowflakeIdGenerator.nextId());
        userRole.setUserId(targetUserId);
        userRole.setRoleId(role.getId());
        userRole.setCreateTime(java.time.LocalDateTime.now());
        userRoleMapper.insert(userRole);

        // 更新用户的角色字段
        userMapper.updateRole(targetUserId, roleCode);

        log.info("管理员{}为用户{}分配了角色{}", adminUserId, targetUserId, roleCode);
    }
}
