package com.yuyutian.mytools.reader.controller.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationAttemptService;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.authorization.ReaderWorkloadAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 调用账本内部 HTTP 契约，先授权后限量读取正文，窄结算例外不扩散到其他路由。 */
@RestController
@RequestMapping("/api/internal/v1/chapter-adaptations/{adaptationId}/executions/{executionId}")
public class AdaptationAttemptController {
    private static final Set<String> RESERVE_FIELDS = Set.of("providerAttemptId", "callKind", "requestSha256");
    private static final Set<String> TERMINAL_FIELDS = Set.of("status", "outputText", "structuredJson", "finishReason", "providerRequestId",
            "inputTokens", "outputTokens", "httpStatus", "errorCode", "diagnosticSha256");
    private final ReaderWorkloadAuthorizer authorizer;
    private final AdaptationAttemptService ledger;
    private final ObjectMapper mapper;

    /** 为新接口单独设置 JSON 约束，不修改其他旧路由行为。 */
    public AdaptationAttemptController(ReaderWorkloadAuthorizer authorizer, AdaptationAttemptService ledger, ObjectMapper mapper) {
        this.authorizer = authorizer;
        this.ledger = ledger;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(240000).maxNumberLength(16).build());
    }

    /** 预留只接收随机调用 ID、固定阶段和摘要，不接受正文、owner 或发布配置。 */
    @PostMapping("/attempts")
    public AdaptationAttemptModels.Reservation reserve(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var authorization = authorize(adaptationId, executionId, request);
        JsonNode node = body(request, 1024, RESERVE_FIELDS);
        if (node.size() != 3) throw invalid();
        try {
            return ledger.reserve(authorization, new AdaptationAttemptModels.Reserve(uuid(required(node, "providerAttemptId")),
                    AdaptationAttemptModels.Kind.valueOf(required(node, "callKind")), required(node, "requestSha256")));
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    /** 空请求触发一次性发送许可；重放永远返回 maySend=false。 */
    @PostMapping("/attempts/{providerAttemptId}/send-started")
    public AdaptationAttemptModels.SendPermit send(@PathVariable String adaptationId, @PathVariable String executionId,
                                                   @PathVariable String providerAttemptId, HttpServletRequest request) {
        var authorization = authorize(adaptationId, executionId, request);
        empty(request);
        return ledger.sendStarted(authorization, uuid(providerAttemptId));
    }

    /** 当前领取执行收敛已到期未决调用，不能借此重新发起 Provider 请求。 */
    @PostMapping("/attempts/recover")
    public Map<String, Integer> recover(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var authorization = authorize(adaptationId, executionId, request);
        empty(request);
        return Map.of("recovered", ledger.recover(authorization));
    }

    /** 窄令牌持有者只能填充同一已发送调用的终态，失效 assertion 的结果强制留档。 */
    @PutMapping("/attempts/{providerAttemptId}")
    public AdaptationAttemptModels.Settlement settle(@PathVariable String adaptationId, @PathVariable String executionId,
                                                     @PathVariable String providerAttemptId, HttpServletRequest request) {
        String certificate = authorizer.settlementCertificate(request);
        noQuery(request);
        UUID resource = uuid(adaptationId);
        UUID execution = uuid(executionId);
        UUID attempt = uuid(providerAttemptId);
        var headers = Collections.list(request.getHeaders("X-Reader-Attempt-Settlement"));
        if (headers.size() != 1 || headers.getFirst().length() > 256) throw fenced();
        String token = headers.getFirst();
        ledger.verifySettlement(resource, execution, attempt, certificate, token);
        WorkloadAuthorization current = null;
        if (request.getHeader("Authorization") != null) {
            try { current = authorizer.authorize(request, WorkloadResource.CHAPTER_ADAPTATION, resource, execution); }
            catch (ChapterAdaptationException exception) {
                // 窄能力已经独立验证；撤销或权威服务失联只能留档，绝不赋予推进状态的授权。
                if (exception.errorCode() != ErrorCode.ADAPTATION_EXECUTION_FENCED
                        && exception.errorCode() != ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE) throw exception;
            }
        }
        JsonNode node = body(request, 2097152, TERMINAL_FIELDS);
        var payload = new AdaptationAttemptModels.Terminal(required(node, "status"), optional(node, "outputText"), optional(node, "structuredJson"),
                optional(node, "finishReason"), optional(node, "providerRequestId"), integer(node, "inputTokens"), integer(node, "outputTokens"),
                status(node), optional(node, "errorCode"), optional(node, "diagnosticSha256"));
        return ledger.settle(resource, execution, attempt, certificate, token, current, payload);
    }

    private WorkloadAuthorization authorize(String resource, String execution, HttpServletRequest request) {
        var result = authorizer.authorize(request, WorkloadResource.CHAPTER_ADAPTATION, uuid(resource), uuid(execution));
        noQuery(request);
        return result;
    }
    private JsonNode body(HttpServletRequest request, int maximum, Set<String> fields) {
        try {
            var contentTypes = Collections.list(request.getHeaders("Content-Type"));
            if (contentTypes.size() != 1) throw invalid();
            MediaType type = MediaType.parseMediaType(contentTypes.getFirst());
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(type) || type.isWildcardType() || type.isWildcardSubtype()
                    || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())
                    || request.getHeader("Content-Encoding") != null || request.getContentLengthLong() > maximum) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
            if (bytes.length > maximum || bytes.length == 0) throw invalid();
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = mapper.readTree(utf8);
            if (root == null || !root.isObject()) throw invalid();
            var names = root.fieldNames();
            while (names.hasNext()) if (!fields.contains(names.next())) throw invalid();
            return root;
        } catch (Exception exception) {
            // 解析错误及读流异常可能携带正文，不能保留原始异常链。
            throw invalid();
        }
    }
    private static String required(JsonNode node, String name) { String value = optional(node, name); if (value == null) throw invalid(); return value; }
    private static String optional(JsonNode node, String name) {
        if (!node.hasNonNull(name)) return null;
        if (!node.get(name).isTextual()) throw invalid();
        return node.get(name).textValue();
    }
    private static Long integer(JsonNode node, String name) {
        if (!node.hasNonNull(name)) return null;
        if (!node.get(name).isIntegralNumber() || !node.get(name).canConvertToLong()) throw invalid();
        return node.get(name).longValue();
    }
    private static Integer status(JsonNode node) {
        Long result = integer(node, "httpStatus");
        if (result == null) return null;
        if (result < 100 || result > 599) throw invalid();
        return result.intValue();
    }
    private static void empty(HttpServletRequest request) { if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw invalid(); }
    private static void noQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw invalid(); }
    private static UUID uuid(String value) {
        try { UUID result = UUID.fromString(value); if (!result.toString().equals(value)) throw invalid(); return result; }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID); }
    private static ChapterAdaptationException fenced() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
}
