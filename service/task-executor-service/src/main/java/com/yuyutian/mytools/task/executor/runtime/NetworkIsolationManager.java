package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorNetworkIsolationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 使用独立 Linux 网络命名空间实施脚本默认拒绝网络策略。
 */
@Component
public class NetworkIsolationManager {

    private final ExecutorNetworkIsolationProperties properties;

    /**
     * 创建脚本网络隔离管理器。
     *
     * @param properties 网络隔离配置
     */
    public NetworkIsolationManager(ExecutorNetworkIsolationProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据脚本包策略包装命令。
     *
     * @param scriptPackage 脚本包名称
     * @param command 原始命令
     * @return 隔离后的参数列表
     * @throws IOException 隔离工具不可用或域名放行能力尚未部署
     */
    public List<String> wrap(String scriptPackage, List<String> command) throws IOException {
        if (!properties.enabled()) {
            return List.copyOf(command);
        }
        Map<String, Set<String>> policies = properties.allowedDomains() == null
                ? Map.of() : properties.allowedDomains();
        Set<String> allowedDomains = policies.getOrDefault(scriptPackage, Set.of());
        if (!allowedDomains.isEmpty()) {
            // 仅声明域名不能形成内核安全边界；透明网关未接入前不得退化为完全放行。
            throw new IOException("Executor domain allowlist gateway is not configured for script package "
                    + scriptPackage);
        }
        Path bubblewrap = properties.bubblewrapPath();
        if (bubblewrap == null || !Files.isRegularFile(bubblewrap) || !Files.isExecutable(bubblewrap)) {
            throw new IOException("Executor default-deny network isolation requires bubblewrap");
        }
        List<String> isolated = new ArrayList<>(command.size() + 12);
        isolated.add(bubblewrap.toString());
        isolated.add("--unshare-net");
        isolated.add("--die-with-parent");
        isolated.add("--new-session");
        // 本组件只负责网络边界；文件访问继续由非 root 用户、发布索引和工作目录权限控制。
        isolated.add("--bind");
        isolated.add("/");
        isolated.add("/");
        isolated.add("--proc");
        isolated.add("/proc");
        isolated.add("--dev-bind");
        isolated.add("/dev");
        isolated.add("/dev");
        isolated.add("--");
        isolated.addAll(command);
        return List.copyOf(isolated);
    }

    /**
     * 校验网络命名空间工具和当前内核权限可实际使用。
     *
     * @throws IOException 配置声明无法安全执行
     */
    public void validate() throws IOException {
        if (!properties.enabled()) {
            return;
        }
        Map<String, Set<String>> policies = properties.allowedDomains() == null
                ? Map.of() : properties.allowedDomains();
        if (policies.values().stream().anyMatch(value -> value != null && !value.isEmpty())) {
            throw new IOException("Executor domain allowlist gateway is not configured");
        }
        Process probe = new ProcessBuilder(wrap("", List.of("/bin/true")))
                .redirectErrorStream(true).start();
        try {
            if (!probe.waitFor(5, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                throw new IOException("Executor network namespace probe timed out");
            }
            if (probe.exitValue() != 0) {
                throw new IOException("Executor network namespace probe failed");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Executor network namespace probe was interrupted", exception);
        }
    }
}
