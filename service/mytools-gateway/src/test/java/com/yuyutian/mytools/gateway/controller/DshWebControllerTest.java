package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.DshWebSessionService;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.PrincipalValidator;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import com.yuyutian.mytools.gateway.web.GatewayEnvelopeAdvice;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

class DshWebControllerTest {
    @Test void successfulTicketSerializesInsideTheAppEnvelope() throws Exception {
        DshWebSessionService service = mock(DshWebSessionService.class);
        GatewayPrincipal owner = new GatewayPrincipal(12, "yuyutian", List.of(), null);
        String ticket = "a".repeat(43);
        when(service.issue(owner, "owner-access")).thenReturn(ticket);
        var mvc = MockMvcBuilders.standaloneSetup(new DshWebController(service))
                .setControllerAdvice(new GatewayEnvelopeAdvice()).build();
        mvc.perform(post("/api/app/v1/dsh/web-ticket")
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, owner)
                        .header("Authorization", "Bearer owner-access"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.url").value("https://dsh-app.yuyutian.top/_mytools/connect?ticket=" + ticket));
    }
    @Test void ticketRequiresAuthenticationEvenWhenOtherDshRoutesDisabled() throws Exception {
        DshWebSessionService service = mock(DshWebSessionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DshWebController(service))
                .addFilters(new GatewayRequestFilter(mock(PrincipalValidator.class), mock(GatewayProperties.class)))
                .build();
        mvc.perform(post("/api/app/v1/dsh/web-ticket")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void redirectHasSecureHostCookieAndNeverWrapsOrExposesSessionInBody() throws Exception {
        DshWebSessionService service = mock(DshWebSessionService.class);
        when(service.redeem("ticket")).thenReturn("opaque-session");
        var mvc = MockMvcBuilders.standaloneSetup(new DshWebController(service))
                .setControllerAdvice(new GatewayEnvelopeAdvice()).build();
        mvc.perform(get("/internal/dsh-web/connect").param("ticket", "ticket"))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(cookie().httpOnly("__Host-mytools-dsh", true))
                .andExpect(cookie().secure("__Host-mytools-dsh", true))
                .andExpect(content().string(""));
    }

    @Test void lanRedirectUsesAnIsolatedStrictCookieWithoutChangingPublicCookiePolicy() throws Exception {
        DshWebSessionService service = mock(DshWebSessionService.class);
        when(service.redeemLan("proxy-key", "ticket")).thenReturn("lan-session");
        var mvc = MockMvcBuilders.standaloneSetup(new DshWebController(service))
                .setControllerAdvice(new GatewayEnvelopeAdvice()).build();

        mvc.perform(get("/internal/dsh-web/lan/connect").param("ticket", "ticket")
                        .header("X-Dsh-Proxy-Key", "proxy-key"))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(cookie().value("mytools-dsh-lan", "lan-session"))
                .andExpect(cookie().httpOnly("mytools-dsh-lan", true))
                .andExpect(cookie().secure("mytools-dsh-lan", false))
                .andExpect(cookie().path("mytools-dsh-lan", "/"))
                .andExpect(cookie().maxAge("mytools-dsh-lan", 1800))
                .andExpect(cookie().doesNotExist("__Host-mytools-dsh"))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", not(containsString("Domain="))))
                .andExpect(content().string(""));
        verify(service).redeemLan("proxy-key", "ticket");
        verify(service, never()).redeem(anyString());
    }

    @Test void lanAuthorizerOnlyPassesTheLanCookieAndKeepsCredentialsOutOfTheBody() throws Exception {
        DshWebSessionService service = mock(DshWebSessionService.class);
        when(service.authorizeLan("proxy-key", "lan-session")).thenReturn("upstream-cookie");
        var mvc = MockMvcBuilders.standaloneSetup(new DshWebController(service))
                .setControllerAdvice(new GatewayEnvelopeAdvice()).build();

        mvc.perform(get("/internal/dsh-web/lan/authorize").header("X-Dsh-Proxy-Key", "proxy-key")
                        .cookie(new Cookie("mytools-dsh-lan", "lan-session"),
                                new Cookie("__Host-mytools-dsh", "public-session")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Dsh-Upstream-Cookie", "upstream-cookie"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(""));
        verify(service).authorizeLan("proxy-key", "lan-session");
        verify(service, never()).authorize(any(), any());
    }

    @Test void lanEndpointsRejectRequestsWithoutTheInternalProxyKey() throws Exception {
        DshWebSessionService service = mock(DshWebSessionService.class);
        when(service.redeemLan(null, "ticket")).thenThrow(new GatewayUnauthorizedException());
        when(service.authorizeLan(null, "lan-session")).thenThrow(new GatewayUnauthorizedException());
        var mvc = MockMvcBuilders.standaloneSetup(new DshWebController(service))
                .setControllerAdvice(new GatewayEnvelopeAdvice(), new GatewayExceptionHandler()).build();

        mvc.perform(get("/internal/dsh-web/lan/connect").param("ticket", "ticket"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/internal/dsh-web/lan/authorize").cookie(new Cookie("mytools-dsh-lan", "lan-session")))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Dsh-Upstream-Cookie"));
    }
}
