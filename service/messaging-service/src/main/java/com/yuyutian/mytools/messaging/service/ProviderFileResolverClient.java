package com.yuyutian.mytools.messaging.service;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * 凭据隔离的渠道文件引用解析客户端。
 */
public class ProviderFileResolverClient {

    private final RestClient restClient;
    private final String token;

    /**
     * 创建渠道文件解析客户端。
     *
     * @param restClient 受控解析器客户端
     * @param token 解析器内部令牌
     */
    public ProviderFileResolverClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token;
    }

    /**
     * 将 provider 文件引用解析为公开地址或受控流模式。
     *
     * @param accountKey 渠道账户键
     * @param attachmentType 附件类型
     * @param providerFileId provider 文件标识
     * @return 解析模式和可选公开地址
     */
    public Resolution resolve(String accountKey, String attachmentType, String providerFileId) {
        requireToken();
        Resolution response = restClient.post().uri("/internal/v1/provider-files/resolve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("channelType", "ONEBOT", "accountKey", accountKey,
                        "attachmentType", attachmentType, "providerFileId", providerFileId))
                .retrieve().body(Resolution.class);
        if (response == null || response.mode() == null) {
            throw new IllegalStateException("Provider resolver returned no resolution mode");
        }
        if ("STREAM".equals(response.mode()) && response.downloadUrl() == null) {
            return response;
        }
        if (!"PUBLIC_URL".equals(response.mode()) || response.downloadUrl() == null) {
            throw new IllegalStateException("Provider resolver returned an invalid resolution");
        }
        URI uri = URI.create(response.downloadUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null
                || response.downloadUrl().length() > 4096) {
            throw new IllegalStateException("Provider resolver returned an invalid download URL");
        }
        return new Resolution("PUBLIC_URL", uri.toASCIIString());
    }

    /**
     * 在解析器请求生命周期内转发 provider 内容并实施字节上限。
     *
     * @param accountKey 渠道账户键
     * @param attachmentType 附件类型
     * @param providerFileId provider 文件标识
     * @param output 受控响应输出流
     * @param maximumBytes 最大允许字节数
     */
    public void stream(String accountKey, String attachmentType, String providerFileId,
                       OutputStream output, long maximumBytes) {
        requireToken();
        restClient.post().uri("/internal/v1/provider-files/content")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("channelType", "ONEBOT", "accountKey", accountKey,
                        "attachmentType", attachmentType, "providerFileId", providerFileId))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("Provider content endpoint rejected the request");
                    }
                    long declared = response.getHeaders().getContentLength();
                    if (declared > maximumBytes) {
                        throw new IllegalStateException("Provider content exceeds declared limit");
                    }
                    byte[] buffer = new byte[1024 * 1024];
                    long total = 0;
                    int length;
                    try {
                        while ((length = response.getBody().read(buffer)) != -1) {
                            total += length;
                            if (total > maximumBytes) {
                                throw new IllegalStateException("Provider content exceeds declared limit");
                            }
                            output.write(buffer, 0, length);
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException("Provider content stream failed", exception);
                    }
                    return null;
                });
    }

    private void requireToken() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Provider resolver internal token is missing");
        }
    }

    /**
     * 解析器最小响应。
     */
    public record Resolution(String mode, String downloadUrl) {
    }
}
