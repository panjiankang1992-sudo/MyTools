package com.mytools.auth.service;

import com.mytools.auth.Model.*;
import com.mytools.auth.service.impl.AuthServiceImpl;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 认证服务单元测试。
 *
 * @author mytools
 * @since 2026-04-22
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(userMapper, roleMapper, userRoleMapper, jwtUtils, snowflakeIdGenerator);
    }

    /**
     * 测试注册成功。
     */
    @Test
    @DisplayName("注册成功")
    void register_Success() {
        // 准备测试数据
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setPassword("Test1234");
        request.setEmail("test@example.com");
        request.setPhone("13800138000");

        // Mock行为
        when(userMapper.existsByUsername("testuser")).thenReturn(0);
        when(userMapper.existsByEmail("test@example.com")).thenReturn(0);
        when(snowflakeIdGenerator.nextId()).thenReturn(100L);
        when(roleMapper.findDefaultUserRole()).thenReturn(new Role(2L, "USER", "普通用户", null));
        when(jwtUtils.generateAccessToken(any(), anyString(), anyString())).thenReturn("mock-token");
        when(jwtUtils.getExpirationMs()).thenReturn(900000L);

        // 执行测试
        RegisterResponse response = authService.register(request);

        // 验证结果
        assertNotNull(response);
        assertEquals(100L, response.getUserId());
        assertEquals("testuser", response.getUsername());
        assertEquals("mock-token", response.getToken());
        assertEquals(900L, response.getExpiresIn());

        // 验证调用
        verify(userMapper).insert(any(User.class));
        verify(userRoleMapper).insert(any(UserRole.class));
    }

    /**
     * 测试用户名已存在时注册失败。
     */
    @Test
    @DisplayName("用户名已存在，注册失败")
    void register_UsernameExists_ThrowsException() {
        // 准备测试数据
        RegisterRequest request = new RegisterRequest();
        request.setUsername("existinguser");
        request.setPassword("Test1234");
        request.setEmail("test@example.com");

        // Mock行为：用户名已存在
        when(userMapper.existsByUsername("existinguser")).thenReturn(1);

        // 执行测试并验证异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.register(request);
        });

        assertEquals(ErrorCode.USER_002.getCode(), exception.getErrorCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    /**
     * 测试邮箱已被使用时注册失败。
     */
    @Test
    @DisplayName("邮箱已被使用，注册失败")
    void register_EmailExists_ThrowsException() {
        // 准备测试数据
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("Test1234");
        request.setEmail("existing@example.com");

        // Mock行为：用户名不存在但邮箱已存在
        when(userMapper.existsByUsername("newuser")).thenReturn(0);
        when(userMapper.existsByEmail("existing@example.com")).thenReturn(1);

        // 执行测试并验证异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.register(request);
        });

        assertEquals(ErrorCode.USER_004.getCode(), exception.getErrorCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    /**
     * 测试登录成功。
     */
    @Test
    @DisplayName("登录成功")
    void login_Success() {
        // 准备测试数据
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("Test1234");

        User user = new User();
        user.setId(100L);
        user.setUsername("testuser");
        user.setPassword(PasswordUtils.encode("Test1234"));
        user.setRole("USER");
        user.setStatus("ACTIVE");

        // Mock行为
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(jwtUtils.generateAccessToken(any(), anyString(), anyString())).thenReturn("mock-token");
        when(jwtUtils.getExpirationMs()).thenReturn(900000L);

        // 执行测试
        LoginResponse response = authService.login(request);

        // 验证结果
        assertNotNull(response);
        assertEquals(100L, response.getUserId());
        assertEquals("testuser", response.getUsername());
        assertEquals("USER", response.getRole());
        assertEquals("mock-token", response.getToken());
        assertEquals(900L, response.getExpiresIn());

        verify(userMapper).updateLastLoginTime(eq(100L), any());
    }

    /**
     * 测试用户不存在时登录失败。
     */
    @Test
    @DisplayName("用户不存在，登录失败")
    void login_UserNotFound_ThrowsException() {
        // 准备测试数据
        LoginRequest request = new LoginRequest();
        request.setAccount("nonexistent");
        request.setPassword("Test1234");

        // Mock行为：用户不存在
        when(userMapper.findByUsername("nonexistent")).thenReturn(null);
        when(userMapper.findByEmail("nonexistent")).thenReturn(null);

        // 执行测试并验证异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.login(request);
        });

        assertEquals(ErrorCode.USER_005.getCode(), exception.getErrorCode());
    }

    /**
     * 测试密码错误时登录失败。
     */
    @Test
    @DisplayName("密码错误，登录失败")
    void login_WrongPassword_ThrowsException() {
        // 准备测试数据
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("WrongPassword123");

        User user = new User();
        user.setId(100L);
        user.setUsername("testuser");
        user.setPassword("$2a$10$hashedpassword");
        user.setRole("USER");
        user.setStatus("ACTIVE");

        // Mock行为：用户存在但密码不匹配（BCrypt验证会失败）
        when(userMapper.findByUsername("testuser")).thenReturn(user);

        // 执行测试并验证异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.login(request);
        });

        assertEquals(ErrorCode.USER_005.getCode(), exception.getErrorCode());
    }

    /**
     * 测试账户被禁用时登录失败。
     */
    @Test
    @DisplayName("账户已禁用，登录失败")
    void login_AccountDisabled_ThrowsException() {
        // 准备测试数据
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("Test1234");

        User user = new User();
        user.setId(100L);
        user.setUsername("testuser");
        user.setPassword(PasswordUtils.encode("Test1234"));
        user.setRole("USER");
        user.setStatus("DISABLED");

        // Mock行为
        when(userMapper.findByUsername("testuser")).thenReturn(user);

        // 执行测试并验证异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.login(request);
        });

        assertEquals(ErrorCode.USER_006.getCode(), exception.getErrorCode());
    }

    /**
     * 测试令牌刷新成功。
     */
    @Test
    @DisplayName("令牌刷新成功")
    void refreshToken_Success() {
        String oldToken = "Bearer old-token";

        // Mock行为
        when(jwtUtils.validateToken("old-token")).thenReturn(true);
        when(jwtUtils.isTokenExpired("old-token")).thenReturn(false);
        when(jwtUtils.getUserIdFromToken("old-token")).thenReturn(100L);
        when(jwtUtils.getUsernameFromToken("old-token")).thenReturn("testuser");
        when(jwtUtils.getRoleFromToken("old-token")).thenReturn("USER");
        when(jwtUtils.generateAccessToken(any(), anyString(), anyString())).thenReturn("new-token");
        when(jwtUtils.getExpirationMs()).thenReturn(900000L);

        // 执行测试
        RefreshResponse response = authService.refreshToken(oldToken);

        // 验证结果
        assertNotNull(response);
        assertEquals("new-token", response.getToken());
        assertEquals(900L, response.getExpiresIn());
    }

    /**
     * 测试无效令牌刷新失败。
     */
    @Test
    @DisplayName("无效令牌，刷新失败")
    void refreshToken_InvalidToken_ThrowsException() {
        String invalidToken = "Bearer invalid-token";

        // Mock行为
        when(jwtUtils.validateToken("invalid-token")).thenReturn(false);

        // 执行测试并验证异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.refreshToken(invalidToken);
        });

        assertEquals(ErrorCode.AUTH_002.getCode(), exception.getErrorCode());
    }

    /**
     * 测试过期令牌刷新失败。
     */
    @Test
    @DisplayName("过期令牌，刷新失败")
    void refreshToken_ExpiredToken_ThrowsException() {
        String expiredToken = "Bearer expired-token";

        // Mock行为
        when(jwtUtils.validateToken("expired-token")).thenReturn(true);
        when(jwtUtils.isTokenExpired("expired-token")).thenReturn(true);

        // 执行测试并验证异常
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            authService.refreshToken(expiredToken);
        });

        assertEquals(ErrorCode.AUTH_001.getCode(), exception.getErrorCode());
    }
}