package com.mytools.auth.service.impl;

import com.mytools.auth.Model.*;
import com.mytools.auth.service.AuthService;
import com.mytools.auth.utils.JwtUtils;
import com.mytools.common.BusinessException;
import com.mytools.common.ErrorCode;
import com.mytools.user.Model.Role;
import com.mytools.user.Model.User;
import com.mytools.user.Model.UserRole;
import com.mytools.user.mapper.RoleMapper;
import com.mytools.user.mapper.UserMapper;
import com.mytools.user.mapper.UserRoleMapper;
import com.mytools.utils.PasswordUtils;
import com.mytools.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务实现类。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final JwtUtils jwtUtils;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    public AuthServiceImpl(UserMapper userMapper, RoleMapper roleMapper,
                           UserRoleMapper userRoleMapper, JwtUtils jwtUtils,
                           SnowflakeIdGenerator snowflakeIdGenerator) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.jwtUtils = jwtUtils;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * 用户注册逻辑：
     * 1. 验证用户名唯一性
     * 2. 验证邮箱唯一性
     * 3. BCrypt加密密码
     * 4. 保存用户记录
     * 5. 分配默认USER角色
     * 6. 生成JWT令牌
     */
    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // 检查用户名是否存在
        if (userMapper.existsByUsername(request.getUsername()) > 0) {
            throw new BusinessException(ErrorCode.USER_002);
        }

        // 检查邮箱是否已被使用
        if (userMapper.existsByEmail(request.getEmail()) > 0) {
            throw new BusinessException(ErrorCode.USER_004);
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
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setRegisterTime(now);
        user.setCreateTime(now);
        user.setUpdateTime(now);

        // 保存用户记录
        userMapper.insert(user);

        // 查询默认USER角色并分配
        Role defaultRole = roleMapper.findDefaultUserRole();
        if (defaultRole == null) {
            log.error("默认USER角色未找到");
            throw new BusinessException(ErrorCode.SYS_001);
        }

        // 构建用户角色关联
        UserRole userRole = new UserRole();
        userRole.setId(snowflakeIdGenerator.nextId());
        userRole.setUserId(userId);
        userRole.setRoleId(defaultRole.getId());
        userRole.setCreateTime(now);

        // 保存用户角色关联
        userRoleMapper.insert(userRole);

        // 生成JWT令牌
        String token = jwtUtils.generateAccessToken(userId, request.getUsername(), "USER");
        long expiresIn = jwtUtils.getExpirationMs() / 1000;

        log.info("用户注册成功: username={}, userId={}", request.getUsername(), userId);

        // 返回响应
        RegisterResponse response = new RegisterResponse();
        response.setUserId(userId);
        response.setUsername(request.getUsername());
        response.setToken(token);
        response.setExpiresIn(expiresIn);
        return response;
    }

    /**
     * 用户登录逻辑：
     * 1. 根据账户（用户名或邮箱）查询用户
     * 2. 验证密码
     * 3. 检查账户状态
     * 4. 更新最后登录时间
     * 5. 生成JWT令牌
     */
    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 先尝试按用户名查询
        User user = userMapper.findByUsername(request.getAccount());
        // 如果找不到，尝试按邮箱查询
        if (user == null) {
            user = userMapper.findByEmail(request.getAccount());
        }

        // 如果仍然找不到，返回认证失败
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_005);
        }

        // 验证密码
        if (!PasswordUtils.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.USER_005);
        }

        // 检查账户状态
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_006);
        }

        // 更新最后登录时间
        userMapper.updateLastLoginTime(user.getId(), LocalDateTime.now());

        // 生成JWT令牌
        String token = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        long expiresIn = jwtUtils.getExpirationMs() / 1000;

        log.info("用户登录成功: username={}, userId={}", user.getUsername(), user.getId());

        // 返回响应
        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setToken(token);
        response.setExpiresIn(expiresIn);
        return response;
    }

    /**
     * 刷新令牌逻辑：
     * 1. 验证旧令牌有效性
     * 2. 检查令牌是否过期
     * 3. 生成新访问令牌
     */
    @Override
    public RefreshResponse refreshToken(String oldToken) {
        // 移除Bearer前缀
        if (oldToken != null && oldToken.startsWith("Bearer ")) {
            oldToken = oldToken.substring(7);
        }

        // 验证令牌有效性
        if (!jwtUtils.validateToken(oldToken)) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }

        // 检查是否已过期
        if (jwtUtils.isTokenExpired(oldToken)) {
            throw new BusinessException(ErrorCode.AUTH_001);
        }

        // 从旧令牌中提取用户信息
        Long userId = jwtUtils.getUserIdFromToken(oldToken);
        String username = jwtUtils.getUsernameFromToken(oldToken);
        String role = jwtUtils.getRoleFromToken(oldToken);

        // 生成新访问令牌
        String newToken = jwtUtils.generateAccessToken(userId, username, role);
        long expiresIn = jwtUtils.getExpirationMs() / 1000;

        log.info("令牌刷新成功: userId={}", userId);

        // 返回响应
        RefreshResponse response = new RefreshResponse();
        response.setToken(newToken);
        response.setExpiresIn(expiresIn);
        return response;
    }
}