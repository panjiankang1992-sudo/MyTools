package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.AudiobookExportTicketService;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AudiobookExportControllerTest {

    @Test
    void shouldBindTicketOwnerGenerationAndExportWhenDownloading() {
        ReaderGatewayClient client = mock(ReaderGatewayClient.class);
        AudiobookExportTicketService tickets = new AudiobookExportTicketService();
        UUID generationId = UUID.randomUUID();
        UUID exportId = UUID.randomUUID();
        var ticket = tickets.issue(55L, generationId, exportId);
        AudiobookExportController controller = new AudiobookExportController(client, tickets);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE, "correlation");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.download(ticket.token(), request, response);

        verify(client).streamAudiobookExport(55L, generationId, exportId, response, "correlation");
    }
}
