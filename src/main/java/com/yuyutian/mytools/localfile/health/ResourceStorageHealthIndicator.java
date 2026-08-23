package com.yuyutian.mytools.localfile.health;

import com.yuyutian.mytools.localfile.service.ResourceStorageGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 资源盘后端健康指标。
 */
@Component("resourceStorage")
@RequiredArgsConstructor
public class ResourceStorageHealthIndicator implements HealthIndicator {

    private final ResourceStorageGuard resourceStorageGuard;

    /**
     * 返回资源盘健康检查结果。
     *
     * @return Spring Boot 健康结果
     */
    @Override
    public Health health() {
        ResourceStorageGuard.StorageStatus status = resourceStorageGuard.status();
        Health.Builder builder = status.available() ? Health.up() : Health.down();
        return builder.withDetail("mounted", status.mounted())
                .withDetail("rootReadable", status.rootReadable())
                .withDetail("mediaReadable", status.mediaReadable())
                .withDetail("thumbnailsReadable", status.thumbnailsReadable())
                .withDetail("reason", status.reason())
                .withDetail("checkedAt", status.checkedAt().toString())
                .build();
    }
}
