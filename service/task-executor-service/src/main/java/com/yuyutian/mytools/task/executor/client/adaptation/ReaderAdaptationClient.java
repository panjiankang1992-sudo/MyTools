package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutorWorkloadTls;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import com.yuyutian.mytools.task.executor.runtime.WorkloadAuthorizationRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** 每个已领取改编执行的固定 Reader 客户端；认证仅从宿主内存读取，不接受任务提供的 URL 或 token。 */
public final class ReaderAdaptationClient {
    private static final Set<String> STATUSES = Set.of("PENDING_DISPATCH", "QUEUED", "CONTEXT_FREEZING", "ANALYZING", "GENERATING",
            "VALIDATING", "REPAIRING", "PERSISTING", "COMPLETED", "FAILED", "CANCEL_REQUESTED", "CANCELLED");
    private static final Set<ErrorCode> FAILURES = Set.of(ErrorCode.INTENT_CONFLICT, ErrorCode.UNAVAILABLE, ErrorCode.UNAUTHORIZED,
            ErrorCode.PROTOCOL, ErrorCode.TOO_LARGE, ErrorCode.CONSTRAINTS, ErrorCode.DISPATCH, ErrorCode.CONTEXT,
            ErrorCode.CONSENT, ErrorCode.CONTENT_REJECTED, ErrorCode.UNKNOWN, ErrorCode.REQUEST_REJECTED, ErrorCode.DEADLINE);
    private final HttpClient http;
    private final WorkloadAuthorizationRegistry authorizations;
    private final UUID adaptationId;
    private final UUID executionId;
    private final long fence;
    private final Instant taskDeadline;
    private final URI base;
    private final Clock clock;
    private final String certificate;

    /** 从已受保护领取的任务建立固定资源范围，构造过程不发网络请求。 */
    public ReaderAdaptationClient(URI reader, ClaimedTask task, ExecutorWorkloadTls tls, WorkloadAuthorizationRegistry authorizations) {
        this(reader, task, tls, authorizations, Clock.systemUTC());
    }

    ReaderAdaptationClient(URI reader, ClaimedTask task, ExecutorWorkloadTls tls, WorkloadAuthorizationRegistry authorizations, Clock clock) {
        if (reader == null || !"https".equals(reader.getScheme()) || reader.getHost() == null || reader.getUserInfo() != null
                || reader.getRawQuery() != null || reader.getRawFragment() != null || !(reader.getPath().isEmpty() || "/".equals(reader.getPath()))
                || tls == null || tls.thumbprint() == null || task == null || !"reader_adapt_novel_chapter".equals(task.taskName())
                || task.parameters() == null || !task.parameters().keySet().equals(Set.of("adaptationId"))
                || !(task.parameters().get("adaptationId") instanceof String id) || authorizations == null) throw fenced();
        this.adaptationId = uuid(id); this.executionId = task.executionId(); this.fence = task.fencingToken();
        this.taskDeadline = task.deadlineAt(); this.clock = clock; this.authorizations = authorizations; this.http = tls.client();
        this.certificate = tls.thumbprint();
        this.base = reader.resolve("/api/internal/v1/chapter-adaptations/" + adaptationId + "/executions/" + executionId + "/");
        currentToken();
    }

    /** 首次或更高 fence 领取，正文来自 Reader 封存上下文。 */
    public Reply claim() {
        JsonNode result = request("POST", "claim", null, null, Duration.ofSeconds(10));
        exact(result, "state", "nextAction", "inputs", "context"); state(result.get("state"));
        return new Reply(result);
    }
    /** 准备过程仍由 Reader 按受控书架身份取文，客户端不能提交正文。 */
    public Reply prepareContext() { return new Reply(request("POST", "prepare-context", null, null, Duration.ofSeconds(90))); }
    /** 状态和恢复证据来自同一 Reader 短事务，禁止在本机拼接旧快照。 */
    public Reply workflow() {
        JsonNode result = request("GET", "workflow", null, null, Duration.ofSeconds(10));
        exact(result, "state", "errorCode", "attempts", "constraints", "review"); state(result.get("state"));
        if (!result.path("attempts").isArray() || result.path("attempts").size() > 5) throw invalid();
        return new Reply(result);
    }
    /** 读取轻量状态，不读取候选正文。 */
    public Reply status() {
        JsonNode result = request("GET", "status", null, null, Duration.ofSeconds(10)); state(result); return new Reply(result);
    }

