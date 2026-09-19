package com.yuyutian.mytools.reader.controller.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationInput;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationViews;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationSourceCheck;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/** 复用受服务令牌保护的 Reader 私网路径，owner 只允许认证 Gateway 注入。 */
@Validated
@RestController
@RequestMapping("/api/v1/reader-state")
public class ChapterAdaptationController {
    private static final Set<String> FIELDS = Set.of("idempotencyKey", "intent", "expectedBindingRevision",
            "expectedCatalogRevision", "expectedSourceSha256", "templateCode", "templateVersion");
    private final ChapterAdaptationService service;
    private final ObjectMapper strictMapper;

    /** 局部启用重复键拒绝，不改变其他旧版 Reader 接口的 JSON 行为。 */
    public ChapterAdaptationController(ChapterAdaptationService service, ObjectMapper mapper) {
        this.service = service;
        this.strictMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 持久建立业务版本后立即返回 202，正文由后台可信读取。 */
    @PostMapping(value = "/shelves/{shelfBookId}/chapters/{chapterId}/adaptations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdaptationViews.Accepted> create(@RequestParam @Positive long ownerId,
                                                          @PathVariable UUID shelfBookId, @PathVariable UUID chapterId,
                                                          @RequestBody String request) {
        return ResponseEntity.accepted().body(service.create(ownerId, shelfBookId, chapterId, input(request)));
    }

    /** 优化已有成功结果，新业务版本与触发版本保持父子关系。 */
    @PostMapping(value = "/chapter-adaptations/{adaptationId}/optimize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdaptationViews.Accepted> optimize(@RequestParam @Positive long ownerId,
                                                            @PathVariable UUID adaptationId, @RequestBody String request) {
        return ResponseEntity.accepted().body(service.derive(ownerId, adaptationId, AdaptationRequestKind.OPTIMIZE, input(request)));
    }

    /** 重新改编基于同根原章，不将旧输出作为生成底稿。 */
    @PostMapping(value = "/chapter-adaptations/{adaptationId}/regenerate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdaptationViews.Accepted> regenerate(@RequestParam @Positive long ownerId,
                                                              @PathVariable UUID adaptationId, @RequestBody String request) {
        return ResponseEntity.accepted().body(service.derive(ownerId, adaptationId, AdaptationRequestKind.REGENERATE, input(request)));
    }

    /** 查询轻量进度，不返回用户意图或正文。 */
    @GetMapping("/chapter-adaptations/{adaptationId}/status")
    public AdaptationViews.Progress progress(@RequestParam @Positive long ownerId, @PathVariable UUID adaptationId) {
        return service.progress(ownerId, adaptationId);
    }

    /** 查询单个不可变业务版本及一个采用正文。 */
    @GetMapping("/chapter-adaptations/{adaptationId}")
    public AdaptationViews.Detail detail(@RequestParam @Positive long ownerId, @PathVariable UUID adaptationId) {
        return service.detail(ownerId, adaptationId);
    }

    /** 显式提交短期来源核验，不接受正文或核验结论。 */
    @PostMapping("/chapter-adaptations/{adaptationId}/source-check")
    public ResponseEntity<AdaptationSourceCheck> verifySource(@RequestParam @Positive long ownerId, @PathVariable UUID adaptationId,
                                                              @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) throw invalid();
        return ResponseEntity.accepted().body(service.verifySource(ownerId, adaptationId));
    }

    /** 核验状态读取不会触发新的后台任务。 */
    @GetMapping("/chapter-adaptations/{adaptationId}/source-check")
    public AdaptationSourceCheck sourceStatus(@RequestParam @Positive long ownerId, @PathVariable UUID adaptationId) {
        return service.sourceStatus(ownerId, adaptationId);
    }

    /** 以有范围的游标查询无正文历史列表。 */
    @GetMapping("/shelves/{shelfBookId}/chapters/{chapterId}/adaptations")
    public AdaptationViews.History history(@RequestParam @Positive long ownerId, @PathVariable UUID shelfBookId,
                                           @PathVariable UUID chapterId, @RequestParam(defaultValue = "20") int limit,
                                           @RequestParam(required = false) String cursor) {
        return service.history(ownerId, shelfBookId, chapterId, limit, cursor);
    }

    /** 按需读取单个允许展示的生成候选。 */
    @GetMapping("/chapter-adaptations/{adaptationId}/attempts/{attemptId}")
    public AdaptationViews.Output attempt(@RequestParam @Positive long ownerId, @PathVariable UUID adaptationId,
                                          @PathVariable UUID attemptId) {
        return service.attempt(ownerId, adaptationId, attemptId);
    }

    /** 显式取消没有正文输入，也不创建新的业务版本。 */
    @PostMapping("/chapter-adaptations/{adaptationId}/cancel")
    public AdaptationViews.Progress cancel(@RequestParam @Positive long ownerId, @PathVariable UUID adaptationId,
                                           @RequestBody(required = false) String request) {
        if (request != null && !request.isBlank()) {
            throw invalid();
        }
        return service.cancel(ownerId, adaptationId);
    }

    private AdaptationInput input(String request) {
        if (request == null || request.length() > 32768) {
            throw invalid();
        }
        try {
            JsonNode node = strictMapper.readTree(request);
            if (node == null || !node.isObject() || node.size() < 4 || node.size() > 7) {
                throw invalid();
            }
            var names = node.fieldNames();
            while (names.hasNext()) {
                // 白名单拒绝额外正文、owner、模型和任意地址，不能静默忽略。
                if (!FIELDS.contains(names.next())) {
                    throw invalid();
                }
            }
            if (!node.path("idempotencyKey").isTextual() || !node.path("intent").isTextual()
                    || !positiveInteger(node.path("expectedBindingRevision")) || !positiveInteger(node.path("expectedCatalogRevision"))
                    || (node.hasNonNull("expectedSourceSha256") && !node.get("expectedSourceSha256").isTextual())) {
                throw invalid();
            }
            if (node.hasNonNull("templateCode") != node.hasNonNull("templateVersion")
                    || (node.hasNonNull("templateCode") && (!node.get("templateCode").isTextual()
                    || !positiveInteger(node.path("templateVersion"))))) throw invalid();
            return new AdaptationInput(node.get("idempotencyKey").textValue(), node.get("intent").textValue(),
                    node.get("expectedBindingRevision").longValue(), node.get("expectedCatalogRevision").longValue(),
                    node.hasNonNull("expectedSourceSha256") ? node.get("expectedSourceSha256").textValue() : null,
                    node.hasNonNull("templateCode") ? node.get("templateCode").textValue() : null,
                    node.hasNonNull("templateVersion") ? node.get("templateVersion").longValue() : null);
        } catch (JsonProcessingException exception) {
            // 解析异常可能包含用户原文，统一抛弃异常链。
            throw invalid();
        }
    }

    private static boolean positiveInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private static ChapterAdaptationException invalid() {
        return new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
    }
}
