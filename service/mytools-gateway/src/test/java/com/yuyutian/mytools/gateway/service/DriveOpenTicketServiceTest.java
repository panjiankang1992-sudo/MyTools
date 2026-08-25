package com.yuyutian.mytools.gateway.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriveOpenTicketServiceTest {
    @Test
    void shouldIssueTenantBoundTicketAndRejectUnknownToken() {
        DriveOpenTicketService service = new DriveOpenTicketService();
        UUID accountId = UUID.randomUUID();

        var ticket = service.issue(55L, accountId, "books/a.epub", "a.epub",
                "application/epub+zip", 42L);

        assertThat(ticket.token()).matches("^[a-f0-9]{32}$");
        assertThat(service.require(ticket.token()).ownerId()).isEqualTo(55L);
        assertThat(service.require(ticket.token()).accountId()).isEqualTo(accountId);
        assertThat(service.require(ticket.token()).path()).isEqualTo("books/a.epub");
        assertThatThrownBy(() -> service.require("0".repeat(32)))
                .isInstanceOf(GatewayNotFoundException.class);
    }
}
