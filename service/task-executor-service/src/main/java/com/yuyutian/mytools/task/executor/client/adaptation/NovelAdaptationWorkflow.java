package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Phase;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** 宿主四阶段编排；每次推进重读 Reader 权威状态，本机不维护第二份业务状态机。 */
public final class NovelAdaptationWorkflow implements AutoCloseable {
    private static final Phase[] ORDER = {Phase.PLAN, Phase.GENERATE, Phase.CRITIC, Phase.REPAIR, Phase.CRITIC};
    private static final Set<ErrorCode> ATTEMPT_FAILURES = Set.of(ErrorCode.UNAVAILABLE, ErrorCode.UNAUTHORIZED,
            ErrorCode.PROTOCOL, ErrorCode.CONSTRAINTS, ErrorCode.CONTENT_REJECTED, ErrorCode.REQUEST_REJECTED,
            ErrorCode.DEADLINE, ErrorCode.UNKNOWN);
    private final ReaderAdaptationClient reader;
    private final NovelProviderClient provider;
    private final NovelAdaptationPrompts prompts = new NovelAdaptationPrompts();
    private final ReentrantLock advancing = new ReentrantLock();
    private final Clock clock;
    private final ReaderSettlementRelay relay;
    private final java.util.function.BooleanSupplier permitted;
    private volatile boolean closed;
    private NovelAdaptationPrompts.Context context;
    private NovelProviderModels.Scope scope;
    private PendingCall pending;

    /** 绑定一个已领取任务的两个专用客户端；构造不取文、不访问模型。 */
    public NovelAdaptationWorkflow(ReaderAdaptationClient reader, NovelProviderClient provider, ReaderSettlementRelay relay) {
        this(reader, provider, relay, () -> true);
    }

    /** 额外绑定宿主实时取消/租约条件，Provider 等待期间同样持续检查。 */
    public NovelAdaptationWorkflow(ReaderAdaptationClient reader, NovelProviderClient provider, ReaderSettlementRelay relay,
                                   java.util.function.BooleanSupplier permitted) {
        this(reader, provider, relay, permitted, Clock.systemUTC());
        if (relay == null) throw new ReaderAdaptationException(ErrorCode.PERSISTENCE_UNAVAILABLE, 0);
    }

    NovelAdaptationWorkflow(ReaderAdaptationClient reader, NovelProviderClient provider, Clock clock) {
        this(reader, provider, null, clock);
    }

    NovelAdaptationWorkflow(ReaderAdaptationClient reader, NovelProviderClient provider, ReaderSettlementRelay relay, Clock clock) {
        this(reader, provider, relay, () -> true, clock);
    }

    private NovelAdaptationWorkflow(ReaderAdaptationClient reader, NovelProviderClient provider, ReaderSettlementRelay relay,
                                    java.util.function.BooleanSupplier permitted, Clock clock) {
        if (reader == null || provider == null || clock == null || permitted == null) throw invalid();
        this.reader = reader; this.provider = provider; this.clock = clock; this.relay = relay; this.permitted = permitted;
    }

    /** 不含正文的执行提示；WAITING 由宿主退避调度，不能立即忙循环。 */
    public enum Action { PROGRESSED, WAITING, COMPLETED, FAILED, STOPPED }

    /** 可进入通用任务诊断的固定元数据，不包含上下文、能力或 Provider 原始消息。 */
    public record Advance(Action action, ErrorCode errorCode) { }

