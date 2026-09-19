package com.yuyutian.mytools.task.scheduler.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** 任务名到资源权限的固定映射，不接受任务参数指定 audience 或包名。 */
public enum WorkloadResource {
    ADAPTATION("reader_adapt_novel_chapter", "adaptationId", "CHAPTER_ADAPTATION", "reader-adaptation-internal"),
    PROJECTION("reader_project_ebook_text", "bindingId", "EBOOK_BINDING", "reader-ebook-projection-internal"),
    PROBE("reader_probe_novel_adaptation_provider", "probeId", "PROVIDER_PROBE", "reader-provider-probe-internal");

    private final String taskName;
    private final String parameter;
    private final String resourceType;
    private final String audience;

    WorkloadResource(String taskName, String parameter, String resourceType, String audience) {
        this.taskName = taskName;
        this.parameter = parameter;
        this.resourceType = resourceType;
        this.audience = audience;
    }

    /** 查询受保护的任务，大小写变体也不能退回无授权执行。 */
    public static WorkloadResource forTask(String name) {
        for (WorkloadResource value : values()) {
            if (value.taskName.equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    /** 只有单个规范 UUID 参数才可形成签名中的不可变摘要。 */
    public String parametersSha256(Map<String, Object> parameters) {
        if (parameters == null || parameters.size() != 1 || !(parameters.get(parameter) instanceof String raw)) {
            throw new IllegalArgumentException("Invalid workload resource parameters");
        }
        UUID id = UUID.fromString(raw);
        if (!id.toString().equals(raw)) {
            throw new IllegalArgumentException("Invalid workload resource identity");
        }
        return sha256("{\"" + parameter + "\":\"" + id + "\"}");
    }

    /** 返回固定任务及包名。 */
    public String taskName() {
        return taskName;
    }

    /** 返回资源类型。 */
    public String resourceType() {
        return resourceType;
    }

    /** 返回内部受众。 */
    public String audience() {
        return audience;
    }

    /** 计算无正文的授权标识或固定参数摘要。 */
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
