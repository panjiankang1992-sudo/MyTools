package com.yuyutian.mytools.toolbox.controller.curriculum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.model.ListType;
import com.yuyutian.mytools.toolbox.model.Semester;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TextbookCatalog;
import com.yuyutian.mytools.toolbox.model.TextbookEntriesPage;
import com.yuyutian.mytools.toolbox.model.TextbookVersion;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogQuery;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import com.yuyutian.mytools.toolbox.service.curriculum.EntryQuery;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教材只读接口：目录、不可变版本结构与条目分页。
 * 不返回 sampleEntries 之类的伪造数据，未发布版本统一按不存在处理。
 */
@RestController
@RequestMapping("/public/v1/textbooks")
public class CurriculumController {

    /** 不可变版本长缓存秒数，取一年。 */
    private static final int VERSION_CACHE_SECONDS = 31_536_000;

    private final CurriculumCatalog catalog;
    private final ToolboxProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 构造控制器。
     *
     * @param catalog    教材内容目录
     * @param properties 门面配置
     * @param objectMapper 公开响应序列化器。
     */
    public CurriculumController(CurriculumCatalog catalog, ToolboxProperties properties, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回已发布教材目录与 20 个必需槽位覆盖情况。
     *
     * @param subject  学科过滤，可为空
     * @param grade    年级过滤，可为空
     * @param semester 册次过滤，可为空
     * @param region   地区代码过滤，可为空
     * @return 目录响应信封
     */
    @GetMapping
    public ResponseEntity<ApiResponse<TextbookCatalog>> list(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Integer grade,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) String region,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        // F11：重载失败后**明确不可用**，而不是继续服务旧快照。
        // 旧快照看起来是好的，用户拿到的是过期目录，而系统对外宣称发布成功。
        if (!catalog.isReady()) {
            throw new BusinessException(ErrorCode.TOOLBOX_008, java.util.Map.of("reason", "catalogNotReady"));
        }
        CatalogQuery query = new CatalogQuery(
                parseSubject(subject),
                parseGrade(grade),
                parseSemester(semester),
                parseRegion(region));
        // 同一次快照同时提供响应和校验值，避免发布并发时把新正文配到旧缓存标识。
        TextbookCatalog view = catalog.catalog(query);
        String etag = catalogEtag(view);
        // GET 条件请求采用弱比较；逐项匹配，不能把任意子串误判为命中。
        if (ifNoneMatch != null && java.util.Arrays.stream(ifNoneMatch.split(","))
                .map(String::trim).anyMatch(value -> value.equals("*") || value.equals(etag)
                        || value.equals("W/" + etag))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache().cachePublic())
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePublic())
                .body(ApiResponse.ok(view));
    }

    /** 根据实际目录内容生成校验值，目录版本号重复或筛选变化也不会复用旧缓存。 */
    private String catalogEtag(TextbookCatalog view) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(view));
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            // 无法产生可靠缓存标识时明确失败，不返回可能指向旧教材的目录。
            throw new BusinessException(ErrorCode.TOOLBOX_008);
        }
    }

    /**
     * 返回指定不可变版本的结构与计数，并支持 ETag 条件请求。
     *
     * @param editionId    教材版本标识
     * @param version      内容版本
     * @param ifNoneMatch  条件请求头，可为空
     * @return 版本响应信封或 304
     */
    @GetMapping("/{editionId}/versions/{version}")
    public ResponseEntity<ApiResponse<TextbookVersion>> version(
            @PathVariable String editionId,
            @PathVariable String version,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        TextbookVersion view = catalog.version(editionId, version)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_003));
        String etag = "\"" + view.sha256() + "\"";
        if (ifNoneMatch != null && (ifNoneMatch.trim().equals("*") || ifNoneMatch.contains(etag))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(VERSION_CACHE_SECONDS)).cachePublic().immutable())
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(VERSION_CACHE_SECONDS)).cachePublic().immutable())
                .body(ApiResponse.ok(view));
    }

    /**
     * 返回条目分页；同维度多值取并集，不同维度取交集，游标绑定版本与过滤集合。
     *
     * @param editionId 教材版本标识
     * @param version   内容版本
     * @param unitIds   单元过滤，重复参数或逗号分隔
     * @param lessonIds 课文过滤，重复参数或逗号分隔
     * @param listTypes 清单类别过滤，重复参数或逗号分隔
     * @param cursor    不透明游标，可为空
     * @param limit     分页条数，可为空
     * @return 分页响应信封
     */
    @GetMapping("/{editionId}/versions/{version}/entries")
    public ResponseEntity<ApiResponse<TextbookEntriesPage>> entries(
            @PathVariable String editionId,
            @PathVariable String version,
            @RequestParam(required = false) List<String> unitIds,
            @RequestParam(required = false) List<String> lessonIds,
            @RequestParam(required = false) List<String> listTypes,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        EntryQuery query = new EntryQuery(
                editionId,
                version,
                normalizeIds(unitIds),
                normalizeIds(lessonIds),
                parseListTypes(listTypes),
                cursor,
                parseLimit(limit));
        return ResponseEntity.ok().body(ApiResponse.ok(catalog.entries(query)));
    }

    /**
     * 解析学科参数。
     *
     * @param value 原始取值
     * @return 学科或 null
     */
    private Subject parseSubject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Subject.parse(value).orElseThrow(() -> BusinessException.invalid("subject", "unsupportedValue"));
    }

    /**
     * 解析册次参数。
     *
     * @param value 原始取值
     * @return 册次或 null
     */
    private Semester parseSemester(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Semester.parse(value).orElseThrow(() -> BusinessException.invalid("semester", "unsupportedValue"));
    }

    /**
     * 解析年级参数。
     *
     * @param value 原始取值
     * @return 年级或 null
     */
    private Integer parseGrade(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > 6) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("min", 1);
            details.put("max", 6);
            throw BusinessException.invalid("grade", "outOfRange", details);
        }
        return value;
    }

    /**
     * 解析地区代码参数。
     *
     * @param value 原始取值
     * @return 地区代码或 null
     */
    private String parseRegion(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 16) {
            throw BusinessException.invalid("region", "tooLong");
        }
        return value;
    }

    /**
     * 归一化重复参数：去除空串并保持调用顺序。
     *
     * @param values 原始参数值，可为空
     * @return 归一化列表
     */
    private List<String> normalizeIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 解析清单类别过滤参数。
     *
     * @param values 原始参数值，可为空
     * @return 清单类别列表
     */
    private List<ListType> parseListTypes(List<String> values) {
        List<String> raw = normalizeIds(values);
        List<ListType> result = new ArrayList<>();
        for (int position = 0; position < raw.size(); position++) {
            String value = raw.get(position);
            int index = position;
            ListType parsed = ListType.parse(value).orElseThrow(() -> {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("index", index);
                return BusinessException.invalid("listTypes", "unsupportedValue", details);
            });
            if (!result.contains(parsed)) {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 解析分页条数：默认值来自配置，超过上限或非正数一律拒绝。
     *
     * @param value 原始取值，可为空
     * @return 合法分页条数
     */
    private int parseLimit(Integer value) {
        int limit = value == null ? properties.curriculum().entriesDefaultLimit() : value;
        int max = properties.curriculum().entriesMaxLimit();
        if (limit < 1 || limit > max) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("min", 1);
            details.put("max", max);
            details.put("actual", limit);
            throw BusinessException.invalid("limit", "outOfRange", details);
        }
        return limit;
    }
}
