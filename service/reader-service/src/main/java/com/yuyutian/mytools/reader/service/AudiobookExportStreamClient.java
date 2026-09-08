package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.AudiobookExportArchive;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 以恒定内存从受管存储转发已完成的整书 ZIP 归档。
 */
@Component
public class AudiobookExportStreamClient {

    private final ReaderProperties properties;

    /**
     * 创建导出归档流客户端。
     *
     * @param properties 阅读服务配置
     */
    public AudiobookExportStreamClient(ReaderProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取并转发一个已完成归档，不向 App 暴露底层存储地址。
     *
     * @param archive 可读取归档定位信息
     * @param response HTTP 响应
     */
    public void stream(AudiobookExportArchive archive, HttpServletResponse response) {
        StorageAddress address = parseStorageUri(archive.storageUri());
        String token = properties.storageInternalToken();
        if (token == null || token.isBlank() || properties.storageGatewayUrl() == null
                || properties.storageGatewayUrl().isBlank()) {
            throw new AudiobookExportUnavailableException();
        }
        HttpURLConnection connection = null;
        try {
            URI target = UriComponentsBuilder.fromHttpUrl(stripTrailingSlash(properties.storageGatewayUrl())
                            + "/api/internal/v1/storage/objects/content")
                    .queryParam("rootName", address.rootName())
                    .queryParam("path", address.relativePath())
                    .build().encode().toUri();
            connection = (HttpURLConnection) target.toURL().openConnection();
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            connection.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, "identity");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(120_000);
            if (connection.getResponseCode() != HttpServletResponse.SC_OK) {
                throw new AudiobookExportUnavailableException();
            }
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLengthLong(archive.sizeBytes());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(archive.fileName(), StandardCharsets.UTF_8).build().toString());
            try (var input = connection.getInputStream(); var output = response.getOutputStream()) {
                input.transferTo(output);
            }
        } catch (AudiobookExportUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AudiobookExportUnavailableException(exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private StorageAddress parseStorageUri(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (!"storage".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || path == null || path.isBlank() || path.equals("/") || path.contains("..")) {
                throw new IllegalArgumentException("invalid storage uri");
            }
            return new StorageAddress(uri.getHost(), path.substring(1));
        } catch (Exception exception) {
            throw new AudiobookExportUnavailableException(exception);
        }
    }

    private String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private record StorageAddress(String rootName, String relativePath) {
    }
}
