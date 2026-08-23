package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.storage.model.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从明确的环境变量引用解析 JSON 凭据。
 */
@Component
public class EnvironmentSecretMaterialResolver implements SecretMaterialResolver {
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private final ObjectMapper objectMapper;

    /**
     * 创建环境变量密钥解析器。
     *
     * @param objectMapper JSON 映射器
     */
    public EnvironmentSecretMaterialResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 env 引用中的 JSON 对象。
     *
     * @param secretRef env 密钥引用
     * @return 不可变凭据字段
     */
    @Override
    public Map<String, String> resolve(String secretRef) {
        if (secretRef == null || !secretRef.startsWith("env://")) {
            throw new IllegalStateException(ErrorCode.SECRET_UNAVAILABLE.code());
        }
        String name = secretRef.substring("env://".length());
        if (!ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new IllegalStateException(ErrorCode.SECRET_UNAVAILABLE.code());
        }
        String value = System.getenv(name);
        if (value == null || value.length() > 32 * 1024) {
            throw new IllegalStateException(ErrorCode.SECRET_UNAVAILABLE.code());
        }
        try {
            Map<String, String> result = objectMapper.readValue(value, new TypeReference<>() { });
            return Map.copyOf(result);
        } catch (IOException | NullPointerException exception) {
            throw new IllegalStateException(ErrorCode.SECRET_UNAVAILABLE.code(), exception);
        }
    }
}
