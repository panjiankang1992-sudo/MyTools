package com.mytools.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证与RBAC集成测试。
 *
 * @author mytools
 * @since 2026-04-22
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 测试完整注册流程。
     */
    @Test
    @DisplayName("完整注册流程")
    void fullRegisterFlow() throws Exception {
        // 注册
        String registerJson = """
                {
                    "username": "integrationuser",
                    "password": "Test1234",
                    "email": "integration@example.com",
                    "phone": "13800138001"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    /**
     * 测试完整登录流程。
     */
    @Test
    @DisplayName("完整登录流程")
    void fullLoginFlow() throws Exception {
        // 先注册
        String registerJson = """
                {
                    "username": "loginuser",
                    "password": "Test1234",
                    "email": "login@example.com"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson))
                .andExpect(status().isCreated());

        // 登录
        String loginJson = """
                {
                    "account": "loginuser",
                    "password": "Test1234"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    /**
     * 测试无token访问受保护资源返回401。
     */
    @Test
    @DisplayName("无token访问受保护资源返回401")
    void accessProtected_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(get("/api/user/info"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 测试无效token访问返回401。
     */
    @Test
    @DisplayName("无效token访问返回401")
    void accessProtected_WithInvalidToken_Returns401() throws Exception {
        mockMvc.perform(get("/api/user/info")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 测试正常token访问返回200。
     */
    @Test
    @DisplayName("正常token访问返回200")
    void accessProtected_WithValidToken_Returns200() throws Exception {
        // 先注册获取token
        String registerJson = """
                {
                    "username": "protecteduser",
                    "password": "Test1234",
                    "email": "protected@example.com"
                }
                """;

        var registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson))
                .andExpect(status().isCreated())
                .andReturn();

        String token = com.jayway.jsonpath.JsonPath
                .read(registerResult.getResponse().getContentAsString(), "$.data.token");

        // 使用token访问
        mockMvc.perform(get("/api/user/info")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}