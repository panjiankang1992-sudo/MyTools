package com.yuyutian.mytools.auth.service.impl;

import com.yuyutian.mytools.auth.Model.*;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.service.AuthService;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.user.Model.Role;
import com.yuyutian.mytools.user.Model.User;
import com.yuyutian.mytools.user.Model.UserRole;
import com.yuyutian.mytools.user.mapper.LoginAttemptMapper;
import com.yuyutian.mytools.user.mapper.RoleFinderMapper;
import com.yuyutian.mytools.user.mapper.UserMapper;
import com.yuyutian.mytools.user.mapper.UserRoleMapper;
import com.yuyutian.mytools.utils.PasswordUtils;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
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
    private final RoleFinderMapper roleFinderMapper;
    private final UserRoleMapper userRoleMapper;
    private final TokenMapper tokenMapper;
    private final LoginAttemptMapper loginAttemptMapper;
    private final JwtUtils jwtUtils;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** 防暴力破解：最大失败次数 */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** 防暴力破解：计数TTL（分钟） */
    private static final int ATTEMPT_TTL_MINUTES = 15;

    @Autowired
    public AuthServiceImpl(UserMapper userMapper, RoleFinderMapper roleFinderMapper,
                           UserRoleMapper userRoleMapper, TokenMapper tokenMapper,
                           LoginAttemptMapper loginAttemptMapper, JwtUtils jwtUtils,
                           SnowflakeIdGenerator snowflakeIdGenerator) {
        this.userMapper = userMapper;
        this.roleFinderMapper = roleFinderMapper;
        this.userRoleMapper = userRoleMapper;
        this.tokenMapper = tokenMapper;
        this.loginAttemptMapper = loginAttemptMapper;
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
     * 6. 生成JWT令牌并保存到数据库
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
        Role defaultRole = roleFinderMapper.findDefaultUserRole();
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

        // 保存令牌到数据库（用于后续校验）
        Token tokenEntity = new Token();
        tokenEntity.setId(snowflakeIdGenerator.nextId());
        tokenEntity.setUserId(userId);
        tokenEntity.setAccessToken(token);
        tokenEntity.setRefreshToken(token);
        tokenEntity.setTokenType("Bearer");
        tokenEntity.setExpireTime(System.currentTimeMillis() + expiresIn * 1000);
        tokenEntity.setRefreshExpireTime(System.currentTimeMillis() + expiresIn * 1000);
        tokenEntity.setStatus("ACTIVE");
        tokenEntity.setTokenName("注册令牌");
        tokenEntity.setCreateTime(now);
        tokenEntity.setUpdateTime(now);
        tokenMapper.insert(tokenEntity);

        log.info("用户注册成功: username={}, userId={}", request.getUsername(), userId);

        // 返回响应
        RegisterResponse response = new RegisterResponse();
        response.setUserId(userId);
        response.setUsername(request.getUsername());
        response.setAccessToken(token);
        response.setExpiresIn(expiresIn);
        return response;
    }

    /**
     * 用户登录逻辑（支持防暴力破解）：
     * 1. 根据账户（用户名或邮箱）查询用户
     * 2. 检查账户是否被锁定
     * 3. 验证密码
     * 4. 检查账户状态
     * 5. 更新最后登录时间
     * 6. 生成JWT令牌并保存到数据库
     * 7. 登录成功后重置失败计数
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

        // 如果仍然找不到，返回认证失败（不暴露具体原因）
        if (user == null) {
            // 记录无用户名登录尝试
            recordFailedAttempt(null, request.getAccount());
            throw new BusinessException(ErrorCode.USER_005);
        }

        // 检查账户是否被锁定
        checkAccountLockout(user.getId(), user.getUsername());

        // 验证密码
        if (!PasswordUtils.matches(request.getPassword(), user.getPassword())) {
            // 密码错误，记录失败尝试
            recordFailedAttempt(user.getId(), user.getUsername());
            throw new BusinessException(ErrorCode.USER_005);
        }

        // 检查账户状态
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_006);
        }

        // 登录成功：重置失败计数
        resetFailedAttempt(user.getId());

        // 更新最后登录时间
        userMapper.updateLastLoginTime(user.getId(), LocalDateTime.now());

        // 生成JWT令牌
        String token = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), user.getUsername(), user.getRole());
        long expiresIn = jwtUtils.getExpirationMs() / 1000;
        long refreshExpiresIn = jwtUtils.getRefreshExpirationMs() / 1000;

        // 保存令牌到数据库（用于后续校验）
        Token tokenEntity = new Token();
        tokenEntity.setId(snowflakeIdGenerator.nextId());
        tokenEntity.setUserId(user.getId());
        tokenEntity.setAccessToken(token);
        tokenEntity.setRefreshToken(refreshToken);
        tokenEntity.setTokenType("Bearer");
        tokenEntity.setExpireTime(System.currentTimeMillis() + expiresIn * 1000);
        tokenEntity.setRefreshExpireTime(System.currentTimeMillis() + refreshExpiresIn * 1000);
        tokenEntity.setStatus("ACTIVE");
        tokenEntity.setTokenName("登录令牌");
        tokenEntity.setCreateTime(LocalDateTime.now());
        tokenEntity.setUpdateTime(LocalDateTime.now());
        tokenMapper.insert(tokenEntity);

        log.info("用户登录成功: username={}, userId={}", user.getUsername(), user.getId());

        // 返回响应
        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setRole(user.getRole());
        response.setAccessToken(token);
        response.setRefreshToken(refreshToken);
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
        response.setAccessToken(newToken);
        response.setExpiresIn(expiresIn);
        return response;
    }

    /**
     * 用户登出逻辑：
     * 1. 解析Token获取用户ID
     * 2. 将Token状态更新为INVALID
     */
    @Override
    public void logout(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AUTH_004);
        }
        String token = authHeader.substring(7);

        // 验证令牌有效性
        if (!jwtUtils.validateToken(token)) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }

        // 使令牌失效
        tokenMapper.invalidateByAccessToken(token);

        // 从令牌中提取用户ID并记录日志
        Long userId = jwtUtils.getUserIdFromToken(token);
        log.info("用户登出成功: userId={}", userId);
    }

    /**
     * 检查账户是否被锁定。
     * 若锁定则抛出业务异常。
     */
    private void checkAccountLockout(Long userId, String username) {
        com.yuyutian.mytools.user.mapper.LoginAttempt attempt = null;
        if (userId != null) {
            attempt = loginAttemptMapper.findByUserId(userId);
        }
        if (attempt == null) {
            attempt = loginAttemptMapper.findByUsername(username);
        }

        if (attempt != null && attempt.getLockTime() != null) {
            // 账户已被锁定
            log.warn("账户已被锁定: username={}, lockTime={}", username, attempt.getLockTime());
            throw new BusinessException(ErrorCode.AUTH_005);
        }
    }

    /**
     * 记录登录失败尝试。
     * 失败次数+1，若达到阈值则锁定账户。
     */
    private void recordFailedAttempt(Long userId, String username) {
        LocalDateTime now = LocalDateTime.now();

        // 查询现有尝试记录
        com.yuyutian.mytools.user.mapper.LoginAttempt attempt = null;
        if (userId != null) {
            attempt = loginAttemptMapper.findByUserId(userId);
        }
        if (attempt == null) {
            attempt = loginAttemptMapper.findByUsername(username);
        }

        if (attempt == null) {
            // 无记录，创建新记录
            attempt = new com.yuyutian.mytools.user.mapper.LoginAttempt();
            attempt.setId(snowflakeIdGenerator.nextId());
            attempt.setUserId(userId);
            attempt.setUsername(username);
            attempt.setFailedCount(1);
            attempt.setLastAttemptTime(now);
            // 设置TTL过期时间（15分钟后重置计数）
            attempt.setExpireTime(now.plusMinutes(ATTEMPT_TTL_MINUTES));
            attempt.setCreateTime(now);
            attempt.setUpdateTime(now);
            loginAttemptMapper.insert(attempt);
        } else {
            // 已有记录，检查是否已过期
            if (attempt.getExpireTime() != null && now.isAfter(attempt.getExpireTime())) {
                // TTL已过期，重置计数
                attempt.setFailedCount(1);
                attempt.setExpireTime(now.plusMinutes(ATTEMPT_TTL_MINUTES));
            } else {
                // TTL未过期，递增计数
                attempt.setFailedCount(attempt.getFailedCount() + 1);
            }
            attempt.setLastAttemptTime(now);
            attempt.setUpdateTime(now);

            // 检查是否达到锁定阈值
            if (attempt.getFailedCount() >= MAX_FAILED_ATTEMPTS) {
                attempt.setLockTime(now);
                log.warn("账户已达到最大失败次数，被锁定: username={}, failedCount={}",
                        username, attempt.getFailedCount());
            }

            loginAttemptMapper.update(attempt);
        }
    }

    /**
     * 重置登录失败尝试计数。
     * 登录成功后调用。
     */
    private void resetFailedAttempt(Long userId) {
        if (userId == null) {
            return;
        }
        loginAttemptMapper.deleteByUserId(userId);
    }
}
