package com.yuyutian.mytools.copilot.controller;

import com.yuyutian.mytools.auth.filter.JwtAuthenticationFilter;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.apilog.service.ApiLogService;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.GlobalExceptionHandler;
import com.yuyutian.mytools.config.SecurityConfig;
import com.yuyutian.mytools.copilot.model.CopilotGatewayInfo;
import com.yuyutian.mytools.copilot.model.CopilotGatewayStream;
import com.yuyutian.mytools.copilot.service.CopilotGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Copilot移动网关的认证、错误码与SSE控制器契约测试。
 */
@WebMvcTest(CopilotGatewayController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SecurityConfig.class, GlobalExceptionHandler.class,
        CopilotGatewayControllerContractTest.FilterConfiguration.class})
class CopilotGatewayControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CopilotGatewayService gatewayService;

    @MockBean
    private ApiLogService apiLogService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private TokenMapper tokenMapper;

    @Test
    void shouldRequireAuthenticationForConfigAndChat() throws Exception {
        mockMvc.perform(get("/api/app/v1/copilot/config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()));
        mockMvc.perform(post("/api/app/v1/copilot/chat")
                        .contentType("application/json")
                        .content("{\"model\":\"test\",\"stream\":true,\"messages\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()));
    }

    @Test
    @WithMockUser
    void shouldExposeOnlyMinimalPublicConfig() throws Exception {
        when(gatewayService.getInfo()).thenReturn(new CopilotGatewayInfo(true, "test-model"));

        mockMvc.perform(get("/api/app/v1/copilot/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.model").value("test-model"))
                .andExpect(jsonPath("$.data.providerUrl").doesNotExist())
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    @Test
    @WithMockUser
    void shouldPreserveDisabledAndInvalidRequestCodes() throws Exception {
        doThrow(new BusinessException(ErrorCode.COPILOT_001))
                .when(gatewayService).openStream(any());

        mockMvc.perform(post("/api/app/v1/copilot/chat")
                        .contentType("application/json")
                        .content("{\"model\":\"test\",\"stream\":true,\"messages\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.COPILOT_001.getCode()));

        doThrow(new BusinessException(ErrorCode.COPILOT_002))
                .when(gatewayService).openStream(any());
        mockMvc.perform(post("/api/app/v1/copilot/chat")
                        .contentType("application/json")
                        .content("{\"model\":\"test\",\"stream\":false,\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.COPILOT_002.getCode()));
    }

    @Test
    @WithMockUser
    void shouldStreamSseWithNoBufferingAndCloseUpstream() throws Exception {
        TrackingInputStream input = new TrackingInputStream(
                "data: {\"choices\":[]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        when(gatewayService.openStream(any())).thenReturn(
                new CopilotGatewayStream(200, "text/event-stream;charset=UTF-8", input));

        MvcResult initial = mockMvc.perform(post("/api/app/v1/copilot/chat")
                        .contentType("application/json")
                        .content("{\"model\":\"test\",\"stream\":true,\"messages\":[]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().string("data: {\"choices\":[]}\n\ndata: [DONE]\n\n"));
        assertTrue(input.closed, "上游SSE输入流必须在响应结束后关闭");
    }

    @TestConfiguration
    static class FilterConfiguration {

        /**
         * 创建使用空依赖的真实JWT过滤器，让测试请求按生产过滤顺序通过。
         *
         * @return JWT认证过滤器。
         */
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtils jwtUtils, TokenMapper tokenMapper) {
            return new JwtAuthenticationFilter(jwtUtils, tokenMapper);
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