    /** 固定阶段和摘要直接取自已构建模型 Ticket，预留请求不能另报模型配置。 */
    public Reservation reserve(NovelProviderClient.Ticket ticket) {
        if (ticket == null) throw invalid();
        JsonNode result = command("attempts", Map.of("providerAttemptId", ticket.providerAttemptId().toString(),
                "callKind", ticket.phase().name(), "requestSha256", ticket.requestSha256()));
        exact(result, "attemptId", "providerAttemptId", "attemptNo", "callKind", "status", "reservedMillis", "expiresAt");
        UUID attempt = uuid(string(result, "attemptId"));
        if (!ticket.providerAttemptId().toString().equals(string(result, "providerAttemptId"))
                || !ticket.phase().name().equals(string(result, "callKind"))
                || !Set.of("REGISTERED", "SEND_STARTED").contains(string(result, "status"))) throw invalid();
        int attemptNo = (int) integer(result, "attemptNo", 1, 5);
        String expected = switch (attemptNo) { case 1 -> "PLAN"; case 2 -> "GENERATE"; case 4 -> "REPAIR"; default -> "CRITIC"; };
        if (!expected.equals(ticket.phase().name())) throw invalid();
        long reserved = integer(result, "reservedMillis", 1, ticket.phase().seconds() * 1000L);
        Instant expires = instant(result, "expiresAt");
        return new Reservation(this, attempt, ticket.providerAttemptId(), ticket.phase(), ticket.requestSha256(), attemptNo, reserved, expires);
    }

