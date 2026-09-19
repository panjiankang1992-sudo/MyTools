package com.yuyutian.mytools.reader.controller.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationStoryService;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.authorization.ReaderWorkloadAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** 当前执行的受限创作控制面，窄结算能力不能用于该类中的任一路由。 */
@RestController
@RequestMapping("/api/internal/v1/chapter-adaptations/{adaptationId}/executions/{executionId}")
public class AdaptationStoryController {
    private final ReaderWorkloadAuthorizer authorizer;
    private final AdaptationStoryService service;
    private final ObjectMapper mapper;

    /** 严格 JSON 仅用于有界身份命令，不接收模型事实清单或原文。 */
    public AdaptationStoryController(ReaderWorkloadAuthorizer authorizer, AdaptationStoryService service, ObjectMapper mapper) {
        this.authorizer = authorizer;
        this.service = service;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(128).maxNumberLength(10).build());
    }

    /** 已落库 PLAN 是唯一模型补充来源，不能请求删除原文规则。 */
    @PostMapping("/constraints")
    public AdaptationStoryModels.Constraints constraints(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var auth = authorize(adaptationId, executionId, request);
        return service.seal(auth, uuid(body(request, Set.of("planAttemptId")).get("planAttemptId").textValue()));
    }

    /** 仅接受固定边和精确预期阶段，防止任意状态覆盖。 */
    @PostMapping("/progress")
    public AdaptationStoryModels.Progress progress(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var auth = authorize(adaptationId, executionId, request);
        var node = body(request, Set.of("expectedStatus", "expectedStage", "nextStatus", "nextStage"));
        return service.progress(auth, node.get("expectedStatus").textValue(), node.get("expectedStage").textValue(), node.get("nextStatus").textValue(), node.get("nextStage").textValue());
    }

    /** 请求仅引用候选和评审调用，最终结论由 Reader 聚合。 */
    @PostMapping("/validations")
    public AdaptationStoryModels.Validation validate(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var auth = authorize(adaptationId, executionId, request);
        var node = body(request, Set.of("candidateAttemptId", "criticAttemptId"));
        return service.validate(auth, uuid(node.get("candidateAttemptId").textValue()), uuid(node.get("criticAttemptId").textValue()));
    }

    /** 引用已通过的校验完成采用，重复请求不会创建第二次 selection。 */
    @PostMapping("/complete")
    public AdaptationStoryModels.Selection complete(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var auth = authorize(adaptationId, executionId, request);
        var node = body(request, Set.of("candidateAttemptId", "validationId"));
        return service.complete(auth, uuid(node.get("candidateAttemptId").textValue()), uuid(node.get("validationId").textValue()));
    }

    /** 失败入口不接受外部消息或正文，只接受白名单错误码。 */
    @PostMapping("/fail")
    public AdaptationStoryModels.Progress fail(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var auth = authorize(adaptationId, executionId, request);
        return service.fail(auth, body(request, Set.of("errorCode")).get("errorCode").textValue());
    }

    /** 恢复读取仍需每次在线授权，不能用窄结算令牌读取候选和报告。 */
    @GetMapping("/workflow")
    public AdaptationStoryModels.Workflow workflow(@PathVariable String adaptationId, @PathVariable String executionId, HttpServletRequest request) {
        var auth = authorize(adaptationId, executionId, request);
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null || request.getHeader("Content-Encoding") != null) throw invalid();
        return service.workflow(auth);
    }

    private WorkloadAuthorization authorize(String adaptationId, String executionId, HttpServletRequest request) {
        var auth = authorizer.authorize(request, WorkloadResource.CHAPTER_ADAPTATION, uuid(adaptationId), uuid(executionId));
        if (request.getQueryString() != null) throw invalid(); return auth;
    }
    private JsonNode body(HttpServletRequest request, Set<String> fields) {
        try {
            var types = Collections.list(request.getHeaders("Content-Type"));
            if (types.size() != 1 || request.getContentLengthLong() > 2048 || request.getHeader("Content-Encoding") != null) throw invalid();
            var type = MediaType.parseMediaType(types.getFirst());
            if (!"application".equals(type.getType()) || !"json".equals(type.getSubtype()) || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(2049);
            if (bytes.length == 0 || bytes.length > 2048) throw invalid();
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            var root = mapper.readTree(raw);
            if (root == null || !root.isObject() || root.size() != fields.size()) throw invalid();
            for (String field : fields) if (!root.path(field).isTextual()) throw invalid();
            return root;
        } catch (Exception exception) { throw invalid(); }
    }
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID); }
}
