package com.yuyutian.mytools.task.scheduler.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 已发布脚本包的同步子任务编排能力目录。
 *
 * <p>深度表示该脚本执行时最多还需同时保留的后继层数。仓库契约测试会扫描所有
 * 生产脚本中的 {@code create_child} 调用，新增编排脚本必须同步更新此目录。</p>
 */
final class TaskOrchestrationCatalog {

    private static final String RESOLVE_ONLY_X_TASK = "download_resolve_x_url";
    private static final Map<String, Integer> MAXIMUM_DESCENDANT_DEPTH_BY_PACKAGE = Map.ofEntries(
            Map.entry("download_local_import", 1),
            Map.entry("download_local_magnet", 1),
            Map.entry("download_message_url_batch", 3),
            Map.entry("download_pikpak_magnet", 1),
            Map.entry("download_pikpak_watch_batch", 1),
            Map.entry("download_resolve_web_archive", 1),
            Map.entry("download_resolve_x_post", 1),
            Map.entry("download_resolve_x_user", 2),
            Map.entry("media_scan_directory", 1),
            Map.entry("reader_probe_search", 1)
    );
    private static final Set<String> ASYNCHRONOUS_CHILD_PACKAGES = Set.of(
            "media_submit_analysis", "message_submit_attachment_download");
    private static final Set<String> EXTERNAL_ROOT_SUCCESSOR_PACKAGES = Set.of("storage_copy_native_tree");

    private TaskOrchestrationCatalog() {
    }

    static boolean mayCreateChildren(String taskName, Collection<String> scriptPackages) {
        return maximumDescendantDepth(taskName, scriptPackages) > 0;
    }

    static int maximumDescendantDepth(String taskName, Collection<String> scriptPackages) {
        // 解析专用任务复用 X 帖子脚本，但 resolveOnly 契约明确不创建下载子任务。
        if (RESOLVE_ONLY_X_TASK.equals(taskName)) {
            return 0;
        }
        return scriptPackages.stream()
                .mapToInt(scriptPackage -> MAXIMUM_DESCENDANT_DEPTH_BY_PACKAGE.getOrDefault(scriptPackage, 0))
                .max()
                .orElse(0);
    }

    static int maximumPublishedDescendantDepth() {
        return MAXIMUM_DESCENDANT_DEPTH_BY_PACKAGE.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    static Set<String> recognizedCreateChildPackages() {
        java.util.HashSet<String> packages = new java.util.HashSet<>(schedulerDirectChildPackages());
        packages.addAll(EXTERNAL_ROOT_SUCCESSOR_PACKAGES);
        return Set.copyOf(packages);
    }

    static Set<String> synchronousDirectChildPackages() {
        return MAXIMUM_DESCENDANT_DEPTH_BY_PACKAGE.keySet();
    }

    static Set<String> schedulerDirectChildPackages() {
        java.util.HashSet<String> packages = new java.util.HashSet<>(MAXIMUM_DESCENDANT_DEPTH_BY_PACKAGE.keySet());
        packages.addAll(ASYNCHRONOUS_CHILD_PACKAGES);
        return Set.copyOf(packages);
    }

    static Set<String> externalRootSuccessorPackages() {
        return EXTERNAL_ROOT_SUCCESSOR_PACKAGES;
    }

    static Set<String> asynchronousChildPackages() {
        return ASYNCHRONOUS_CHILD_PACKAGES;
    }
}