    /**
     * 执行一个受权推进单元；一次模型调用包含预留、单次发送和结算，回写失败保留同一载荷。
     * 生产构造要求加密 relay，先同步保存能力与终态；只有包内纯编排夹具允许无日志构造。
     */
    public Advance advance() {
        // 同一个实例只能有一个调用方推进，防止两个入口同时预留或结算。
        if (!advancing.tryLock()) return waiting();
        try {
            if (closed) return new Advance(Action.STOPPED, ErrorCode.FENCED);
            if (pending != null) return continueCall();
            if (!allowed()) return new Advance(Action.STOPPED, ErrorCode.FENCED);
            if (context == null) return initialize();
            JsonNode view = reader.workflow().body();
            JsonNode state = view.path("state");
            String status = string(state, "status");
            if ("COMPLETED".equals(status)) return new Advance(Action.COMPLETED, null);
            if ("FAILED".equals(status)) return new Advance(Action.FAILED, error(view.get("errorCode")));
            if (bool(state, "stopRequested") || Set.of("CANCELLED", "CANCEL_REQUESTED").contains(status)) {
                return new Advance(Action.STOPPED, ErrorCode.FENCED);
            }
            JsonNode attempts = attempts(view, state);
            if (!state.path("pendingAttempts").isEmpty()) {
                // 没有本机原发送现场的调用只能等待 Reader 到期回收，接管不补发模型。
                reader.recover();
                return waiting();
            }
            for (JsonNode attempt : attempts) {
                if (!"SUCCEEDED".equals(string(attempt, "status")) || bool(attempt, "archivedOnly")) {
                    ErrorCode failure = error(attempt.get("errorCode"));
                    reader.fail(ATTEMPT_FAILURES.contains(failure) ? failure : ErrorCode.UNKNOWN);
                    return progressed();
                }
            }
            String stage = string(state, "currentStage");
            return switch (status) {
                case "ANALYZING" -> analyze(attempts, stage);
                case "GENERATING" -> generate(view, attempts, stage);
                case "VALIDATING" -> validate(view, attempts, stage);
                case "REPAIRING" -> repair(view, attempts, stage);
                case "PERSISTING" -> complete(view, attempts, stage);
                default -> throw invalid();
            };
        } finally { advancing.unlock(); }
    }

    private Advance initialize() {
        JsonNode claim = reader.claim().body();
        if (bool(claim.path("state"), "stopRequested")) return new Advance(Action.STOPPED, ErrorCode.FENCED);
        String action = string(claim, "nextAction");
        if (!Set.of("PREPARE_CONTEXT", "ANALYZE", "RESUME", "RECOVER_ATTEMPTS").contains(action)) throw invalid();
        if ("RECOVER_ATTEMPTS".equals(action)) {
            reader.recover();
            return waiting();
        }
        if ("PREPARE_CONTEXT".equals(action)) {
            // 准备回复不直接当作完整快照；下一轮重新领取经 Reader 核验的封存内容。
            reader.prepareContext();
            return progressed();
        }
        JsonNode inputs = claim.get("inputs");
        exact(inputs, "intent", "promptVersion", "constraintVersion", "providerDeploymentId", "providerCode", "modelId",
                "credentialGeneration", "disclosureVersion");
        text(inputs, "providerCode", 128); text(inputs, "disclosureVersion", 128);
        NovelProviderModels.Scope newScope = new NovelProviderModels.Scope(text(inputs, "providerDeploymentId", 128),
                text(inputs, "modelId", 256), integer(inputs, "credentialGeneration", 1, Long.MAX_VALUE));
        NovelAdaptationPrompts.Context newContext = context(inputs, claim.get("context"));
        // 所有输入验证成功后一起发布，失败不能留下半初始化的身份与正文。
        this.scope = newScope; this.context = newContext;
        return progressed();
    }

    private Advance analyze(JsonNode attempts, String stage) {
        requireStage(stage, "PLAN_PENDING", "PLAN");
        if (attempts.isEmpty()) return call(Phase.PLAN, prompts.plan(context));
        count(attempts, 1);
        try { reader.seal(id(attempts.get(0), "attemptId")); }
        catch (ReaderAdaptationException exception) {
            if (exception.error() != ErrorCode.INTENT_CONFLICT) throw exception;
            reader.fail(ErrorCode.INTENT_CONFLICT);
        }
        return progressed();
    }

    private Advance generate(JsonNode view, JsonNode attempts, String stage) {
        requireStage(stage, "GENERATE_PENDING", "GENERATE");
        if (attempts.size() == 1) return call(Phase.GENERATE, prompts.generate(context, constraints(view)));
        count(attempts, 2); reader.progress(false); return progressed();
    }

