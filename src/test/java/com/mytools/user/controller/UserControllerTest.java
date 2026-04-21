package com.mytools.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytools.auth.mapper.TokenMapper;
import com.mytools.common.BusinessException;
import com.mytools.common.ErrorCode;
import com.mytools.user.Model.*;
import com.mytools.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用户控制器单元测试。
 *
 * @author mytools
 * @since 2026-04-22
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private com.mytools.auth.utils.JwtUtils jwtUtils;

    @MockBean
    private TokenMapper tokenMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 测试获取用户信息返回200。
     */
    @Test
    @WithMockUser(username = "100")
    @DisplayName("获取用户信息返回200")
    void getUserInfo_Returns200() throws Exception {
        UserInfoResponse response = new UserInfoResponse(
                100L, "testuser", "test@example.com", "13800138000",
                "USER", "ACTIVE", LocalDateTime.now(), LocalDateTime.now());

        when(jwtUtils.getUserIdFromToken(any())).thenReturn(100L);
        when(userService.getUserInfo(100L)).thenReturn(response);

        mockMvc.perform(get("/api/user/info")
                        .header("Authorization", "Bearer mock-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(100))
                .andExpect(jsonPath("$.data.username").value("testuser"));
    }

    /**
     * 测试更新用户信息返回200。
     */
    @Test
    @WithMockUser(username = "100")
    @DisplayName("更新用户信息返回200")
    void updateUserInfo_Returns200() throws Exception {
        UpdateUserInfoRequest request = new UpdateUserInfoRequest();
        request.setEmail("new@example.com");

        UserInfoResponse response = new UserInfoResponse(
                100L, "testuser", "new@example.com", "13800138000",
                "USER", "ACTIVE", LocalDateTime.now(), LocalDateTime.now());

        when(jwtUtils.getUserIdFromToken(any())).thenReturn(100L);
        when(userService.updateUserInfo(eq(100L), any(UpdateUserInfoRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/user/info")
                        .with(csrf())
                        .header("Authorization", "Bearer mock-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.email").value("new@example.com"));
    }

    /**
     * 测试修改密码返回200。
     */
    @Test
    @WithMockUser(username = "100")
    @DisplayName("修改密码返回200")
    void changePassword_Returns200() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("OldPass123");
        request.setNewPassword("NewPass456");

        when(jwtUtils.getUserIdFromToken(any())).thenReturn(100L);

        mockMvc.perform(put("/api/user/password")
                        .with(csrf())
                        .header("Authorization", "Bearer mock-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("密码修改成功"));
    }

    /**
     * 测试管理员禁用用户返回200。
     */
    @Test
    @WithMockUser(username = "200", roles = {"ADMIN"})
    @DisplayName("管理员禁用用户返回200")
    void disableUser_Returns200() throws Exception {
        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("DISABLED");

        UserStatusResponse response = new UserStatusResponse(100L, "DISABLED");

        when(jwtUtils.getUserIdFromToken(any())).thenReturn(200L);
        when(userService.updateUserStatus(eq(100L), eq("DISABLED"), eq(200L))).thenReturn(response);

        mockMvc.perform(put("/api/user/100/status")
                        .with(csrf())
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(100))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    /**
     * 测试非管理员无权禁用用户返回403。
     */
    @Test
    @WithMockUser(username = "100", roles = {"USER"})
    @DisplayName("非管理员禁用用户返回403")
    void disableUser_NonAdmin_Returns403() throws Exception {
        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("DISABLED");

        when(jwtUtils.getUserIdFromToken(any())).thenReturn(100L);
        when(userService.updateUserStatus(eq(100L), eq("DISABLED"), eq(100L)))
                .thenThrow(new BusinessException(ErrorCode.AUTH_003));

        mockMvc.perform(put("/api/user/100/status")
                        .with(csrf())
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    /**
     * 测试管理员删除用户返回200。
     */
    @Test
    @WithMockUser(username = "200", roles = {"ADMIN"})
    @DisplayName("管理员删除用户返回200")
    void deleteUser_Returns200() throws Exception {
        when(jwtUtils.getUserIdFromToken(any())).thenReturn(200L);

        mockMvc.perform(delete("/api/user/100")
                        .with(csrf())
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("删除成功"));
    }

    /**
     * 测试删除不存在的用户返回404。
     */
    @Test
    @WithMockUser(username = "200", roles = {"ADMIN"})
    @DisplayName("删除不存在的用户返回404")
    void deleteUser_NotFound_Returns404() throws Exception {
        when(jwtUtils.getUserIdFromToken(any())).thenReturn(200L);
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.USER_001))
                .when(userService).deleteUser(eq(999L), eq(200L));

        mockMvc.perform(delete("/api/user/999")
                        .with(csrf())
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }
}