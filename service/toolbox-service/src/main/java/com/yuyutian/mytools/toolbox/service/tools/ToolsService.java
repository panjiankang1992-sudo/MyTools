package com.yuyutian.mytools.toolbox.service.tools;

import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.model.PdfExecutionLimits;
import com.yuyutian.mytools.toolbox.model.ToolCapability;
import com.yuyutian.mytools.toolbox.model.ToolCategory;
import com.yuyutian.mytools.toolbox.model.ToolLimits;
import com.yuyutian.mytools.toolbox.model.ToolsResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 工具与能力查询服务：把注册表、限额与功能开关组装成稳定的 /tools 响应。
 */
@Service
public class ToolsService {

    /** 普通 JSON 请求体上限，取自契约 5.5 的 1 MiB。 */
    private static final long JSON_BODY_BYTES = 1_048_576L;
    /** 单次文本输入 UTF-8 字节上限，取自契约 5.5。 */
    private static final long TEXT_BYTES = 262_144L;

    private final ToolRegistry toolRegistry;
    private final ToolboxProperties properties;

    /**
     * 构造服务。
     *
     * @param toolRegistry 工具注册表
     * @param properties   门面配置
     */
    public ToolsService(ToolRegistry toolRegistry, ToolboxProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 返回 /tools 的完整响应。
     *
     * @return 工具注册、能力、限额与策略版本
     */
    public ToolsResponse describe() {
        // PDF 执行链未启用时，公开站只在浏览器本机处理文件，不能声明服务端执行。
        boolean pdfEnabled = properties.pdfEnabled();
        return new ToolsResponse(
                properties.policyVersion(),
                pdfEnabled ? "server" : "local",
                toolRegistry.all(),
                capabilities(),
                limits());
    }

    /**
     * 组装分类与服务能力声明。
     *
     * @return 能力列表
     */
    private List<ToolCapability> capabilities() {
        boolean pdfEnabled = properties.pdfEnabled();
        boolean statsEnabled = properties.statsEnabled();
        return List.of(
                new ToolCapability(ToolCategory.PDF.value(), pdfEnabled, pdfEnabled ? "server" : "local",
                        pdfEnabled ? null : "capability.pdfDisabled"),
                new ToolCapability(ToolCategory.COPYBOOK.value(), true, "client", null),
                new ToolCapability(ToolCategory.WORDS.value(), true, "client", null),
                new ToolCapability("stats", statsEnabled, "server",
                        statsEnabled ? null : "capability.statsDisabled"));
    }

    /**
     * 组装限额声明：本服务实际执行的限额，以及尚未启用的公开 PDF 执行限额。
     *
     * @return 限额声明
     */
    private ToolLimits limits() {
        return new ToolLimits(
                JSON_BODY_BYTES,
                properties.events().maxBodyBytes(),
                TEXT_BYTES,
                properties.events().maxBatchSize(),
                properties.curriculum().entriesMaxLimit(),
                properties.session().ttlHours(),
                pdfExecutionLimits());
    }

    /**
     * 返回契约 5.5 的公开 PDF 初值；active=false 明确表示本部署尚未启用执行链。
     *
     * @return PDF 执行限额声明
     */
    private PdfExecutionLimits pdfExecutionLimits() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("maxFileBytes", 31_457_280L);
        values.put("maxTotalInputBytes", 52_428_800L);
        values.put("maxInputFiles", 30L);
        values.put("maxAttachmentFiles", 20L);
        values.put("maxInputPages", 100L);
        values.put("maxMergeOutputPages", 100L);
        values.put("maxSplitOutputPages", 200L);
        values.put("maxArtifacts", 100L);
        values.put("maxRenderPages", 30L);
        values.put("maxRenderPixels", 30_000_000L);
        values.put("maxRenderEdgePx", 16_000L);
        values.put("maxTextBytes", TEXT_BYTES);
        values.put("maxJsonBodyBytes", JSON_BODY_BYTES);
        values.put("chunkBytes", 1_048_576L);
        values.put("maxConcurrentTasksPerSession", 1L);
        values.put("maxGlobalWorkers", 1L);
        values.put("maxQueueDepth", 20L);
        values.put("queueBudgetMinutes", 120L);
        values.put("executionBudgetMinutes", 15L);
        values.put("publishBudgetMinutes", 5L);
        values.put("inspectionQueueDepth", 60L);
        values.put("maxStagedBytes", 5_368_709_120L);
        values.put("sessionTaskQuotaPer24h", 30L);
        return new PdfExecutionLimits(false, "limits.pdfExecutionInactive",
                Collections.unmodifiableMap(values));
    }
}
