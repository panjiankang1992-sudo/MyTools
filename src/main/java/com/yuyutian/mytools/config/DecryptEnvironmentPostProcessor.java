package com.yuyutian.mytools.config;

import com.yuyutian.mytools.utils.AesEncryptUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 环境变量后置处理器。
 * 在 Spring Boot 加载 YAML 配置之前解密数据库密码。
 * 使用方式：在 application.yml 中配置 password: aes:加密后的密码
 *
 * @author mytools
 * @since 2026-04-22
 */
public class DecryptEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String AES_KEY = "CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8=";
    private static final String PROPERTY_SOURCE_NAME = "decryptedDatasource";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] datasourceKeys = {"my_tools", "sales_order"};
        Map<String, Object> decryptedProps = new HashMap<>();

        for (String key : datasourceKeys) {
            String passwordKey = "spring.datasource.dynamic.datasource." + key + ".password";
            String encryptedPassword = environment.getProperty(passwordKey, "");

            if (encryptedPassword.startsWith("aes:")) {
                String encrypted = encryptedPassword.substring(4);
                try {
                    String decrypted = AesEncryptUtils.decrypt(encrypted, AES_KEY);
                    decryptedProps.put(passwordKey, decrypted);
                } catch (Exception e) {
                    throw new RuntimeException("密码解密失败: " + key, e);
                }
            }
        }

        if (!decryptedProps.isEmpty()) {
            environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, decryptedProps)
            );
        }
    }
}