    /** 只从已验证的 Reader 回复构造结算能力；响应丢失后不能假设获得发送权。 */
    public SendCapability sendStarted(Reservation reservation) {
        if (reservation == null || reservation.owner != this) throw fenced();
        JsonNode result = request("POST", "attempts/" + reservation.providerAttemptId + "/send-started", null, null, Duration.ofSeconds(10));
        exact(result, "providerAttemptId", "maySend", "transmission", "sendBy", "callDeadlineAt", "settlementExpiresAt", "attemptSettlementToken");
        if (!reservation.providerAttemptId.toString().equals(string(result, "providerAttemptId")) || !result.path("maySend").isBoolean()
                || integer(result, "transmission", 1, 1) != 1) throw invalid();
        String token = string(result, "attemptSettlementToken");
        if (!token.matches("settle-v1\\.[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")) throw invalid();
        String[] tokenParts = token.split("\\.");
        for (int index = 2; index <= 3; index++) {
            byte[] decoded = Base64.getUrlDecoder().decode(tokenParts[index]);
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(tokenParts[index])) throw invalid();
        }
        Instant sendBy = instant(result, "sendBy"); Instant deadline = instant(result, "callDeadlineAt"); Instant expiry = instant(result, "settlementExpiresAt");
        Instant now = clock.instant();
        if (!expiry.equals(deadline.plusSeconds(120)) || !now.isBefore(expiry) || expiry.isAfter(now.plusSeconds(302))
                || deadline.isAfter(taskDeadline) || !sendBy.isBefore(deadline)) throw invalid();
        return new SendCapability(this, reservation, result.get("maySend").booleanValue(), sendBy, deadline, expiry, token);
    }

    /** 结算只能重放同一能力与载荷，撤销后省略 assertion 并交由 Reader 强制留档。 */
    public Reply settle(SendCapability capability, NovelStageOutput.Terminal terminal) {
        if (capability == null || capability.owner != this || terminal == null || !clock.instant().isBefore(capability.expiresAt)) throw fenced();
        JsonNode body = NovelProviderJson.MAPPER.valueToTree(terminal);
        String digest = NovelProviderJson.sha(NovelProviderJson.encode(body).getBytes(StandardCharsets.UTF_8));
        capability.payloadSha.compareAndSet(null, digest);
        if (!digest.equals(capability.payloadSha.get())) throw new ReaderAdaptationException(ErrorCode.IDEMPOTENCY_CONFLICT, 409);
        JsonNode result = request("PUT", "attempts/" + capability.reservation.providerAttemptId, body, capability, Duration.ofSeconds(10));
        exact(result, "attemptId", "providerAttemptId", "status", "archivedOnly", "terminalPayloadSha256");
        if (!capability.reservation.attemptId.toString().equals(string(result, "attemptId"))
                || !capability.reservation.providerAttemptId.toString().equals(string(result, "providerAttemptId"))
                || !terminal.status().equals(string(result, "status")) || !result.path("archivedOnly").isBoolean()
                || !string(result, "terminalPayloadSha256").matches("[0-9a-f]{64}")) throw invalid();
        return new Reply(result);
    }

    /** 用已落库计划封存约束，不能自报固定事实清单。 */
    public Reply seal(UUID planAttemptId) { return new Reply(command("constraints", Map.of("planAttemptId", required(planAttemptId)))); }
    /** 候选保存后只开放固定的进入评审边。 */
    public Reply progress(boolean repair) {
        JsonNode result = command("progress", Map.of("expectedStatus", repair ? "REPAIRING" : "GENERATING", "expectedStage", repair ? "REPAIR" : "GENERATE",
                "nextStatus", "VALIDATING", "nextStage", "CRITIC_PENDING")); progress(result); return new Reply(result);
    }
    /** 最终校验只引用候选和 critic，由 Reader 计算。 */
    public Reply validate(UUID candidateId, UUID criticId) {
        JsonNode result = command("validations", Map.of("candidateAttemptId", required(candidateId), "criticAttemptId", required(criticId)));
        progress(result.get("progress"));
        if (!candidateId.toString().equals(string(result, "candidateAttemptId")) || !criticId.toString().equals(string(result, "criticAttemptId"))) throw invalid();
        return new Reply(result);
    }
    /** 引用既有校验原子完成采用，不上传新的正文。 */
    public Reply complete(UUID candidateId, UUID validationId) {
        JsonNode result = command("complete", Map.of("candidateAttemptId", required(candidateId), "validationId", required(validationId)));
        progress(result.get("progress"));
        if (!candidateId.toString().equals(string(result, "candidateAttemptId")) || !validationId.toString().equals(string(result, "validationId"))) throw invalid();
        return new Reply(result);
    }
    /** 仅向 Reader 提交固定错误类型，不带外部消息。 */
    public Reply fail(ErrorCode error) {
        if (error == null || !FAILURES.contains(error)) throw invalid();
        JsonNode result = command("fail", Map.of("errorCode", error.code())); progress(result); return new Reply(result);
    }
    /** 回收已到期调用，只查询 Reader 决定的收敛结果，不能重发模型。 */
    public int recover() {
        JsonNode result = request("POST", "attempts/recover", null, null, Duration.ofSeconds(10));
        exact(result, "recovered"); return (int) integer(result, "recovered", 0, 5);
    }
    /** 模型等待期间检查已续期的当前宿主授权，而非启动时的旧 token。 */
    public boolean active() {
        try { currentToken(); return true; } catch (ReaderAdaptationException exception) { return false; }
    }

    ReaderRelayEnvelope relay(SendCapability capability) {
        if (capability == null || capability.owner != this || !clock.instant().isBefore(capability.expiresAt)) throw fenced();
        return new ReaderRelayEnvelope(base.resolve("/"), certificate, adaptationId, executionId, capability.reservation.attemptId,
                capability.reservation.providerAttemptId, capability.reservation.requestSha, capability.expiresAt, capability.token, null);
    }

    /** 响应仅供宿主明确读取，默认 JSON 和诊断都不能递归输出内容。 */
    @JsonIgnoreType
    public static final class Reply {
        private final JsonNode body;
        private Reply(JsonNode body) { this.body = body; }
        /** 返回内容副本给受控宿主编排，不可直接写入通用任务结果。 */
        public JsonNode body() { return body.deepCopy(); }
        /** 隐藏所有内部返回字段。 */
        @Override public String toString() { return "ReaderReply[REDACTED]"; }
    }
    /** 本机预留收据只能由固定路由客户端创建，不携带结算令牌。 */
    @JsonIgnoreType
    public static final class Reservation {
        private final ReaderAdaptationClient owner;
        private final UUID attemptId;
        private final UUID providerAttemptId;
        private final NovelProviderModels.Phase phase;
        private final String requestSha;
        private final int attemptNo;
        private final long reservedMillis;
        private final Instant expiresAt;
        private Reservation(ReaderAdaptationClient owner, UUID attemptId, UUID providerId, NovelProviderModels.Phase phase,
                            String requestSha, int number, long millis, Instant expires) {
            this.owner = owner; this.attemptId = attemptId; this.providerAttemptId = providerId; this.phase = phase;
            this.requestSha = requestSha; this.attemptNo = number; this.reservedMillis = millis; this.expiresAt = expires;
        }
        /** 返回 Reader attempt 主键，后续封存和评审使用它而不是 Provider 请求身份。 */
        public UUID attemptId() { return attemptId; }
        /** 返回固定调用序号。 */
        public int attemptNo() { return attemptNo; }
        /** 返回预留时固定的阶段。 */
        public NovelProviderModels.Phase phase() { return phase; }
        /** 返回预留时固定的超时额度。 */
        public long reservedMillis() { return reservedMillis; }
        /** 返回预留到期时间，不允许宿主自行延长。 */
        public Instant expiresAt() { return expiresAt; }
        /** 不输出能力或完整预留上下文。 */
        @Override public String toString() { return "ReaderReservation[attemptNo=" + attemptNo + "]"; }
    }
    /** 仅宿主持有的原调用结算能力，令牌没有公开 getter 且不可通用序列化。 */
    @JsonIgnoreType
    public static final class SendCapability {
        private final ReaderAdaptationClient owner;
        private final Reservation reservation;
        private final boolean maySend;
        private final Instant sendBy;
        private final Instant deadline;
        private final Instant expiresAt;
        private final String token;
        private final AtomicReference<String> payloadSha = new AtomicReference<>();
        private SendCapability(ReaderAdaptationClient owner, Reservation reservation, boolean maySend, Instant sendBy,
                               Instant deadline, Instant expiresAt, String token) {
            this.owner = owner; this.reservation = reservation; this.maySend = maySend; this.sendBy = sendBy;
            this.deadline = deadline; this.expiresAt = expiresAt; this.token = token;
        }
        /** 向模型客户端只传无结算令牌的发送许可。 */
        public NovelProviderModels.Permit providerPermit() {
            return new NovelProviderModels.Permit(reservation.providerAttemptId, reservation.requestSha, maySend, sendBy, deadline);
        }
        /** 返回窄能力的硬期限，撤销后不能续期。 */
        public Instant expiresAt() { return expiresAt; }
        /** 不暴露结算令牌。 */
        @Override public String toString() { return "ReaderSendCapability[token=REDACTED]"; }
    }

    private JsonNode command(String route, Map<String, String> fields) {
        return request("POST", route, NovelProviderJson.MAPPER.valueToTree(fields), null, Duration.ofSeconds(10));
    }
    private JsonNode request(String method, String route, JsonNode body, SendCapability settlement, Duration maximum) {
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            String token = settlement == null ? currentToken() : optionalToken();
            Instant cutoff = settlement == null ? taskDeadline : settlement.expiresAt;
            Duration remaining = Duration.between(clock.instant(), cutoff);
            if (remaining.isNegative() || remaining.isZero()) throw fenced();
            Duration timeout = remaining.compareTo(maximum) < 0 ? remaining : maximum;
            long started = System.nanoTime();
            byte[] bytes = body == null ? new byte[0] : NovelProviderJson.encode(body).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > (settlement == null ? 2048 : 2097152)) throw invalid();
            var builder = HttpRequest.newBuilder(base.resolve(route)).timeout(timeout).header("Accept", "application/json").header("Accept-Encoding", "identity");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (settlement != null) builder.header("X-Reader-Attempt-Settlement", settlement.token);
            if (body != null) builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, bytes.length == 0 ? HttpRequest.BodyPublishers.noBody() : new NovelProviderClient.OncePublisher(bytes));
            future = http.sendAsync(builder.build(), info -> new ReaderResponseBody(info.headers(), info.statusCode() == 200 ? 4194304 : 8192));
            while (true) {
                if (System.nanoTime() - started >= timeout.toNanos() || !clock.instant().isBefore(cutoff)) throw unavailable();
                if (settlement == null) currentToken();
                try {
                    HttpResponse<byte[]> response = future.get(100, TimeUnit.MILLISECONDS);
                    if (settlement == null) currentToken();
                    var types = response.headers().allValues("Content-Type");
                    if (types.size() != 1 || !types.getFirst().toLowerCase(Locale.ROOT)
                            .matches("application/json(;\\s*charset=(utf-8|\"utf-8\"))?")) throw unavailable();
                    JsonNode result = NovelProviderJson.parse(NovelProviderJson.utf8(response.body()));
                    if (response.statusCode() != 200) {
                        // 远端 message 即使回显 token，也不参与异常构造。
                        throw new ReaderAdaptationException(ErrorCode.fromReader(result.path("code").asText()), response.statusCode());
                    }
                    return result;
                } catch (TimeoutException exception) {
                    // 只等待当前请求，不发起网络重试；调用方恢复时仍使用同一持久身份。
                }
            }
        } catch (ReaderAdaptationException exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception exception) { throw unavailable(); }
        finally { if (future != null && !future.isDone()) future.cancel(true); }
    }
    private String currentToken() {
        try {
            if (!clock.instant().isBefore(taskDeadline)) throw fenced();
            return authorizations.current(executionId).token();
        } catch (Exception exception) { throw fenced(); }
    }
    private String optionalToken() { try { return currentToken(); } catch (ReaderAdaptationException exception) { return null; } }
    private void state(JsonNode value) {
        exact(value, "adaptationId", "executionId", "fencingToken", "status", "currentStage", "deadlineAt", "stopRequested",
                "callsRemaining", "retriesRemaining", "providerMillisRemaining", "pendingAttempts");
        if (value == null || !adaptationId.toString().equals(string(value, "adaptationId"))
                || !executionId.toString().equals(string(value, "executionId")) || integer(value, "fencingToken", 1, Long.MAX_VALUE) != fence
                || !value.path("stopRequested").isBoolean() || !value.path("pendingAttempts").isArray()
                || value.path("pendingAttempts").size() > 5 || instant(value, "deadlineAt").isAfter(taskDeadline)) throw invalid();
        integer(value, "callsRemaining", 0, 5); integer(value, "retriesRemaining", 0, 2); integer(value, "providerMillisRemaining", 0, 660000);
        stage(value);
    }
    private void progress(JsonNode value) {
        exact(value, "adaptationId", "status", "currentStage", "version");
        if (!adaptationId.toString().equals(string(value, "adaptationId"))) throw invalid();
        integer(value, "version", 1, Long.MAX_VALUE); stage(value);
    }
    private static void stage(JsonNode value) {
        if (!STATUSES.contains(string(value, "status")) || !string(value, "currentStage").matches("[A-Z][A-Z_]{0,63}")) throw invalid();
    }
    private static void exact(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) throw invalid();
        for (String name : names) if (!node.has(name)) throw invalid();
    }
    private static String string(JsonNode node, String field) { if (node == null || !node.path(field).isTextual()) throw invalid(); return node.get(field).textValue(); }
    private static long integer(JsonNode node, String field, long minimum, long maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum || value.longValue() > maximum) throw invalid();
        return value.longValue();
    }
    private static Instant instant(JsonNode node, String field) { try { return Instant.parse(string(node, field)); } catch (Exception exception) { throw invalid(); } }
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
        catch (Exception exception) { throw invalid(); }
    }
    private static String required(UUID id) { if (id == null) throw invalid(); return id.toString(); }
    private static ReaderAdaptationException invalid() { return new ReaderAdaptationException(ErrorCode.CONTEXT, 0); }
    private static ReaderAdaptationException fenced() { return new ReaderAdaptationException(ErrorCode.FENCED, 0); }
    private static ReaderAdaptationException unavailable() { return new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 0); }
}
