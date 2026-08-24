package com.yuyutian.mytools.task.scheduler.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度步骤与领域脚本包发布源的一致性测试。
 */
@SpringBootTest
class TaskPackageContractTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldResolveEveryEnabledStepToOnePublishedEntrypoint() throws IOException {
        Path serviceRoot = resolveServiceRoot();
        List<Map<String, Object>> steps = jdbcTemplate.queryForList("""
                SELECT DISTINCT script_package,script_version,entrypoint
                FROM task_step_definition WHERE enabled=TRUE
                ORDER BY script_package,script_version,entrypoint
                """);
        assertTrue(steps.size() > 50, "Expected the migrated task catalog to be loaded");
        List<Path> manifests;
        try (var paths = Files.find(serviceRoot, 5,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equals("manifest.yaml"))) {
            manifests = paths.toList();
        }
        for (Map<String, Object> step : steps) {
            String packageName = String.valueOf(step.get("script_package"));
            String version = String.valueOf(step.get("script_version"));
            String entrypoint = String.valueOf(step.get("entrypoint"));
            List<Path> matches = manifests.stream().filter(path ->
                    path.getParent().getFileName().toString().equals(version)
                            && path.getParent().getParent().getFileName().toString().equals(packageName)).toList();
            assertEquals(1, matches.size(), "Script package version must have one owner: "
                    + packageName + ":" + version);
            Path script = matches.getFirst().getParent().resolve(entrypoint).normalize();
            assertTrue(script.startsWith(matches.getFirst().getParent()) && Files.isRegularFile(script)
                    && !Files.isSymbolicLink(script), "Script entrypoint must exist: "
                    + packageName + ":" + version + ":" + entrypoint);
        }
    }

    @Test
    void shouldUseExecutorRecognizedStepKindsForEveryEnabledStep() {
        Integer invalid = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_step_definition
                WHERE enabled=TRUE AND step_kind NOT IN ('NORMAL','ON_FAILURE','ON_TIMEOUT','ON_CANCEL')
                """, Integer.class);
        assertEquals(0, invalid, "Every enabled terminal step must use the Executor step kind protocol");
    }

    private Path resolveServiceRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path serviceRoot = "task-scheduler-service".equals(workingDirectory.getFileName().toString())
                ? workingDirectory.getParent() : workingDirectory.resolve("service");
        assertTrue(Files.isDirectory(serviceRoot.resolve("task-scheduler-service")),
                "MyTools service root is missing");
        return serviceRoot;
    }
}
