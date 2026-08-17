package com.yuyutian.mytools.cloudfile.service.impl;

import com.yuyutian.mytools.cloudfile.model.CloudFileItem;
import com.yuyutian.mytools.cloudfile.model.CloudFileListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
public class WebdavClient {

    private static final String PROPFIND_METHOD = "PROPFIND";
    private static final String MKCOL_METHOD = "MKCOL";
    private static final String MOVE_METHOD = "MOVE";
    private static final String COPY_METHOD = "COPY";
    private static final String DELETE_METHOD = "DELETE";
    private static final String DEPTH_HEADER = "Depth";
    private static final String DESTINATION_HEADER = "Destination";

    // DAV namespace URI
    private static final String DAV_NS = "DAV:";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final String urlPathPrefix;

    public WebdavClient(String baseUrl, String username, String password) {
        this.baseUrl = normalizeUrl(baseUrl);
        this.username = username;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.urlPathPrefix = URI.create(this.baseUrl).getPath();
    }

    public CloudFileListResponse list(String path, int depth) throws Exception {
        String url = buildUrl(path);
        String propfindBody = buildPropfindBody();
        HttpResponse<String> response = executePropfind(url, propfindBody, depth);
        checkResponse(response);
        return parsePropfindResponse(response.body(), path);
    }

