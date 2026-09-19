package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.config.ReaderChapterContentProperties;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeModels;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.ReaderRuntimeInvocation;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository.SourceExecutionSnapshot;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.utils.adaptation.BoundedByteArraySubscriber;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 受控调用 Legado 兼容 Reader Runtime。
 */
@Component
public class ReaderRuntimeClient {
    private static final Pattern BREAK_TAGS = Pattern.compile("(?i)<\\s*(br|/p|/div|/li|/h[1-6])[^>]*>");
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern EXCESSIVE_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern BLOCKED_CONTENT = Pattern.compile(
            "免登录访问次数已达上限|请登录后刷新页面|访问过于频繁|请输入验证码|Access Denied",
            Pattern.CASE_INSENSITIVE);
    private final ObjectMapper objectMapper;
    private final ReaderProperties properties;
    private final HttpClient httpClient;
    private final ReaderChapterContentProperties contentLimits;

    /** 创建运行时客户端。 */
    public ReaderRuntimeClient(ObjectMapper objectMapper, ReaderProperties properties,
                               ReaderChapterContentProperties contentLimits) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.contentLimits = contentLimits;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 加载图书详情和目录。 */
    public BookSourceRuntimeModels.Catalog catalog(long ownerId, String sourceUrl, String bookUrl,
                                                    Map<String, Object> snapshot) {
        String namespace = prepare(ownerId, sourceUrl, snapshot);
        return catalog(namespace, sourceUrl, bookUrl, false);
    }

    /** 按持久化调用身份安装 exact 版本并读取受限目录，地址必须来自可信书架绑定。 */
    public BookSourceRuntimeModels.Catalog catalog(ReaderRuntimeInvocation invocation,
                                                    SourceExecutionSnapshot source, String bookUrl) {
        invocation.requireSnapshot(source);
        requireLocator(bookUrl);
        prepare(invocation.namespace(), source.snapshot());
        return catalog(invocation.namespace(), source.sourceUrl(), bookUrl, true);
    }

