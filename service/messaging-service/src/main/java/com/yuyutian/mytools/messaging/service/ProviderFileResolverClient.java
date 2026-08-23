package com.yuyutian.mytools.messaging.service;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
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
     * 将 provider 文件引用换取短期下载地址。
     *
     * @param accountKey 渠道账户键
     * @param attachmentType 附件类型
     * @param providerFileId provider 文件标识
     * @return 受控下载地址
     */
    public String resolve(String accountKey, String attachmentType, String providerFileId) {
        Resolution response = restClient.post().uri("/internal/v1/provider-files/resolve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("channelType", "ONEBOT", "accountKey", accountKey,
                        "attachmentType", attachmentType, "providerFileId", providerFileId))
                .retrieve().body(Resolution.class);
        if (response == null || response.downloadUrl() == null) {
            throw new IllegalStateException("Provider resolver returned no download URL");
        }
        URI uri = URI.create(response.downloadUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null
                || response.downloadUrl().length() > 4096) {
            throw new IllegalStateException("Provider resolver returned an invalid download URL");
        }
        return uri.toASCIIString();
    }

    /**
     * 解析器最小响应。
     */
    public record Resolution(String downloadUrl) {
    }
}
