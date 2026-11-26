package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.ConnectivityService;
import com.yuyutian.mytools.gateway.service.PrincipalValidator;
import com.yuyutian.mytools.gateway.web.GatewayEnvelopeAdvice;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证公网签发与局域网挑战使用同一个网关及其认证边界。 */
class ConnectivityControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrincipalValidator validator = mock(PrincipalValidator.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(validator.validate("test-access")).thenReturn(new GatewayPrincipal(
                7L, "reader", List.of("USER"), UUID.randomUUID()));
        mvc = MockMvcBuilders.standaloneSetup(new ConnectivityController(
                        new ConnectivityService("shared-gateway", "v1")))
                .setControllerAdvice(new GatewayEnvelopeAdvice(), new GatewayExceptionHandler())
                .addFilters(new GatewayRequestFilter(validator, mock(GatewayProperties.class)))
                .build();
    }

    @Test
    void shouldRequireAuthenticationForBootstrapOnEitherOrigin() throws Exception {
        mvc.perform(get("https://public.example/api/app/v1/connectivity/bootstrap"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("http://192.168.8.9:23201/api/app/v1/connectivity/bootstrap"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldVerifyLanChallengeUsingPublicBootstrapWithoutForwardingBearer() throws Exception {
        String body = mvc.perform(get("https://public.example/api/app/v1/connectivity/bootstrap")
                        .header("Authorization", "Bearer test-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lanPort").value(23201))
                .andExpect(jsonPath("$.data.serviceType").value("_mytools-gateway._tcp.local"))
                .andReturn().getResponse().getContentAsString();
        JsonNode bootstrap = mapper.readTree(body).path("data");
        String nonce = "abcdefghijklmnop";
        String proofBody = mvc.perform(post("http://192.168.8.9:23201/api/public/connectivity/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode()
                                .put("probeId", bootstrap.path("probeId").asText())
                                .put("nonce", nonce).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceId").value("shared-gateway"))
                .andExpect(jsonPath("$.data.nonce").value(nonce))
                .andReturn().getResponse().getContentAsString();
        String message = "shared-gateway\n" + nonce + "\nv1\n" + bootstrap.path("expiresAt").asLong();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(
                bootstrap.path("proofKey").asText()), "HmacSHA256"));

        assertThat(mapper.readTree(proofBody).path("data").path("proof").asText())
                .isEqualTo(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))));
        // 挑战接口只使用短期证明材料，不再次读取或转发用户令牌。
        verify(validator).validate("test-access");
    }

    @Test
    void shouldRejectUnknownAndMalformedChallenge() throws Exception {
        mvc.perform(post("/api/public/connectivity/challenge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"probeId\":\"" + "0".repeat(32) + "\",\"nonce\":\"abcdefghijklmnop\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/connectivity/challenge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"probeId\":\"invalid\",\"nonce\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }
}
