package com.mytools.user.service;

import com.mytools.auth.mapper.TokenMapper;
import com.mytools.common.BusinessException;
import com.mytools.common.ErrorCode;
import com.mytools.user.Model.*;
import com.mytools.user.mapper.UserMapper;
import com.mytools.user.mapper.UserRoleMapper;
import com.mytools.user.service.impl.UserServiceImpl;
import com.mytools.utils.PasswordUtils;
import com.mytools.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 用户服务单元测试。
 *
 * @author mytools
 * @since 2026-04-22
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private TokenMapper tokenMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, tokenMapper, userRoleMapper, snowflakeIdGenerator);
    }

    /**
     * 测试获取用户信息成功。
     */
    @Test
    @DisplayName("获取用户信息成功")
    void getUserInfo_Success() {
        // 准备测试数据
        Long userId = 100L;
        User user = new User();
        user.setId(userId);
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPhone("13800138000");
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setRegisterTime(LocalDateTime.now());
        user.setLastLoginTime(LocalDateTime.now());

        when(userMapper.findById(userId)).thenReturn(user);

        // 执行测试
        UserInfoResponse response = userService.getUserInfo(userId);

        // 验证结果
        assertNotNull(response);
        assertEquals(userId, response.getUserId());
        assertEquals("testuser", response.getUsername());
        assertEquals("test@example.com", response.getEmail());
        assertEquals("USER", response.getRole());
        assertEquals("ACTIVE", response.getStatus());
    }

    /**
     * 测试用户不存在时获取信息失败。
     */
    @Test
    @DisplayName("用户不存在，获取信息失败")
    void getUserInfo_UserNotFound_ThrowsException() {
        when(userMapper.findById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            userService.getUserInfo(999L);
        });

        assertEquals(ErrorCode.USER_001.getCode(), exception.getErrorCode());
    }

    /**
     * 测试更新用户信息成功。
     */
    @Test
    @DisplayName("更新用户信息成功")
    void updateUserInfo_Success() {
        Long userId = 100L;
        User user = new User();
        user.setId(userId);
        user.setUsername("testuser");
        user.setEmail("old@example.com");
        user.setPhone("13800138000");
        user.setRole("USER");
        user.setStatus("ACTIVE");

        UpdateUserInfoRequest request = new UpdateUserInfoRequest();
        request.setEmail("new@example.com");

        when(userMapper.findById(userId)).thenReturn(user);
        when(userMapper.findByEmail("new@example.com")).thenReturn(null);
        when(userMapper.updateEmail(eq(userId), eq("new@example.com"))).thenReturn(1);

        // 执行测试
        UserInfoResponse response = userService.updateUserInfo(userId, request);

        // 验证结果
        assertNotNull(response);
        verify(userMapper).updateEmail(userId, "new@example.com");
    }

    /**
     * 测试更新邮箱时邮箱已被使用。
     */
    @Test
    @DisplayName("更新邮箱已被使用")
    void updateUserInfo_EmailExists_ThrowsException() {
        Long userId = 100L;
        User user = new User();
        user.setId(userId);
        user.setUsername("testuser");
        user.setEmail("old@example.com");
        user.setRole("USER");
        user.setStatus("ACTIVE");

        User existingUser = new User();
        existingUser.setId(200L); // 不同用户

        UpdateUserInfoRequest request = new UpdateUserInfoRequest();
        request.setEmail("existing@example.com");

        when(userMapper.findById(userId)).thenReturn(user);
        when(userMapper.findByEmail("existing@example.com")).thenReturn(existingUser);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            userService.updateUserInfo(userId, request);
        });

        assertEquals(ErrorCode.USER_007.getCode(), exception.getErrorCode());
    }

    /**
     * 测试修改密码成功。
     */
    @Test
    @DisplayName("修改密码成功")
    void changePassword_Success() {
        Long userId = 100L;
        String oldPassword = "OldPass123";
        String newPassword = "NewPass456";

        // BCrypt hash of "OldPass123"
        String encodedOldPassword = PasswordUtils.encode("OldPass123");

        User user = new User();
        user.setId(userId);
        user.setPassword(encodedOldPassword);

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);

        when(userMapper.findById(userId)).thenReturn(user);
        when(userMapper.updatePassword(eq(userId), any(String.class))).thenReturn(1);

        // 执行测试
        assertDoesNotThrow(() -> userService.changePassword(userId, request));

        verify(userMapper).updatePassword(eq(userId), any(String.class));
    }

    /**
     * 测试旧密码错误。
     */
    @Test
    @DisplayName("旧密码错误")
    void changePassword_WrongOldPassword_ThrowsException() {
        Long userId = 100L;
        User user = new User();
        user.setId(userId);
        user.setPassword("$2a$10$dummyhash");

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("WrongPassword");
        request.setNewPassword("NewPass123");

        when(userMapper.findById(userId)).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            userService.changePassword(userId, request);
        });

        assertEquals(ErrorCode.USER_008.getCode(), exception.getErrorCode());
    }

    /**
     * 测试管理员禁用用户成功。
     */
    @Test
    @DisplayName("管理员禁用用户成功")
    void updateUserStatus_Disable_Success() {
        Long targetUserId = 100L;
        Long adminUserId = 200L;

        User targetUser = new User();
        targetUser.setId(targetUserId);
        targetUser.setStatus("ACTIVE");

        User adminUser = new User();
        adminUser.setId(adminUserId);
        adminUser.setRole("ADMIN");

        when(userMapper.findById(targetUserId)).thenReturn(targetUser);
        when(userMapper.findById(adminUserId)).thenReturn(adminUser);
        when(userMapper.updateStatus(targetUserId, "DISABLED")).thenReturn(1);

        // 执行测试
        UserStatusResponse response = userService.updateUserStatus(targetUserId, "DISABLED", adminUserId);

        // 验证结果
        assertNotNull(response);
        assertEquals(targetUserId, response.getUserId());
        assertEquals("DISABLED", response.getStatus());
    }

    /**
     * 测试非管理员无权禁用用户。
     */
    @Test
    @DisplayName("非管理员无权禁用用户")
    void updateUserStatus_NonAdmin_ThrowsException() {
        Long targetUserId = 100L;
        Long nonAdminUserId = 300L;

        User targetUser = new User();
        targetUser.setId(targetUserId);

        User nonAdminUser = new User();
        nonAdminUser.setId(nonAdminUserId);
        nonAdminUser.setRole("USER");

        when(userMapper.findById(targetUserId)).thenReturn(targetUser);
        when(userMapper.findById(nonAdminUserId)).thenReturn(nonAdminUser);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            userService.updateUserStatus(targetUserId, "DISABLED", nonAdminUserId);
        });

        assertEquals(ErrorCode.AUTH_003.getCode(), exception.getErrorCode());
    }

    /**
     * 测试删除用户成功。
     */
    @Test
    @DisplayName("管理员删除用户成功")
    void deleteUser_Success() {
        Long targetUserId = 100L;
        Long adminUserId = 200L;

        User targetUser = new User();
        targetUser.setId(targetUserId);

        User adminUser = new User();
        adminUser.setId(adminUserId);
        adminUser.setRole("ADMIN");

        when(userMapper.findById(targetUserId)).thenReturn(targetUser);
        when(userMapper.findById(adminUserId)).thenReturn(adminUser);
        when(tokenMapper.deleteByUserId(targetUserId)).thenReturn(1);
        when(userRoleMapper.deleteByUserId(targetUserId)).thenReturn(1);
        when(userMapper.deleteById(targetUserId)).thenReturn(1);

        // 执行测试
        assertDoesNotThrow(() -> userService.deleteUser(targetUserId, adminUserId));

        verify(tokenMapper).deleteByUserId(targetUserId);
        verify(userRoleMapper).deleteByUserId(targetUserId);
        verify(userMapper).deleteById(targetUserId);
    }

    /**
     * 测试删除不存在的用户。
     */
    @Test
    @DisplayName("删除不存在的用户")
    void deleteUser_UserNotFound_ThrowsException() {
        when(userMapper.findById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            userService.deleteUser(999L, 200L);
        });

        assertEquals(ErrorCode.USER_001.getCode(), exception.getErrorCode());
    }
}