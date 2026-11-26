package com.yuyutian.mytools.toolbox.common;

import com.yuyutian.mytools.toolbox.config.AdminProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 管理员登录的失败尝试限流。
 *
 * 设计第 6 节要求管理端登录／保存有限流（`TOOLBOX_ADMIN_008`，并返回 `Retry-After`）。
 *
 * **关于"与现有规则保持一致"**：这一条我**没有做到**——
 * 我在本仓库与远程部署上都找不到现成的登录限流规则可对齐
 * （远程只有构建产物，没有源码，也没有相关配置项）。
 * 因此这里采用的是一套**可辩护的默认值**，并且**全部可配置**：
 * 若你那边确实有既有规则，改配置即可对齐，不必改代码。
 *
 * **只统计失败**：成功登录不计入配额——否则正常用户会在多次成功登录后被挡住。
 * 这样"防暴力破解"与"不影响正常使用"两个目标才不冲突。
 *
 * **单实例内存实现**：当前部署是单实例（systemd 单进程），因此够用。
 * 若将来多实例，这里需要换成共享存储——**这一点写在这里，免得将来被误认为"已经支持集群"**。
 */
@Component
public class AdminLoginRateLimiter {

    /** 每个来源保留的失败时间戳；用双端队列便于淘汰过期的。 */
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    private final AdminProperties properties;

    /**
     * 构造限流器。
     *
     * @param properties 管理端配置（提供窗口与次数上限）
     */
    public AdminLoginRateLimiter(AdminProperties properties) {
        this.properties = properties;
    }

    /**
     * 判断某来源当前是否已被限流。
     *
     * @param source 来源标识（通常是客户端 IP）
     * @return 需要等待的秒数；0 表示未被限流
     */
    public long retryAfterSeconds(String source) {
        long limit = properties.loginMaxFailures();
        if (limit <= 0) {
            return 0;
        }
        Duration window = Duration.ofSeconds(properties.loginWindowSeconds());
        Deque<Instant> queue = failures.get(source);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            Instant cutoff = Instant.now().minus(window);
            while (!queue.isEmpty() && queue.peekFirst().isBefore(cutoff)) {
                queue.pollFirst();
            }
            if (queue.size() < limit) {
                return 0;
            }
            // 还要等第一个失败记录滑出窗口。
            Instant oldest = queue.peekFirst();
            Instant until = oldest.plus(window);
            long seconds = Duration.between(Instant.now(), until).getSeconds();
            return Math.max(seconds, 1);
        }
    }

    /**
     * 记录一次登录失败。
     *
     * @param source 来源标识
     */
    public void recordFailure(String source) {
        if (properties.loginMaxFailures() <= 0) {
            return;
        }
        Deque<Instant> queue = failures.computeIfAbsent(source, key -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(Instant.now());
        }
    }

    /**
     * 登录成功后清空该来源的失败记录。
     *
     * @param source 来源标识
     */
    public void clear(String source) {
        failures.remove(source);
    }
}
