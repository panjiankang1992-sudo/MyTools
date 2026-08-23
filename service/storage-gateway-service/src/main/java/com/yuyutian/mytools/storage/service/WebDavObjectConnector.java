package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.RemoteContent;
import com.yuyutian.mytools.storage.model.StorageProvider;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用 WebDAV PROPFIND 原生读取单级目录。
 */
@Component
public class WebDavObjectConnector implements ProviderObjectConnector {
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_OBJECTS = 10000;
    private final SecretMaterialResolver secretResolver;
    private final HttpClient httpClient;

    /**
     * 创建 WebDAV 连接器。
     *
     * @param secretResolver 密钥解析器
     */
    @Autowired
    public WebDavObjectConnector(SecretMaterialResolver secretResolver) {
        this(secretResolver, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WebDavObjectConnector(SecretMaterialResolver secretResolver, HttpClient httpClient) {
        this.secretResolver = secretResolver;
        this.httpClient = httpClient;
    }

    /**
     * 返回 WebDAV Provider 类型。
     *
     * @return 固定类型
     */
    @Override
    public String providerType() {
        return "WEBDAV";
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsContentRead() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsContentWrite() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public long maximumContentWriteBytes() {
        return Long.MAX_VALUE;
    }

    /**
     * 使用深度一 PROPFIND 查询并标准化响应。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @return 标准化对象列表
     */
    @Override
    public List<RemoteObjectView> list(StorageProvider provider, String path) {
        String safePath = RemotePathValidator.validate(path, true);
        URI endpoint = NativeProviderEndpointValidator.webDav(provider.endpointUri());
        URI requestUri = appendPath(endpoint, safePath);
        Map<String, String> secret = secretResolver.resolve(provider.secretRef());
        String username = required(secret, "username");
        String password = required(secret, "password");
        String requestBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/>"
                + "<d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>";
        HttpRequest request = HttpRequest.newBuilder(requestUri).timeout(Duration.ofMinutes(2))
                .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8)))
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(requestBody)).build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 207 || response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            return parse(response.body(), requestUri, safePath);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    /**
     * 使用原生 GET 打开一个受限普通文件。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @param maximumBytes 最大字节数
     * @return 有界内容流
     */
    @Override
    public RemoteContent openContent(
            StorageProvider provider, String path, long maximumBytes) {
        String safePath = RemotePathValidator.validate(path, false);
        URI requestUri = objectUri(provider, safePath);
        HttpRequest request = authenticated(provider, requestUri).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (response.statusCode() != 200 || length < 0 || length > maximumBytes) {
                response.body().close();
                throw new IllegalStateException(length > maximumBytes
                        ? ErrorCode.REMOTE_CONTENT_TOO_LARGE.code() : ErrorCode.REMOTE_FAILURE.code());
            }
            return new RemoteContent(
                    new BoundedInputStream(response.body(), maximumBytes), length);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    /**
     * 使用原生 PUT 流式写入一个普通文件。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @param content 内容流
     * @param contentLength 精确内容长度
     * @return 是否由本次请求创建目标
     */
    @Override
    public boolean writeContent(StorageProvider provider, String path, InputStream content, long contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
        }
        String safePath = RemotePathValidator.validate(path, false);
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> content), contentLength);
        HttpRequest request = authenticated(provider, objectUri(provider, safePath))
                .header("Content-Type", "application/octet-stream")
                .header("If-None-Match", "*").PUT(publisher).build();
        return sendWrite(request);
    }

    /**
     * 使用原生 DELETE 补偿删除一个普通文件。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     */
    @Override
    public void deleteContent(StorageProvider provider, String path) {
        String safePath = RemotePathValidator.validate(path, false);
        HttpRequest request = authenticated(provider, objectUri(provider, safePath)).DELETE().build();
        sendWithoutBody(request, Set.of(200, 204, 404));
    }

    private HttpRequest.Builder authenticated(StorageProvider provider, URI uri) {
        Map<String, String> secret = secretResolver.resolve(provider.secretRef());
        String credential = required(secret, "username") + ":" + required(secret, "password");
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        credential.getBytes(StandardCharsets.UTF_8)));
    }

    private URI objectUri(StorageProvider provider, String path) {
        return appendObjectPath(NativeProviderEndpointValidator.webDav(provider.endpointUri()), path);
    }

    private URI appendObjectPath(URI endpoint, String path) {
        String encoded = java.util.Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
        return endpoint.resolve(encoded);
    }

    private void sendWithoutBody(HttpRequest request, java.util.Set<Integer> successCodes) {
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (!successCodes.contains(response.statusCode())) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private boolean sendWrite(HttpRequest request) {
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (Set.of(200, 201, 204).contains(response.statusCode())) {
                return true;
            }
            if (response.statusCode() == 412) {
                return false;
            }
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private List<RemoteObjectView> parse(byte[] body, URI requestUri, String parentPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(
                new InputSource(new ByteArrayInputStream(body)));
        NodeList responses = document.getElementsByTagNameNS("DAV:", "response");
        if (responses.getLength() > MAXIMUM_OBJECTS + 1) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        List<RemoteObjectView> result = new ArrayList<>();
        String requestPath = trimTrailingSlash(requestUri.getPath());
        for (int index = 0; index < responses.getLength(); index++) {
            Element response = (Element) responses.item(index);
            String href = text(response, "href");
            URI hrefUri = requestUri.resolve(href);
            String hrefPath = trimTrailingSlash(hrefUri.getPath());
            if (!sameAuthority(requestUri, hrefUri) || hrefPath.equals(requestPath)) {
                continue;
            }
            if (!requestPath.equals(parentPath(hrefPath)) || !hasSuccessfulProperties(response)) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            String name = text(response, "displayname").trim();
            if (name.isBlank()) {
                name = hrefPath.substring(hrefPath.lastIndexOf('/') + 1);
            }
            validateName(name);
            boolean directory = response.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
            long size = parseSize(text(response, "getcontentlength"), directory);
            Instant modifiedAt = parseModifiedAt(text(response, "getlastmodified"));
            String relativePath = parentPath.isBlank() ? name : parentPath + "/" + name;
            result.add(new RemoteObjectView(relativePath, name, directory, size, modifiedAt, null));
        }
        return List.copyOf(result);
    }

    private URI appendPath(URI endpoint, String path) {
        if (path.isBlank()) {
            return endpoint;
        }
        String encoded = java.util.Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
        return endpoint.resolve(encoded + "/");
    }

    private String text(Element element, String localName) {
        NodeList values = element.getElementsByTagNameNS("DAV:", localName);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent();
    }

    private boolean hasSuccessfulProperties(Element response) {
        NodeList statuses = response.getElementsByTagNameNS("DAV:", "status");
        for (int index = 0; index < statuses.getLength(); index++) {
            if (statuses.item(index).getTextContent().matches(".*\\s2[0-9]{2}\\s.*")) {
                return true;
            }
        }
        return statuses.getLength() == 0;
    }

    private String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? "/" : path.substring(0, separator);
    }

    private String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new IllegalStateException(ErrorCode.SECRET_UNAVAILABLE.code());
        }
        return value;
    }

    private void validateName(String name) {
        if (name.isBlank() || name.length() > 512 || name.contains("/") || name.contains("\\")
                || ".".equals(name) || "..".equals(name)) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
    }

    private long parseSize(String value, boolean directory) {
        if (directory || value.isBlank()) {
            return 0;
        }
        try {
            long size = Long.parseLong(value);
            return Math.max(0, size);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private Instant parseModifiedAt(String value) {
        try {
            return value.isBlank() ? null : ZonedDateTime.parse(value,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // 远端时间缺失或格式不兼容时不阻断目录读取。
            return null;
        }
    }

    private boolean sameAuthority(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equals(uri.getScheme()) ? 443 : 80;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") && value.length() > 1 ? value.substring(0, value.length() - 1) : value;
    }
}