    private Advance validate(JsonNode view, JsonNode attempts, String stage) {
        requireStage(stage, "CRITIC_PENDING", "CRITIC");
        int count = attempts.size();
        if (count == 2 || count == 4) {
            return call(Phase.CRITIC, prompts.critic(context, constraints(view), output(attempts.get(count - 1))));
        }
        if (count != 3 && count != 5) throw invalid();
        reader.validate(id(attempts.get(count - 2), "attemptId"), id(attempts.get(count - 1), "attemptId"));
        return progressed();
    }

    private Advance repair(JsonNode view, JsonNode attempts, String stage) {
        requireStage(stage, "REPAIR_PENDING", "REPAIR");
        JsonNode review = review(view, attempts, 1, "REPAIRABLE");
        if (attempts.size() == 3) {
            return call(Phase.REPAIR, prompts.repair(context, constraints(view), output(attempts.get(1)),
                    string(review, "reportJson"), string(review, "deterministicJson"), string(review, "criticJson")));
        }
        count(attempts, 4); reader.progress(true); return progressed();
    }

    private Advance complete(JsonNode view, JsonNode attempts, String stage) {
        requireStage(stage, "PERSISTING");
        int count = attempts.size();
        if (count != 3 && count != 5) throw invalid();
        JsonNode review = review(view, attempts, count == 3 ? 1 : 2, "PASS");
        reader.complete(id(review, "candidateAttemptId"), id(review, "validationId"));
        // 成功只能由随后重读的 Reader COMPLETED 确认，不把本机命令已发送当作完成。
        return progressed();
    }

    private Advance call(Phase phase, NovelAdaptationPrompts.Prepared prompt) {
        if (phase != prompt.phase()) throw invalid();
        pending = new PendingCall(provider.prepare(UUID.randomUUID(), scope, prompt));
        return continueCall();
    }

    private Advance continueCall() {
        PendingCall call = pending;
        if (call.capability != null && !clock.instant().isBefore(call.capability.expiresAt())) {
            // 窄能力到期后只允许 Reader 恢复账本；不构造新身份，不重做原生成。
            if (call.lease != null) call.lease.close();
            pending = null;
            return waiting();
        }
        if (call.terminal == null) {
            if (!allowed()) return new Advance(Action.STOPPED, ErrorCode.FENCED);
            if (call.reservation == null) call.reservation = reader.reserve(call.ticket);
            if (call.capability == null) call.capability = reader.sendStarted(call.reservation);
            // 原窄能力同步落盘成功前，禁止触发 Provider；恢复线程跳过当前活跃 Lease。
            if (relay != null && call.lease == null) call.lease = relay.arm(reader, call.capability);
            if (!call.capability.providerPermit().maySend()) {
                // send-started 回复丢失后得到的重放许可仅能结算，绝无第二次发送权。
                call.terminal = unknown();
            } else {
                try { call.terminal = provider.execute(call.ticket, call.capability.providerPermit(), this::allowed); }
                catch (RuntimeException exception) {
                    // 不确定异常发生在 HTTP 之前还是之后时，一律留下结果未知，不将外部异常写入诊断。
                    call.terminal = unknown();
                }
            }
        }
        if (call.terminal == null) call.terminal = unknown();
        if (call.lease != null && !call.recorded) {
            call.lease.record(call.terminal);
            call.recorded = true;
        }
        reader.settle(call.capability, call.terminal);
        if (call.lease != null) call.lease.acknowledge();
        // 只有 Reader 确认结算后才丢弃内存载荷；此处不能更新业务状态或重置调用预算。
        pending = null;
        return progressed();
    }

