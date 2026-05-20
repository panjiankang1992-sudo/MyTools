# 云端文件浏览器 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现"云端文件"功能——通过 WebDAV 协议浏览、编辑、管理用户云盘中的文件。

**Architecture:** 后端代理模式。前端通过后端 `/api/cloud/**` 接口操作云盘，后端持有解密后的 WebDAV 密码（Basic Auth），使用 Java 11 内置 `HttpClient` 实现 WebDAV PROPFIND/GET/PUT/MKCOL/DELETE/MOVE/COPY 等协议方法。前端无需知道密码，无 CORS 问题。

**Tech Stack:** Spring Boot 3 + Java 21 `HttpClient` + Vue3 + TypeScript + NaiveUI + Monaco Editor

---

## 文件结构

```
src/main/java/com/yuyutian/mytools/cloudfile/
├── controller/CloudFileController.java
├── service/
│   ├── CloudFileService.java
│   └── impl/
│       ├── CloudFileServiceImpl.java
│       └── WebdavClient.java          ← 核心：WebDAV 协议封装
├── model/
│   ├── CloudFileItem.java
│   ├── CloudFileListResponse.java
│   ├── FileOperationRequest.java
│   └── FileOperationResponse.java

webapp/src/
├── views/cloudfile/
│   ├── index.vue                     ← 主页面（目录树 + 文件列表）
│   ├── CloudFileEditor.vue           ← Monaco Editor 弹窗
│   └── CloudFileUpload.vue           ← 上传组件
├── store/modules/cloudfile/
│   └── index.ts
├── service/api/cloudfile.ts
└── typings/api/cloudfile.d.ts

router/elegant/routes.ts             ← 添加 /cloud-file 路由
messages_zh_CN.properties            ← 国际化
messages_en.properties
```

---

## Task 1: 后端 - DTO 模型类

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/model/CloudFileItem.java`
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/model/CloudFileListResponse.java`
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/model/FileOperationRequest.java`
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/model/FileOperationResponse.java`

- [ ] **Step 1: 创建 CloudFileItem.java**

```java
package com.yuyutian.mytools.cloudfile.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudFileItem {

    private String name;

    private String path;

    private boolean isDirectory;

    private long size;

    @JsonProperty("contentType")
    private String contentType;

    private Instant lastModified;

    private String etag;
}
```

- [ ] **Step 2: 创建 CloudFileListResponse.java**

```java
package com.yuyutian.mytools.cloudfile.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudFileListResponse {

    private String path;

    private List<CloudFileItem> items;
}
```

- [ ] **Step 3: 创建 FileOperationRequest.java**

```java
package com.yuyutian.mytools.cloudfile.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileOperationRequest {

    @NotBlank(message = "path.notBlank")
    private String path;

    /** 用于重命名 */
    private String newName;

    /** 用于移动/复制目标路径 */
    private String to;

    /** 移动/复制来源路径 */
    private String from;

    /** 递归删除目录 */
    private Boolean recursive = false;
}
```

- [ ] **Step 4: 创建 FileOperationResponse.java**

```java
package com.yuyutian.mytools.cloudfile.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileOperationResponse {
    private String name;
    private String path;
    private long size;
    private Instant lastModified;
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/model/ && git commit -m "feat(cloudfile): add DTO model classes"
```

---

## Task 2: 后端 - WebdavClient（核心协议封装）

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/service/impl/WebdavClient.java`

> WebDAV 使用 Java 11 内置 `HttpClient`，无需额外依赖。

- [ ] **Step 1: 创建 WebdavClient.java**

```java
package com.yuyutian.mytools.cloudfile.service.impl;

import com.yuyutian.mytools.cloudfile.model.CloudFileItem;
import com.yuyutian.mytools.cloudfile.model.CloudFileListResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
public class WebdavClient {