    private BookSourceRuntimeModels.Catalog catalog(String namespace, String sourceUrl, String bookUrl,
                                                     boolean bounded) {
        String sourceQuery = "&bookSourceUrl=" + encode(sourceUrl);
        JsonNode info = send(namespace, "/reader3/getBookInfo?url=" + encode(bookUrl) + sourceQuery,
                "GET", "", bounded).path("data");
        JsonNode data = send(namespace, "/reader3/getChapterList?bookUrl=" + encode(bookUrl) + sourceQuery,
                "GET", "", bounded).path("data");
        if (bounded && data.isArray() && data.size() > contentLimits.maximumCatalogChapters()) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CATALOG_TOO_LARGE);
        }
        List<BookSourceRuntimeModels.Chapter> chapters = new ArrayList<>();
        var locators = new HashSet<String>();
        if (data.isArray()) {
            int fallbackIndex = 0;
            for (JsonNode item : data) {
                String title = text(item, "title");
                String resourceUri = text(item, "url");
                if (bounded) {
                    // canonical 目录不能静默丢章或合并同一定位符，以免伪造邻接关系。
                    AdaptationText.requireText(title, 1, 500, ErrorCode.ADAPTATION_CATALOG_TOO_LARGE);
                    requireLocator(resourceUri);
                    if (!locators.add(resourceUri)) {
                        throw new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
                    }
                } else if (title.isBlank() || resourceUri.isBlank()) {
                    continue;
                }
                int index = item.path("index").canConvertToInt() ? item.path("index").asInt() : fallbackIndex;
                // 新目录以有界数组的真实顺序分配位置，忽略运行时自报的重复或负数序号。
                chapters.add(new BookSourceRuntimeModels.Chapter(title, resourceUri, bounded ? fallbackIndex : Math.max(0, index)));
                fallbackIndex++;
            }
        }
        if (chapters.isEmpty()) throw new ReaderRuntimeUnavailableException();
        return new BookSourceRuntimeModels.Catalog(text(info, "name"), text(info, "author"),
                plainText(text(info, "intro")), text(info, "coverUrl"),
                text(info, "latestChapterTitle"), List.copyOf(chapters));
    }

    /** 加载单个章节正文。 */
    public BookSourceRuntimeModels.Content content(long ownerId, String sourceUrl, String chapterUrl,
                                                    Map<String, Object> snapshot) {
        String namespace = prepare(ownerId, sourceUrl, snapshot);
        return content(namespace, sourceUrl, chapterUrl, false);
    }

    /** 按同一书源版本与隔离调用读取完整章节，地址必须来自服务端 canonical locator。 */
    public BookSourceRuntimeModels.Content content(ReaderRuntimeInvocation invocation,
                                                    SourceExecutionSnapshot source, String chapterUrl) {
        invocation.requireSnapshot(source);
        requireLocator(chapterUrl);
        prepare(invocation.namespace(), source.snapshot());
        return content(invocation.namespace(), source.sourceUrl(), chapterUrl, true);
    }

    private BookSourceRuntimeModels.Content content(String namespace, String sourceUrl, String chapterUrl,
                                                     boolean bounded) {
        String query = "?chapterUrl=" + encode(chapterUrl) + "&bookSourceUrl=" + encode(sourceUrl);
        JsonNode data = send(namespace, "/reader3/getBookContent" + query, "GET", "", bounded).path("data");
        String content = data.isTextual() ? plainText(data.asText()) : "";
        if (content.isBlank() || BLOCKED_CONTENT.matcher(content).find()) {
            throw new ReaderRuntimeUnavailableException();
        }
        if (bounded) {
            AdaptationText.requireText(content, 1, contentLimits.maximumChapterCodepoints(), ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
        }
        return new BookSourceRuntimeModels.Content("text", content, List.of());
    }

    private void prepare(String namespace, Map<String, Object> snapshot) {
        try {
            String serialized = objectMapper.writeValueAsString(List.of(snapshot));
            if (serialized.getBytes(StandardCharsets.UTF_8).length > 1048576) {
                throw new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
            }
            send(namespace, "/reader3/deleteAllBookSources", "POST", "", true);
            send(namespace, "/reader3/saveBookSources", "POST", serialized, true);
        } catch (JsonProcessingException exception) {
            throw new ReaderRuntimeUnavailableException();
        }
    }

    private String prepare(long ownerId, String sourceUrl, Map<String, Object> snapshot) {
        String namespace = ownerId + ":reader:" + sha256(sourceUrl).substring(0, 16);
        try {
            send(namespace, "/reader3/deleteAllBookSources", "POST", "");
            send(namespace, "/reader3/saveBookSources", "POST",
                    objectMapper.writeValueAsString(List.of(snapshot)));
            return namespace;
        } catch (JsonProcessingException exception) {
            throw new ReaderRuntimeUnavailableException();
        }
    }

    private JsonNode send(String namespace, String path, String method, String body) {
        if (properties.runtimeSecureKey() == null || properties.runtimeSecureKey().isBlank()) {
            throw new ReaderRuntimeUnavailableException();
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root() + path))
                    .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                    .header("X-Secure-Key", properties.runtimeSecureKey()).header("X-User-NS", namespace);
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json").POST(body.isBlank()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ReaderRuntimeUnavailableException();
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("isSuccess").asBoolean(false)) throw new ReaderRuntimeUnavailableException();
            return root;
        } catch (ReaderRuntimeUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ReaderRuntimeUnavailableException();
        }
    }

    private JsonNode send(String namespace, String path, String method, String body, boolean bounded) {
        if (!bounded) {
            return send(namespace, path, method, body);
        }
        if (properties.runtimeSecureKey() == null || properties.runtimeSecureKey().isBlank()) {
            throw new ReaderRuntimeUnavailableException();
        }
        boolean catalog = path.startsWith("/reader3/getChapterList?");
        boolean chapter = path.startsWith("/reader3/getBookContent?");
        int limit = catalog ? contentLimits.maximumCatalogResponseBytes()
                : chapter ? contentLimits.maximumChapterResponseBytes() : 1048576;
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root() + path))
                    .timeout(Duration.ofSeconds(contentLimits.requestTimeoutSeconds()))
                    .header("Accept", "application/json").header("Accept-Encoding", "identity")
                    .header("X-Secure-Key", properties.runtimeSecureKey()).header("X-User-NS", namespace);
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.GET();
            }
            pending = httpClient.sendAsync(builder.build(), info -> new BoundedByteArraySubscriber(limit));
            // 此等待覆盖整个响应体而不仅是响应头，慢速持续流也不能无限占用线程。
            HttpResponse<byte[]> response = pending.get(contentLimits.requestTimeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !"identity".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse("identity"))) {
                throw new ReaderRuntimeUnavailableException();
            }
            JsonNode root = objectMapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.body());
            if (root == null || !root.isObject() || !root.path("isSuccess").isBoolean() || !root.path("isSuccess").booleanValue()) {
                throw new ReaderRuntimeUnavailableException();
            }
            return root;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReaderRuntimeUnavailableException();
        } catch (ExecutionException exception) {
            // 只保留错误分类，不携带运行时 URL、响应或认证 Header。
            Throwable cause = exception.getCause();
            while (cause != null && !(cause instanceof BoundedByteArraySubscriber.LimitExceededException)) {
                cause = cause.getCause();
            }
            if (cause != null) {
                throw new ChapterAdaptationException(catalog ? ErrorCode.ADAPTATION_CATALOG_TOO_LARGE
                        : ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
            }
            throw new ReaderRuntimeUnavailableException();
        } catch (TimeoutException | JsonProcessingException exception) {
            throw new ReaderRuntimeUnavailableException();
        } catch (java.io.IOException exception) {
            throw new ReaderRuntimeUnavailableException();
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private void requireLocator(String value) {
        AdaptationText.requireText(value, 1, 512, ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        if (value.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        try {
            URI uri = URI.create(value);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
            }
        } catch (IllegalArgumentException exception) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
    }

    private String root() {
        String value = properties.runtimeBaseUrl().trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText("").trim() : "";
    }

    private String plainText(String value) {
        String withLines = BREAK_TAGS.matcher(value).replaceAll("\n");
        String withoutTags = HTML_TAGS.matcher(withLines).replaceAll("");
        String decoded = HtmlUtils.htmlUnescape(withoutTags).replace('\u00a0', ' ')
                .replace("\r\n", "\n").replace('\r', '\n');
        return EXCESSIVE_BLANK_LINES.matcher(decoded).replaceAll("\n\n").trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