    private static NovelAdaptationPrompts.Context context(JsonNode inputs, JsonNode node) {
        exact(node, "manifestVersion", "manifestSha256", "kind", "fragments");
        if (!"adaptation-context-framed-v1".equals(string(node, "manifestVersion"))) throw invalid();
        JsonNode fragments = node.get("fragments");
        if (!fragments.isArray() || fragments.size() < 4 || fragments.size() > 5) throw invalid();
        Map<String, String> texts = new HashMap<>();
        UUID target = null;
        UUID base = null;
        Set<UUID> adjacent = new HashSet<>();
        for (JsonNode fragment : fragments) {
            exact(fragment, "role", "sourceChapterId", "text");
            String role = string(fragment, "role");
            int maximum = switch (role) {
                case "TARGET_ORIGINAL", "BASE_INPUT" -> 120000;
                case "PREVIOUS_TAIL", "NEXT_HEAD" -> 4000;
                case "CATALOG_METADATA" -> 16000;
                case "BOOK_START_MARKER", "BOOK_END_MARKER" -> 32;
                default -> throw invalid();
            };
            if (texts.putIfAbsent(role, text(fragment, "text", maximum)) != null) throw invalid();
            if (Set.of("TARGET_ORIGINAL", "BASE_INPUT", "PREVIOUS_TAIL", "NEXT_HEAD").contains(role)) {
                UUID chapter = id(fragment, "sourceChapterId");
                if ("TARGET_ORIGINAL".equals(role)) target = chapter;
                else if ("BASE_INPUT".equals(role)) base = chapter;
                else if (!adjacent.add(chapter)) throw invalid();
            } else if (!fragment.get("sourceChapterId").isNull()) throw invalid();
        }
        if (target == null || adjacent.contains(target) || base != null && !base.equals(target)
                || !texts.containsKey("CATALOG_METADATA")
                || texts.containsKey("PREVIOUS_TAIL") == texts.containsKey("BOOK_START_MARKER")
                || texts.containsKey("NEXT_HEAD") == texts.containsKey("BOOK_END_MARKER")
                || texts.containsKey("BOOK_START_MARKER") && !"BOOK_START".equals(texts.get("BOOK_START_MARKER"))
                || texts.containsKey("BOOK_END_MARKER") && !"BOOK_END".equals(texts.get("BOOK_END_MARKER"))) throw invalid();
        String original = texts.get("TARGET_ORIGINAL");
        return new NovelAdaptationPrompts.Context(string(node, "kind"), string(inputs, "intent"), string(inputs, "promptVersion"),
                string(inputs, "constraintVersion"), original, sha(original), string(node, "manifestSha256"),
                texts.get("PREVIOUS_TAIL"), texts.containsKey("BOOK_START_MARKER"), texts.get("NEXT_HEAD"),
                texts.containsKey("BOOK_END_MARKER"), texts.get("BASE_INPUT"));
    }

    private static JsonNode attempts(JsonNode view, JsonNode state) {
        JsonNode attempts = view.get("attempts");
        if (attempts == null || !attempts.isArray() || attempts.size() > 5
                || integer(state, "callsRemaining", 0, 5) != 5 - attempts.size()
                || !state.path("pendingAttempts").isArray()) throw invalid();
        Set<UUID> ids = new HashSet<>();
        Set<UUID> providers = new HashSet<>();
        int pendingCount = 0;
        for (int index = 0; index < attempts.size(); index++) {
            JsonNode attempt = attempts.get(index);
            exact(attempt, "attemptId", "providerAttemptId", "attemptNo", "callKind", "status", "archivedOnly",
                    "errorCode", "outputText", "structuredJson");
            if (!ids.add(id(attempt, "attemptId")) || !providers.add(id(attempt, "providerAttemptId"))
                    || integer(attempt, "attemptNo", 1, 5) != index + 1
                    || !ORDER[index].name().equals(string(attempt, "callKind"))) throw invalid();
            bool(attempt, "archivedOnly");
            String status = string(attempt, "status");
            if (!Set.of("REGISTERED", "SEND_STARTED", "SUCCEEDED", "FAILED", "CALL_OUTCOME_UNKNOWN").contains(status)) throw invalid();
            if ("REGISTERED".equals(status) || "SEND_STARTED".equals(status)) pendingCount++;
        }
        if (pendingCount != state.get("pendingAttempts").size() || pendingCount > 1) throw invalid();
        return attempts;
    }

    private static String constraints(JsonNode view) {
        JsonNode rules = view.get("constraints");
        exact(rules, "deterministicJson", "supplementJson", "mergedJson", "deterministicSha256", "supplementSha256", "mergedSha256");
        for (String field : new String[]{"deterministic", "supplement", "merged"}) {
            String canonical = NovelProviderJson.encode(NovelProviderJson.parse(text(rules, field + "Json", 131072)));
            if (!sha(canonical).equals(string(rules, field + "Sha256"))) throw invalid();
        }
        return string(rules, "mergedJson");
    }

