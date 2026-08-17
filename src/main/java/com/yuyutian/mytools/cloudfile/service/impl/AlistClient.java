package com.yuyutian.mytools.cloudfile.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.cloudfile.model.CloudFileItem;
import com.yuyutian.mytools.cloudfile.model.CloudFileListResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class AlistClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String username;
    private String token;
    private final HttpClient httpClient;

    public AlistClient(String baseUrl, String username, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 使用用户名和密码登录，返回新 token。
     */
    public String login(String plainPassword) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "username", username,
                "password", plainPassword
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());

        if (root.has("code") && root.get("code").asInt() == 200) {
            this.token = root.path("data").path("token").asText();
            return this.token;
        }
        throw new IOException("Alist login failed: " + root.path("message").asText("unknown error"));
    }

    /**
     * 列出指定路径下的文件和目录。
     */
    public CloudFileListResponse list(String path) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "path", path.isEmpty() ? "/" : path,
                "password", "",
                "page", 1,
                "per_page", 500
        ));

        HttpResponse<String> response = post("/api/fs/list", body);
        JsonNode root = MAPPER.readTree(response.body());

        if (root.has("code") && root.get("code").asInt() == 200) {
            JsonNode content = root.path("data").path("content");
            List<CloudFileItem> items = new ArrayList<>();
            for (JsonNode item : content) {
                boolean isDir = item.path("is_dir").asBoolean();
                String name = item.path("name").asText();
                String normalizedPath = path.isEmpty() ? "/" : path;
                String itemPath = normalizedPath.equals("/")
                        ? "/" + name
                        : normalizedPath + "/" + name;
                items.add(new CloudFileItem(
                        itemPath,
                        name,
                        isDir,
                        item.path("size").asLong(0),
                        null,
                        parseInstant(item.path("modified").asText(null)),
                        null
                ));
            }
            return new CloudFileListResponse(path.isEmpty() ? "/" : path, items);
        }

        if (response.statusCode() == 401) {
            throw new IOException("TOKEN_EXPIRED");
        }
        throw new IOException("Alist list failed: " + root.path("message").asText("unknown"));
    }

    /**
     * 获取文件的直链 URL，用于预览（图片/文本）。
     */
    public String getRawUrl(String path) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "path", path,
                "password", ""
        ));

        HttpResponse<String> response = post("/api/fs/get", body);
        JsonNode root = MAPPER.readTree(response.body());

        if (root.has("code") && root.get("code").asInt() == 200) {
            String rawUrl = root.path("data").path("raw_url").asText(null);
            if (rawUrl == null || rawUrl.isEmpty()) {
                throw new IOException("No raw_url available for: " + path);
            }
            return rawUrl;
        }

        if (response.statusCode() == 401) {
            throw new IOException("TOKEN_EXPIRED");
        }
        throw new IOException("Alist get failed: " + root.path("message").asText("unknown"));
    }

    /**
     * 通过Alist返回的受控原始地址打开下载流。
     *
     * @param path 远程文件路径
     * @return 原始文件响应流
     * @throws Exception 网络或协议异常
     */
    public HttpResponse<java.io.InputStream> openStream(String path) throws Exception {
        return openStream(path, null);
    }

    /**
     * 通过Alist返回的受控原始地址打开支持单段Range的媒体流。
     *
     * @param path 远程文件路径
     * @param rangeHeader 可选单段Range请求头
     * @return 原始文件响应流
     * @throws Exception 网络或协议异常
     */
    public HttpResponse<java.io.InputStream> openStream(String path, String rangeHeader) throws Exception {
        String rawUrl = getRawUrl(path);
        URI uri = rawUrl.startsWith("http://") || rawUrl.startsWith("https://")
                ? URI.create(rawUrl)
                : URI.create(baseUrl + (rawUrl.startsWith("/") ? rawUrl : "/" + rawUrl));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMinutes(2))
                .GET();
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            requestBuilder.header("Range", rangeHeader);
        }
        HttpResponse<java.io.InputStream> response = httpClient.send(
                requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if ((response.statusCode() < 200 || response.statusCode() >= 300)
                && response.statusCode() != 416) {
            response.body().close();
            throw new IOException("Alist download failed: HTTP " + response.statusCode());
        }
        return response;
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response;
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
