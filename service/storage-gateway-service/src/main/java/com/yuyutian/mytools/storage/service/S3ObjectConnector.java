package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.RemoteContent;
import com.yuyutian.mytools.storage.model.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * 使用签名版本四原生访问 S3 对象。
 */
@Component
public class S3ObjectConnector implements ProviderObjectConnector {
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_PAGES = 10;
    private static final long MAXIMUM_PUT_BYTES = 5L * 1024 * 1024 * 1024;
    private static final String EMPTY_SHA256 = sha256Hex(new byte[0]);
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    private final SecretMaterialResolver secretResolver;
    private final HttpClient httpClient;
    private final Clock clock;

    /**
     * 创建 S3 连接器。
     *
     * @param secretResolver 密钥解析器
     */
    @Autowired
    public S3ObjectConnector(SecretMaterialResolver secretResolver) {
        this(secretResolver, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    S3ObjectConnector(SecretMaterialResolver secretResolver, HttpClient httpClient, Clock clock) {
        this.secretResolver = secretResolver;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    /**
     * 返回 S3 Provider 类型。
     *
     * @return 固定类型
     */
    @Override
    public String providerType() {
        return "S3";
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
        return MAXIMUM_PUT_BYTES;
    }

    /**
     * 使用 ListObjectsV2 分页查询单级目录。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @return 标准化对象列表
     */
    @Override
    public List<RemoteObjectView> list(StorageProvider provider, String path) {
        String safePath = RemotePathValidator.validate(path, true);
        String prefix = safePath.isBlank() ? "" : safePath + "/";
        URI endpoint = NativeProviderEndpointValidator.s3(provider.endpointUri());
        Map<String, String> secret = secretResolver.resolve(provider.secretRef());
        Credentials credentials = credentials(secret);
        List<RemoteObjectView> result = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        String continuationToken = null;
        for (int page = 0; page < MAXIMUM_PAGES; page++) {
            Page parsed = requestPage(provider, endpoint, credentials, prefix, continuationToken);
            for (RemoteObjectView object : parsed.objects()) {
                if (!paths.add(object.path())) {
                    throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
                }
                result.add(object);
            }
            if (!parsed.truncated()) {
                return List.copyOf(result);
            }
            continuationToken = parsed.nextToken();
            if (continuationToken == null || continuationToken.isBlank()) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
        }
        throw new IllegalStateException(ErrorCode.REMOTE_LIST_LIMIT.code());
    }

    /**
     * 使用 SigV4 GET 打开一个受限普通对象。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @param maximumBytes 最大字节数
     * @return 有界内容流
     */
    @Override
    public RemoteContent openContent(StorageProvider provider, String path, long maximumBytes) {
        String safePath = RemotePathValidator.validate(path, false);
        Credentials credentials = credentials(secretResolver.resolve(provider.secretRef()));
        HttpRequest request = signedObjectBuilder(provider, credentials, "GET", safePath,
                UNSIGNED_PAYLOAD, Map.of()).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (response.statusCode() != 200 || length < 0 || length > maximumBytes) {
                response.body().close();
                throw new IllegalStateException(length > maximumBytes
                        ? ErrorCode.REMOTE_CONTENT_TOO_LARGE.code() : ErrorCode.REMOTE_FAILURE.code());
            }
            return new RemoteContent(new BoundedInputStream(response.body(), maximumBytes), length);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    /**
     * 使用条件 SigV4 PUT 创建一个普通对象。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     * @param content 内容流
     * @param contentLength 精确内容长度
     * @return 是否由本次请求创建目标
     */
    @Override
    public boolean writeContent(StorageProvider provider, String path, InputStream content, long contentLength) {
        if (contentLength < 0 || contentLength > MAXIMUM_PUT_BYTES) {
            throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
        }
        String safePath = RemotePathValidator.validate(path, false);
        Credentials credentials = credentials(secretResolver.resolve(provider.secretRef()));
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> content), contentLength);
        HttpRequest request = signedObjectBuilder(provider, credentials, "PUT", safePath,
                UNSIGNED_PAYLOAD, Map.of("if-none-match", "*"))
                .header("Content-Type", "application/octet-stream").PUT(publisher).build();
        return sendWrite(request);
    }

    /**
     * 使用 SigV4 DELETE 补偿删除一个普通对象。
     *
     * @param provider Provider 配置
     * @param path 安全相对路径
     */
    @Override
    public void deleteContent(StorageProvider provider, String path) {
        String safePath = RemotePathValidator.validate(path, false);
        Credentials credentials = credentials(secretResolver.resolve(provider.secretRef()));
        HttpRequest request = signedObjectBuilder(provider, credentials, "DELETE", safePath,
                UNSIGNED_PAYLOAD, Map.of()).DELETE().build();
        sendDelete(request);
    }

    private HttpRequest.Builder signedObjectBuilder(StorageProvider provider, Credentials credentials,
            String method, String path, String payloadHash, Map<String, String> additionalSignedHeaders) {
        URI endpoint = NativeProviderEndpointValidator.s3(provider.endpointUri());
        String objectPath = java.util.Arrays.stream(path.split("/"))
                .map(this::encode).collect(java.util.stream.Collectors.joining("/"));
        URI requestUri = URI.create(trimTrailingSlash(endpoint.toString()) + "/"
                + encode(provider.remoteKey()) + "/" + objectPath);
        Instant now = clock.instant();
        String timestamp = REQUEST_TIME.format(now);
        String date = REQUEST_DATE.format(now);
        TreeMap<String, String> signedHeaders = new TreeMap<>();
        signedHeaders.put("host", hostHeader(requestUri));
        signedHeaders.put("x-amz-content-sha256", payloadHash);
        signedHeaders.put("x-amz-date", timestamp);
        if (credentials.sessionToken() != null) {
            signedHeaders.put("x-amz-security-token", credentials.sessionToken());
        }
        signedHeaders.putAll(additionalSignedHeaders);
        String signedHeaderNames = String.join(";", signedHeaders.keySet());
        String canonicalHeaders = signedHeaders.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().trim() + "\n")
                .collect(java.util.stream.Collectors.joining());
        String canonicalRequest = method + "\n" + requestUri.getRawPath() + "\n\n"
                + canonicalHeaders + "\n" + signedHeaderNames + "\n" + payloadHash;
        String scope = date + "/" + provider.regionName() + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(credentials.secretAccessKey(), date,
                provider.regionName()), stringToSign.getBytes(StandardCharsets.UTF_8)));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaderNames + ", Signature=" + signature;
        HttpRequest.Builder builder = HttpRequest.newBuilder(requestUri).timeout(Duration.ofMinutes(30))
                .header("Authorization", authorization).header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", timestamp);
        if (credentials.sessionToken() != null) {
            builder.header("x-amz-security-token", credentials.sessionToken());
        }
        additionalSignedHeaders.forEach(builder::header);
        return builder;
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

