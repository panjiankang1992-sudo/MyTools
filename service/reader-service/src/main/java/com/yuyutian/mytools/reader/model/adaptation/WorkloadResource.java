package com.yuyutian.mytools.reader.model.adaptation;

import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;

import java.util.UUID;

/** 三类后台资源具有互斥的任务包、audience 和参数摘要，不能跨资源复用授权。 */
public enum WorkloadResource {
    CHAPTER_ADAPTATION("reader_adapt_novel_chapter", "reader-adaptation-internal", "adaptationId"),
    EBOOK_BINDING("reader_project_ebook_text", "reader-ebook-projection-internal", "bindingId"),
    PROVIDER_PROBE("reader_probe_novel_adaptation_provider", "reader-provider-probe-internal", "probeId");
    private final String packageName;
    private final String audience;
    private final String parameter;

    WorkloadResource(String packageName, String audience, String parameter) {
        this.packageName = packageName;
        this.audience = audience;
        this.parameter = parameter;
    }

    /** 返回不可变任务包名称。 */
    public String packageName() { return packageName; }
    /** 返回该资源唯一的 audience。 */
    public String audience() { return audience; }
    /** 依据路径资源标识复算 Scheduler 单一参数的规范 SHA-256。 */
    public String parametersSha256(UUID resourceId) { return AdaptationText.sha256("{\"" + parameter + "\":\"" + resourceId + "\"}"); }
}
