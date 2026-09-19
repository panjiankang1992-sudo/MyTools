package com.yuyutian.mytools.task.scheduler.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.WorkloadIntrospection;
import com.yuyutian.mytools.task.scheduler.service.TaskExecutionAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** mTLS Reader 专属的在线授权入口，不能用静态业务令牌替代工作负载证书。 */
@RestController
@RequestMapping("/api/internal/v1/task-execution-authorizations")
public class WorkloadAuthorizationController {
    private final TaskExecutionAuthorizationService service;
    private final ObjectMapper mapper;

    /** 使用严格且独立的解析器，不修改项目其他接口的 JSON 兼容性。 */
    public WorkloadAuthorizationController(TaskExecutionAuthorizationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 返回本次在线检查，禁止客户端或中间缓存复用 active 结论。 */
    @PostMapping("/introspect")
    public ResponseEntity<WorkloadIntrospection.Response> introspect(@RequestBody byte[] body) {
        WorkloadIntrospection.Request request;
        try {
            if (body.length > 4096) {
                throw new IllegalArgumentException();
            }
            var root = mapper.readTree(body);
            if (root == null || !root.isObject() || root.size() != 9 || !root.path("fencingToken").isIntegralNumber()
                    || !root.path("assertionGeneration").isIntegralNumber() || !root.path("fencingToken").canConvertToLong()
                    || !root.path("assertionGeneration").canConvertToLong()) {
                throw new IllegalArgumentException();
            }
            for (String key : java.util.List.of("jti", "taskInstanceId", "executionId")) {
                if (!root.path(key).isTextual() || !java.util.UUID.fromString(root.get(key).textValue()).toString().equals(root.get(key).textValue())) {
                    throw new IllegalArgumentException();
                }
            }
            for (String key : java.util.List.of("cnfThumbprint", "audience", "resourceType", "taskParametersSha256")) {
                if (!root.path(key).isTextual()) {
                    throw new IllegalArgumentException();
                }
            }
            request = mapper.treeToValue(root, WorkloadIntrospection.Request.class);
        } catch (Exception exception) {
            throw new SchedulerException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Invalid workload introspection request");
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store, private").body(service.introspect(request));
    }

    /** 发布轮换中的公钥，仍须通过 Reader mTLS 身份验证。 */
    @GetMapping("/jwks")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().header("Cache-Control", "no-store, private").body(service.jwks());
    }
}
