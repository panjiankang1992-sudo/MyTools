package com.yuyutian.mytools.auth;

import com.yuyutian.mytools.auth.controller.AuthController;
import com.yuyutian.mytools.auth.service.AuthService;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.GlobalExceptionHandler;
import com.yuyutian.mytools.openapi.controller.OpenApiController;
import com.yuyutian.mytools.user.mapper.UserMapper;
import com.yuyutian.mytools.user.service.UserService;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class AuthorizationHeaderExceptionTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private AuthService authService;

    @Mock
    private UserService userService;

    @Mock
    private WebdavAccountService webdavAccountService;

    @Mock
    private UserMapper userMapper;

    private MockMvc publicApiMvc;

    private MockMvc authMvc;

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(messageSource());
        publicApiMvc = standaloneSetup(new OpenApiController(jwtUtils, authService, userService, webdavAccountService))
                .setControllerAdvice(exceptionHandler)
                .build();
        authMvc = standaloneSetup(new AuthController(authService, userMapper))
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void getPublicProfileWithoutAuthorizationReturnsUnauthorized() throws Exception {
        publicApiMvc.perform(get("/api/public/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("20002"));
    }

    @Test
    void getPublicProfileWithMalformedAuthorizationReturnsUnauthorized() throws Exception {
        publicApiMvc.perform(get("/api/public/profile").header("Authorization", "Basic token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("20002"));
    }

    @Test
    void refreshWithoutAuthorizationReturnsUnauthorized() throws Exception {
        authMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("20002"));
    }

    private StaticMessageSource messageSource() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("auth.token.invalid", Locale.ENGLISH, "Invalid token");
        messageSource.addMessage("sys.validation.failed", Locale.ENGLISH, "Parameter validation failed");
        return messageSource;
    }
}
