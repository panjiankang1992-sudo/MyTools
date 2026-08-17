package com.yuyutian.mytools.copilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.copilot.model.CopilotGatewayStream;
import com.yuyutian.mytools.copilot.model.CopilotGatewayInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Copilot模型网关服务。
 */
@Service
public class CopilotGatewayService {

    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final URI providerUri;
    private final String providerApiKey;
    private final String model;

    /**
     * 创建Copilot模型网关服务。
     *
     * @param objectMapper JSON序列化器。
     * @param enabled 是否启用网关。
     * @param providerUrl 固定上游接口地址。
     * @param providerApiKey 服务端模型密钥。
     * @param model Agent Core应使用的模型标识。
     */
    public CopilotGatewayService(
            ObjectMapper objectMapper,
            @Value("${copilot.gateway.enabled:false}") boolean enabled,
            @Value("${copilot.gateway.provider-url:http://127.0.0.1:11434/v1/chat/completions}") String providerUrl,
            @Value("${copilot.gateway.api-key:}") String providerApiKey,
            @Value("${copilot.gateway.model:huihui_ai/qwen3-vl-abliterated:4b}") String model) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.providerUri = validateProviderUri(providerUrl);
        this.providerApiKey = providerApiKey;
        this.model = validateModel(model);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 返回不包含服务端密钥和上游地址的公开配置。
     *
     * @return Copilot公开配置。
     */
    public CopilotGatewayInfo getInfo() {
        return new CopilotGatewayInfo(enabled, model);
    }

    /**
     * 将Agent Core投影的模型请求转发到固定上游。
     *
     * @param requestBody Core投影的OpenAI兼容请求体。
     * @return 上游流式响应。
     */
    public CopilotGatewayStream openStream(JsonNode requestBody) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.COPILOT_001);
        }
        if (requestBody == null || !requestBody.isObject() || !requestBody.path("stream").asBoolean(false)
                || !requestBody.path("messages").isArray() || !model.equals(requestBody.path("model").asText())) {
            throw new BusinessException(ErrorCode.COPILOT_002);
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(requestBody);
            if (payload.length == 0 || payload.length > MAX_REQUEST_BYTES) {
                throw new BusinessException(ErrorCode.COPILOT_002);
            }
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(providerUri)
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            if (!providerApiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + providerApiKey);
            }
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("text/event-stream;charset=UTF-8");
            return new CopilotGatewayStream(response.statusCode(), contentType, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.COPILOT_003);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.COPILOT_003);
        }
    }

    private static String validateModel(String model) {
        String value = model == null ? "" : model.trim();
        if (value.isEmpty() || value.length() > 160) {
            throw new IllegalStateException("Copilot model must contain 1 to 160 characters");
        }
        return value;
    }

    private static URI validateProviderUri(String providerUrl) {
        try {
            URI uri = URI.create(providerUrl);
            if (uri.getHost() == null || (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("Invalid Copilot provider URL");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Copilot provider URL must be a fixed HTTP or HTTPS URL", exception);
        }
    }
}
