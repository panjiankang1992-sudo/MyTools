package com.yuyutian.mytools.token.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.token.model.TokenInfo;
import com.yuyutian.mytools.token.model.TokenPageResponse;
import com.yuyutian.mytools.token.service.TokenManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenControllerContractTest {

    @Test
    void shouldReturnUnauthorizedWhenCurrentSessionDisappeared() {
        TokenManagementService service = mock(TokenManagementService.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(service.getCurrentToken("Bearer access-token")).thenReturn(null);
        TokenController controller = new TokenController(service, jwtUtils);

        ResponseEntity<Result<TokenInfo>> response = controller.getCurrentToken("Bearer access-token");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.AUTH_002.getCode(), response.getBody().getCode());
    }

    @Test
    void shouldSearchOwnedTokenListWithoutParsingUserIdAsToken() {
        TokenManagementService service = mock(TokenManagementService.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.getUserIdFromToken("access-token")).thenReturn(42L);
        TokenPageResponse page = new TokenPageResponse();
        page.setList(List.of());
        when(service.getTokenPage(42L, 1, 100)).thenReturn(page);
        TokenController controller = new TokenController(service, jwtUtils);

        ResponseEntity<Result<TokenInfo>> response = controller.getTokenDetail("99", "Bearer access-token");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.TOKEN_001.getCode(), response.getBody().getCode());
        verify(jwtUtils).getUserIdFromToken("access-token");
        verify(service, never()).getCurrentToken("Bearer 42");
    }
}
