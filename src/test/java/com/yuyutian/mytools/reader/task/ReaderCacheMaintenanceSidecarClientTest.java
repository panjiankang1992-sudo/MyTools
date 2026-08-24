package com.yuyutian.mytools.reader.task;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReaderCacheMaintenanceSidecarClientTest {

    @Test
    void shouldCreateBoundedExpiredCacheMaintenance() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ReaderCacheMaintenanceSidecarProperties properties = new ReaderCacheMaintenanceSidecarProperties();
        properties.setServiceUrl("http://reader/");
        properties.setInternalToken("reader-token");
        properties.setBatchSize(250);
        UUID maintenanceId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/internal/v1/cache-maintenance"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer reader-token"))
                .andExpect(content().json("""
                        {"idempotencyKey":"maintenance-key","maintenanceType":"EXPIRED",
                         "cutoffAt":"2026-08-24T06:00:00Z","batchSize":250}
                        """))
                .andRespond(withSuccess("{\"id\":\"" + maintenanceId + "\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));

        var accepted = new ReaderCacheMaintenanceSidecarClient(restTemplate, properties)
                .create(Instant.parse("2026-08-24T06:00:00Z"), "maintenance-key");

        assertThat(accepted.id()).isEqualTo(maintenanceId);
        server.verify();
    }
}
