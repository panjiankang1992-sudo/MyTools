package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.model.AudiobookAudioChapter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 以恒定内存从受管存储转发已登记的有声书章节音频。
 */
@Component
public class AudiobookAudioStreamClient {

    private final ReaderProperties properties;

    /**
     * 创建章节音频流客户端。
     *
     * @param properties 阅读服务配置
     */
    public AudiobookAudioStreamClient(ReaderProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取并转发一个受管章节音频，保留单段 Range 语义。
     *
     * @param chapter 可播放章节
     * @param range 客户端请求范围
     * @param response 当前 HTTP 响应
     */
    public void stream(AudiobookAudioChapter chapter, String range, HttpServletResponse response) {
        stream(chapter.storageUri(), chapter.format(), range, response);
    }

    /**
     * 读取并转发一个已登记的受管音频对象，保留单段 Range 语义。
     *
     * @param storageUri 仅限内部使用的受管存储地址
     * @param format 音频格式
     * @param range 客户端请求范围
     * @param response 当前 HTTP 响应
     */
    public void stream(String storageUri, String format, String range, HttpServletResponse response) {
        StorageAddress address = parseStorageUri(storageUri);
        String token = properties.storageInternalToken();
        if (token == null || token.isBlank() || properties.storageGatewayUrl() == null
                || properties.storageGatewayUrl().isBlank()) {
            throw new AudiobookAudioUnavailableException();
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
            if (range != null && !range.isBlank()) {
                connection.setRequestProperty(HttpHeaders.RANGE, range);
            }
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(120_000);
            int status = connection.getResponseCode();
            if (status != HttpServletResponse.SC_OK && status != HttpServletResponse.SC_PARTIAL_CONTENT) {
                throw new AudiobookAudioUnavailableException();
            }
            response.setStatus(status);
            // 存储网关统一返回二进制类型，播放端需要章节资产的真实音频类型。
            response.setContentType(audioMediaType(format));
            copyHeader(connection, response, HttpHeaders.CONTENT_LENGTH, null);
            copyHeader(connection, response, HttpHeaders.CONTENT_RANGE, null);
            copyHeader(connection, response, HttpHeaders.ACCEPT_RANGES, "bytes");
            try (var input = connection.getInputStream(); var output = response.getOutputStream()) {
                input.transferTo(output);
            }
        } catch (AudiobookAudioUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AudiobookAudioUnavailableException(exception);
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
            throw new AudiobookAudioUnavailableException(exception);
        }
    }

    private void copyHeader(HttpURLConnection connection, HttpServletResponse response, String name,
                            String fallback) {
        String value = connection.getHeaderField(name);
        if (value == null || value.isBlank()) {
            value = fallback;
        }
        if (value != null && !value.isBlank()) {
            response.setHeader(name, value);
        }
    }

    private String audioMediaType(String format) {
        return "mp3".equalsIgnoreCase(format) ? "audio/mpeg" : "audio/" + format.toLowerCase();
    }

    private String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private record StorageAddress(String rootName, String relativePath) {
    }
}