    private void sendDelete(HttpRequest request) {
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (!Set.of(200, 204, 404).contains(response.statusCode())) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private Page requestPage(StorageProvider provider, URI endpoint, Credentials credentials,
                             String prefix, String continuationToken) {
        TreeMap<String, String> query = new TreeMap<>();
        if (continuationToken != null) {
            query.put("continuation-token", continuationToken);
        }
        query.put("delimiter", "/");
        query.put("list-type", "2");
        query.put("max-keys", "1000");
        query.put("prefix", prefix);
        String canonicalQuery = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        URI requestUri = URI.create(trimTrailingSlash(endpoint.toString()) + "/"
                + encode(provider.remoteKey()) + "?" + canonicalQuery);
        Instant now = clock.instant();
        String timestamp = REQUEST_TIME.format(now);
        String date = REQUEST_DATE.format(now);
        String host = hostHeader(requestUri);
        TreeMap<String, String> signedHeaders = new TreeMap<>();
        signedHeaders.put("host", host);
        signedHeaders.put("x-amz-content-sha256", EMPTY_SHA256);
        signedHeaders.put("x-amz-date", timestamp);
        if (credentials.sessionToken() != null) {
            signedHeaders.put("x-amz-security-token", credentials.sessionToken());
        }
        String signedHeaderNames = String.join(";", signedHeaders.keySet());
        String canonicalHeaders = signedHeaders.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().trim() + "\n")
                .collect(java.util.stream.Collectors.joining());
        String canonicalRequest = "GET\n" + requestUri.getRawPath() + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaderNames + "\n" + EMPTY_SHA256;
        String scope = date + "/" + provider.regionName() + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(credentials.secretAccessKey(), date,
                provider.regionName()), stringToSign.getBytes(StandardCharsets.UTF_8)));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaderNames + ", Signature=" + signature;
        HttpRequest.Builder builder = HttpRequest.newBuilder(requestUri).timeout(Duration.ofMinutes(2))
                .header("Authorization", authorization).header("x-amz-content-sha256", EMPTY_SHA256)
                .header("x-amz-date", timestamp).GET();
        if (credentials.sessionToken() != null) {
            builder.header("x-amz-security-token", credentials.sessionToken());
        }
        try {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            return parse(response.body(), prefix);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private Page parse(byte[] body, String prefix) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(
                    new InputSource(new ByteArrayInputStream(body)));
            List<RemoteObjectView> objects = new ArrayList<>();
            NodeList contents = document.getElementsByTagName("Contents");
            for (int index = 0; index < contents.getLength(); index++) {
                Element content = (Element) contents.item(index);
                String key = childText(content, "Key");
                String name = directName(key, prefix, false);
                if (name == null) {
                    continue;
                }
                long size = parseSize(childText(content, "Size"));
                objects.add(new RemoteObjectView(prefix + name, name, false, size,
                        parseInstant(childText(content, "LastModified")), null));
            }
            NodeList commonPrefixes = document.getElementsByTagName("CommonPrefixes");
            for (int index = 0; index < commonPrefixes.getLength(); index++) {
                String key = childText((Element) commonPrefixes.item(index), "Prefix");
                String name = directName(key, prefix, true);
                objects.add(new RemoteObjectView(prefix + name, name, true, 0, null, null));
            }
            if (objects.size() > 1000) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            boolean truncated = Boolean.parseBoolean(rootText(document, "IsTruncated"));
            String token = rootText(document, "NextContinuationToken");
            return new Page(List.copyOf(objects), truncated, token.isBlank() ? null : token);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private String directName(String key, String prefix, boolean directory) {
        if (!key.startsWith(prefix)) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        String remainder = key.substring(prefix.length());
        if (directory) {
            if (!remainder.endsWith("/") || remainder.length() == 1) {
                throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
            }
            remainder = remainder.substring(0, remainder.length() - 1);
        } else if (remainder.isBlank()) {
            return null;
        }
        if (remainder.contains("/") || remainder.contains("\\") || ".".equals(remainder)
                || "..".equals(remainder) || remainder.length() > 512) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        return remainder;
    }

    private Credentials credentials(Map<String, String> values) {
        String accessKeyId = required(values, "accessKeyId");
        String secretAccessKey = required(values, "secretAccessKey");
        String sessionToken = values.get("sessionToken");
        if (sessionToken != null && (sessionToken.isBlank() || sessionToken.length() > 8192)) {
            throw new IllegalStateException(ErrorCode.SECRET_UNAVAILABLE.code());
        }
        return new Credentials(accessKeyId, secretAccessKey, sessionToken);
    }

    private String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new IllegalStateException(ErrorCode.SECRET_UNAVAILABLE.code());
        }
        return value;
    }

    private String childText(Element element, String name) {
        NodeList values = element.getElementsByTagName(name);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent();
    }

    private String rootText(Document document, String name) {
        NodeList values = document.getElementsByTagName(name);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent();
    }

    private long parseSize(String value) {
        try {
            long size = Long.parseLong(value);
            if (size < 0) {
                throw new NumberFormatException("negative size");
            }
            return size;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private Instant parseInstant(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port < 0 || ("https".equals(uri.getScheme()) && port == 443)
                || ("http".equals(uri.getScheme()) && port == 80);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private byte[] signingKey(String secretAccessKey, String date, String region) {
        byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8),
                date.getBytes(StandardCharsets.UTF_8));
        byte[] regionKey = hmac(dateKey, region.getBytes(StandardCharsets.UTF_8));
        byte[] serviceKey = hmac(regionKey, "s3".getBytes(StandardCharsets.UTF_8));
        return hmac(serviceKey, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code(), exception);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
    }

    private record Page(List<RemoteObjectView> objects, boolean truncated, String nextToken) {
    }
}
