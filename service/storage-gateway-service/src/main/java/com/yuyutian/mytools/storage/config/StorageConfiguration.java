package com.yuyutian.mytools.storage.config;

import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储网关启动配置。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    /**
     * 创建并登记默认受管根。
     *
     * @param properties 存储配置
     * @param repository 存储仓储
     * @return 启动任务
     */
    @Bean
    public ApplicationRunner managedRootInitializer(StorageProperties properties, StorageRepository repository) {
        return arguments -> {
            Path root = properties.defaultRootPath().toAbsolutePath().normalize();
            try {
                Files.createDirectories(root);
            } catch (IOException exception) {
                throw new IllegalStateException("Managed storage root cannot be created", exception);
            }
            String label = properties.defaultRootNodeLabel() == null || properties.defaultRootNodeLabel().isBlank()
                    ? "storage.mount." + properties.defaultRootName() : properties.defaultRootNodeLabel();
            String value = properties.defaultRootNodeValue() == null || properties.defaultRootNodeValue().isBlank()
                    ? "present" : properties.defaultRootNodeValue();
            if (!label.matches("^[A-Za-z][A-Za-z0-9_.-]{0,127}$") || value.length() > 256) {
                throw new IllegalStateException("Managed storage root node affinity is invalid");
            }
            repository.ensureRoot(properties.defaultRootName(), properties.defaultRootPurpose(), root.toString(),
                    label, value);
        };
    }
}
