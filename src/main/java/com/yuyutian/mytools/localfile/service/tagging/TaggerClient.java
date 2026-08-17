package com.yuyutian.mytools.localfile.service.tagging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.nio.file.Path;

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
    private final ObjectMapper objectMapper;

    @Value("${tagging.service.url:http://192.168.1.9:8024}")
    private String taggingServiceUrl;

    @Value("${tagging.service.model:huihui_ai/qwen3-vl-abliterated:4b}")
    private String taggingModel;

    /**
     * 图片/视频/音频文件打标签请求。
     */
    /** 标签结果。 */
    @Data
    public static class TagResult {
        private String tagName;
        private String tagType;
        private Double confidence;
    }

    /** 成人内容独立识别结果。 */
    public record AdultResult(boolean adult, double confidence) {
    }

    /**
     * 使用独立模型请求判断视觉资源是否为成人向内容。
     *
     * @param file 资源或缩略图
     * @param mimeType MIME类型
     * @return 成人内容识别结果
     */
    public AdultResult classifyAdult(File file, String mimeType) {
        try {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            return callAdultModel(file.getName(), mimeType, "", base64);
        } catch (IOException ex) {
            throw new TaggerException(ErrorCode.FILE_005, ex);
        }
    }

    /**
     * 使用独立模型请求判断文本或元数据是否为成人向内容。
     *
     * @param filename 文件名
     * @param mimeType MIME类型
     * @param text 文本样本
     * @return 成人内容识别结果
     */
    public AdultResult classifyAdultText(String filename, String mimeType, String text) {
        String limited = text.length() > 12000 ? text.substring(0, 12000) : text;
        return callAdultModel(filename, mimeType, limited, null);
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
        String fileToEncode = thumbnailPath != null && new File(thumbnailPath).isFile()
                ? thumbnailPath
                : file.getAbsolutePath();
        String base64Data;
        try {
            base64Data = Base64.getEncoder().encodeToString(Files.readAllBytes(new File(fileToEncode).toPath()));
        } catch (IOException e) {
            log.error("读取文件失败: {}", fileToEncode, e);
            throw new TaggerException(ErrorCode.FILE_005, e);
        }

        String prompt = buildPrompt(file.getName(), mimeType,
                "Analyze the provided image content and generate accurate tags.");
        return callOllama(prompt, base64Data);
    }

    /**
     * 对文本文件进行打标签。
     *
     * @param text 文件文本内容
     * @param filename 文件名
     * @return 标签列表
     */
    public List<TagResult> tagTextFile(String text, String filename) {
        String limitedText = text.length() > 12000 ? text.substring(0, 12000) : text;
        String prompt = buildPrompt(filename, "text/plain",
                "Analyze the following text or file metadata and generate accurate tags:\n" + limitedText);
        return callOllama(prompt, null);
    }

    /**
     * 检查服务是否可用。
     */
    public boolean isServiceAvailable() {
        try {
            restTemplate.getForObject(taggingServiceUrl + "/api/tags", String.class);
            return true;
        } catch (RestClientException e) {
            log.warn("打标签服务不可用: {}", taggingServiceUrl);
            return false;
        }
    }

    /**
     * 调用本地模型并返回结构化JSON结果。
     *
     * @param prompt 结构化分析提示词
     * @return JSON结果
     */
    @SuppressWarnings("unchecked")
    public JsonNode analyzeJson(String prompt) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> request = new HashMap<>();
        request.put("model", taggingModel);
        request.put("stream", false);
        request.put("think", false);
        request.put("format", "json");
        request.put("messages", List.of(message));
        request.put("options", Map.of("temperature", 0.05, "num_predict", 1200, "num_ctx", 8192));

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    taggingServiceUrl + "/api/chat", request, Map.class);
            if (response == null || !(response.get("message") instanceof Map<?, ?> responseMessage)) {
                throw new TaggerException(ErrorCode.FILE_008);
            }
            Object content = responseMessage.get("content");
            String json = content instanceof String text ? text.trim() : "";
            if (json.isEmpty() && responseMessage.get("thinking") instanceof String thinking) {
                json = thinking.trim();
            }
            return objectMapper.readTree(json.replace("```json", "").replace("```", "").trim());
        } catch (RestClientException | IOException ex) {
            log.error("调用本地模型结构化分析失败", ex);
            throw new TaggerException(ErrorCode.FILE_008, ex);
        }
    }

    /**
     * 使用多张视频截图生成结构化摘要和简介。
     *
     * @param prompt 视频元数据和输出约束
     * @param screenshots 已生成的视频截图，最多十二张
     * @return 模型返回的结构化 JSON
     */
    @SuppressWarnings("unchecked")
    public JsonNode analyzeVideoDescription(String prompt, List<Path> screenshots) {
        if (screenshots == null || screenshots.isEmpty() || screenshots.size() > 12) {
            throw new TaggerException(ErrorCode.FILE_008);
        }
        List<String> images = new ArrayList<>();
        try {
            for (Path screenshot : screenshots) {
                if (!Files.isRegularFile(screenshot) || Files.size(screenshot) > 5L * 1024 * 1024) {
                    throw new TaggerException(ErrorCode.FILE_008);
                }
                images.add(Base64.getEncoder().encodeToString(Files.readAllBytes(screenshot)));
            }
        } catch (IOException ex) {
            throw new TaggerException(ErrorCode.FILE_005, ex);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        message.put("images", images);
        Map<String, Object> request = new HashMap<>();
        request.put("model", taggingModel);
        request.put("stream", false);
        request.put("think", false);
        request.put("format", "json");
        request.put("messages", List.of(message));
        request.put("options", Map.of("temperature", 0.1, "num_predict", 1200, "num_ctx", 8192));
        try {
            Map<String, Object> response = restTemplate.postForObject(
                    taggingServiceUrl + "/api/chat", request, Map.class);
            if (response == null || !(response.get("message") instanceof Map<?, ?> responseMessage)) {
                throw new TaggerException(ErrorCode.FILE_008);
            }
            String content = responseMessage.get("content") instanceof String text ? text.trim() : "";
            if (content.isEmpty() && responseMessage.get("thinking") instanceof String thinking) {
                content = thinking.trim();
            }
            return objectMapper.readTree(content.replace("```json", "").replace("```", "").trim());
        } catch (RestClientException | IOException ex) {
            throw new TaggerException(ErrorCode.FILE_008, ex);
        }
    }

    private String buildPrompt(String filename, String mimeType, String contentInstruction) {
        return "You are a file tagging engine. Never refuse classification. " + contentInstruction + "\n"
                + "Filename: " + filename + "\nMIME type: " + mimeType + "\n"
                + "Return JSON only in this exact shape: "
                + "{\"tags\":[{\"tag_name\":\"short Chinese tag\",\"tag_type\":\"topic\",\"confidence\":0.95}]}. "
                + "Return 3 to 6 concise Simplified Chinese tags. Confidence must be between 0 and 1.";
    }

    @SuppressWarnings("unchecked")
    private AdultResult callAdultModel(String filename, String mimeType, String text, String imageBase64) {
        String prompt = "Classify whether this resource is R18 or adult-oriented. Consider explicit sexual content, "
                + "pornography and strongly sexualized nudity as adult. Do not mark ordinary art, medical material, "
                + "swimwear or non-sexual affection as adult. Filename: " + filename + "\nMIME type: " + mimeType
                + (text.isBlank() ? "" : "\nContent sample:\n" + text)
                + "\nReturn JSON only: {\"adult\":true|false,\"confidence\":0.0}.";
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        if (imageBase64 != null) message.put("images", List.of(imageBase64));
        Map<String, Object> request = new HashMap<>();
        request.put("model", taggingModel);
        request.put("stream", false);
        request.put("think", false);
        request.put("format", "json");
        request.put("messages", List.of(message));
        request.put("options", Map.of("temperature", 0.0, "num_predict", 120, "num_ctx", 4096));
        try {
            Map<String, Object> response = restTemplate.postForObject(taggingServiceUrl + "/api/chat", request, Map.class);
            if (response == null || !(response.get("message") instanceof Map<?, ?> responseMessage)) {
                throw new TaggerException(ErrorCode.FILE_008);
            }
            String content = responseMessage.get("content") instanceof String value ? value.trim() : "";
            if (content.isEmpty() && responseMessage.get("thinking") instanceof String thinking) content = thinking.trim();
            JsonNode result = objectMapper.readTree(content.replace("```json", "").replace("```", "").trim());
            return new AdultResult(result.path("adult").asBoolean(false),
                    Math.max(0D, Math.min(1D, result.path("confidence").asDouble(0D))));
        } catch (RestClientException | IOException ex) {
            throw new TaggerException(ErrorCode.FILE_008, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<TagResult> callOllama(String prompt, String imageBase64) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        if (imageBase64 != null) message.put("images", List.of(imageBase64));

        Map<String, Object> request = new HashMap<>();
        request.put("model", taggingModel);
        request.put("stream", false);
        request.put("think", false);
        request.put("format", "json");
        request.put("messages", List.of(message));
        request.put("options", Map.of("temperature", 0.1, "num_predict", 300, "num_ctx", 4096));

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    taggingServiceUrl + "/api/chat", request, Map.class);
            if (response == null || !(response.get("message") instanceof Map<?, ?> responseMessage)) {
                throw new TaggerException(ErrorCode.FILE_008);
            }
            Object content = responseMessage.get("content");
            String contentText = content instanceof String text ? text.trim() : "";
            if (contentText.isEmpty() && responseMessage.get("thinking") instanceof String thinking) {
                // 部分去审查视觉模型会把结构化结果放入 thinking 字段。
                contentText = thinking.trim();
            }
            if (contentText.isEmpty()) {
                throw new TaggerException(ErrorCode.FILE_008);
            }
            return parseTags(contentText);
        } catch (RestClientException | IOException e) {
            log.error("调用本地 Ollama 打标签失败", e);
            throw new TaggerException(ErrorCode.FILE_008, e);
        }
    }

    private List<TagResult> parseTags(String content) throws IOException {
        String normalized = content.replace("```json", "").replace("```", "").trim();
        JsonNode tagsNode = objectMapper.readTree(normalized).path("tags");
        if (!tagsNode.isArray()) return Collections.emptyList();

        List<TagResult> results = new ArrayList<>();
        for (JsonNode tagNode : tagsNode) {
            String tagName = tagNode.path("tag_name").asText("").trim();
            if (tagName.isEmpty()) continue;
            TagResult result = new TagResult();
            result.setTagName(tagName);
            result.setTagType(tagNode.path("tag_type").asText("topic"));
            result.setConfidence(Math.max(0D, Math.min(1D, tagNode.path("confidence").asDouble(0.8D))));
            results.add(result);
        }
        return results.stream().limit(6).toList();
    }
}
