package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.repository.MediaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过资产注册表和存储网关读取任务生成的缩略图。
 */
@Service
public class DerivedThumbnailContentService {
    private static final int MAXIMUM_THUMBNAIL_BYTES = 20 * 1024 * 1024;
    private final MediaRepository repository;
    private final RestClient assetClient;
    private final RestClient storageClient;

    /**
     * 创建派生缩略图内容服务。
     *
     * @param repository 媒体仓储
     * @param builder REST 客户端构建器
     * @param assetUrl 资产注册表地址
     * @param assetToken 资产注册表令牌
     * @param storageUrl 存储网关地址
     * @param storageToken 存储网关令牌
     */
    public DerivedThumbnailContentService(MediaRepository repository, RestClient.Builder builder,
            @Value("${media-library.asset-registry-url:http://127.0.0.1:23270}") String assetUrl,
            @Value("${media-library.asset-registry-token:}") String assetToken,
            @Value("${media-library.storage-gateway-url:http://127.0.0.1:23240}") String storageUrl,
            @Value("${media-library.storage-token:}") String storageToken) {
        this.repository = repository;
        this.assetClient = builder.clone().baseUrl(assetUrl).defaultHeader("Authorization", "Bearer " + assetToken).build();
        this.storageClient = builder.clone().baseUrl(storageUrl).defaultHeader("Authorization", "Bearer " + storageToken).build();
    }

    /**
     * 读取当前媒体最新且可用的派生缩略图。
     *
     * @param ownerId 所有者
     * @param mediaId 媒体标识
     * @return 缩略图内容
     */
    public Optional<LegacyMediaContentService.Content> content(long ownerId, UUID mediaId) {
        UUID assetId = repository.latestThumbnailAsset(ownerId, mediaId).orElse(null);
        if (assetId == null) {
            return Optional.empty();
        }
        return assetContent(assetId, true);
    }

    /**
     * 读取当前媒体登记在资产服务中的原始图片。
     *
     * @param ownerId 所有者
     * @param mediaId 媒体标识
     * @return 图片内容
     */
    public Optional<LegacyMediaContentService.Content> originalImage(long ownerId, UUID mediaId) {
        var media = repository.view(mediaId)
                .filter(value -> value.ownerId() == ownerId)
                .orElseThrow(() -> new IllegalArgumentException("media content not found"));
        if (!media.mimeType().toLowerCase().startsWith("image/")) {
            return Optional.empty();
        }
        return assetContent(media.assetId(), false);
    }

    private Optional<LegacyMediaContentService.Content> assetContent(UUID assetId, boolean requireJpeg) {
        Map<?, ?> asset = assetClient.get().uri("/internal/v1/assets/{id}", assetId).retrieve().body(Map.class);
        Map<?, ?> location = availableLocation(asset);
        String mimeType = asset == null ? "" : String.valueOf(asset.get("mimeType"));
        if (asset == null || location == null || !mimeType.startsWith("image/")
                || requireJpeg && !"image/jpeg".equals(mimeType)) {
            return Optional.empty();
        }
        long expectedSize = ((Number) asset.get("sizeBytes")).longValue();
        if (expectedSize < 1 || expectedSize > MAXIMUM_THUMBNAIL_BYTES) {
            throw new IllegalStateException("derived media thumbnail size is invalid");
        }
        String uri = String.valueOf(location.get("storageUri"));
        java.net.URI parsed = java.net.URI.create(uri);
        String path = parsed.getPath().replaceFirst("^/", "");
        String requestUri = UriComponentsBuilder.fromPath("/api/internal/v1/storage/objects/content")
                .queryParam("rootName", parsed.getHost()).queryParam("path", path).build().encode().toUriString();
        byte[] body;
        try {
            body = storageClient.get().uri(requestUri).retrieve().body(byte[].class);
        } catch (RestClientResponseException exception) {
            // 迁移清单可能早于存储对象落盘，缺失时继续回退旧文件而不是放大成服务异常。
            if (exception.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw exception;
        }
        if (body == null || body.length != expectedSize) {
            throw new IllegalStateException("derived media thumbnail content is invalid");
        }
        return Optional.of(new LegacyMediaContentService.Content(new ByteArrayResource(body), mimeType, body.length));
    }

    private Map<?, ?> availableLocation(Map<?, ?> asset) {
        if (asset == null || !(asset.get("locations") instanceof List<?> locations)) {
            return null;
        }
        for (Object value : locations) {
            if (value instanceof Map<?, ?> location && "AVAILABLE".equals(location.get("availability"))
                    && "STORAGE_GATEWAY".equals(location.get("providerType"))
                    && String.valueOf(location.get("storageUri")).startsWith("storage://")) {
                return location;
            }
        }
        return null;
    }
}
