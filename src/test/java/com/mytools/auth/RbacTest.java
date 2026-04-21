package com.mytools.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RBAC权限测试。
 *
 * @author mytools
 * @since 2026-04-22
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RbacTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 测试USER角色无法访问管理员接口返回403。
     */
    @Test
    @WithMockUser(username = "100", roles = {"USER"})
    @DisplayName("USER角色无法访问管理员接口返回403")
    void userCannotAccessAdminEndpoint_Returns403() throws Exception {
        mockMvc.perform(get("/api/user/999/status")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }

    /**
     * 测试ADMIN角色可以访问管理员接口返回200。
     */
    @Test
    @WithMockUser(username = "200", roles = {"ADMIN"})
    @DisplayName("ADMIN角色可以访问管理员接口返回200")
    void adminCanAccessAdminEndpoint_Returns200() throws Exception {
        mockMvc.perform(get("/api/user/100/status")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    /**
     * 测试无token访问管理员接口返回401。
     */
    @Test
    @DisplayName("无token访问管理员接口返回401")
    void noTokenAccessAdmin_Returns401() throws Exception {
        mockMvc.perform(get("/api/user/100/status"))
                .andExpect(status().isUnauthorized());
    }
}