    private static final String PROPFIND_METHOD = "PROPFIND";
    private static final String MKCOL_METHOD = "MKCOL";
    private static final String MOVE_METHOD = "MOVE";
    private static final String COPY_METHOD = "COPY";
    private static final String DELETE_METHOD = "DELETE";
    private static final String DEPTH_HEADER = "Depth";
    private static final String DESTINATION_HEADER = "Destination";
    private static final String IF_HEADER = "If";
    private static final Pattern HREF_PATTERN = Pattern.compile("<D:href>(.*?)</D:href>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESourcetype_PATTERN = Pattern.compile("<D:resourcetype><D:collection/></D:resourcetype>", Pattern.CASE_INSENSITIVE);
    private static final Pattern GETCONTENTLENGTH_PATTERN = Pattern.compile("<D:getcontentlength>(.*?)</D:getcontentlength>", Pattern.CASE_INSENSITIVE);
    private static final Pattern GETCONTENTTYPE_PATTERN = Pattern.compile("<D:getcontenttype>(.*?)</D:getcontenttype>", Pattern.CASE_INSENSITIVE);
    private static final Pattern GETLASTMODIFIED_PATTERN = Pattern.compile("<D:getlastmodified>(.*?)</D:getlastmodified>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETAG_PATTERN = Pattern.compile("<D:getetag>(.*?)</D:getetag>", Pattern.CASE_INSENSITIVE);

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;

    public WebdavClient(String baseUrl, String username, String password) {
        this.baseUrl = normalizeUrl(baseUrl);
        this.username = username;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public CloudFileListResponse list(String path, int depth) throws Exception {
        String url = buildUrl(path);
        String propfindBody = buildPropfindBody(depth);
        HttpResponse<String> response = executePropfind(url, propfindBody, depth);
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

    public CloudFileItem put(String path, byte[] content) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, "PUT")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
        return new CloudFileItem(null, path, false, content.length, null, Instant.now(), null);
    }

    public void mkdir(String path) throws Exception {
        String url = buildUrl(path);
        HttpRequest request = newRequest(url, MKCOL_METHOD).PUT(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        checkResponse(response);
    }

    public void delete(String path, boolean recursive) throws Exception {
        if (recursive) {
            // 递归删除：先列出所有文件，再逐个删除，最后删目录
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

    // ========== 内部方法 ==========

    private HttpResponse<String> executePropfind(String url, String body, int depth) throws Exception {
        HttpRequest request = newRequest(url, PROPFIND_METHOD)
                .header(DEPTH_HEADER, depth == 99 ? "infinity" : String.valueOf(depth))
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
        String encodedPath = path.startsWith("/") ? path : "/" + path;
        return baseUrl + encodedPath;
    }

    private String normalizeUrl(String url) {
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private String buildPropfindBody(int depth) {
        // 请求 propfind 默认属性
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
        NodeList responses = doc.getElementsByTagName("D:response");

        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            String href = getElementText(resp, "D:href");
            if (href == null || href.isEmpty()) continue;

            String path = hrefToPath(href);
            if (path.equals(parentPath) || path.equals(parentPath + "/")) continue;

            boolean isDir = hasResourcetypeCollection(resp);
            long size = parseLong(getElementText(resp, "D:getcontentlength"));
            String contentType = getElementText(resp, "D:getcontenttype");
            String lastModifiedStr = getElementText(resp, "D:getlastmodified");
            String etag = getElementText(resp, "D:getetag");
            Instant lastModified = parseHttpDate(lastModifiedStr);
            String name = pathToName(path);

            items.add(new CloudFileItem(name, path, isDir, size, contentType, lastModified, etag));
        }

        return new CloudFileListResponse(parentPath, items);
    }

    private String hrefToPath(String href) {
        try {
            String path = java.net.URLDecoder.decode(href, StandardCharsets.UTF_8);
            if (path.startsWith(baseUrl)) {
                path = path.substring(baseUrl.length());
            }
            return normalizePath(path);
        } catch (Exception e) {
            return href;
        }
    }

    private String pathToName(String path) {
        path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        path = path.replace("\\", "/");
        while (path.contains("//")) path = path.replace("//", "/");
        if (!path.startsWith("/")) path = "/" + path;
        while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return path.isEmpty() ? "/" : path;
    }

    private String getElementText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return null;
    }

    private boolean hasResourcetypeCollection(Element resp) {
        NodeList list = resp.getElementsByTagName("D:resourcetype");
        for (int i = 0; i < list.getLength(); i++) {
            Element rt = (Element) list.item(i);
            if (rt.getElementsByTagName("D:collection").getLength() > 0) {
                return true;
            }
        }
        return false;
    }

    private long parseLong(String s) {
        try { return s != null ? Long.parseLong(s.trim()) : 0; } catch (Exception e) { return 0; }
    }

    private Instant parseHttpDate(String s) {
        if (s == null) return null;
        try { return Instant.parse(s); } catch (Exception e) {
            try { return java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (Exception ex) { return null; }
        }
    }

    private void checkResponse(HttpResponse<?> response) throws IOException {
        int status = response.statusCode();
        if (status >= 400) {
            throw new IOException("WebDAV error: " + status + " " + (response.body() != null ? response.body().toString() : ""));
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/service/impl/WebdavClient.java && git commit -m "feat(cloudfile): add WebdavClient with PROPFIND/GET/PUT/MKCOL/DELETE/MOVE/COPY"
```

---

## Task 3: 后端 - CloudFileService 接口与实现

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/service/CloudFileService.java`
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/service/impl/CloudFileServiceImpl.java`

> 核心职责：从数据库取出 WebDAV 配置（加密密码）→ 解密 → 创建 WebdavClient → 委托执行操作。

- [ ] **Step 1: 创建 CloudFileService.java**

```java
package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.*;

public interface CloudFileService {

    /** 列出目录 */
    CloudFileListResponse listFiles(Long userId, String path, int depth);

    /** 获取文件内容（文本预览） */
    String getFileContent(Long userId, String path);

    /** 下载文件（字节流） */
    byte[] downloadFile(Long userId, String path);

    /** 上传文件 */
    FileOperationResponse uploadFile(Long userId, String dirPath, String filename, byte[] content);

    /** 创建目录 */
    void createDirectory(Long userId, String path);

    /** 重命名 */
    void rename(Long userId, String path, String newName);

    /** 移动 */
    void move(Long userId, String from, String to);

    /** 复制 */
    void copy(Long userId, String from, String to);

    /** 删除 */
    void delete(Long userId, String path, boolean recursive);
}
```

- [ ] **Step 2: 创建 CloudFileServiceImpl.java**

```java
package com.yuyutian.mytools.cloudfile.service.impl;

import com.yuyutian.mytools.cloudfile.model.*;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.utils.AesEncryptUtils;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.WebdavAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudFileServiceImpl implements CloudFileService {

    private static final String AES_KEY = "CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8=";
    private static final Pattern TEXT_MIME_PATTERN = Pattern.compile(
            "^(text/|application/(json|xml|javascript|x-javascript|xhtml|ecmascript|typescript))|.*\\.(txt|md|json|xml|html|htm|css|js|ts|py|java|cpp|c|h|sh|yaml|yml|properties)$",
            Pattern.CASE_INSENSITIVE);

    private final WebdavAccountMapper webdavAccountMapper;

    @Override
    public CloudFileListResponse listFiles(Long userId, String path, int depth) {
        WebdavClient client = buildClient(userId);
        try {
            return client.list(path, depth);
        } catch (Exception e) {
            throw new BusinessException("50001", "无法连接到云盘服务: " + e.getMessage());
        }
    }

    @Override
    public String getFileContent(Long userId, String path) {
        WebdavClient client = buildClient(userId);
        try {
            String contentType = detectTextFile(path) ? "text/plain" : null;
            if (contentType == null) {
                throw new BusinessException("50001", "不支持预览该类型文件");
            }
            return client.getContent(path);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("50001", "读取文件失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] downloadFile(Long userId, String path) {
        WebdavClient client = buildClient(userId);
        try {
            return client.getBytes(path);
        } catch (Exception e) {
            throw new BusinessException("50001", "下载文件失败: " + e.getMessage());
        }
    }

    @Override
    public FileOperationResponse uploadFile(Long userId, String dirPath, String filename, byte[] content) {
        WebdavClient client = buildClient(userId);
        String path = (dirPath.endsWith("/") ? dirPath : dirPath + "/") + filename;
        try {
            CloudFileItem item = client.put(path, content);
            return new FileOperationResponse(item.getName(), item.getPath(), item.getSize(), item.getLastModified());
        } catch (Exception e) {
            throw new BusinessException("50001", "上传文件失败: " + e.getMessage());
        }
    }

    @Override
    public void createDirectory(Long userId, String path) {
        WebdavClient client = buildClient(userId);
        try {
            client.mkdir(path);
        } catch (Exception e) {
            throw new BusinessException("50001", "创建目录失败: " + e.getMessage());
        }
    }

    @Override
    public void rename(Long userId, String path, String newName) {
        String parent = path.substring(0, path.lastIndexOf('/'));
        String newPath = (parent.isEmpty() ? "/" : parent) + "/" + newName;
        move(userId, path, newPath);
    }

    @Override
    public void move(Long userId, String from, String to) {
        WebdavClient client = buildClient(userId);
        try {
            client.move(from, to);
        } catch (Exception e) {
            throw new BusinessException("50001", "移动失败: " + e.getMessage());
        }
    }

    @Override
    public void copy(Long userId, String from, String to) {
        WebdavClient client = buildClient(userId);
        try {
            client.copy(from, to);
        } catch (Exception e) {
            throw new BusinessException("50001", "复制失败: " + e.getMessage());
        }
    }

    @Override
    public void delete(Long userId, String path, boolean recursive) {
        WebdavClient client = buildClient(userId);
        try {
            client.delete(path, recursive);
        } catch (Exception e) {
            throw new BusinessException("50001", "删除失败: " + e.getMessage());
        }
    }

    private WebdavClient buildClient(Long userId) {
        WebdavAccount account = webdavAccountMapper.selectByUserId(userId);
        if (account == null) {
            throw new BusinessException("40001", "请先在个人信息中配置 WebDAV");
        }
        String password = "";
        if (account.getPassword() != null && !account.getPassword().isBlank()) {
            try {
                password = AesEncryptUtils.decrypt(account.getPassword(), AES_KEY);
            } catch (Exception e) {
                log.error("Failed to decrypt WebDAV password for user {}", userId);
                throw new BusinessException("50001", "WebDAV 配置无效");
            }
        }
        return new WebdavClient(account.getUrl(), account.getUsername(), password);
    }

    private boolean detectTextFile(String path) {
        String name = path.toLowerCase();
        if (name.contains(".")) {
            String ext = name.substring(name.lastIndexOf('.'));
            return TEXT_MIME_PATTERN.matcher("file" + ext).matches();
        }
        return false;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/service/ && git commit -m "feat(cloudfile): add CloudFileService with WebDAV integration"
```

---

## Task 4: 后端 - CloudFileController

**Files:**
- Create: `src/main/java/com/yuyutian/mytools/cloudfile/controller/CloudFileController.java`

- [ ] **Step 1: 创建 CloudFileController.java**

```java
package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.cloudfile.model.*;
import com.yuyutian.mytools.common.MessageHelper;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
public class CloudFileController {

    private final CloudFileService cloudFileService;
    private final JwtUtils jwtUtils;

    /**
     * 列出目录内容
     * GET /api/cloud/files?path=/&depth=1
     */
    @GetMapping("/api/cloud/files")
    public ResponseEntity<Result<CloudFileListResponse>> listFiles(
            @RequestHeader("Authorization") String auth,
            @RequestParam(value = "path", defaultValue = "/") String path,
            @RequestParam(value = "depth", defaultValue = "1") int depth) {

        Long userId = resolveUserId(auth);
        CloudFileListResponse resp = cloudFileService.listFiles(userId, decode(path), depth);
        return ResponseEntity.ok(Result.success(resp));
    }

    /**
     * 下载或预览文件
     * GET /api/cloud/file?path=/docs/readme.md&preview=true
     */
    @GetMapping("/api/cloud/file")
    public ResponseEntity<?> getFile(
            @RequestHeader("Authorization") String auth,
            @RequestParam("path") String path,
            @RequestParam(value = "preview", defaultValue = "false") boolean preview) {

        Long userId = resolveUserId(auth);
        String decodedPath = decode(path);

        if (preview) {
            String content = cloudFileService.getFileContent(userId, decodedPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } else {
            byte[] bytes = cloudFileService.downloadFile(userId, decodedPath);
            String filename = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .body(new ByteArrayResource(bytes));
        }
    }

    /**
     * 上传文件
     * POST /api/cloud/file (multipart/form-data: file, path, filename)
     */
    @PostMapping("/api/cloud/file")
    public ResponseEntity<Result<FileOperationResponse>> uploadFile(
            @RequestHeader("Authorization") String auth,
            @RequestParam("file") MultipartFile file,
            @RequestParam("path") String dirPath,
            @RequestParam(value = "filename", required = false) String filename) {

        Long userId = resolveUserId(auth);
        String targetFilename = (filename != null && !filename.isBlank()) ? filename : file.getOriginalFilename();
        try {
            FileOperationResponse resp = cloudFileService.uploadFile(
                    userId, decode(dirPath), targetFilename, file.getBytes());
            return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), resp));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Result.error("50001", e.getMessage()));
        }
    }

    /**
     * 创建目录
     * POST /api/cloud/dir
     */
    @PostMapping("/api/cloud/dir")
    public ResponseEntity<Result<Void>> createDir(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request) {

        Long userId = resolveUserId(auth);
        cloudFileService.createDirectory(userId, decode(request.getPath()));
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    /**
     * 重命名
     * POST /api/cloud/rename
     */
    @PostMapping("/api/cloud/rename")
    public ResponseEntity<Result<Void>> rename(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request) {

        Long userId = resolveUserId(auth);
        cloudFileService.rename(userId, decode(request.getPath()), request.getNewName());
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    /**
     * 移动
     * POST /api/cloud/move
     */
    @PostMapping("/api/cloud/move")
    public ResponseEntity<Result<Void>> move(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request) {

        Long userId = resolveUserId(auth);
        cloudFileService.move(userId, decode(request.getFrom()), decode(request.getTo()));
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    /**
     * 复制
     * POST /api/cloud/copy
     */
    @PostMapping("/api/cloud/copy")
    public ResponseEntity<Result<Void>> copy(
            @RequestHeader("Authorization") String auth,
            @RequestBody FileOperationRequest request) {

        Long userId = resolveUserId(auth);
        cloudFileService.copy(userId, decode(request.getFrom()), decode(request.getTo()));
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    /**
     * 删除
     * DELETE /api/cloud/file?path=/docs/readme.md&recursive=false
     */
    @DeleteMapping("/api/cloud/file")
    public ResponseEntity<Result<Void>> delete(
            @RequestHeader("Authorization") String auth,
            @RequestParam("path") String path,
            @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {

        Long userId = resolveUserId(auth);
        cloudFileService.delete(userId, decode(path), recursive);
        return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
    }

    private Long resolveUserId(String auth) {
        String token = extractToken(auth);
        return jwtUtils.getUserIdFromToken(token);
    }

    private String extractToken(String auth) {
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return auth;
    }

    private String decode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/controller/ && git commit -m "feat(cloudfile): add CloudFileController with all CRUD endpoints"
```

---

## Task 5: 后端 - SecurityConfig（认证配置）

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/config/SecurityConfig.java`

> 在 `.requestMatchers("/api/cloud/**").authenticated()` 添加一行。确认 `/api/cloud/**` 默认走登录认证。

- [ ] **Step 1: 添加 SecurityConfig 规则**

在现有 `.requestMatchers("/api/cloud/**")` 行之后确认规则。`/api/auth/**` 已是 `permitAll`，`/api/cloud/**` 未声明则默认走 `authenticated()`（无需修改）。但为了明确，可显式添加一行：

在 `SecurityConfig.java` 的 permitAll 块之后，找到 authenticated 块（`anyRequest().authenticated()`），`/api/cloud/**` 会自动落入此规则，无需改动。

- [ ] **Step 2: Commit**

```bash
git commit -m "feat(cloudfile): ensure /api/cloud/** requires authentication (default)"
```

---

## Task 6: 前端 - API 服务层

**Files:**
- Create: `webapp/src/service/api/cloudfile.ts`
- Create: `webapp/src/typings/api/cloudfile.d.ts`

- [ ] **Step 1: 创建 typings**

```typescript
// webapp/src/typings/api/cloudfile.d.ts
declare namespace Api {
  namespace CloudFile {
    interface CloudFileItem {
      name: string;
      path: string;
      isDirectory: boolean;
      size: number;
      contentType: string | null;
      lastModified: string | null;
      etag: string | null;
    }

    interface CloudFileListResponse {
      path: string;
      items: CloudFileItem[];
    }

    interface FileOperationResponse {
      name: string;
      path: string;
      size: number;
      lastModified: string;
    }

    interface FileOperationRequest {
      path?: string;
      newName?: string;
      from?: string;
      to?: string;
      recursive?: boolean;
    }
  }
}
```

- [ ] **Step 2: 创建 cloudfile.ts**

```typescript
// webapp/src/service/api/cloudfile.ts
import { request } from '../request';

/** 列出目录 */
export function fetchCloudFiles(path = '/', depth = 1) {
  const encoded = encodeURIComponent(path);
  return request<Api.CloudFile.CloudFileListResponse>(
    `/api/cloud/files?path=${encoded}&depth=${depth}`
  );
}

/** 获取文件内容（文本预览） */
export function fetchFileContent(path: string) {
  const encoded = encodeURIComponent(path);
  return request<string>(`/api/cloud/file?path=${encoded}&preview=true`, {
    responseType: 'text'
  });
}

/** 下载文件 */
export function downloadCloudFile(path: string) {
  const encoded = encodeURIComponent(path);
  return request<Blob>(`/api/cloud/file?path=${encoded}`, {
    responseType: 'blob'
  });
}

/** 上传文件 */
export function uploadCloudFile(dirPath: string, filename: string, file: File | Blob) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('path', dirPath);
  formData.append('filename', filename);
  return request<Api.CloudFile.FileOperationResponse>('/api/cloud/file', {
    method: 'POST',
    data: formData
  });
}

/** 创建目录 */
export function createCloudDir(path: string) {
  return request('/api/cloud/dir', {
    method: 'POST',
    data: { path }
  });
}

/** 重命名 */
export function renameCloudFile(path: string, newName: string) {
  return request('/api/cloud/rename', {
    method: 'POST',
    data: { path, newName }
  });
}

/** 移动 */
export function moveCloudFile(from: string, to: string) {
  return request('/api/cloud/move', {
    method: 'POST',
    data: { from, to }
  });
}

/** 复制 */
export function copyCloudFile(from: string, to: string) {
  return request('/api/cloud/copy', {
    method: 'POST',
    data: { from, to }
  });
}

/** 删除 */
export function deleteCloudFile(path: string, recursive = false) {
  const encoded = encodeURIComponent(path);
  return request(`/api/cloud/file?path=${encoded}&recursive=${recursive}`, {
    method: 'DELETE'
  });
}
```

- [ ] **Step 3: Commit**

```bash
git add webapp/src/service/api/cloudfile.ts webapp/src/typings/api/cloudfile.d.ts && git commit -m "feat(cloudfile): add frontend API service and TypeScript types"
```

---

## Task 7: 前端 - Pinia Store

**Files:**
- Create: `webapp/src/store/modules/cloudfile/index.ts`

- [ ] **Step 1: 创建 Store**

```typescript
// webapp/src/store/modules/cloudfile/index.ts
import { defineStore } from 'pinia';
import { fetchCloudFiles } from '@/service/api/cloudfile';
import type { CloudFileItem } from '@/typings/api/cloudfile';

interface CloudFileTreeNode {
  key: string;
  label: string;
  isLeaf: boolean;
  children?: CloudFileTreeNode[];
  loading?: boolean;
}

interface CloudFileState {
  currentPath: string;
  items: CloudFileItem[];
  treeData: CloudFileTreeNode[];
  loading: boolean;
}

export const useCloudFileStore = defineStore('cloudfile', {
  state: (): CloudFileState => ({
    currentPath: '/',
    items: [],
    treeData: [],
    loading: false
  }),

  actions: {
    async loadFiles(path: string, depth = 1) {
      this.loading = true;
      try {
        const { data } = await fetchCloudFiles(path, depth);
        this.currentPath = path;
        this.items = data.items || [];
        if (depth > 1) {
          this.updateTreeChildren(path, data.items.filter((i: CloudFileItem) => i.isDirectory));
        }
      } finally {
        this.loading = false;
      }
    },

    updateTreeChildren(parentPath: string, dirs: CloudFileItem[]) {
      const updateNode = (nodes: CloudFileTreeNode[]): boolean => {
        for (const node of nodes) {
          if (node.key === parentPath) {
            node.children = dirs.map(d => ({
              key: d.path,
              label: d.name,
              isLeaf: false,
              children: []
            }));
            return true;
          }
          if (node.children && updateNode(node.children)) return true;
        }
        return false;
      };
      updateNode(this.treeData);
    },

    buildTree(dirs: CloudFileItem[]) {
      this.treeData = dirs.map(d => ({
        key: d.path,
        label: d.name,
        isLeaf: false,
        children: []
      }));
    }
  }
});
```

- [ ] **Step 2: Commit**

```bash
git add webapp/src/store/modules/cloudfile/ && git commit -m "feat(cloudfile): add Pinia store for cloudfile state"
```

---

## Task 8: 前端 - 主页面（目录树 + 文件列表）

**Files:**
- Create: `webapp/src/views/cloudfile/index.vue`

> 基于 NaiveUI NTree + NDataTable + NModal + NDropdown 实现。

- [ ] **Step 1: 创建 index.vue**

```vue
<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue';
import {
  NLayout, NLayoutSider, NLayoutContent, NBreadcrumb,
  NButton, NDataTable, NTree, NSpace, NPopconfirm,
  NModal, NInput, NUpload, NUploadDragger, NMessageProvider,
  NIcon, useMessage, useDialog, NDropdown, NTag, NEmpty, NSpin
} from 'naive-ui';
import {
  FolderOpenOutline, DocumentTextOutline, ImageOutline,
  VideocamOutline, MusicNoteOutline, GameControllerOutline,
  CloudUploadOutline, FolderOutline, RefreshOutline,
  CreateOutline, TrashOutline, DownloadOutline, CopyOutline,
  ReturnLeftOutline, ChevronRightOutline
} from '@vicons/ionicons5';
import { useCloudFileStore } from '@/store/modules/cloudfile';
import { fetchCloudFiles, createCloudDir, renameCloudFile,
  deleteCloudFile, moveCloudFile, copyCloudFile,
  uploadCloudFile, downloadCloudFile } from '@/service/api/cloudfile';
import type { CloudFileItem } from '@/typings/api/cloudfile';

const message = useMessage();
const store = useCloudFileStore();
const router = useRouter();

// 状态
const showMkdirModal = ref(false);
const showRenameModal = ref(false);
const showMoveModal = ref(false);
const mkdirName = ref('');
const renameName = ref('');
const moveTargetPath = ref('');
const uploading = ref(false);

// Monaco Editor 弹窗（懒加载）
const showEditor = ref(false);
const editorPath = ref('');
const editorContent = ref('');
const editorLoading = ref(false);

// 工具栏
const newDirInput = ref('');

// 文件图标映射
function getFileIcon(item: CloudFileItem) {
  if (item.isDirectory) return FolderOutline;
  const ext = item.name.split('.').pop()?.toLowerCase() || '';
  const iconMap: Record<string, any> = {
    md: DocumentTextOutline, txt: DocumentTextOutline,
    jpg: ImageOutline, png: ImageOutline, gif: ImageOutline, svg: ImageOutline,
    mp4: VideocamOutline, avi: VideocamOutline, mov: VideocamOutline,
    mp3: MusicNoteOutline, wav: MusicNoteOutline,
    exe: GameControllerOutline, zip: GameControllerOutline,
  };
  return iconMap[ext] || DocumentTextOutline;
}

// 格式化大小
function formatSize(bytes: number): string {
  if (bytes === 0) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// 格式化时间
function formatDate(iso: string | null): string {
  if (!iso) return '-';
  try { return new Date(iso).toLocaleString('zh-CN'); } catch { return '-'; }
}

// 面包屑
const breadcrumbs = computed(() => {
  const parts = store.currentPath.split('/').filter(Boolean);
  return [{ name: 'root', path: '/' }, ...parts.map((p, i) => ({
    name: p,
    path: '/' + parts.slice(0, i + 1).join('/')
  }))];
});

function navigateTo(path: string) {
  store.loadFiles(path, 1);
}

// 列定义
const columns = [
  {
    title: '名称',
    key: 'name',
    render(row: CloudFileItem) {
      return h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }, onClick: () => row.isDirectory ? navigateTo(row.path) : openFile(row) }, [
        h(NIcon, { size: 18, color: row.isDirectory ? '#f0a020' : '#666' }, { default: () => h(getFileIcon(row)) }),
        h('span', { style: { color: row.isDirectory ? '#e6a23c' : 'inherit' } }, row.name)
      ]);
    }
  },
  { title: '大小', key: 'size', width: 100, render: (row: CloudFileItem) => formatSize(row.size) },
  { title: '修改时间', key: 'lastModified', width: 180, render: (row: CloudFileItem) => formatDate(row.lastModified) },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render(row: CloudFileItem) {
      return h(NSpace, { size: 8 }, {
        default: () => [
          !row.isDirectory && h(NButton, { size: 'tiny', quaternary: true, onClick: () => downloadFile(row) }, { icon: () => h(NIcon, null, { default: () => h(DownloadOutline) }) }),
          h(NButton, { size: 'tiny', quaternary: true, onClick: () => openRename(row) }, { icon: () => h(NIcon, null, { default: () => h(CreateOutline) }) }),
          h(NButton, { size: 'tiny', quaternary: true, onClick: () => openMove(row) }, { icon: () => h(NIcon, null, { default: () => h(ReturnLeftOutline) }) }),
          h(NButton, { size: 'tiny', quaternary: true, onClick: () => openCopy(row) }, { icon: () => h(NIcon, null, { default: () => h(CopyOutline) }) }),
          h(NPopconfirm, { onPositiveClick: () => confirmDelete(row) }, {
            trigger: () => h(NButton, { size: 'tiny', quaternary: true, type: 'error' }, { icon: () => h(NIcon, null, { default: () => h(TrashOutline) }) }),
            positiveText: '确认', negativeText: '取消',
            content: () => `确认删除 ${row.name}${row.isDirectory ? '（含所有内容）' : ''}？`
          })
        ]
      });
    }
  }
];

// 文件操作
async function openFile(item: CloudFileItem) {
  const ext = item.name.split('.').pop()?.toLowerCase() || '';
  const textExts = ['md', 'txt', 'json', 'xml', 'html', 'htm', 'css', 'js', 'ts', 'py', 'java', 'c', 'cpp', 'h', 'sh', 'yaml', 'yml', 'properties'];
  if (textExts.includes(ext)) {
    editorPath.value = item.path;
    editorLoading.value = true;
    showEditor.value = true;
    try {
      const { data } = await fetchFileContent(item.path);
      editorContent.value = data;
    } catch {
      message.error('无法读取文件内容');
    } finally {
      editorLoading.value = false;
    }
  } else {
    downloadFile(item);
  }
}

async function downloadFile(item: CloudFileItem) {
  try {
    const { data } = await downloadCloudFile(item.path);
    const url = URL.createObjectURL(new Blob([data]));
    const a = document.createElement('a');
    a.href = url; a.download = item.name; a.click();
    URL.revokeObjectURL(url);
  } catch { message.error('下载失败'); }
}

// 新建目录
async function handleMkdir() {
  if (!newDirInput.value.trim()) return;
  const newPath = (store.currentPath === '/' ? '' : store.currentPath) + '/' + newDirInput.value.trim();
  try {
    await createCloudDir(newPath);
    message.success('创建成功');
    showMkdirModal.value = false;
    newDirInput.value = '';
    store.loadFiles(store.currentPath, 1);
  } catch { message.error('创建失败'); }
}

// 重命名
async function handleRename() {
  if (!renameName.value.trim()) return;
  try {
    await renameCloudFile(store.renameTarget!, renameName.value.trim());
    message.success('重命名成功');
    showRenameModal.value = false;
    store.loadFiles(store.currentPath, 1);
  } catch (e: any) { message.error(e?.message || '重命名失败'); }
}

// 删除
async function confirmDelete(item: CloudFileItem) {
  try {
    await deleteCloudFile(item.path, item.isDirectory);
    message.success('删除成功');
    store.loadFiles(store.currentPath, 1);
  } catch { message.error('删除失败'); }
}

// 上传
async function handleUpload(options: { file: File }) {
  uploading.value = true;
  try {
    await uploadCloudFile(store.currentPath, options.file.name, options.file);
    message.success('上传成功');
    store.loadFiles(store.currentPath, 1);
  } catch { message.error('上传失败'); } finally { uploading.value = false; }
}

// 初始化
onMounted(async () => {
  try {
    await store.loadFiles('/', 1);
    // 用根目录的子目录构建目录树
    const { data } = await fetchCloudFiles('/', 1);
    const dirs = (data?.items || []).filter((i: CloudFileItem) => i.isDirectory);
    store.buildTree(dirs);
  } catch { message.error('加载失败，请检查 WebDAV 配置'); }
});
</script>

<template>
  <n-layout has-sider style="height: 100%">
    <!-- 左侧目录树 -->
    <n-layout-sider :width="220" bordered content-style="padding: 12px">
      <n-h6 style="margin-bottom: 8px; color: #666">目录</n-h6>
      <n-tree
        :data="store.treeData"
        block-line
        expand-on-click
        :default-expanded-keys="['/']"
        :selected-keys="[store.currentPath]"
        @update:selected-keys="([path]) => path && navigateTo(path)"
        @load-meta="async ({ option }: any) => {
          const { data } = await fetchCloudFiles(option.key, 1);
          const dirs = (data?.items || []).filter((i: CloudFileItem) => i.isDirectory);
          option.children = dirs.map((d: CloudFileItem) => ({ key: d.path, label: d.name, isLeaf: false }));
        }"
      />
    </n-layout-sider>

    <!-- 右侧文件列表 -->
    <n-layout-content content-style="padding: 16px">
      <!-- 工具栏 -->
      <div style="display:flex; align-items:center; gap:8px; margin-bottom:12px; flex-wrap:wrap">
        <!-- 面包屑 -->
        <n-breadcrumb>
          <n-breadcrumb-item v-for="bc in breadcrumbs" :key="bc.path" @click="navigateTo(bc.path)">
            <n-icon v-if="bc.path === '/'" :component="FolderOpenOutline" /> {{ bc.path === '/' ? '根目录' : bc.name }}
          </n-breadcrumb-item>
        </n-breadcrumb>

        <div style="flex:1" />

        <!-- 上传按钮 -->
        <n-upload :show-file-list="false" :custom-request="handleUpload" multiple>
          <n-button type="primary" size="small">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>
            上传文件
          </n-button>
        </n-upload>

        <!-- 新建目录 -->
        <n-button size="small" @click="showMkdirModal = true">
          <template #icon><n-icon :component="FolderOutline" /></template>
          新建目录
        </n-button>

        <!-- 刷新 -->
        <n-button size="small" @click="store.loadFiles(store.currentPath, 1)">
          <template #icon><n-icon :component="RefreshOutline" /></template>
        </n-button>
      </div>

      <!-- 文件列表 -->
      <n-spin :show="store.loading">
        <n-data-table
          :columns="columns"
          :data="store.items"
          :row-key="(row: CloudFileItem) => row.path"
          :pagination="false"
          :bordered="false"
          striped
          size="small"
        />
        <n-empty v-if="!store.loading && store.items.length === 0" description="空目录" style="margin-top:40px" />
      </n-spin>
    </n-layout-content>
  </n-layout>

  <!-- 新建目录弹窗 -->
  <n-modal v-model:show="showMkdirModal" preset="dialog" title="新建目录" @positive-click="handleMkdir">
    <n-input v-model:value="newDirInput" placeholder="请输入目录名称" style="margin-top:12px" />
    <template #action><n-button @click="showMkdirModal = false">取消</n-button></template>
  </n-modal>

  <!-- 重命名弹窗 -->
  <n-modal v-model:show="showRenameModal" preset="dialog" title="重命名" @positive-click="handleRename">
    <n-input v-model:value="renameName" placeholder="请输入新名称" style="margin-top:12px" />
    <template #action><n-button @click="showRenameModal = false">取消</n-button></template>
  </n-modal>
</template>
```

> **注意：** `editorPath`、`editorLoading`、`store.renameTarget` 等变量在 Step 9 中补充到 `<script setup>`。此处先创建文件，后续 Task 9 追加代码到 `<script>` 中。

- [ ] **Step 2: Commit（第一部分：基础页面）**

```bash
git add webapp/src/views/cloudfile/ && git commit -m "feat(cloudfile): add cloudfile index page with tree and file list"
```

---

## Task 9: 前端 - Monaco Editor 弹窗组件

**Files:**
- Modify: `webapp/src/views/cloudfile/index.vue`（追加 editor 状态和方法）
- Create: `webapp/src/views/cloudfile/CloudFileEditor.vue`

- [ ] **Step 1: 安装 Monaco Editor**

在 webapp 目录执行：

```bash
cd webapp && pnpm add monaco-editor && pnpm add -D @types/monaco-editor
```

> 如果包体积过大，可以考虑用 `@guolao/vue-monaco-editor`（Vue3 封装）或直接用 CDN 引入。

- [ ] **Step 2: 创建 CloudFileEditor.vue**

```vue
<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue';
import { NModal, NButton, NSpace, NInput, NIcon } from 'naive-ui';
import * as monaco from 'monaco-editor';
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';

self.MonacoEnvironment = { getWorker: () => new editorWorker() };

const props = defineProps<{
  show: boolean;
  path: string;
  initialContent: string;
}>();

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  (e: 'save', path: string, content: string): void;
}>();

const content = ref(props.initialContent);
const isDirty = ref(false);
const editorRef = ref<HTMLDivElement>();
let editor: monaco.editor.IStandaloneCodeEditor | null = null;

watch(() => props.show, (val) => {
  if (val) {
    content.value = props.initialContent;
    isDirty.value = false;
    setTimeout(initEditor, 50);
  }
});

watch(() => props.initialContent, (val) => {
  if (val !== content.value) {
    content.value = val;
    editor?.setValue(val);
  }
});

function initEditor() {
  if (!editorRef.value) return;
  if (editor) { editor.dispose(); editor = null; }
  editor = monaco.editor.create(editorRef.value, {
    value: content.value,
    language: detectLang(props.path),
    theme: 'vs',
    minimap: { enabled: false },
    fontSize: 13,
    lineNumbers: 'on',
    wordWrap: 'on',
    automaticLayout: true,
  });
  editor.onDidChangeModelContent(() => {
    content.value = editor!.getValue();
    isDirty.value = true;
  });
}

function detectLang(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() || '';
  const map: Record<string, string> = {
    md: 'markdown', txt: 'plainText', json: 'json', xml: 'xml',
    html: 'html', htm: 'html', css: 'css', js: 'javascript',
    ts: 'typescript', py: 'python', java: 'java', c: 'c', cpp: 'cpp',
    h: 'c', sh: 'shell', yaml: 'yaml', yml: 'yaml', properties: 'plainText'
  };
  return map[ext] || 'plainText';
}

function handleSave() {
  emit('save', props.path, content.value);
  isDirty.value = false;
}

function handleClose() {
  if (isDirty.value) {
    if (!confirm('有未保存的更改，确认关闭？')) return;
  }
  emit('update:show', false);
}

onBeforeUnmount(() => editor?.dispose());
</script>

<template>
  <n-modal
    :show="show"
    :mask-closable="false"
    style="width:90vw; max-width:1200px; height:80vh"
    @update:show="v => emit('update:show', v)"
  >
    <div style="display:flex; flex-direction:column; height:100%; background:#fff; border-radius:8px; overflow:hidden">
      <!-- 标题栏 -->
      <div style="padding:12px 16px; border-bottom:1px solid #eee; display:flex; align-items:center; gap:12px">
        <span style="font-weight:600; flex:1">{{ path }}</span>
        <n-tag v-if="isDirty" type="warning" size="small">未保存</n-tag>
        <n-button type="primary" size="small" @click="handleSave">保存</n-button>
        <n-button size="small" @click="handleClose">关闭</n-button>
      </div>
      <!-- 编辑器 -->
      <div ref="editorRef" style="flex:1; overflow:hidden" />
    </div>
  </n-modal>
</template>
```

- [ ] **Step 3: 在 index.vue 的 `<script setup>` 中追加 editor 相关状态和方法**

在 `index.vue` 的 `<script setup>` 末尾追加：

```typescript
// 编辑器相关
const editorPath = ref('');
const editorContent = ref('');
const showEditor = ref(false);
const editorLoading = ref(false);

// 重命名相关
const showRenameModal = ref(false);
const renameName = ref('');
const renameTarget = ref('');

function openRename(item: CloudFileItem) {
  renameTarget.value = item.path;
  renameName.value = item.name;
  showRenameModal.value = true;
}

// 移动相关
const showMoveModal = ref(false);
const moveTarget = ref('');
const moveFrom = ref('');

function openMove(item: CloudFileItem) {
  moveFrom.value = item.path;
  moveTarget.value = item.path.substring(0, item.path.lastIndexOf('/')) + '/';
  showMoveModal.value = true;
}

async function handleMove() {
  if (!moveTarget.value.trim()) return;
  try {
    await moveCloudFile(moveFrom.value, moveTarget.value.trim());
    message.success('移动成功');
    showMoveModal.value = false;
    store.loadFiles(store.currentPath, 1);
  } catch { message.error('移动失败'); }
}

// 复制相关
const showCopyModal = ref(false);
const copyTarget = ref('');
const copyFrom = ref('');

function openCopy(item: CloudFileItem) {
  copyFrom.value = item.path;
  const dir = item.path.substring(0, item.path.lastIndexOf('/'));
  copyTarget.value = dir + '/copy_of_' + item.name;
  showCopyModal.value = true;
}

async function handleCopy() {
  if (!copyTarget.value.trim()) return;
  try {
    await copyCloudFile(copyFrom.value, copyTarget.value.trim());
    message.success('复制成功');
    showCopyModal.value = false;
    store.loadFiles(store.currentPath, 1);
  } catch { message.error('复制失败'); }
}

// 编辑器保存
async function handleEditorSave(path: string, newContent: string) {
  // 需要新增后端 PUT 接口更新文件内容
  // 目前通过 uploadCloudFile 重新上传覆盖
  try {
    const filename = path.split('/').pop()!;
    const dir = path.substring(0, path.lastIndexOf('/')) || '/';
    await uploadCloudFile(dir, filename, new Blob([newContent], { type: 'text/plain' }));
    message.success('保存成功');
    showEditor.value = false;
    store.loadFiles(store.currentPath, 1);
  } catch { message.error('保存失败'); }
}
```

并在 `index.vue` 模板末尾追加：

```vue
<!-- 移动弹窗 -->
<n-modal v-model:show="showMoveModal" preset="dialog" title="移动到" @positive-click="handleMove">
  <n-input v-model:value="moveTarget" placeholder="目标路径，如 /archive/readme.md" style="margin-top:12px" />
  <template #action><n-button @click="showMoveModal = false">取消</n-button></template>
</n-modal>

<!-- 复制弹窗 -->
<n-modal v-model:show="showCopyModal" preset="dialog" title="复制到" @positive-click="handleCopy">
  <n-input v-model:value="copyTarget" placeholder="目标路径，如 /backup/readme.md" style="margin-top:12px" />
  <template #action><n-button @click="showCopyModal = false">取消</n-button></template>
</n-modal>

<!-- Monaco Editor -->
<cloud-file-editor
  v-model:show="showEditor"
  :path="editorPath"
  :initial-content="editorContent"
  @save="handleEditorSave"
/>
```

> 追加编辑器的 `handleEditorSave` 方法中，需要新增后端 PUT `/api/cloud/file` 支持直接传文本内容（目前仅支持 multipart 上传）。Task 10 中处理。

- [ ] **Step 4: Commit**

```bash
git add webapp/src/views/cloudfile/ && git commit -m "feat(cloudfile): add Monaco Editor for text file editing"
```

---

## Task 10: 前端 - 路由与国际化

**Files:**
- Modify: `webapp/src/router/guard/route.ts`（添加 /cloud-file 路由）
- Modify: `webapp/src/i18n/` 文件（添加云端文件菜单和标签）
- Modify: `webapp/src/locales/` 或 `webapp/src/views/` 国际化文件

> 注：前端路由由 SoybeanAdmin Elegant Router 管理，需在 `route.ts` 的 guard 中手动添加。

- [ ] **Step 1: 添加路由**

在 `webapp/src/router/guard/route.ts` 中的 routes 数组末尾添加：

```typescript
{
  path: '/cloud-file',
  name: 'cloud-file',
  component: 'layout.main$view.cloudfile.index',
  meta: {
    title: '云端文件',
    icon: 'carbon:cloud',
    order: 3,
    requiresAuth: true,
    i18nKey: 'route.cloudfile'
  }
}
```

- [ ] **Step 2: 添加国际化**

在 `messages_zh_CN.properties` 添加：

```properties
route.cloudfile=云端文件
cloudfile.not.configured=请先在个人信息中配置 WebDAV
cloudfile.goto.config=去配置
cloudfile.upload.success=上传成功
cloudfile.upload.failed=上传失败
cloudfile.create.dir.success=目录创建成功
cloudfile.delete.confirm=确认删除
cloudfile.unsaved.changes=有未保存的更改
```

在 `messages_en.properties` 添加：

```properties
route.cloudfile=Cloud Files
cloudfile.not.configured=Please configure WebDAV in your profile first
cloudfile.goto.config=Configure
cloudfile.upload.success=Upload successful
cloudfile.upload.failed=Upload failed
cloudfile.create.dir.success=Directory created
cloudfile.delete.confirm=Confirm delete
cloudfile.unsaved.changes=You have unsaved changes
```

- [ ] **Step 3: Commit**

```bash
git add webapp/src/router/guard/route.ts webapp/src/i18n/ webapp/src/locales/ && git commit -m "feat(cloudfile): add cloudfile route and i18n entries"
```

---

## Task 11: 后端 - PUT /api/cloud/file 支持文本内容

**Files:**
- Modify: `src/main/java/com/yuyutian/mytools/cloudfile/controller/CloudFileController.java`

> 前端编辑器保存时需要以纯文本方式 PUT 上传，目前 Controller 的 PUT 仅支持 multipart。当前端传 `Content-Type: text/plain; charset=utf-8` 时，以文本方式保存。

- [ ] **Step 1: 修改 CloudFileController.java，添加文本上传端点**

在 `@PostMapping("/api/cloud/file")` 之后添加：

```java
/**
 * 文本方式上传/覆盖文件（用于 Monaco Editor 保存）
 * PUT /api/cloud/text-file
 * Body: 纯文本内容
 * Params: path
 */
@PutMapping("/api/cloud/text-file")
public ResponseEntity<Result<Void>> putTextFile(
        @RequestHeader("Authorization") String auth,
        @RequestParam("path") String path,
        @RequestBody String content) {

    Long userId = resolveUserId(auth);
    String decodedPath = decode(path);
    String filename = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
    String dirPath = decodedPath.substring(0, decodedPath.lastIndexOf('/'));
    cloudFileService.uploadFile(userId, dirPath.isEmpty() ? "/" : dirPath, filename, content.getBytes(StandardCharsets.UTF_8));
    return ResponseEntity.ok(Result.success(MessageHelper.getMessage("success.operation"), null));
}
```

同时在 `import` 区域追加：

```java
import java.nio.charset.StandardCharsets;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/yuyutian/mytools/cloudfile/controller/ && git commit -m "feat(cloudfile): add PUT /api/cloud/text-file for Monaco Editor save"
```

---

## Task 12: 编译、构建与部署

- [ ] **Step 1: 后端编译**

```bash
mvn compile -q 2>&1 | tail -10
```

- [ ] **Step 2: 前端构建**

```bash
cd webapp && pnpm build 2>&1 | tail -20
```

- [ ] **Step 3: 部署**

```bash
# 后端
cp target/mytools-1.0.0.jar /opt/yuyutian/MyTools/backend/mytools-backend.jar

# 前端
cp -r webapp/dist/* /opt/yuyutian/MyTools/frontend/

# 重启
/opt/yuyutian/MyTools/manage.sh restart
```

- [ ] **Step 4: 验证**

1. 打开前端，确认"云端文件"菜单出现（侧边栏）
2. 点击进入，如未配置 WebDAV，显示引导页面
3. 配置 WebDAV 后，进入云盘，列出根目录文件
4. 点击目录进入子目录（面包屑跳转）
5. 新建目录 → 填写名称 → 确认创建 → 刷新列表验证
6. 上传文件 → 选择文件 → 上传 → 刷新列表验证
7. 双击文本文件 → Monaco Editor 打开 → 修改 → 保存 → 刷新列表验证
8. 右键重命名 → 填写新名称 → 确认 → 刷新列表验证
9. 右键删除 → 确认 → 刷新列表验证

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(cloudfile): complete cloud file browser feature

- Backend: WebdavClient with PROPFIND/GET/PUT/MKCOL/DELETE/MOVE/COPY
- Backend: CloudFileService + Controller with all CRUD endpoints
- Frontend: Tree + file list page with Monaco Editor
- Frontend: Upload, mkdir, rename, move, copy, delete operations
- Frontend: i18n and routing"
```

---

## 任务清单

| # | 任务 | 状态 |
|---|------|------|
| 1 | 后端 DTO 模型类 | ⬜ |
| 2 | WebdavClient 协议封装 | ⬜ |
| 3 | CloudFileService 接口与实现 | ⬜ |
| 4 | CloudFileController | ⬜ |
| 5 | SecurityConfig 认证 | ⬜ |
| 6 | 前端 API 服务层 | ⬜ |
| 7 | 前端 Pinia Store | ⬜ |
| 8 | 前端主页面（目录树 + 文件列表） | ⬜ |
| 9 | Monaco Editor 弹窗 | ⬜ |
| 10 | 路由与国际化 | ⬜ |
| 11 | 后端文本上传端点 | ⬜ |
| 12 | 编译、构建、部署 | ⬜ |
