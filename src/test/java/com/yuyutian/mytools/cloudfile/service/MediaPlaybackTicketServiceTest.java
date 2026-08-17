package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.MediaPlaybackTicket;
import com.yuyutian.mytools.cloudfile.model.MediaPlaybackMetrics;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaPlaybackTicketServiceTest {

    @Test
    void shouldBindTicketToOneAuthorizedResource() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
        MediaPlaybackTicketService service = new MediaPlaybackTicketService(clock);

        MediaPlaybackTicket ticket = service.issue(42L, 99L, 7L, "/movie.mp4");
        MediaPlaybackTicketService.TicketBinding binding = service.resolve(ticket.ticket());

        assertNotNull(binding);
        assertEquals(42L, binding.userId());
        assertEquals(99L, binding.sessionId());
        assertEquals(7L, binding.accountId());
        assertEquals("/movie.mp4", binding.path());
        assertEquals("/api/app/v1/media/tickets/" + ticket.ticket(), ticket.streamPath());
    }

    @Test
    void shouldRejectExpiredTicket() {
        Instant issuedAt = Instant.parse("2026-08-11T12:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(issuedAt, issuedAt, issuedAt.plusSeconds(3 * 60 * 60));
        MediaPlaybackTicketService service = new MediaPlaybackTicketService(clock);
        MediaPlaybackTicket ticket = service.issue(42L, 99L, 7L, "/movie.mp4");

        assertNull(service.resolve(ticket.ticket()));
    }

    @Test
    void shouldRevokeAllTicketsForOneLoginSession() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
        MediaPlaybackTicketService service = new MediaPlaybackTicketService(clock);
        MediaPlaybackTicket revoked = service.issue(42L, 99L, 7L, "/movie.mp4");
        MediaPlaybackTicket retained = service.issue(42L, 100L, 7L, "/music.mp3");

        service.revokeSession(99L);

        assertNull(service.resolve(revoked.ticket()));
        assertNotNull(service.resolve(retained.ticket()));
    }

    @Test
    void shouldTrackTransferredBytesAndActiveStreams() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
        MediaPlaybackTicketService service = new MediaPlaybackTicketService(clock);
        MediaPlaybackTicket ticket = service.issue(42L, 99L, 7L, "/movie.mp4");

        service.streamStarted(ticket.ticket());
        service.recordTransfer(ticket.ticket(), 64 * 1024);
        MediaPlaybackMetrics active = service.getMetrics(ticket.ticket());
        service.streamFinished(ticket.ticket());
        MediaPlaybackMetrics finished = service.getMetrics(ticket.ticket());

        assertNotNull(active);
        assertEquals(64 * 1024, active.transferredBytes());
        assertEquals(1, active.activeStreams());
        assertEquals(clock.millis(), active.lastTransferTime());
        assertNotNull(finished);
        assertEquals(0, finished.activeStreams());
    }
}
