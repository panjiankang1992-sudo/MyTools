package com.mytools.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytools.auth.Model.*;
import com.mytools.auth.mapper.TokenMapper;
import com.mytools.auth.service.AuthService;
import com.mytools.common.BusinessException;
import com.mytools.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证控制器单元测试。
 *
 * @author mytools
 * @since 2026-04-22
 */
@WebMvcTest(controllers = AuthController.class,
        excludeAutoConfiguration = {org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class})
class AuthControllerTest {

    @MockBean
    private TokenMapper tokenMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 测试注册接口返回201。
     */
    @Test
    @DisplayName("注册接口返回201")
    void register_Returns201() throws Exception {
        // 准备测试数据
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setPassword("Test1234");
        request.setEmail("test@example.com");
        request.setPhone("13800138000");

        RegisterResponse response = new RegisterResponse(100L, "testuser", "mock-token", 900L);

        // Mock行为
        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        // 执行测试
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.message").value("注册成功"))
                .andExpect(jsonPath("$.data.userId").value(100))
                .andExpect(jsonPath("$.data.username").value("testuser"))
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    /**
     * 测试登录接口返回200。
     */
    @Test
    @WithMockUser
    @DisplayName("登录接口返回200")
    void login_Returns200() throws Exception {
        // 准备测试数据
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("Test1234");

        LoginResponse response = new LoginResponse(100L, "testuser", "USER", "mock-token", 900L);

        // Mock行为
        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        // 执行测试
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("登录成功"))
                .andExpect(jsonPath("$.data.userId").value(100))
                .andExpect(jsonPath("$.data.username").value("testuser"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.token").value("mock-token"));
    }

    /**
     * 测试刷新令牌接口返回200。
     */
    @Test
    @WithMockUser
    @DisplayName("刷新令牌接口返回200")
    void refresh_Returns200() throws Exception {
        // 准备测试数据
        RefreshResponse response = new RefreshResponse("new-token", 900L);

        // Mock行为
        when(authService.refreshToken("Bearer old-token")).thenReturn(response);

        // 执行测试
        mockMvc.perform(post("/api/auth/refresh")
                        .with(csrf())
                        .header("Authorization", "Bearer old-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("刷新成功"))
                .andExpect(jsonPath("$.data.token").value("new-token"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    /**
     * 测试注册时用户已存在返回409。
     */
    @Test
    @DisplayName("注册时用户已存在返回409")
    void register_UsernameExists_Returns409() throws Exception {
        // 准备测试数据
        RegisterRequest request = new RegisterRequest();
        request.setUsername("existinguser");
        request.setPassword("Test1234");
        request.setEmail("test@example.com");

        // Mock行为：抛出用户名已存在异常
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.USER_002));

        // 执行测试
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /**
     * 测试登录凭据错误返回401。
     */
    @Test
    @WithMockUser
    @DisplayName("登录凭据错误返回401")
    void login_WrongCredentials_Returns401() throws Exception {
        // 准备测试数据
        LoginRequest request = new LoginRequest();
        request.setAccount("testuser");
        request.setPassword("WrongPassword");

        // Mock行为：抛出认证失败异常
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.USER_005));

        // 执行测试
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40005));
    }
}