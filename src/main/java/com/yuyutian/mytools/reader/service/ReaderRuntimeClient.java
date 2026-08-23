package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.config.ReaderRuntimeProperties;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeSearchModels;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 仅供后端访问的Legado兼容书源规则执行器客户端。
 */
@Component
public class ReaderRuntimeClient {
    private static final int SOURCE_BATCH_SIZE = 100;
    private static final Pattern BREAK_TAGS = Pattern.compile("(?i)<\\s*(br|/p|/div|/li|/h[1-6])[^>]*>");
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern EXCESSIVE_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern BLOCKED_CONTENT = Pattern.compile(
            "免登录访问次数已达上限|请登录后刷新页面|访问过于频繁|请输入验证码|Access Denied",
            Pattern.CASE_INSENSITIVE);
    private final ObjectMapper objectMapper;
    private final ReaderRuntimeProperties properties;
    private final HttpClient httpClient;

    /**
     * 创建书源规则执行器客户端。
     *
     * @param objectMapper JSON转换器
     * @param properties 执行器配置
     */
    public ReaderRuntimeClient(ObjectMapper objectMapper, ReaderRuntimeProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 用数据库中的有效快照完整替换指定用户在执行器中的书源。
     *
     * @param userId 用户ID
     * @param sources 有效书源JSON
     */
    public void replaceSources(Long userId, List<JsonNode> sources) {
        ensureEnabled();
        send(userId, "/reader3/deleteAllBookSources", "POST", "");
        for (int offset = 0; offset < sources.size(); offset += SOURCE_BATCH_SIZE) {
            int end = Math.min(sources.size(), offset + SOURCE_BATCH_SIZE);
            try {
                send(userId, "/reader3/saveBookSources", "POST",
                        objectMapper.writeValueAsString(sources.subList(offset, end)));
            } catch (Exception exception) {
                throw runtimeFailure();
            }
        }
    }

    /**
     * 在单个用户书源中执行搜索规则。
     *
     * @param userId 用户ID
     * @param sourceUrl 书源地址
     * @param sourceName 书源名称
     * @param keyword 关键词
     * @param page 页码
     * @return 搜索结果
     */
    public List<BookSourceRuntimeSearchModels.SearchResult> search(Long userId, String sourceUrl,
                                                                   String sourceName, String keyword, int page) {
        String path = "/reader3/searchBook?key=" + encode(keyword) + "&page=" + page
                + "&bookSourceUrl=" + encode(sourceUrl);
        JsonNode data = send(userId, path, "GET", "").path("data");
        List<BookSourceRuntimeSearchModels.SearchResult> output = new ArrayList<>();
        if (!data.isArray()) return output;
        for (JsonNode item : data) {
            String bookUrl = text(item, "bookUrl");
            String name = text(item, "name");
            if (bookUrl.isBlank() || name.isBlank()) continue;
            String origin = text(item, "origin");
            output.add(new BookSourceRuntimeSearchModels.SearchResult(name, text(item, "author"),
                    text(item, "intro"), text(item, "lastChapter"), text(item, "coverUrl"), bookUrl,
                    origin.isBlank() ? sourceUrl : origin, sourceName));
        }
        return output;
    }

    /**
     * 执行图书详情和目录规则。
     *
     * @param userId 用户ID
     * @param sourceUrl 书源地址
     * @param bookUrl 图书地址
     * @return 图书目录
     */
    public BookSourceRuntimeReaderModels.Catalog catalog(Long userId, String sourceUrl, String bookUrl) {
        String sourceQuery = "&bookSourceUrl=" + encode(sourceUrl);
        JsonNode info = send(userId, "/reader3/getBookInfo?url=" + encode(bookUrl) + sourceQuery,
                "GET", "").path("data");
        JsonNode data = send(userId, "/reader3/getChapterList?bookUrl=" + encode(bookUrl) + sourceQuery,
                "GET", "").path("data");
        List<BookSourceRuntimeReaderModels.Chapter> chapters = new ArrayList<>();
        if (data.isArray()) {
            int fallbackIndex = 0;
            for (JsonNode item : data) {
                String title = text(item, "title");
                String resourceUri = text(item, "url");
                if (title.isBlank() || resourceUri.isBlank()) continue;
                int index = item.path("index").canConvertToInt() ? item.path("index").asInt() : fallbackIndex;
                chapters.add(new BookSourceRuntimeReaderModels.Chapter(title, resourceUri, Math.max(0, index)));
                fallbackIndex++;
            }
        }
        if (chapters.isEmpty()) throw unreadableSource();
        return new BookSourceRuntimeReaderModels.Catalog(text(info, "name"), text(info, "author"),
                plainText(text(info, "intro")), text(info, "coverUrl"), text(info, "latestChapterTitle"),
                List.copyOf(chapters));
    }

    /**
     * 执行章节正文规则并清理为阅读器可分页的纯文本。
     *
     * @param userId 用户ID
     * @param sourceUrl 书源地址
     * @param chapterUrl 章节地址
     * @param chapterIndex 章节序号
     * @return 章节内容
     */
    public BookSourceRuntimeReaderModels.Content content(Long userId, String sourceUrl,
                                                          String chapterUrl, int chapterIndex) {
        String query = "?chapterUrl=" + encode(chapterUrl) + "&bookSourceUrl=" + encode(sourceUrl);
        JsonNode data = send(userId, "/reader3/getBookContent" + query, "GET", "").path("data");
        String content = data.isTextual() ? plainText(data.asText()) : "";
        if (content.isBlank() || BLOCKED_CONTENT.matcher(content).find()) throw unreadableSource();
        return new BookSourceRuntimeReaderModels.Content("text", content, List.of());
    }

    private JsonNode send(Long userId, String path, String method, String body) {
        ensureEnabled();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(normalizedBaseUrl() + path))
                    .timeout(Duration.ofSeconds(Math.max(3, properties.getRequestTimeoutSeconds())))
                    .header("Accept", "application/json")
                    .header("X-Secure-Key", properties.getSecureKey())
                    .header("X-User-NS", String.valueOf(userId));
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(body.isBlank() ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Reader runtime HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("isSuccess").asBoolean(false)) {
                throw new IllegalStateException(root.path("errorMsg").asText("Reader runtime rejected request"));
            }
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw runtimeFailure();
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled() || properties.getSecureKey() == null || properties.getSecureKey().isBlank()) {
            throw new BusinessException(ErrorCode.READER_009);
        }
    }

    private String normalizedBaseUrl() {
        String value = properties.getBaseUrl().trim();
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

    private BusinessException runtimeFailure() {
        return new BusinessException(ErrorCode.READER_009);
    }

    private BusinessException unreadableSource() {
        return new BusinessException(ErrorCode.READER_011);
    }
}
