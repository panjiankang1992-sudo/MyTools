package com.yuyutian.mytools.media.task;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

/**
 * 通过旧文件标识解析新媒体与资产标识。
 */
@Component
public class LegacyMediaAnalysisTargetClient {
    private final RestTemplate restTemplate;
    private final MediaProcessingSidecarProperties properties;

    /**
     * 创建旧媒体映射客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties 旁路配置
     */
    public LegacyMediaAnalysisTargetClient(RestTemplate restTemplate,
                                           MediaProcessingSidecarProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 解析已完成迁移的旧媒体。
     *
     * @param legacyId 旧文件标识
     * @return 分析目标
     */
    public AnalysisTarget resolve(long legacyId) {
        if (properties.getMediaLibraryToken() == null || properties.getMediaLibraryToken().isBlank()) {
            throw new IllegalStateException("Media Library token is missing");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getMediaLibraryToken());
        String root = properties.getMediaLibraryUrl().replaceAll("/+$", "");
        var response = restTemplate.exchange(root + "/internal/v1/media/migrations/legacy-items/"
                        + legacyId + "/analysis-target", HttpMethod.GET, new HttpEntity<>(headers),
                AnalysisTarget.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Media Library returned an empty analysis target");
        }
        return response.getBody();
    }

    /**
     * 已迁移媒体的完整分析身份。
     */
    public record AnalysisTarget(UUID mediaItemId, UUID assetRegistryId, long ownerId,
                                 String displayName, String mimeType, long sizeBytes,
                                 String contentSha256) {
    }
}
