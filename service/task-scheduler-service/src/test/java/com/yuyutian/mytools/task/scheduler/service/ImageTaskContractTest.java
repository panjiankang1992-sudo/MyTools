package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class ImageTaskContractTest {
    @Test
    void migrationAcceptsActualWorkerResultAndRejectsInternalPaths() throws Exception {
        String sql;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V150__seed_image_generation_task.sql"))) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String literal = sql.lines().map(String::trim).filter(line -> line.startsWith("'{\"type\":")).toList().get(1);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> schema = mapper.readValue(literal.substring(1, literal.length() - 2), new TypeReference<>() {});
        TaskSchemaValidationService validator = new TaskSchemaValidationService(mapper);
        assertDoesNotThrow(() -> validator.validateResult(schema, Map.of("indices", List.of(0), "workflowRevision", "krea2-turbo-v1")));
        assertThrows(RuntimeException.class, () -> validator.validateResult(schema, Map.of("indices", List.of(0), "workflowRevision", "krea2-turbo-v1", "path", "/internal/image.png")));
    }
}
