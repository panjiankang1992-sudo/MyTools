package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.config.ChapterAdaptationGatewayProperties;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterAdaptationGatewayModels.*;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.ChapterAdaptationGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayBadRequestException;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

/** App 改编公开入口只使用登录主体和固定路径，不允许客户端注入 owner 或小说正文。 */
@RestController
@RequestMapping("/api/app/v1/reader")
public class ChapterAdaptationGatewayController {
    private static final Set<String> CREATE_FIELDS = Set.of("idempotencyKey", "intent", "expectedBindingRevision", "expectedCatalogRevision", "expectedSourceSha256", "templateCode", "templateVersion");
    private final GatewayProperties gateway;
    private final ChapterAdaptationGatewayProperties features;
    private final ChapterAdaptationGatewayClient client;
    private final ObjectMapper json;

    /** 使用独立严格解析器，禁止未知键、重复键及尾随 JSON。 */
    public ChapterAdaptationGatewayController(GatewayProperties gateway, ChapterAdaptationGatewayProperties features,
                                               ChapterAdaptationGatewayClient client, ObjectMapper mapper) {
        this.gateway = gateway; this.features = features; this.client = client;
        json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 查询本人书架的准备能力，不能据此推断已经取得第三方处理同意。 */
    @GetMapping("/shelves/{shelf}/chapter-adaptation-capability")
    public Capability capability(@PathVariable String shelf, HttpServletRequest request) {
        long owner = owner(request, false, Set.of()); return client.capability(owner, id(shelf), correlation(request));
    }
    /** 只提交准备幂等键，不接受任意书源或正文地址。 */
    @PostMapping(value = "/shelves/{shelf}/chapter-adaptation-capability/ensure", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Capability> ensure(@PathVariable String shelf, @RequestBody String body, HttpServletRequest request) {
        long owner = owner(request, true, Set.of()); JsonNode value = object(body, Set.of("idempotencyKey"), 1);
        return ResponseEntity.accepted().body(client.ensure(owner, id(shelf), new Ensure(key(value.path("idempotencyKey"))), correlation(request)));
    }
    /** 权威目录分页只传固定 limit 和签名游标。 */
    @GetMapping("/shelves/{shelf}/chapters")
    public Catalog catalog(@PathVariable String shelf, @RequestParam(defaultValue = "200") String limit,
                           @RequestParam(required = false) String cursor, HttpServletRequest request) {
        long owner = owner(request, false, Set.of("limit", "cursor"));
        return client.catalog(owner, id(shelf), limit(limit, 500), cursor(cursor), correlation(request));
    }
    /** 首次创建后返回 202，输入意图必填，正文始终由 Reader 可信读取。 */
    @PostMapping(value = "/shelves/{shelf}/chapters/{chapter}/adaptations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Accepted> create(@PathVariable String shelf, @PathVariable String chapter,
                                           @RequestBody String body, HttpServletRequest request) {
        long owner = owner(request, true, Set.of());
        return ResponseEntity.accepted().body(client.create(owner, id(shelf), id(chapter), create(body), correlation(request)));
    }
    /** 优化指定历史版本；不修改旧结果。 */
    @PostMapping(value = "/chapter-adaptations/{adaptation}/optimize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Accepted> optimize(@PathVariable String adaptation, @RequestBody String body, HttpServletRequest request) {
        long owner = owner(request, true, Set.of());
        return ResponseEntity.accepted().body(client.optimize(owner, id(adaptation), create(body), correlation(request)));
    }
    /** 重新改编指定历史的原章；不传送旧候选作为底稿。 */
    @PostMapping(value = "/chapter-adaptations/{adaptation}/regenerate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Accepted> regenerate(@PathVariable String adaptation, @RequestBody String body, HttpServletRequest request) {
        long owner = owner(request, true, Set.of());
        return ResponseEntity.accepted().body(client.regenerate(owner, id(adaptation), create(body), correlation(request)));
    }
    /** 轮询不返回用户意图和正文。 */
    @GetMapping("/chapter-adaptations/{adaptation}/status")
    public Progress progress(@PathVariable String adaptation, HttpServletRequest request) {
        long owner = owner(request, false, Set.of()); return client.progress(owner, id(adaptation), correlation(request));
    }
    /** 读取一个业务版本及其正式采用正文。 */
    @GetMapping("/chapter-adaptations/{adaptation}")
    public Detail detail(@PathVariable String adaptation, HttpServletRequest request) {
        long owner = owner(request, false, Set.of()); return client.detail(owner, id(adaptation), correlation(request));
    }
    /** 只读对比当前账户的冻结原章和采用结果，不依赖新建开关。 */
    @GetMapping("/chapter-adaptations/{adaptation}/comparison")
    public Comparison comparison(@PathVariable String adaptation, HttpServletRequest request) {
        long owner = owner(request, false, Set.of()); return client.comparison(owner, id(adaptation), correlation(request));
    }
    /** 核验请求独立于模型创建开关，没有可由客户端声明的正文和结论。 */
    @PostMapping("/chapter-adaptations/{adaptation}/source-check")
    public ResponseEntity<SourceCheck> verifySource(@PathVariable String adaptation, @RequestBody(required = false) String body,
                                                   HttpServletRequest request) {
        long owner = owner(request, false, Set.of());
        if (body != null && !body.isBlank()) throw invalid();
        return ResponseEntity.accepted().body(client.sourceCheck(owner, id(adaptation), true, correlation(request)));
    }
    /** 查询来源核验不再触发书源读取。 */
    @GetMapping("/chapter-adaptations/{adaptation}/source-check")
    public SourceCheck sourceStatus(@PathVariable String adaptation, HttpServletRequest request) {
        long owner = owner(request, false, Set.of()); return client.sourceCheck(owner, id(adaptation), false, correlation(request));
    }
    /** 读取同书同章历史分页。 */
    @GetMapping("/shelves/{shelf}/chapters/{chapter}/adaptations")
    public History history(@PathVariable String shelf, @PathVariable String chapter,
                           @RequestParam(defaultValue = "20") String limit, @RequestParam(required = false) String cursor,
                           HttpServletRequest request) {
        long owner = owner(request, false, Set.of("limit", "cursor"));
        return client.history(owner, id(shelf), id(chapter), limit(limit, 50), cursor(cursor), correlation(request));
    }
    /** 候选能否展示仍由 Reader 的内容与约束评审决定。 */
    @GetMapping("/chapter-adaptations/{adaptation}/attempts/{attempt}")
    public Output attempt(@PathVariable String adaptation, @PathVariable String attempt, HttpServletRequest request) {
        long owner = owner(request, false, Set.of()); return client.attempt(owner, id(adaptation), id(attempt), correlation(request));
    }
    /** 新建关闭后仍允许取消，不接收额外 JSON 参数。 */
    @PostMapping("/chapter-adaptations/{adaptation}/cancel")
    public Progress cancel(@PathVariable String adaptation, @RequestBody(required = false) String body, HttpServletRequest request) {
        long owner = owner(request, false, Set.of());
        if (body != null && !body.isBlank()) throw invalid();
        return client.cancel(owner, id(adaptation), correlation(request));
    }

    /** APP 只能列举风格，管理发布没有公共路由。 */
    @GetMapping("/adaptation-style-templates")
    public StyleCatalog styles(HttpServletRequest request) {
        return client.styles(owner(request, false, Set.of()), correlation(request));
    }

    private long owner(HttpServletRequest request, boolean creating, Set<String> query) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal) || principal.userId() < 1 || !gateway.readerTenantAllowed(principal.userId())
                || !features.readEnabled() || (creating && !features.createEnabled())) throw new GatewayRouteDisabledException();
        // 不仅忽略 owner 参数，而是拒绝所有未定义或重复参数，防止客户端误认为其生效。
        if (!query.containsAll(request.getParameterMap().keySet()) || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw invalid();
        return principal.userId();
    }
    private Create create(String body) {
        JsonNode value = object(body, CREATE_FIELDS, 4);
        if (!value.path("intent").isTextual() || !positive(value.path("expectedBindingRevision")) || !positive(value.path("expectedCatalogRevision"))) throw invalid();
        String intent = value.get("intent").textValue();
        if (intent.length() > 8192) throw invalid();
        for (int offset = 0; offset < intent.length();) {
            char unit = intent.charAt(offset); int point = intent.codePointAt(offset);
            if ((Character.isSurrogate(unit) && (point < 65536)) || (Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t')) throw invalid();
            offset += Character.charCount(point);
        }
        int start = 0; int end = intent.length();
        while (start < end && blank(intent.codePointAt(start))) start += Character.charCount(intent.codePointAt(start));
        while (end > start && blank(intent.codePointBefore(end))) end -= Character.charCount(intent.codePointBefore(end));
        intent = intent.substring(start, end);
        int count = intent.codePointCount(0, intent.length()); if (count < 5 || count > 2000) throw invalid();
        String sha = null;
        if (value.hasNonNull("expectedSourceSha256")) {
            if (!value.get("expectedSourceSha256").isTextual() || !value.get("expectedSourceSha256").textValue().matches("[a-f0-9]{64}")) throw invalid();
            sha = value.get("expectedSourceSha256").textValue();
        }
        String templateCode = null; Long templateVersion = null;
        if (value.hasNonNull("templateCode") != value.hasNonNull("templateVersion")) throw invalid();
        if (value.hasNonNull("templateCode")) {
            if (!value.get("templateCode").isTextual() || !value.get("templateCode").textValue().matches("[a-z][a-z0-9-]{0,63}")
                    || !positive(value.path("templateVersion")) || value.get("templateVersion").longValue() > 1000000) throw invalid();
            templateCode = value.get("templateCode").textValue(); templateVersion = value.get("templateVersion").longValue();
        }
        return new Create(key(value.path("idempotencyKey")), intent, value.get("expectedBindingRevision").longValue(), value.get("expectedCatalogRevision").longValue(), sha, templateCode, templateVersion);
    }
    private JsonNode object(String body, Set<String> fields, int minimum) {
        try {
            if (body == null || body.length() > 32768) throw invalid();
            JsonNode value = json.readTree(body);
            if (value == null || !value.isObject() || value.size() < minimum || value.size() > fields.size()) throw invalid();
            var names = value.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) throw invalid(); return value;
        } catch (Exception exception) { throw invalid(); }
    }
    private static boolean blank(int point) { return Character.isWhitespace(point) || Character.isSpaceChar(point); }
    private static boolean positive(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0 && value.longValue() <= 9007199254740991L; }
    private static String key(JsonNode value) { if (!value.isTextual() || !value.textValue().matches("[!-~]{1,128}")) throw invalid(); return value.textValue(); }
    private static UUID id(String raw) {
        try { UUID id = UUID.fromString(raw); if (!id.toString().equals(raw)) throw invalid(); return id; }
        catch (Exception exception) { throw invalid(); }
    }
    private static int limit(String raw, int maximum) {
        try { if (!raw.matches("[0-9]{1,3}")) throw invalid(); int value = Integer.parseInt(raw); if (value < 1 || value > maximum) throw invalid(); return value; }
        catch (Exception exception) { throw invalid(); }
    }
    private static String cursor(String value) { if (value != null && (!value.matches("[A-Za-z0-9_.-]{1,4096}"))) throw invalid(); return value; }
    private static String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE);
        try { return UUID.fromString(String.valueOf(value)).toString(); } catch (Exception exception) { return UUID.randomUUID().toString(); }
    }
    private static GatewayBadRequestException invalid() { return new GatewayBadRequestException(); }
}
