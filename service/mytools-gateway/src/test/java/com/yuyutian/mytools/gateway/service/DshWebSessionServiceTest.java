package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.DshWebProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshWebSessionServiceTest {
    @TempDir Path directory;
    private final PrincipalValidator validator = mock(PrincipalValidator.class);
    private final GatewayPrincipal owner = new GatewayPrincipal(12, "yuyutian", List.of(), UUID.randomUUID());
    private DshWebProperties properties() throws Exception {
        DshWebProperties p = new DshWebProperties();
        p.setEnabled(true); p.setAllowedUsernames(Set.of("yuyutian")); p.setProxySecret("p".repeat(43));
        Path file = directory.resolve("upstream-cookie");
        Files.writeString(file, "dsh-auth-" + "a".repeat(43) + "=v1.abc." + "b".repeat(43));
        p.setCookieFile(file.toString());
        doReturn(owner).when(validator).validate("owner-access");
        return p;
    }
    @Test void exchangesOnceAndRequiresBothProxyAndLiveIdentity() throws Exception {
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        String ticket = service.issue(owner, "owner-access");
        String session = service.redeem(ticket);
        assertThrows(GatewayUnauthorizedException.class, () -> service.redeem(ticket));
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize(null, session));
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), "forged"));
        assertTrue(service.authorize("p".repeat(43), session).startsWith("dsh-auth-"));
        when(validator.validate("owner-access")).thenThrow(new GatewayUnauthorizedException());
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), session));
        doReturn(owner).when(validator).validate("owner-access");
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), session));
    }
    @Test void rejectsOtherAccountsDisabledAccessAndChangedIdentity() throws Exception {
        DshWebProperties p = properties();
        DshWebSessionService service = new DshWebSessionService(p, validator);
        GatewayPrincipal other = new GatewayPrincipal(13, "other", List.of(), UUID.randomUUID());
        assertThrows(DshWebForbiddenException.class, () -> service.issue(other, "other-access"));
        String ticket = service.issue(owner, "owner-access");
        when(validator.validate("owner-access")).thenReturn(new GatewayPrincipal(12, "yuyutian", List.of(), UUID.randomUUID()));
        assertThrows(GatewayUnauthorizedException.class, () -> service.redeem(ticket));
        p.setEnabled(false);
        assertThrows(GatewayRouteDisabledException.class, () -> service.issue(owner, "owner-access"));
    }
    @Test void expiresTicketAndSession() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        DshWebSessionService service = new DshWebSessionService(properties(), validator, clock);
        String expired = service.issue(owner, "owner-access");
        when(clock.millis()).thenReturn(31_001L);
        assertThrows(GatewayUnauthorizedException.class, () -> service.redeem(expired));
        String session = service.redeem(service.issue(owner, "owner-access"));
        when(clock.millis()).thenReturn(1_900_000L);
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), session));
    }
    @Test void legacyLoginWithoutSessionIdStillRequiresLiveToken() throws Exception {
        GatewayPrincipal legacy = new GatewayPrincipal(12, "yuyutian", List.of(), null);
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        doReturn(legacy).when(validator).validate("owner-access");
        String session = service.redeem(service.issue(legacy, "owner-access"));
        assertTrue(service.authorize("p".repeat(43), session).startsWith("dsh-auth-"));
        when(validator.validate("owner-access")).thenThrow(new GatewayUnauthorizedException());
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), session));
    }
    @Test void reconnectRevokesPreviousBrowserSession() throws Exception {
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        String previous = service.redeem(service.issue(owner, "owner-access"));
        String current = service.redeem(service.issue(owner, "owner-access"));
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), previous));
        assertTrue(service.authorize("p".repeat(43), current).startsWith("dsh-auth-"));
    }
    @Test void legacyReconnectRevokesOnlyTheSameLogin() throws Exception {
        GatewayPrincipal legacy = new GatewayPrincipal(12, "yuyutian", List.of(), null);
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        doReturn(legacy).when(validator).validate("owner-access");
        doReturn(legacy).when(validator).validate("second-device-access");
        String previous = service.redeem(service.issue(legacy, "owner-access"));
        String secondDevice = service.redeem(service.issue(legacy, "second-device-access"));
        String current = service.redeem(service.issue(legacy, "owner-access"));
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), previous));
        assertTrue(service.authorize("p".repeat(43), current).startsWith("dsh-auth-"));
        assertTrue(service.authorize("p".repeat(43), secondDevice).startsWith("dsh-auth-"));
    }
    @Test void legacyRepeatedTicketInvalidatesOnlyTheSameLoginTicket() throws Exception {
        GatewayPrincipal legacy = new GatewayPrincipal(12, "yuyutian", List.of(), null);
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        doReturn(legacy).when(validator).validate("owner-access");
        doReturn(legacy).when(validator).validate("second-device-access");
        String previous = service.issue(legacy, "owner-access");
        String secondDevice = service.issue(legacy, "second-device-access");
        String current = service.issue(legacy, "owner-access");
        assertThrows(GatewayUnauthorizedException.class, () -> service.redeem(previous));
        assertNotNull(service.redeem(current));
        assertNotNull(service.redeem(secondDevice));
    }
    @Test void doesNotReturnMalformedUpstreamHeader() throws Exception {
        DshWebProperties p = properties();
        DshWebSessionService service = new DshWebSessionService(p, validator);
        String session = service.redeem(service.issue(owner, "owner-access"));
        Files.writeString(Path.of(p.getCookieFile()), "cookie\r\nInjected: value");
        assertThrows(GatewayDownstreamException.class, () -> service.authorize("p".repeat(43), session));
    }

    @Test void lanRedemptionRequiresProxyBeforeConsumingTheTicket() throws Exception {
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        String ticket = service.issue(owner, "owner-access");

        assertThrows(GatewayUnauthorizedException.class, () -> service.redeemLan(null, ticket));
        assertThrows(GatewayUnauthorizedException.class, () -> service.redeemLan("wrong-key", ticket));
        String session = service.redeemLan("p".repeat(43), ticket);
        assertThrows(GatewayUnauthorizedException.class, () -> service.redeemLan("p".repeat(43), ticket));
        assertTrue(service.authorizeLan("p".repeat(43), session).startsWith("dsh-auth-"));
    }

    @Test void browserSessionsCannotCrossBetweenLanAndPublicEntrances() throws Exception {
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        String lanSession = service.redeemLan("p".repeat(43), service.issue(owner, "owner-access"));

        // 局域网会话即使被重命名为公网 Cookie，也不具有公网会话权限。
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), lanSession));
        assertTrue(service.authorizeLan("p".repeat(43), lanSession).startsWith("dsh-auth-"));

        String publicSession = service.redeem(service.issue(owner, "owner-access"));
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorizeLan("p".repeat(43), publicSession));
        assertTrue(service.authorize("p".repeat(43), publicSession).startsWith("dsh-auth-"));
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorizeLan("p".repeat(43), lanSession));
    }

    @Test void lanSessionRequiresLiveIdentityAndRemainsRevokedAfterReauthentication() throws Exception {
        DshWebSessionService service = new DshWebSessionService(properties(), validator);
        String session = service.redeemLan("p".repeat(43), service.issue(owner, "owner-access"));

        assertThrows(GatewayUnauthorizedException.class, () -> service.authorizeLan(null, session));
        when(validator.validate("owner-access")).thenThrow(new GatewayUnauthorizedException());
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorizeLan("p".repeat(43), session));
        doReturn(owner).when(validator).validate("owner-access");
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorizeLan("p".repeat(43), session));
    }

    @Test void lanSessionsExpireAndCannotBeUsedThroughThePublicAuthorizer() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        DshWebSessionService service = new DshWebSessionService(properties(), validator, clock);
        String session = service.redeemLan("p".repeat(43), service.issue(owner, "owner-access"));

        when(clock.millis()).thenReturn(1_801_001L);
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorizeLan("p".repeat(43), session));
        assertThrows(GatewayUnauthorizedException.class, () -> service.authorize("p".repeat(43), session));
    }
}