    public String getContent(String path) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, "GET").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        checkResponse(response);
        return response.body();
    }

    public byte[] getBytes(String path) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, "GET").build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        checkResponse(response);
        return response.body();
    }

    /**
     * 打开远程文件流，并按需向 WebDAV 服务转发单段 Range 请求。
     *
     * @param path 远程文件路径
     * @param rangeHeader 经过校验的 Range 请求头
     * @return 远端流式响应
     * @throws Exception 网络或远端协议错误
     */
    public HttpResponse<InputStream> openStream(String path, String rangeHeader) throws Exception {
        String url = buildUrl(path);
        HttpRequest.Builder builder = newRequest(url, "GET").GET();
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            builder.header(HttpHeaders.RANGE, rangeHeader);
        }
        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if ((response.statusCode() < 200 || response.statusCode() >= 300)
                && response.statusCode() != 416) {
            response.body().close();
            throw new IOException("WebDAV stream failed: HTTP " + response.statusCode());
        }
        return response;
    }

    public CloudFileItem put(String path, byte[] content) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, "PUT")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
        return new CloudFileItem(null, path, false, content.length, null, Instant.now(), null);
    }

    /**
     * 从输入流上传文件。
     *
     * @param path 远程目标路径
     * @param content 文件输入流
     * @param contentLength 文件长度
     * @return 远程文件元数据
     * @throws Exception 网络或协议异常
     */
    public CloudFileItem put(String path, InputStream content, long contentLength) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, "PUT")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> content))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
        return new CloudFileItem(null, path, false, contentLength, null, Instant.now(), null);
    }

    public CloudFileItem put(String path, String content) throws Exception {
        return put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public void mkdir(String path) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, MKCOL_METHOD).PUT(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
    }

    public void delete(String path, boolean recursive) throws Exception {
        if (recursive) {
            CloudFileListResponse list = list(path, 99);
            for (CloudFileItem item : list.getItems()) {
                if (item.isDirectory()) {
                    delete(item.getPath(), true);
                } else {
                    deleteFile(item.getPath());
                }
            }
        }
        deleteFile(path);
    }

    private void deleteFile(String path) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, DELETE_METHOD).DELETE().build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
    }

    public void move(String from, String to) throws Exception {
        String fromUrl = buildUrl(from);
        String toUrl = buildUrl(to);
        HttpRequest request = newRequest(fromUrl, MOVE_METHOD)
                .header(DESTINATION_HEADER, toUrl)
                .header("Overwrite", "T")
                .method(MOVE_METHOD, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
    }

    public void copy(String from, String to) throws Exception {
        String fromUrl = buildUrl(from);
        String toUrl = buildUrl(to);
        HttpRequest request = newRequest(fromUrl, COPY_METHOD)
                .header(DESTINATION_HEADER, toUrl)
                .header("Overwrite", "T")
                .header(DEPTH_HEADER, "infinity")
                .method(COPY_METHOD, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
    }

    // ========== Private helper methods ==========

    private HttpResponse<String> executePropfind(String url, String body, int depth) throws Exception {
        String depthHeader = depth >= 99 ? "infinity" : String.valueOf(depth);
        HttpRequest request = newRequest(url, PROPFIND_METHOD)
                .header(DEPTH_HEADER, depthHeader)
                .header(HttpHeaders.CONTENT_TYPE, "application/xml")
                .method(PROPFIND_METHOD, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder newRequest(String url, String method) {
        String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                .header("X-Requested-With", "MyTools")
                .timeout(Duration.ofSeconds(60));
    }

    private String buildUrl(String path) {
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        return baseUrl + cleanPath;
    }

    private String normalizeUrl(String url) {
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private String buildPropfindBody() {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:displayname/>
                <D:getcontentlength/>
                <D:getcontenttype/>
                <D:getlastmodified/>
                <D:getetag/>
                <D:resourcetype/>
              </D:prop>
            </D:propfind>
            """;
    }

    private CloudFileListResponse parsePropfindResponse(String xmlBody, String parentPath) throws Exception {
        List<CloudFileItem> items = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xmlBody)));
        NodeList responses = doc.getElementsByTagNameNS(DAV_NS, "response");

        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            String href = getElementTextNS(resp, "href");
            if (href == null || href.isEmpty()) continue;

            String itemPath = hrefToPath(href);
            // Skip the parent directory itself
            String normalizedParent = normalizePath(parentPath);
            if (itemPath.equals(normalizedParent)) continue;

            boolean isDir = hasResourcetypeCollection(resp);
            long size = parseLong(getElementTextNS(resp, "getcontentlength"));
            String contentType = getElementTextNS(resp, "getcontenttype");
            String lastModifiedStr = getElementTextNS(resp, "getlastmodified");
            String etag = getElementTextNS(resp, "getetag");
            Instant lastModified = parseHttpDate(lastModifiedStr);
            String name = pathToName(itemPath);

            items.add(new CloudFileItem(name, itemPath, isDir, size, contentType, lastModified, etag));
        }

        return new CloudFileListResponse(parentPath, items);
    }

    private String hrefToPath(String href) {
        try {
            String decoded = URLDecoder.decode(href, StandardCharsets.UTF_8);
            // Strip the urlPathPrefix (e.g., /dav) and the full baseUrl
            if (!urlPathPrefix.isEmpty() && !urlPathPrefix.equals("/") && decoded.startsWith(urlPathPrefix)) {
                decoded = decoded.substring(urlPathPrefix.length());
            }
            if (decoded.startsWith(baseUrl)) {
                decoded = decoded.substring(baseUrl.length());
            }
            return normalizePath(decoded);
        } catch (Exception e) {
            return href;
        }
    }

    private String pathToName(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        path = path.replace("\\", "/");
        while (path.contains("//")) path = path.replace("//", "/");
        if (!path.startsWith("/")) path = "/" + path;
        while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return path.isEmpty() ? "/" : path;
    }

    private String getElementTextNS(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS(DAV_NS, localName);
        if (list.getLength() > 0) return list.item(0).getTextContent();
        return null;
    }

    private boolean hasResourcetypeCollection(Element resp) {
        NodeList list = resp.getElementsByTagNameNS(DAV_NS, "resourcetype");
        for (int i = 0; i < list.getLength(); i++) {
            Element rt = (Element) list.item(i);
            if (rt.getElementsByTagNameNS(DAV_NS, "collection").getLength() > 0) return true;
        }
        return false;
    }

    private long parseLong(String s) {
        try { return s != null ? Long.parseLong(s.trim()) : 0; } catch (Exception e) { return 0; }
    }

    private Instant parseHttpDate(String s) {
        if (s == null) return null;
        try { return Instant.parse(s); } catch (Exception e) {
            try { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (Exception ex) { return null; }
        }
    }

    private void checkResponse(HttpResponse<?> response) throws IOException {
        int status = response.statusCode();
        if (status >= 400) {
            String body = "";
            try { body = response.body().toString(); } catch (Exception ignored) {}
            throw new IOException("WebDAV error " + status + ": " + body);
        }
    }
}
