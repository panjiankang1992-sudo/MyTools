package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.AudiobookPlaybackTicketService;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AudiobookPlaybackControllerTest {

    @Test
    void shouldBindTicketOwnerAndChapterWhenStreaming() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        AudiobookPlaybackTicketService tickets = new AudiobookPlaybackTicketService();
        UUID generationId = UUID.randomUUID();
        var ticket = tickets.issue(55L, generationId, 3);
        AudiobookPlaybackController controller = new AudiobookPlaybackController(client, tickets);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Range", "bytes=100-");
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.play(ticket.token(), request, response);

        verify(client).streamAudiobookChapter(55L, generationId, 3, "bytes=100-", response, "correlation");
    }
}
