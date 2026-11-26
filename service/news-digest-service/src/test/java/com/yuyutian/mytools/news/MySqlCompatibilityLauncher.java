package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 在隔离数据库所在主机使用同一套 JUnit 用例，避免跨网延迟影响迁移验收。 */
public final class MySqlCompatibilityLauncher {
    private MySqlCompatibilityLauncher() { }

    /** 显式配置、执行并保存计数；任何失败或跳过均返回非零退出码。 */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 2 || System.getenv("NEWS_MYSQL_TEST_CONFIG") == null
                || (arguments.length == 2 && !arguments[1].matches("[A-Za-z][A-Za-z0-9_]*"))) {
            throw new IllegalArgumentException("MYSQL_TEST_CONFIGURATION_REQUIRED");
        }
        // 现场升级路径新增用例可单独验证，避免重复执行已经通过的无关场景。
        var selector = arguments.length == 2 ? DiscoverySelectors.selectMethod(MySqlCompatibilityTest.class, arguments[1])
                : DiscoverySelectors.selectClass(MySqlCompatibilityTest.class);
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selector).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));
        summary.printFailuresTo(new PrintWriter(System.out, true));
        Map<String, Long> result = Map.of("tests", summary.getTestsFoundCount(), "passed", summary.getTestsSucceededCount(),
                "failed", summary.getTestsFailedCount(), "skipped", summary.getTestsSkippedCount(),
                "aborted", summary.getTestsAbortedCount(), "containersFailed", summary.getContainersFailedCount());
        Files.writeString(Path.of(arguments[0]), new ObjectMapper().writeValueAsString(result));
        // 不能把未发现用例、被条件跳过或初始化失败当作真实 MySQL 验收通过。
        if (summary.getTestsFoundCount() == 0 || summary.getTestsFoundCount() != summary.getTestsSucceededCount()
                || summary.getContainersFailedCount() != 0) System.exit(1);
    }
}
