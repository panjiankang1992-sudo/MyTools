package com.yuyutian.mytools.localfile.service.tagging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuyutian.mytools.common.ErrorCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Base64;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 打标签服务客户端。
 * 调用外部HTTP接口进行文件标签识别。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaggerClient {

    private final RestTemplate restTemplate;

    @Value("${tagging.service.url:http://192.168.1.9:8024}")
    private String taggingServiceUrl;

    /**
     * 图片/视频/音频文件打标签请求。
     */
    @Data
    public static class MediaTaggingRequest {
        @JsonProperty("image_base64")
        private String imageBase64;

        @JsonProperty("filename")
        private String filename;

        @JsonProperty("mime_type")
        private String mimeType;
    }

    /**
     * 文本文件打标签请求。
     */
    @Data
    public static class TextTaggingRequest {
        @JsonProperty("text")
        private String text;

        @JsonProperty("filename")
        private String filename;
    }

    /**
     * 标签结果。
     */
    @Data
    public static class TagResult {
        @JsonProperty("tag_name")
        private String tagName;

        @JsonProperty("tag_type")
        private String tagType;

        @JsonProperty("confidence")
        private Double confidence;
    }

    /**
     * 打标签响应。
     */
    @Data
    public static class TaggingResponse {
        @JsonProperty("success")
        private Boolean success;

        @JsonProperty("tags")
        private List<TagResult> tags;

        @JsonProperty("error")
        private String error;
    }

    /**
     * 对图片/视频/音频文件进行打标签。
     *
     * @param file 文件对象
     * @param thumbnailPath 缩略图路径（视频/音频使用缩略图）
     * @param mimeType MIME类型
     * @return 标签列表
     */
    public List<TagResult> tagMediaFile(File file, String thumbnailPath, String mimeType) {
        String base64Data;
        String fileToEncode = file.exists() ? file.getAbsolutePath() : thumbnailPath;

        try (FileInputStream fis = new FileInputStream(fileToEncode)) {
            byte[] bytes = fis.readAllBytes();
            base64Data = Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            log.error("读取文件失败: {}", fileToEncode, e);
            throw new TaggerException(ErrorCode.FILE_005, e);
        }

        MediaTaggingRequest request = new MediaTaggingRequest();
        request.setImageBase64(base64Data);
        request.setFilename(file.getName());
        request.setMimeType(mimeType);

        try {
            TaggingResponse response = restTemplate.postForObject(
                    taggingServiceUrl + "/tag",
                    request,
                    TaggingResponse.class
            );

            if (response == null || !Boolean.TRUE.equals(response.getSuccess())) {
                throw new TaggerException(ErrorCode.FILE_008);
            }

            return response.getTags() != null ? response.getTags() : new ArrayList<>();
        } catch (RestClientException e) {
            log.error("调用打标签服务失败", e);
            throw new TaggerException(ErrorCode.FILE_008, e);
        }
    }

    /**
     * 对文本文件进行打标签。
     *
     * @param text 文件文本内容
     * @param filename 文件名
     * @return 标签列表
     */
    public List<TagResult> tagTextFile(String text, String filename) {
        TextTaggingRequest request = new TextTaggingRequest();
        request.setText(text);
        request.setFilename(filename);

        try {
            TaggingResponse response = restTemplate.postForObject(
                    taggingServiceUrl + "/tag/text",
                    request,
                    TaggingResponse.class
            );

            if (response == null || !Boolean.TRUE.equals(response.getSuccess())) {
                throw new TaggerException(ErrorCode.FILE_008);
            }

            return response.getTags() != null ? response.getTags() : new ArrayList<>();
        } catch (RestClientException e) {
            log.error("调用打标签服务失败", e);
            throw new TaggerException(ErrorCode.FILE_008, e);
        }
    }

    /**
     * 检查服务是否可用。
     */
    public boolean isServiceAvailable() {
        try {
            restTemplate.getForObject(taggingServiceUrl + "/health", String.class);
            return true;
        } catch (RestClientException e) {
            log.warn("打标签服务不可用: {}", taggingServiceUrl);
            return false;
        }
    }
}
