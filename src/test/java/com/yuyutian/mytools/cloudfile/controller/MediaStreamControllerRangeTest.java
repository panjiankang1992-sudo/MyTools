package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.cloudfile.model.MediaPlaybackTicket;
import com.yuyutian.mytools.cloudfile.model.RemoteMediaStream;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import com.yuyutian.mytools.cloudfile.service.MediaPlaybackTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaStreamControllerRangeTest {

    @Test
    void shouldPreserveRangeNotSatisfiableThroughPlaybackTicket() {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        MediaPlaybackTicketService ticketService = new MediaPlaybackTicketService();
        MediaPlaybackTicket ticket = ticketService.issue(42L, 99L, 7L, "/movie.mp4");
        TokenMapper tokenMapper = mock(TokenMapper.class);
        Token session = new Token();
        session.setId(99L);
        session.setUserId(42L);
        session.setStatus("ACTIVE");
        session.setExpireTime(System.currentTimeMillis() + 60_000);
        when(tokenMapper.findById(99L)).thenReturn(session);
        when(cloudFileService.openMediaStream(42L, 7L, "/movie.mp4", "bytes=200-300"))
                .thenReturn(new RemoteMediaStream(
                        new ByteArrayInputStream(new byte[0]),
                        HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value(),
                        Optional.of("video/mp4"),
                        Optional.of("0"),
                        Optional.of("bytes */100"),
                        Optional.of("bytes"),
                        Optional.empty(),
                        Optional.empty()));
        MediaStreamController controller = new MediaStreamController(
                cloudFileService, ticketService, mock(JwtUtils.class), tokenMapper);

        ResponseEntity<StreamingResponseBody> response = controller.streamByTicket(
                ticket.ticket(), "bytes=200-300");

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */100", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
    }

    @Test
    void shouldRejectTicketWhenBoundLoginSessionWasRevoked() {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        MediaPlaybackTicketService ticketService = new MediaPlaybackTicketService();
        MediaPlaybackTicket ticket = ticketService.issue(42L, 99L, 7L, "/movie.mp4");
        TokenMapper tokenMapper = mock(TokenMapper.class);
        Token session = new Token();
        session.setId(99L);
        session.setUserId(42L);
        session.setStatus("INVALID");
        session.setExpireTime(System.currentTimeMillis() + 60_000);
        when(tokenMapper.findById(99L)).thenReturn(session);
        MediaStreamController controller = new MediaStreamController(
                cloudFileService, ticketService, mock(JwtUtils.class), tokenMapper);

        com.yuyutian.mytools.common.BusinessException exception = assertThrows(
                com.yuyutian.mytools.common.BusinessException.class,
                () -> controller.streamByTicket(ticket.ticket(), null));

        assertEquals("90004", exception.getCode());
        assertNull(ticketService.resolve(ticket.ticket()));
    }

    @Test
    void shouldExposeMetricsOnlyToBoundUser() throws Exception {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        MediaPlaybackTicketService ticketService = new MediaPlaybackTicketService();
        MediaPlaybackTicket ticket = ticketService.issue(42L, 99L, 7L, "/movie.mp4");
        TokenMapper tokenMapper = mock(TokenMapper.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        Token session = new Token();
        session.setId(99L);
        session.setUserId(42L);
        session.setStatus("ACTIVE");
        session.setExpireTime(System.currentTimeMillis() + 60_000);
        when(tokenMapper.findById(99L)).thenReturn(session);
        when(tokenMapper.findByAccessToken("valid-token")).thenReturn(session);
        when(jwtUtils.getUserIdFromToken("valid-token")).thenReturn(42L);
        when(cloudFileService.openMediaStream(42L, 7L, "/movie.mp4", null))
                .thenReturn(new RemoteMediaStream(
                        new ByteArrayInputStream(new byte[4096]), HttpStatus.OK.value(),
                        Optional.of("video/mp4"), Optional.of("4096"), Optional.empty(),
                        Optional.of("bytes"), Optional.empty(), Optional.empty()));
        MediaStreamController controller = new MediaStreamController(
                cloudFileService, ticketService, jwtUtils, tokenMapper);

        ResponseEntity<StreamingResponseBody> stream = controller.streamByTicket(ticket.ticket(), null);
        stream.getBody().writeTo(new ByteArrayOutputStream());
        var response = controller.getTicketMetrics(ticket.ticket(), "Bearer valid-token");

        assertEquals(4096, response.getBody().getData().transferredBytes());
        assertEquals(0, response.getBody().getData().activeStreams());
    }

    @Test
    void shouldRejectMetricsRequestFromAnotherSessionOfSameUser() {
        CloudFileService cloudFileService = mock(CloudFileService.class);
        MediaPlaybackTicketService ticketService = new MediaPlaybackTicketService();
        MediaPlaybackTicket ticket = ticketService.issue(42L, 99L, 7L, "/movie.mp4");
        TokenMapper tokenMapper = mock(TokenMapper.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        Token otherSession = new Token();
        otherSession.setId(100L);
        otherSession.setUserId(42L);
        otherSession.setStatus("ACTIVE");
        otherSession.setExpireTime(System.currentTimeMillis() + 60_000);
        when(jwtUtils.getUserIdFromToken("other-device-token")).thenReturn(42L);
        when(tokenMapper.findByAccessToken("other-device-token")).thenReturn(otherSession);
        MediaStreamController controller = new MediaStreamController(
                cloudFileService, ticketService, jwtUtils, tokenMapper);

        com.yuyutian.mytools.common.BusinessException exception = assertThrows(
                com.yuyutian.mytools.common.BusinessException.class,
                () -> controller.getTicketMetrics(ticket.ticket(), "Bearer other-device-token"));

        assertEquals("90004", exception.getCode());
        assertNotNull(ticketService.resolve(ticket.ticket()));
    }
}
