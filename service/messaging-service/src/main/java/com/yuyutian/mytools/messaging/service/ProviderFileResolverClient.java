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

    private final RestClient oneBotRestClient;
    private final String oneBotToken;
    private final RestClient telegramRestClient;
    private final String telegramToken;

    /**
     * 创建渠道文件解析客户端。
     *
     * @param restClient 受控解析器客户端
     * @param token 解析器内部令牌
     */
    public ProviderFileResolverClient(RestClient restClient, String token) {
        this(restClient, token, restClient, token);
    }

    /**
     * 创建支持多个 provider 的文件解析客户端。
     *
     * @param oneBotRestClient OneBot 客户端
     * @param oneBotToken OneBot 内部令牌
     * @param telegramRestClient Telegram 客户端
     * @param telegramToken Telegram 内部令牌
     */
    public ProviderFileResolverClient(RestClient oneBotRestClient, String oneBotToken,
                                      RestClient telegramRestClient, String telegramToken) {
        this.oneBotRestClient = oneBotRestClient;
        this.oneBotToken = oneBotToken;
        this.telegramRestClient = telegramRestClient;
        this.telegramToken = telegramToken;
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
        return resolve("ONEBOT", accountKey, attachmentType, providerFileId);
    }

    /**
     * 按渠道解析 provider 文件引用。
     *
     * @param channelType 渠道类型
     * @param accountKey 渠道账户键
     * @param attachmentType 附件类型
     * @param providerFileId provider 文件标识
     * @return 解析模式和可选公开地址
     */
    public Resolution resolve(String channelType, String accountKey, String attachmentType,
                              String providerFileId) {
        Target target = target(channelType);
        Resolution response = target.client().post().uri("/internal/v1/provider-files/resolve")
                .header("Authorization", "Bearer " + target.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("channelType", channelType, "accountKey", accountKey,
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
        stream("ONEBOT", accountKey, attachmentType, providerFileId, output, maximumBytes);
    }

    /**
     * 按渠道流式代理 provider 文件内容。
     *
     * @param channelType 渠道类型
     * @param accountKey 渠道账户键
     * @param attachmentType 附件类型
     * @param providerFileId provider 文件标识
     * @param output 受控响应输出流
     * @param maximumBytes 最大允许字节数
     */
    public void stream(String channelType, String accountKey, String attachmentType, String providerFileId,
                       OutputStream output, long maximumBytes) {
        Target target = target(channelType);
        target.client().post().uri("/internal/v1/provider-files/content")
                .header("Authorization", "Bearer " + target.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("channelType", channelType, "accountKey", accountKey,
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

    private Target target(String channelType) {
        RestClient client = "TELEGRAM".equals(channelType) ? telegramRestClient : oneBotRestClient;
        String token = "TELEGRAM".equals(channelType) ? telegramToken : oneBotToken;
        if (!("TELEGRAM".equals(channelType) || "ONEBOT".equals(channelType))) {
            throw new IllegalStateException("Provider resolver channel is unsupported");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Provider resolver internal token is missing");
        }
        return new Target(client, token);
    }

    /**
     * 解析器最小响应。
     */
    public record Resolution(String mode, String downloadUrl) {
    }

    private record Target(RestClient client, String token) {
    }
}
