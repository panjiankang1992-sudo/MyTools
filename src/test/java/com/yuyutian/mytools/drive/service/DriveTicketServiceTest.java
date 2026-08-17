package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveOpenTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriveTicketServiceTest {

    @Test
    void shouldBindOpaqueTicketToValidatedTarget() {
        DriveTicketService service = new DriveTicketService();
        DriveOpenTarget target = new DriveOpenTarget(5L, 8L, 12L, "family", "movies/a.mp4",
                "a.mp4", "video/mp4", 123L);

        DriveTicketService.TicketResult result = service.issue(target);

        assertThat(result.ticket()).matches("[a-f0-9]{32}");
        assertThat(result.streamPath()).isEqualTo("/api/app/v1/drive-tickets/" + result.ticket());
        assertThat(service.resolve(result.ticket()).target()).isEqualTo(target);
        assertThat(service.resolve("invalid")).isNull();
    }
}
