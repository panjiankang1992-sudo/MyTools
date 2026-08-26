package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeModels;
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
import java.util.List;
import java.util.Map;
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

    /** 创建运行时客户端。 */
    public ReaderRuntimeClient(ObjectMapper objectMapper, ReaderProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 加载图书详情和目录。 */
    public BookSourceRuntimeModels.Catalog catalog(long ownerId, String sourceUrl, String bookUrl,
                                                    Map<String, Object> snapshot) {
        String namespace = prepare(ownerId, sourceUrl, snapshot);
        String sourceQuery = "&bookSourceUrl=" + encode(sourceUrl);
        JsonNode info = send(namespace, "/reader3/getBookInfo?url=" + encode(bookUrl) + sourceQuery,
                "GET", "").path("data");
        JsonNode data = send(namespace, "/reader3/getChapterList?bookUrl=" + encode(bookUrl) + sourceQuery,
                "GET", "").path("data");
        List<BookSourceRuntimeModels.Chapter> chapters = new ArrayList<>();
        if (data.isArray()) {
            int fallbackIndex = 0;
            for (JsonNode item : data) {
                String title = text(item, "title");
                String resourceUri = text(item, "url");
                if (title.isBlank() || resourceUri.isBlank()) continue;
                int index = item.path("index").canConvertToInt() ? item.path("index").asInt() : fallbackIndex;
                chapters.add(new BookSourceRuntimeModels.Chapter(title, resourceUri, Math.max(0, index)));
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
        String query = "?chapterUrl=" + encode(chapterUrl) + "&bookSourceUrl=" + encode(sourceUrl);
        JsonNode data = send(namespace, "/reader3/getBookContent" + query, "GET", "").path("data");
        String content = data.isTextual() ? plainText(data.asText()) : "";
        if (content.isBlank() || BLOCKED_CONTENT.matcher(content).find()) {
            throw new ReaderRuntimeUnavailableException();
        }
        return new BookSourceRuntimeModels.Content("text", content, List.of());
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