    private static JsonNode review(JsonNode view, JsonNode attempts, int round, String outcome) {
        JsonNode review = view.get("review");
        exact(review, "validationId", "candidateAttemptId", "criticAttemptId", "round", "outcome", "contentPolicyOutcome",
                "deterministicJson", "criticJson", "reportJson", "reportSha256");
        int candidateIndex = round == 1 ? 1 : 3;
        if (attempts.size() < candidateIndex + 2 || integer(review, "round", 1, 2) != round
                || !outcome.equals(string(review, "outcome")) || !"PASS".equals(string(review, "contentPolicyOutcome"))
                || !id(review, "candidateAttemptId").equals(id(attempts.get(candidateIndex), "attemptId"))
                || !id(review, "criticAttemptId").equals(id(attempts.get(candidateIndex + 1), "attemptId"))
                || !sha(NovelProviderJson.encode(NovelProviderJson.parse(text(review, "reportJson", 131072))))
                    .equals(string(review, "reportSha256"))) throw invalid();
        id(review, "validationId");
        return review;
    }

    private static String output(JsonNode attempt) { return text(attempt, "outputText", 120000); }
    private static NovelStageOutput.Terminal unknown() {
        return new NovelStageOutput.Terminal("CALL_OUTCOME_UNKNOWN", null, null, null, null, null, null,
                null, ErrorCode.UNKNOWN.code(), null);
    }
    private static ErrorCode error(JsonNode value) { return value != null && value.isTextual() ? ErrorCode.fromReader(value.textValue()) : ErrorCode.UNKNOWN; }
    private static Advance waiting() { return new Advance(Action.WAITING, null); }
    private static Advance progressed() { return new Advance(Action.PROGRESSED, null); }
    private static String sha(String text) { return NovelProviderJson.sha(text.getBytes(StandardCharsets.UTF_8)); }
    private static void count(JsonNode array, int count) { if (array.size() != count) throw invalid(); }
    private static void requireStage(String stage, String... allowed) { if (!Set.of(allowed).contains(stage)) throw invalid(); }
    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }
    private static String string(JsonNode node, String field) { if (node == null || !node.path(field).isTextual()) throw invalid(); return node.get(field).textValue(); }
    private static String text(JsonNode node, String field, int maximum) { String result = string(node, field); NovelProviderJson.text(result, maximum); return result; }
    private static boolean bool(JsonNode node, String field) { if (node == null || !node.path(field).isBoolean()) throw invalid(); return node.get(field).booleanValue(); }
    private static long integer(JsonNode node, String field, long minimum, long maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum || value.longValue() > maximum) throw invalid();
        return value.longValue();
    }
    private static UUID id(JsonNode node, String field) {
        try { String text = string(node, field); UUID id = UUID.fromString(text); if (!id.toString().equals(text)) throw invalid(); return id; }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static ReaderAdaptationException invalid() { return new ReaderAdaptationException(ErrorCode.CONTEXT, 0); }

    private boolean allowed() {
        try { return !closed && permitted.getAsBoolean() && reader.active(); }
        catch (RuntimeException ignored) { return false; }
    }

    /** 停止普通生成并释放待结算日志给独立消费者；不会删除尚未获 Reader 确认的原结果。 */
    @Override public void close() {
        closed = true;
        advancing.lock();
        try {
            if (pending != null && pending.lease != null) pending.lease.close();
            pending = null; context = null; scope = null;
        } finally { advancing.unlock(); }
    }

    /** 原调用的内存现场，禁止默认诊断或通用 WAL 递归读取正文及结算能力。 */
    private static final class PendingCall {
        private final NovelProviderClient.Ticket ticket;
        private ReaderAdaptationClient.Reservation reservation;
        private ReaderAdaptationClient.SendCapability capability;
        private NovelStageOutput.Terminal terminal;
        private ReaderSettlementRelay.Lease lease;
        private boolean recorded;
        private PendingCall(NovelProviderClient.Ticket ticket) { this.ticket = ticket; }
        /** 隐藏原调用全部载荷。 */
        @Override public String toString() { return "PendingNovelCall[REDACTED]"; }
    }
}
