package com.yuyutian.mytools.gateway.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterAdaptationGatewayModels.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 固定 Reader 私网路径及有界响应，不转发客户端令牌、查询参数、正文或下游错误消息。 */
@Component
public final class ChapterAdaptationGatewayClient {
    private final GatewayProperties properties;
    private final RestTemplate http;
    private final ObjectMapper json;

    /** 独立的无重定向/无代理客户端，不修改其他 Gateway 路由的请求工厂。 */
    @Autowired
    public ChapterAdaptationGatewayClient(GatewayProperties properties, ObjectMapper mapper) {
        this(properties, mapper, factory(properties));
    }

    ChapterAdaptationGatewayClient(GatewayProperties properties, ObjectMapper mapper, ClientHttpRequestFactory factory) {
        this.properties = properties; this.http = new RestTemplate(factory);
        this.http.setErrorHandler(new DefaultResponseErrorHandler() {
            /** 所有状态均交给有界提取器，禁止默认错误处理器预先读取整个错误正文。 */
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) { return false; }
        });
        this.json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                .maxStringLength(1000000).maxNumberLength(20).build());
    }

    /** 风格目录仍要求认证账户与 Reader 内部令牌。 */
    public StyleCatalog styles(long owner, String correlation) {
        var value = exchange(owner, "/adaptation-style-templates", HttpMethod.GET, null, null, correlation, StyleCatalog.class, 200);
        require(value.items() != null && value.items().stream().allMatch(item -> item != null && item.code() != null
                && item.code().matches("[a-z][a-z0-9-]{0,63}") && item.version() > 0 && item.version() <= 1000000
                && item.name() != null && !item.name().isBlank() && item.description() != null
                && item.promptSha256() != null && item.promptSha256().matches("[a-f0-9]{64}")));
        return value;
    }
    /** 查询本人书架准备能力。 */
    public Capability capability(long owner, UUID shelf, String correlation) {
        var value = exchange(owner, shelf(shelf) + "/chapter-adaptation-capability", HttpMethod.GET, null, null, correlation, Capability.class, 200);
        require(shelf.equals(value.shelfBookId())); return value;
    }
    /** 幂等准备本人书架的权威章节。 */
    public Capability ensure(long owner, UUID shelf, Ensure body, String correlation) {
        var value = exchange(owner, shelf(shelf) + "/chapter-adaptation-capability/ensure", HttpMethod.POST, body, null, correlation, Capability.class, 202);
        require(shelf.equals(value.shelfBookId())); return value;
    }
    /** 查询不带资源地址的目录。 */
    public Catalog catalog(long owner, UUID shelf, int limit, String cursor, String correlation) {
        var query = new Page(limit, cursor);
        var value = exchange(owner, shelf(shelf) + "/chapters", HttpMethod.GET, null, query, correlation, Catalog.class, 200);
        require(shelf.equals(value.shelfBookId()) && value.items() != null && value.items().size() <= limit); return value;
    }
    /** 创建原章改编，保留真实 202 收据语义。 */
    public Accepted create(long owner, UUID shelf, UUID chapter, Create body, String correlation) {
        var value = exchange(owner, shelf(shelf) + "/chapters/" + chapter + "/adaptations", HttpMethod.POST, body, null, correlation, Accepted.class, 202);
        require(chapter.equals(value.chapterId()) && "INITIAL".equals(value.requestKind())); return value;
    }
    /** 基于旧采用正文优化，仍由 Reader 解析书籍与章节身份。 */
    public Accepted optimize(long owner, UUID id, Create body, String correlation) {
        var value = exchange(owner, adaptation(id) + "/optimize", HttpMethod.POST, body, null, correlation, Accepted.class, 202);
        require("OPTIMIZE".equals(value.requestKind()) && !id.equals(value.adaptationId())); return value;
    }
    /** 基于原章重新改编，不传送旧候选正文。 */
    public Accepted regenerate(long owner, UUID id, Create body, String correlation) {
        var value = exchange(owner, adaptation(id) + "/regenerate", HttpMethod.POST, body, null, correlation, Accepted.class, 202);
        require("REGENERATE".equals(value.requestKind()) && !id.equals(value.adaptationId())); return value;
    }
    /** 读取轻量进度。 */
    public Progress progress(long owner, UUID id, String correlation) {
        var value = exchange(owner, adaptation(id) + "/status", HttpMethod.GET, null, null, correlation, Progress.class, 200);
        require(id.equals(value.adaptationId())); return value;
    }
    /** 读取指定不可变版本，不允许另一版本混入回复。 */
    public Detail detail(long owner, UUID id, String correlation) {
        var value = exchange(owner, adaptation(id), HttpMethod.GET, null, null, correlation, Detail.class, 200);
        require(value.version() != null && id.equals(value.version().adaptationId())); return value;
    }
    /** 固定只读路径与版本身份，转发有界的冻结正文对比。 */
    public Comparison comparison(long owner, UUID id, String correlation) {
        var value = exchange(owner, adaptation(id) + "/comparison", HttpMethod.GET, null, null, correlation, Comparison.class, 200);
        require(id.equals(value.adaptationId()) && value.attemptId() != null && value.originalSha256() != null
                && value.originalSha256().matches("[a-f0-9]{64}") && value.resultSha256() != null
                && value.resultSha256().matches("[a-f0-9]{64}") && value.hunks() != null && value.hunks().size() <= 1000);
        require("HUNKS".equals(value.mode()) || "SIDE_BY_SIDE".equals(value.mode()));
        if ("SIDE_BY_SIDE".equals(value.mode())) require(value.hunks().isEmpty() && value.original() != null && value.adapted() != null);
        else require(value.original() == null && value.adapted() == null && !value.hunks().isEmpty()
                && value.hunks().stream().allMatch(hunk -> hunk != null && hunk.kind() != null
                && java.util.Set.of("EQUAL", "INSERT", "DELETE", "REPLACE").contains(hunk.kind())
                && hunk.originalStart() >= 0 && hunk.originalStart() <= 500 && hunk.adaptedStart() >= 0 && hunk.adaptedStart() <= 500
                && hunk.original() != null && hunk.adapted() != null && hunk.original().size() <= 500 && hunk.adapted().size() <= 500
                && hunk.original().stream().allMatch(java.util.Objects::nonNull) && hunk.adapted().stream().allMatch(java.util.Objects::nonNull)));
        return value;
    }
    /** 提交或读取独立来源核验；固定无正文，不等待外部书源。 */
    public SourceCheck sourceCheck(long owner, UUID id, boolean request, String correlation) {
        var value = exchange(owner, adaptation(id) + "/source-check", request ? HttpMethod.POST : HttpMethod.GET,
                null, null, correlation, SourceCheck.class, request ? 202 : 200);
        require(id.equals(value.adaptationId()) && value.status() != null
                && java.util.Set.of("QUEUED", "CHECKING", "CURRENT", "STALE", "UNKNOWN").contains(value.status())
                && value.bindingRevision() > 0 && value.catalogRevision() > 0);
        if ("CURRENT".equals(value.status())) require(value.sourceCheckedAt() != null && value.validUntil() != null
                && value.validUntil().isAfter(value.sourceCheckedAt()) && !value.validUntil().isAfter(value.sourceCheckedAt().plusSeconds(60))
                && value.sourceSha256() != null && value.sourceSha256().matches("[a-f0-9]{64}"));
        return value;
    }
    /** 读取账户告知，功能关闭不移除撤销入口。 */
    public Features features(long owner, String correlation) {
        return checkedFeatures(exchange(owner, "/features/reader-adaptation", HttpMethod.GET, null, null, correlation, Features.class, 200));
    }
    /** 只转发五个同意字段，不传送客户端文案或模型地址。 */
    public Features acceptConsent(long owner, ConsentAccept body, String correlation) {
        return checkedFeatures(exchange(owner, "/features/reader-adaptation/consent", HttpMethod.POST, body, null, correlation, Features.class, 200));
    }
    /** 用固定安全整数查询参数撤销，不携带 DELETE 正文。 */
    public Features revokeConsent(long owner, long revision, String correlation) {
        require(revision >= 0 && revision <= 9007199254740991L);
        return checkedFeatures(exchange(owner, "/features/reader-adaptation/consent", HttpMethod.DELETE, null,
                new Page(null, null, revision), correlation, Features.class, 200));
    }
    private Features checkedFeatures(Features value) {
        require(value.consentStatus() != null && java.util.Set.of("REQUIRED", "ACCEPTED", "UNAVAILABLE", "NOT_REQUIRED").contains(value.consentStatus())
                && value.consentRevision() >= 0 && value.consentRevision() <= 9007199254740991L);
        if ("UNAVAILABLE".equals(value.consentStatus())) require(value.disclosure() == null && !value.createEnabled());
        else if ("NOT_REQUIRED".equals(value.consentStatus())) require(value.disclosure() == null
                && !value.hasActiveConsent() && value.acceptedAt() == null && value.consentRevision() == 0);
        else require(value.disclosure() != null && value.disclosure().payload() != null
                && value.disclosure().disclosureSha256() != null && value.disclosure().disclosureSha256().matches("[a-f0-9]{64}"));
        if ("ACCEPTED".equals(value.consentStatus())) require(value.hasActiveConsent() && value.acceptedAt() != null && value.consentRevision() > 0);
        return value;
    }
    /** 读取有界的同书同章历史，分页不携带正文。 */
    public History history(long owner, UUID shelf, UUID chapter, int limit, String cursor, String correlation) {
        var value = exchange(owner, shelf(shelf) + "/chapters/" + chapter + "/adaptations", HttpMethod.GET, null, new Page(limit, cursor), correlation, History.class, 200);
        require(value.items() != null && value.items().size() <= limit && value.items().stream()
                .allMatch(item -> item != null && shelf.equals(item.shelfBookId()) && chapter.equals(item.chapterId()))); return value;
    }
    /** 按需读取被 Reader 明确允许展示的候选正文。 */
    public Output attempt(long owner, UUID id, UUID attempt, String correlation) {
        var value = exchange(owner, adaptation(id) + "/attempts/" + attempt, HttpMethod.GET, null, null, correlation, Output.class, 200);
        require(attempt.equals(value.attemptId())); return value;
    }
    /** 取消不受新建开关影响，不自动再次生成。 */
    public Progress cancel(long owner, UUID id, String correlation) {
        var value = exchange(owner, adaptation(id) + "/cancel", HttpMethod.POST, null, null, correlation, Progress.class, 200);
        require(id.equals(value.adaptationId())); return value;
    }

    private <T> T exchange(long owner, String path, HttpMethod method, Object body, Page page, String correlation, Class<T> type, int expectedStatus) {
        try {
            if (owner < 1 || properties.readerToken() == null || properties.readerToken().isBlank()) throw new GatewayDownstreamException();
            URI root = URI.create(properties.readerUrl());
            if (!("http".equals(root.getScheme()) || "https".equals(root.getScheme())) || root.getHost() == null
                    || root.getUserInfo() != null || root.getQuery() != null || root.getFragment() != null
                    || !(root.getPath().isEmpty() || "/".equals(root.getPath()))) throw new GatewayDownstreamException();
            var builder = UriComponentsBuilder.fromUri(root).replacePath("/api/v1/reader-state" + path).queryParam("ownerId", owner);
            if (page != null) {
                if (page.limit() != null) builder.queryParam("limit", page.limit());
                if (page.cursor() != null) builder.queryParam("cursor", page.cursor());
                if (page.expectedConsentRevision() != null) builder.queryParam("expectedConsentRevision", page.expectedConsentRevision());
            }
            URI uri = builder.build().encode().toUri();
            byte[] payload = body == null ? null : json.writeValueAsBytes(body);
            return http.execute(uri, method, request -> {
                request.getHeaders().setBearerAuth(properties.readerToken());
                request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                request.getHeaders().setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                request.getHeaders().set("Accept-Encoding", "identity");
                request.getHeaders().set("X-Correlation-Id", UUID.fromString(correlation).toString());
                if (payload != null) request.getBody().write(payload);
            }, response -> {
                int status = response.getStatusCode().value();
                int limit = status == expectedStatus ? 2097152 : 8192;
                var media = response.getHeaders().getContentType();
                if (media == null || !MediaType.APPLICATION_JSON.isCompatibleWith(media)
                        || (response.getHeaders().getFirst("Content-Encoding") != null
                        && !"identity".equalsIgnoreCase(response.getHeaders().getFirst("Content-Encoding")))) throw new GatewayDownstreamException();
                byte[] bytes = response.getBody().readNBytes(limit + 1);
                if (bytes.length == 0 || bytes.length > limit) throw new GatewayDownstreamException();
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (status != expectedStatus) {
                    var error = json.readTree(text);
                    String code = error.path("code").asText();
                    if (status >= 400 && status <= 599 && code.matches("READER_[0-9]{3}"))
                        throw new GatewayReaderRejectedException(response.getStatusCode(), code);
                    throw new GatewayDownstreamException();
                }
                var tree = json.readTree(text);
                // 仅补充旧 Reader 历史中缺失的可选字段，不放宽其他响应字段校验。
                if (type == Detail.class) legacyStyle(tree.path("version"));
                if (type == History.class && tree.path("items").isArray())
                    for (var item : tree.path("items")) legacyStyle(item);
                T value = json.treeToValue(tree, type);
                if (value == null) throw new GatewayDownstreamException(); return value;
            });
        } catch (GatewayReaderRejectedException exception) { throw exception; }
        catch (Exception exception) { throw new GatewayDownstreamException(); }
    }

    private static ClientHttpRequestFactory factory(GatewayProperties properties) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setProxy(Proxy.NO_PROXY); factory.setConnectTimeout(Math.max(1, properties.connectTimeoutMillis()));
        factory.setReadTimeout(Math.max(1, properties.readTimeoutMillis())); return factory;
    }
    private static String shelf(UUID id) { return "/shelves/" + id; }
    private static void legacyStyle(com.fasterxml.jackson.databind.JsonNode item) {
        if (item instanceof com.fasterxml.jackson.databind.node.ObjectNode object && !object.has("styleTemplate")) object.putNull("styleTemplate");
    }
    private static String adaptation(UUID id) { return "/chapter-adaptations/" + id; }
    private static void require(boolean valid) { if (!valid) throw new GatewayDownstreamException(); }
    private record Page(Integer limit, String cursor, Long expectedConsentRevision) {
        private Page(int limit, String cursor) { this(limit, cursor, null); }
    }
}
