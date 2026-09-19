package com.yuyutian.mytools.reader.controller.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationConsentModels.*;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationConsentRepository;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** 账户级告知与明确同意，仅允许已认证 Gateway 注入 owner；不接受正文和任意告知文案。 */
@RestController
@Validated
@RequestMapping("/api/v1/reader-state/features/reader-adaptation")
public class AdaptationConsentController {
    private final AdaptationConsentRepository repository;
    private final ObjectMapper mapper;

    /** 独立严格解析，避免额外字段或重复布尔值伪造明确同意。 */
    public AdaptationConsentController(AdaptationConsentRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    /** 读取能力与本人的授权修订，不创建同意记录。 */
    @GetMapping
    public Features features(@RequestParam @Positive long ownerId, HttpServletRequest request) {
        query(request, Set.of("ownerId")); return repository.creationFeatures(ownerId);
    }

    /** 两项布尔确认必须为 JSON true，数字修订只能是安全整数。 */
    @PostMapping(value = "/consent", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Features accept(@RequestParam @Positive long ownerId, @RequestBody String body, HttpServletRequest request) {
        query(request, Set.of("ownerId"));
        try {
            if (body.length() > 4096) throw invalid();
            var node = mapper.readTree(body);
            if (node == null || !node.isObject() || node.size() != 5 || !node.path("accepted").isBoolean()
                    || !node.path("rightsAttested").isBoolean() || !node.path("expectedConsentRevision").isIntegralNumber()
                    || !node.path("expectedConsentRevision").canConvertToLong() || !node.path("disclosureVersion").isTextual()
                    || !node.path("disclosureSha256").isTextual()) throw invalid();
            return repository.accept(ownerId, mapper.treeToValue(node, Accept.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw invalid(); }
    }

    /** 无正文撤销全部告知版本；旧修订返回冲突，客户端须刷新后再次明确操作。 */
    @DeleteMapping("/consent")
    public Features revoke(@RequestParam @Positive long ownerId, @RequestParam String expectedConsentRevision,
                           @RequestBody(required = false) String body, HttpServletRequest request) {
        query(request, Set.of("ownerId", "expectedConsentRevision"));
        if (body != null && !body.isBlank()) throw invalid();
        try {
            if (!expectedConsentRevision.matches("0|[1-9][0-9]{0,15}")) throw invalid();
            return repository.revoke(ownerId, Long.parseLong(expectedConsentRevision));
        } catch (NumberFormatException exception) { throw invalid(); }
    }
    private static void query(HttpServletRequest request, Set<String> fields) {
        if (!fields.containsAll(request.getParameterMap().keySet()) || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw invalid();
    }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID); }
}
