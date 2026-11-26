package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NewsSchedulerClientTest {
    @Test void creationCarriesFixedIdentityAndRejectsConfusedTaskResponse() {
        var builder = RestClient.builder().baseUrl("http://scheduler");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NewsSchedulerClient(builder.build());
        DailyRun run = run(); UUID task = UUID.randomUUID();
        server.expect(requestTo("http://scheduler/api/v1/task-instances")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"taskName\":\"news_generate_edition\",\"idempotencyKey\":\"news:" + run.id()
                        + "\",\"businessType\":\"NEWS_EDITION\",\"businessId\":\"" + run.id()
                        + "\",\"priority\":0,\"parameters\":{\"runId\":\"" + run.id() + "\"},\"requiredNodeLabels\":{\"news.node\":\"node\"}}"))
                .andRespond(withSuccess(payload(run, task, "QUEUED"), MediaType.APPLICATION_JSON));
        assertThat(client.create(run).id()).isEqualTo(task);
        server.verify(); server.reset();
        server.expect(requestTo("http://scheduler/api/v1/task-instances")).andRespond(withSuccess(payload(run, task, "QUEUED").replace("NEWS_EDITION", "OTHER"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.create(run)).isInstanceOf(NewsSchedulerClient.Failure.class)
                .extracting(error -> ((NewsSchedulerClient.Failure) error).permanent()).isEqualTo(true);
    }

    @Test void rateLimitsAndNetworkErrorsRetryButAuthenticationNeedsRepair() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.FORBIDDEN}) {
            var builder = RestClient.builder().baseUrl("http://scheduler");
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new NewsSchedulerClient(builder.build());
            server.expect(requestTo("http://scheduler/api/v1/task-instances")).andRespond(withStatus(status).body("secret-response"));
            assertThatThrownBy(() -> client.create(run())).isInstanceOf(NewsSchedulerClient.Failure.class)
                    .hasMessageNotContaining("secret-response").extracting(error -> ((NewsSchedulerClient.Failure) error).permanent())
                    .isEqualTo(status == HttpStatus.FORBIDDEN);
        }
    }

    @Test void authorizationUsesLiveLeaseAndSanitizedReceipt() {
        var builder = RestClient.builder().baseUrl("http://scheduler");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NewsSchedulerClient(builder.build());
        var proof = new ExecutionProof(UUID.randomUUID(), UUID.randomUUID());
        UUID task = UUID.randomUUID();
        server.expect(requestTo("http://scheduler/api/v1/task-instances/news/executions/" + proof.executionId() + "/authorize"))
                .andExpect(header("X-Task-Lease-Token", proof.leaseToken().toString()))
                .andRespond(withSuccess("{\"taskId\":\"" + task + "\",\"businessId\":\"run\",\"idempotencyKey\":\"key\",\"nodeId\":\"node\",\"fencingToken\":1,\"leaseUntil\":\"2026-10-18T00:01:00Z\"}", MediaType.APPLICATION_JSON));
        assertThat(client.authorize(proof).taskId()).isEqualTo(task);
        assertThat(proof.toString()).doesNotContain(proof.leaseToken().toString());
        server.verify();
    }

    @Test void regionRequestsCannotBeConfusedWithEditionScope() {
        var builder = RestClient.builder().baseUrl("http://scheduler");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NewsSchedulerClient(builder.build());
        DailyRun run = run();
        var job = new com.yuyutian.mytools.news.Model.PipelineModels.RegionJob(run.id(), "node", run.cutoff(), run.expiresAt(), null, null, "QUEUED", 0, null, null, null, run.createdAt(), run.updatedAt());
        String response = payload(run, UUID.randomUUID(), "QUEUED").replace("news_generate_edition", "news_resolve_region")
                .replace("NEWS_EDITION", "NEWS_REGION").replace("news:", "news:region:").replace("runId", "jobId");
        server.expect(requestTo("http://scheduler/api/v1/task-instances"))
                .andExpect(content().json("{\"taskName\":\"news_resolve_region\",\"businessType\":\"NEWS_REGION\",\"parameters\":{\"jobId\":\"" + run.id() + "\"}}"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThat(client.createRegion(job).status()).isEqualTo("QUEUED");
        server.verify(); server.reset();
        server.expect(requestTo("http://scheduler/api/v1/task-instances")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.create(run)).isInstanceOf(NewsSchedulerClient.Failure.class);
    }

    private DailyRun run() {
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        return new DailyRun(UUID.randomUUID(), UUID.randomUUID(), 1, 1, LocalDate.of(2026, 10, 18), "UTC", now, now.plusSeconds(43200), 0, "node", "QUEUED", null, null, null, 0, null, now, now);
    }
    private String payload(DailyRun run, UUID task, String status) {
        return "{\"id\":\"" + task + "\",\"taskName\":\"news_generate_edition\",\"idempotencyKey\":\"news:" + run.id()
                + "\",\"businessType\":\"NEWS_EDITION\",\"businessId\":\"" + run.id() + "\",\"parameters\":{\"runId\":\"" + run.id()
                + "\"},\"requiredNodeLabels\":{\"news.node\":\"node\"},\"status\":\"" + status + "\"}";
    }
}
