package com.yuyutian.mytools.copilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.copilot.model.CopilotGatewayStream;
import com.yuyutian.mytools.copilot.model.CopilotGatewayInfo;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.copilot.service.CopilotGatewayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 移动端Copilot模型网关控制器。
 */
@RestController
@RequestMapping("/api/app/v1/copilot")
@RequiredArgsConstructor
public class CopilotGatewayController {

    private final CopilotGatewayService gatewayService;

    /**
     * 获取应用初始化Agent Core所需的公开配置。
     *
     * @return 不包含密钥和上游地址的配置。
     */
    @GetMapping("/config")
    public Result<CopilotGatewayInfo> config() {
        return Result.success(gatewayService.getInfo());
    }

    /**
     * 转发Agent Core生成的流式模型请求。
     *
     * @param requestBody Core投影的请求体。
     * @return SSE流式响应。
     */
    @PostMapping(value = "/chat", produces = "text/event-stream")
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody JsonNode requestBody) {
        CopilotGatewayStream stream = gatewayService.openStream(requestBody);
        StreamingResponseBody body = outputStream -> {
            try (stream) {
                stream.body().transferTo(outputStream);
                outputStream.flush();
            }
        };
        return ResponseEntity.status(stream.statusCode())
                .header(HttpHeaders.CONTENT_TYPE, stream.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
