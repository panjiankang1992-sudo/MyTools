package com.yuyutian.mytools.localfile.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地媒体短期票据服务测试。
 */
class LocalMediaTicketServiceTest {

    /**
     * 验证票据格式、绑定关系和流地址。
     */
    @Test
    void shouldIssueBoundShortLivedTicket() {
        LocalMediaTicketService service = new LocalMediaTicketService();
        LocalMediaTicketService.TicketResult result = service.issue(11L, 22L, 33L);

        assertTrue(result.ticket().matches("[a-f0-9]{32}"));
        assertEquals("/api/app/v1/local-media/tickets/" + result.ticket(), result.streamPath());
        LocalMediaTicketService.TicketBinding binding = service.resolve(result.ticket());
        assertNotNull(binding);
        assertEquals(11L, binding.userId());
        assertEquals(22L, binding.sessionId());
        assertEquals(33L, binding.fileId());
        assertNull(service.resolve("invalid"));
    }
}
