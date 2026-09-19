package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 视频任务契约测试：把迁移里固定的参数/结果 Schema 与业务服务真实派发的内容、
 * 任务包真实产出的结果放在一起校验，避免三方各自漂移。
 */
class VideoTaskContractTest {
    private static final String MIGRATION = "/db/migration/V155__seed_video_generation_task.sql";

    /** 读取迁移里的 JSON Schema 字面量：第 index 个以 '{"type": 开头的单引号字符串。 */
    private static Map<String, Object> schemaLiteral(int index) throws Exception {
        String sql;
        try (var stream = Objects.requireNonNull(VideoTaskContractTest.class.getResourceAsStream(MIGRATION))) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> literals = sql.lines().map(String::trim).filter(line -> line.startsWith("'{\"type\":")).toList();
        assertTrue(literals.size() > index, "迁移里的 Schema 字面量数量不足");
        String literal = literals.get(index);
        return new ObjectMapper().readValue(literal.substring(1, literal.length() - 2), new TypeReference<>() { });
    }

    /** 业务服务派发时实际提交的参数；与服务端 VideoService.advance 的快照裁剪保持一致。 */
    private static Map<String, Object> dispatchParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("resourceId", "wan-vace-1.3b-local");
        parameters.put("prompt", "The subject in the frame begins to move gently.");
        parameters.put("mode", "FIRST_FRAME");
        parameters.put("output", Map.of("size", "832x480", "frames", 49, "fps", 16));
        parameters.put("seed", 42);
        parameters.put("idempotencyKey", "video-key-1");
        parameters.put("width", 832);
        parameters.put("height", 480);
        parameters.put("modelRevision", "wan2.1-vace-1.3b-fp16");
        parameters.put("workflowRevision", "video-vace-1.3b-v1");
        parameters.put("audioPolicy", "SILENT");
        parameters.put("resolvedInputs", List.of(Map.of(
                "inputId", "0f5a1f0e-0f4b-4d3a-9a1e-6a2b7c8d9e0f",
                "uploadId", "1f5a1f0e-0f4b-4d3a-9a1e-6a2b7c8d9e0f",
                "role", "FIRST_FRAME",
                "sha256", "a".repeat(64))));
        parameters.put("jobId", "2f5a1f0e-0f4b-4d3a-9a1e-6a2b7c8d9e0f");
        return parameters;
    }

    @Test
    void migrationParameterSchemaAcceptsServiceDispatchPayload() throws Exception {
        TaskSchemaValidationService validator = new TaskSchemaValidationService(new ObjectMapper());
        Map<String, Object> schema = schemaLiteral(0);
        assertDoesNotThrow(() -> validator.validateParameters(schema, dispatchParameters()));
        // 原始描述与输入列表属于服务端内部审计字段，不得随派发参数外泄。
        for (String leaked : List.of("effectivePrompt", "inputs")) {
            Map<String, Object> value = new LinkedHashMap<>(dispatchParameters());
            value.put(leaked, "x");
            assertThrows(RuntimeException.class, () -> validator.validateParameters(schema, value), leaked);
        }
    }

    @Test
    void migrationParameterSchemaRejectsUnvalidatedShapes() throws Exception {
        TaskSchemaValidationService validator = new TaskSchemaValidationService(new ObjectMapper());
        Map<String, Object> schema = schemaLiteral(0);
        // 帧数、帧率、尺寸与音频策略都是固定契约，客户端不能改。
        Map<String, Object> wrongFrames = new LinkedHashMap<>(dispatchParameters());
        wrongFrames.put("output", Map.of("size", "832x480", "frames", 81, "fps", 16));
        assertThrows(RuntimeException.class, () -> validator.validateParameters(schema, wrongFrames));
        Map<String, Object> wrongAudio = new LinkedHashMap<>(dispatchParameters());
        wrongAudio.put("audioPolicy", "KEEP_SOURCE_AUDIO");
        assertThrows(RuntimeException.class, () -> validator.validateParameters(schema, wrongAudio));
        Map<String, Object> wrongResource = new LinkedHashMap<>(dispatchParameters());
        wrongResource.put("resourceId", "krea2-local");
        assertThrows(RuntimeException.class, () -> validator.validateParameters(schema, wrongResource));
    }

    @Test
    void migrationResultSchemaMatchesPackageSchema() throws Exception {
        // 迁移里的结果 Schema 必须与任务包发布的结果 Schema 完全一致，否则执行器一上线就会被拒。
        Path packageSchema = Path.of("..", "video-generation-service", "packages", "video_generate", "1.0.0",
                "schemas", "result.schema.json");
        assertTrue(Files.isRegularFile(packageSchema), "任务包结果 Schema 缺失：" + packageSchema.toAbsolutePath());
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> fromPackage = mapper.readValue(Files.readString(packageSchema), new TypeReference<>() { });
        assertEquals(mapper.readTree(mapper.writeValueAsString(fromPackage)),
                mapper.readTree(mapper.writeValueAsString(schemaLiteral(1))));
    }

    @Test
    void migrationResultSchemaAcceptsWorkerResultAndRejectsInternalPaths() throws Exception {
        TaskSchemaValidationService validator = new TaskSchemaValidationService(new ObjectMapper());
        Map<String, Object> schema = schemaLiteral(1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("frames", 49);
        result.put("fps", 16);
        result.put("width", 832);
        result.put("height", 480);
        result.put("durationMs", 3063);
        result.put("workflowRevision", "video-first-frame-v1");
        result.put("controlSha256", "b".repeat(64));
        result.put("outputSha256", "c".repeat(64));
        result.put("coverSha256", "d".repeat(64));
        result.put("referenceFill", "blend");
        result.put("fillBlend", 0.25);
        result.put("sourceQuality", Map.of("brightness", 0.42, "detail", 0.011));
        result.put("promptId", "3f5a1f0e-0f4b-4d3a-9a1e-6a2b7c8d9e0f");
        result.put("inferenceMillis", 488600);
        result.put("resourcePeak", Map.of("gpuPeakMiB", 14540, "memoryAvailableMiB", 15471));
        assertDoesNotThrow(() -> validator.validateResult(schema, result));
        Map<String, Object> leaky = new LinkedHashMap<>(result);
        leaky.put("path", "/opt/yuyutian/mytools/runtime/video-generation/outputs/x/video.mp4");
        assertThrows(RuntimeException.class, () -> validator.validateResult(schema, leaky));
    }
}
