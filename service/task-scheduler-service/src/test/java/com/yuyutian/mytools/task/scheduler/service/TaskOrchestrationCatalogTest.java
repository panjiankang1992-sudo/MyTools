package com.yuyutian.mytools.task.scheduler.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOrchestrationCatalogTest {

    @Test
    void shouldRecognizeEveryPublishedCreateChildPackage() throws IOException {
        Set<String> detectedPackages = new HashSet<>();
        Path serviceRoot = repositoryRoot().resolve("service");
        try (var paths = Files.walk(serviceRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".py"))
                    .filter(path -> path.toString().contains("/packages/"))
                    .filter(path -> path.toString().contains("/scripts/"))
                    .filter(this::containsCreateChildCall)
                    .map(this::packageName)
                    .forEach(detectedPackages::add);
        }

        assertEquals(13, detectedPackages.size());
        assertEquals(detectedPackages, TaskOrchestrationCatalog.recognizedCreateChildPackages());
        assertEquals(12, TaskOrchestrationCatalog.schedulerDirectChildPackages().size());
        assertEquals(10, TaskOrchestrationCatalog.synchronousDirectChildPackages().size());
        assertEquals(Set.of("storage_copy_native_tree"),
                TaskOrchestrationCatalog.externalRootSuccessorPackages());
        assertEquals(Set.of("media_submit_analysis", "message_submit_attachment_download"),
                TaskOrchestrationCatalog.asynchronousChildPackages());
        assertFalse(TaskOrchestrationCatalog.schedulerDirectChildPackages()
                .contains("storage_copy_native_tree"));
    }

    @Test
    void shouldKeepPublishedQqDownloadChainDepthInSyncWithScripts() throws IOException {
        Path serviceRoot = repositoryRoot().resolve("service/download-ingestion-service/packages");
        assertScriptCreates(serviceRoot, "download_message_url_batch", "download_x_user");
        assertScriptCreates(serviceRoot, "download_resolve_x_user", "download_x_post");
        assertScriptCreates(serviceRoot, "download_resolve_x_post", "download_http_asset");

        assertEquals(3, TaskOrchestrationCatalog.maximumPublishedDescendantDepth());
        assertEquals(3, TaskOrchestrationCatalog.maximumDescendantDepth(
                "download_message_url_batch", Set.of("download_message_url_batch")));
        assertEquals(2, TaskOrchestrationCatalog.maximumDescendantDepth(
                "download_x_user", Set.of("download_resolve_x_user")));
        assertEquals(1, TaskOrchestrationCatalog.maximumDescendantDepth(
                "download_x_post", Set.of("download_resolve_x_post")));
        assertFalse(TaskOrchestrationCatalog.mayCreateChildren(
                "download_resolve_x_url", Set.of("download_resolve_x_post")));
    }

    private boolean containsCreateChildCall(Path path) {
        try {
            return Files.readString(path).contains(".create_child(");
        } catch (IOException exception) {
            throw new IllegalStateException("Published script cannot be read", exception);
        }
    }

    private String packageName(Path path) {
        for (int index = 0; index < path.getNameCount() - 1; index++) {
            if ("packages".equals(path.getName(index).toString())) {
                return path.getName(index + 1).toString();
            }
        }
        throw new IllegalArgumentException("Published script is outside a package");
    }

    private void assertScriptCreates(Path packageRoot, String scriptPackage, String childTaskName)
            throws IOException {
        Path script = packageRoot.resolve(scriptPackage).resolve("1.0.0/scripts/main.py");
        assertTrue(Files.readString(script).contains('"' + childTaskName + '"'));
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("service/task-scheduler-service"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Repository root cannot be located");
        }
        return current;
    }
}
