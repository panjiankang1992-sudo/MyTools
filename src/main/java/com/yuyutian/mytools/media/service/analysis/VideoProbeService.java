package com.yuyutian.mytools.media.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.media.model.VideoMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 使用 ffprobe 提取视频元数据。
 */
@Service
@RequiredArgsConstructor
public class VideoProbeService {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PROBE_BYTES = 1024 * 1024;

    private final MediaCommandRunner commandRunner;
    private final ObjectMapper objectMapper;

    /**
     * 探测视频容器、时长和音视频流信息。
     *
     * @param video 视频文件
     * @return 视频元数据
     * @throws IOException 探测失败或输出非法
     */
    public VideoMetadata probe(Path video) throws IOException {
        String output = commandRunner.runForOutput(List.of(
                "ffprobe", "-v", "error", "-show_format", "-show_streams",
                "-of", "json", video.toString()), PROBE_TIMEOUT, MAX_PROBE_BYTES);
        JsonNode root = objectMapper.readTree(output);
        JsonNode format = root.path("format");
        JsonNode videoStream = findStream(root.path("streams"), "video");
        JsonNode audioStream = findStream(root.path("streams"), "audio");
        long durationMs = Math.round(parseDouble(format.path("duration").asText("0")) * 1000D);
        if (durationMs <= 0 || videoStream.isMissingNode()) {
            throw new IOException("Video probe output is missing duration or video stream");
        }
        return new VideoMetadata(
                durationMs,
                bounded(format.path("format_name").asText("unknown"), 64),
                bounded(videoStream.path("codec_name").asText("unknown"), 64),
                bounded(audioStream.path("codec_name").asText("none"), 64),
                videoStream.path("width").asInt(0),
                videoStream.path("height").asInt(0),
                parseFrameRate(videoStream.path("avg_frame_rate").asText("0/1")),
                parseLong(format.path("bit_rate").asText("0")));
    }

    private JsonNode findStream(JsonNode streams, String type) {
        if (streams.isArray()) {
            for (JsonNode stream : streams) {
                if (type.equals(stream.path("codec_type").asText())) {
                    return stream;
                }
            }
        }
        return objectMapper.missingNode();
    }

    private double parseFrameRate(String value) {
        String[] parts = value.split("/", 2);
        double numerator = parseDouble(parts[0]);
        double denominator = parts.length == 2 ? parseDouble(parts[1]) : 1D;
        return denominator == 0D ? 0D : numerator / denominator;
    }

    private double parseDouble(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed >= 0D ? parsed : 0D;
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private long parseLong(String value) {
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
