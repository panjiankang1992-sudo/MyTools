package com.yuyutian.mytools.reader.controller.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStyleModels.*;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationStyleRepository;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** 管理接口使用独立文件令牌，不接受普通 Reader 令牌或 APP 身份。 */
@RestController
@RequestMapping("/api/v1/reader-admin/adaptation-style-templates")
public class AdaptationStyleAdminController {
    private final AdaptationStyleRepository styles;
    private final ObjectMapper mapper;
    private final String tokenFile;

    /** 空令牌文件配置默认关闭管理发布。 */
    public AdaptationStyleAdminController(AdaptationStyleRepository styles, ObjectMapper mapper,
            @Value("${reader.adaptation-style-admin-token-file:}") String tokenFile) {
        this.styles = styles; this.tokenFile = tokenFile;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 技能先读取当前修订，不需要访问数据库。 */
    @GetMapping
    public Catalog catalog(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(authorization); return styles.catalog();
    }

    /** 发布只追加修订；网络重试复用相同幂等键。 */
    @PostMapping(consumes = "application/json")
    public Summary publish(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody String body) {
        authorize(authorization);
        if (body.length() > 65536) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
        try {
            var node = mapper.readTree(body);
            if (node == null || !node.isObject() || node.size() != 6 || !node.path("expectedLatestVersion").isIntegralNumber()
                    || !node.path("expectedLatestVersion").canConvertToInt()) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
            for (String field : java.util.List.of("idempotencyKey", "code", "name", "description", "prompt"))
                if (!node.path(field).isTextual()) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
            return styles.publish(mapper.treeToValue(node, Publish.class), "operator-skill").template();
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
        }
    }

    private void authorize(String authorization) {
        String expected = "";
        try {
            // 只读取显式配置的小型文件，配置错误一律拒绝，不泄露文件内容。
            if (!tokenFile.isBlank() && Files.size(Path.of(tokenFile)) <= 4096)
                expected = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException ignored) { expected = ""; }
        String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (expected.length() < 32 || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ErrorCode.INTERNAL_UNAUTHORIZED.message());
    }
}
