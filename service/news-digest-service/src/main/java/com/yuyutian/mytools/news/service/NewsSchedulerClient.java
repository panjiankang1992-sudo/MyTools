package com.yuyutian.mytools.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.Model.PipelineModels.RegionJob;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.config.SchedulingProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 固定目标的 Scheduler 适配器；业务凭据仅用于创建、对账和在线授权。 */
@Component
public class NewsSchedulerClient {
    private final RestClient client;

    /** 设置连接与响应超时，禁止重定向携带凭据。 */
    @org.springframework.beans.factory.annotation.Autowired
    public NewsSchedulerClient(SchedulingProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(8));
        client = RestClient.builder().baseUrl(properties.schedulerUrl()).requestFactory(factory)
                .defaultHeader("X-Task-Service-Id", "news-digest-service")
                .defaultHeader("X-Task-Business-Token", properties.businessToken()).build();
    }

    NewsSchedulerClient(RestClient client) { this.client = client; }

    /** 每次重试复用相同运行标识和完整参数，恢复创建成功但响应丢失的情况。 */
    public TaskState create(DailyRun run) {
        return invoke(() -> task(client.post().uri("/api/v1/task-instances").body(Map.of(
                "taskName", "news_generate_edition", "idempotencyKey", "news:" + run.id(), "businessType", "NEWS_EDITION",
                "businessId", run.id().toString(), "priority", 0, "parameters", Map.of("runId", run.id().toString()),
                "requiredNodeLabels", Map.of("news.node", run.nodeId()))).retrieve().body(JsonNode.class), run));
    }

    /** 查询已绑定任务并再次校验完整业务范围。 */
    public TaskState get(DailyRun run) {
        return invoke(() -> task(client.get().uri("/api/v1/task-instances/{id}", run.taskId()).retrieve().body(JsonNode.class), run));
    }

    /** 请求取消之后仍然持续对账，不能把请求已接受当成已终止。 */
    public TaskState cancel(DailyRun run) {
        return invoke(() -> task(client.post().uri("/api/v1/task-instances/{id}/cancel", run.taskId()).body(Map.of()).retrieve().body(JsonNode.class), run));
    }

    /** 地区任务仅传递冻结作业标识，不把供应商凭据放入 Scheduler。 */
    public TaskState createRegion(RegionJob job) {
        return invoke(() -> regionTask(client.post().uri("/api/v1/task-instances").body(Map.of(
                "taskName", "news_resolve_region", "idempotencyKey", "news:region:" + job.id(), "businessType", "NEWS_REGION",
                "businessId", job.id().toString(), "priority", 0, "parameters", Map.of("jobId", job.id().toString()),
                "requiredNodeLabels", Map.of("news.node", job.nodeId()))).retrieve().body(JsonNode.class), job));
    }
    /** 查询地区任务并检查任务域。 */
    public TaskState getRegion(RegionJob job) {
        return invoke(() -> regionTask(client.get().uri("/api/v1/task-instances/{id}", job.taskId()).retrieve().body(JsonNode.class), job));
    }
    /** 地区任务取消仍须查询到真实终态。 */
    public TaskState cancelRegion(RegionJob job) {
        return invoke(() -> regionTask(client.post().uri("/api/v1/task-instances/{id}/cancel", job.taskId()).body(Map.of()).retrieve().body(JsonNode.class), job));
    }
    private TaskState regionTask(JsonNode node, RegionJob job) {
        return task(node, new Target(job.id(), job.nodeId(), job.taskId(), "news_resolve_region", "NEWS_REGION", "news:region:", "jobId"));
    }

    /** 手动任务仅使用当前派发身份，相同身份重放不会创建第二个执行。 */
    public TaskState createWeekly(com.yuyutian.mytools.news.Model.WeeklyModels.Job job) {
        return invoke(() -> weeklyTask(client.post().uri("/api/v1/task-instances").body(Map.of(
                "taskName", "news_generate_weekly", "idempotencyKey", "news:weekly:" + job.dispatchId(), "businessType", "NEWS_WEEKLY",
                "businessId", job.dispatchId().toString(), "priority", 0, "parameters", Map.of("jobId", job.dispatchId().toString()),
                "requiredNodeLabels", Map.of("news.node", job.nodeId()))).retrieve().body(JsonNode.class), job));
    }

    /** 查询本次手动派发的真实执行状态。 */
    public TaskState getWeekly(com.yuyutian.mytools.news.Model.WeeklyModels.Job job) {
        return invoke(() -> weeklyTask(client.get().uri("/api/v1/task-instances/{id}", job.taskId()).retrieve().body(JsonNode.class), job));
    }

    /** 到达本次总预算后请求停止，不改变每日任务开关。 */
    public TaskState cancelWeekly(com.yuyutian.mytools.news.Model.WeeklyModels.Job job) {
        return invoke(() -> weeklyTask(client.post().uri("/api/v1/task-instances/{id}/cancel", job.taskId())
                .body(Map.of()).retrieve().body(JsonNode.class), job));
    }

    private TaskState weeklyTask(JsonNode node, com.yuyutian.mytools.news.Model.WeeklyModels.Job job) {
        return task(node, new Target(job.dispatchId(), job.nodeId(), job.taskId(), "news_generate_weekly", "NEWS_WEEKLY", "news:weekly:", "jobId"));
    }

    /** 每次发布在线验证当前执行租约，授权结论不得缓存。 */
    public ExecutionGrant authorize(ExecutionProof proof) {
        return invoke(() -> {
            ExecutionGrant result = client.post().uri("/api/v1/task-instances/news/executions/{id}/authorize", proof.executionId())
                    .header("X-Task-Lease-Token", proof.leaseToken().toString()).body(Map.of()).retrieve().body(ExecutionGrant.class);
            if (result == null || result.taskId() == null || result.businessId() == null || result.idempotencyKey() == null
                    || result.nodeId() == null || result.leaseUntil() == null) throw new Failure(true);
            return result;
        });
    }

    private TaskState task(JsonNode node, DailyRun run) {
        return task(node, new Target(run.id(), run.nodeId(), run.taskId(), "news_generate_edition", "NEWS_EDITION", "news:", "runId"));
    }
    private TaskState task(JsonNode node, Target run) {
        if (node == null || !run.taskName().equals(node.path("taskName").asText())
                || !run.businessType().equals(node.path("businessType").asText()) || !run.id().toString().equals(node.path("businessId").asText())
                || !(run.prefix() + run.id()).equals(node.path("idempotencyKey").asText())
                || node.path("parameters").size() != 1 || !run.id().toString().equals(node.path("parameters").path(run.parameter()).asText())
                || node.path("requiredNodeLabels").size() != 1 || !run.nodeId().equals(node.path("requiredNodeLabels").path("news.node").asText())) throw new Failure(true);
        try {
            UUID id = UUID.fromString(node.path("id").asText());
            if (run.taskId() != null && !run.taskId().equals(id)) throw new Failure(true);
            String status = node.path("status").asText();
            if (!java.util.Set.of("CREATED", "QUEUED", "RUNNING", "WAITING_CHILDREN", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "CANCELLING").contains(status)) throw new Failure(true);
            return new TaskState(id, status);
        } catch (IllegalArgumentException exception) { throw new Failure(true); }
    }

    /** 用不同键前缀隔离报告和日报任务，避免相同 UUID 造成混淆。 */
    private record Target(UUID id, String nodeId, UUID taskId, String taskName, String businessType, String prefix, String parameter) { }

    private <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (RestClientResponseException exception) {
            throw new Failure(exception.getStatusCode().value() != 429 && !exception.getStatusCode().is5xxServerError());
        } catch (RestClientException exception) { throw new Failure(false); }
    }

    /** 仅包含脱敏的失败类别，不保留远端响应或凭据。 */
    public static class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean permanent;
        /** 创建固定错误码的调度异常。 */
        public Failure(boolean permanent) { super((permanent ? ErrorCode.NEWS_017 : ErrorCode.NEWS_015).message()); this.permanent = permanent; }
        /** 返回是否需要人工修复配置后恢复。 */
        public boolean permanent() { return permanent; }
    }

    /** 用于任务状态对账的最小快照。 */
    public record TaskState(UUID id, String status) {
        /** 仅终止状态允许结束对账。 */
        public boolean terminal() { return java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT").contains(status); }
    }
}
