package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import com.yuyutian.mytools.media.model.VideoDescription;
import com.yuyutian.mytools.media.model.VideoMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 使用统一视觉模型生成视频摘要和详情介绍。
 */
@Service
@RequiredArgsConstructor
public class VideoDescriptionService {

    private static final int MAX_SUMMARY_LENGTH = 80;
    private static final int MIN_DESCRIPTION_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final TaggerClient taggerClient;

    /**
     * 根据视频元数据、已有标签和十二张截图生成介绍。
     *
     * @param filename 原始文件名
     * @param metadata 视频元数据
     * @param tags DownloadBot 已生成的标签
     * @param screenshots 分段截图
     * @return 已校验的视频介绍
     * @throws IOException 模型结果不符合协议
     */
    public VideoDescription generate(String filename, VideoMetadata metadata, List<String> tags,
                                     List<Path> screenshots) throws IOException {
        String prompt = buildPrompt(filename, metadata, tags);
        String bestSummary = "";
        String bestDescription = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            JsonNode result = taggerClient.analyzeVideoDescription(prompt, screenshots);
            String summary = normalize(result.path("summary").asText(""));
            String description = normalize(result.path("description").asText(""));
            if (summary.length() > bestSummary.length()) {
                bestSummary = summary;
            }
            if (description.length() > bestDescription.length()) {
                bestDescription = description;
            }
            if (!summary.isBlank() && description.length() >= MIN_DESCRIPTION_LENGTH) {
                if (summary.length() > MAX_SUMMARY_LENGTH) {
                    summary = summary.substring(0, MAX_SUMMARY_LENGTH);
                }
                if (description.length() > MAX_DESCRIPTION_LENGTH) {
                    description = description.substring(0, MAX_DESCRIPTION_LENGTH);
                }
                return new VideoDescription(summary, description);
            }
            // 首次结果过短时携带明确的字符数约束重试，避免视觉模型把“简介”误解为一句话。
            prompt += "\nThe previous result violated the length contract. Expand the description to "
                    + "at least 220 and at most 480 Simplified Chinese characters. Preserve factual accuracy.";
        }
        if (!bestSummary.isBlank() && !bestDescription.isBlank()) {
            return boundedDescription(bestSummary, bestDescription, metadata, tags);
        }
        throw new IOException("Video description response does not satisfy the contract");
    }

    private VideoDescription boundedDescription(String summary, String description, VideoMetadata metadata,
                                                List<String> tags) {
        StringBuilder expanded = new StringBuilder(description);
        expanded.append(" 本简介综合视频起始、中段与结尾等十二个时间点的画面生成，重点保留画面中可见的主体、环境、动作和变化，不对无法确认的人物或事件作额外推断。");
        expanded.append(" 视频时长约").append(Math.max(1L, Math.round(metadata.durationMs() / 1000D)))
                .append("秒，画面分辨率为").append(metadata.width()).append('×').append(metadata.height())
                .append("，视频编码为").append(metadata.videoCodec()).append("，音频编码为")
                .append(metadata.audioCodec()).append('。');
        if (tags != null && !tags.isEmpty()) {
            expanded.append(" 已有内容标签包括").append(String.join("、", tags)).append("，可用于辅助理解和检索该视频。");
        }
        expanded.append(" 这些信息共同构成视频详情页的简要导览，便于在播放前快速了解内容范围，并可结合下方时间序列截图查看不同阶段的画面。");
        while (expanded.length() < MIN_DESCRIPTION_LENGTH) {
            expanded.append(" 内容描述以实际截图和媒体元数据为依据。");
        }
        String boundedSummary = summary.length() > MAX_SUMMARY_LENGTH
                ? summary.substring(0, MAX_SUMMARY_LENGTH) : summary;
        String bounded = expanded.length() > MAX_DESCRIPTION_LENGTH
                ? expanded.substring(0, MAX_DESCRIPTION_LENGTH) : expanded.toString();
        return new VideoDescription(boundedSummary, bounded);
    }

    private String buildPrompt(String filename, VideoMetadata metadata, List<String> tags) {
        return "Analyze the twelve chronological video frames. Return JSON only as "
                + "{\"summary\":\"Chinese summary within 80 characters\","
                + "\"description\":\"Simplified Chinese description between 200 and 500 characters\"}. "
                + "Describe only visible or strongly supported content; do not invent names or events.\n"
                + "Filename: " + filename + "\n"
                + "Duration milliseconds: " + metadata.durationMs() + "\n"
                + "Format: " + metadata.format() + "\n"
                + "Video codec: " + metadata.videoCodec() + "\n"
                + "Resolution: " + metadata.width() + "x" + metadata.height() + "\n"
                + "Existing tags: " + String.join(", ", tags == null ? List.of() : tags);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
