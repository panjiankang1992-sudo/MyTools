package com.yuyutian.mytools.toolbox.service.tools;

import com.yuyutian.mytools.toolbox.model.ToolCategory;
import com.yuyutian.mytools.toolbox.model.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工具注册表：契约第 2 节的 12 个 toolId 是唯一事实来源，以 Java 常量维护而不是数据库。
 * 事件校验与 /tools 响应共用同一份定义，避免前后端两套名单。
 */
@Component
public class ToolRegistry {

    /** 字帖类工具标识，只允许上报 worksheet_success。 */
    private static final Set<String> WORKSHEET_TOOL_IDS = Set.of(
            "worksheet.chinese-dictation",
            "worksheet.chinese-pinyin",
            "worksheet.english-dictation",
            "worksheet.english-meaning");

    /** 全部工具定义，顺序稳定，供注册表查询使用。 */
    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition("pdf.images", ToolCategory.PDF, "/pdf/images", "toolPdfImages", "toolPdfImagesDesc", "image", true, 10),
            new ToolDefinition("pdf.text", ToolCategory.PDF, "/pdf/text", "toolPdfText", "toolPdfTextDesc", "text", true, 20),
            new ToolDefinition("pdf.markdown", ToolCategory.PDF, "/pdf/markdown", "toolPdfMarkdown", "toolPdfMarkdownDesc", "markdown", true, 30),
            new ToolDefinition("pdf.merge", ToolCategory.PDF, "/pdf/merge", "toolPdfMerge", "toolPdfMergeDesc", "merge", true, 40),
            new ToolDefinition("pdf.split", ToolCategory.PDF, "/pdf/split", "toolPdfSplit", "toolPdfSplitDesc", "split", true, 50),
            new ToolDefinition("pdf.crop", ToolCategory.PDF, "/pdf/crop", "toolPdfCrop", "toolPdfCropDesc", "crop", true, 60),
            new ToolDefinition("pdf.render", ToolCategory.PDF, "/pdf/render", "toolPdfRender", "toolPdfRenderDesc", "render", true, 70),
            new ToolDefinition("pdf.extract", ToolCategory.PDF, "/pdf/extract", "toolPdfExtract", "toolPdfExtractDesc", "extract", true, 80),
            new ToolDefinition("worksheet.chinese-dictation", ToolCategory.COPYBOOK, "/copybook", "modeChineseDictation", "modeChineseDictationDesc", "pencil", true, 10),
            new ToolDefinition("worksheet.chinese-pinyin", ToolCategory.COPYBOOK, "/copybook", "modeChinesePinyin", "modeChinesePinyinDesc", "pencil", true, 20),
            new ToolDefinition("worksheet.english-dictation", ToolCategory.WORDS, "/words", "modeEnglishDictation", "modeEnglishDictationDesc", "book", true, 10),
            new ToolDefinition("worksheet.english-meaning", ToolCategory.WORDS, "/words", "modeEnglishMeaning", "modeEnglishMeaningDesc", "book", true, 20));

    private final Map<String, ToolDefinition> byId;

    /**
     * 构造注册表并建立标识索引。
     */
    public ToolRegistry() {
        Map<String, ToolDefinition> index = new LinkedHashMap<>();
        for (ToolDefinition definition : TOOLS) {
            index.put(definition.id(), definition);
        }
        this.byId = Map.copyOf(index);
    }

    /**
     * 返回全部工具定义，顺序与契约表格一致。
     *
     * @return 工具定义列表
     */
    public List<ToolDefinition> all() {
        return TOOLS;
    }

    /**
     * 按标识查找工具。
     *
     * @param toolId 工具标识
     * @return 工具定义或空
     */
    public Optional<ToolDefinition> find(String toolId) {
        return toolId == null ? Optional.empty() : Optional.ofNullable(byId.get(toolId));
    }

    /**
     * 判断标识是否为字帖类工具。
     *
     * @param toolId 工具标识
     * @return 是否为四个 worksheet.* 工具之一
     */
    public boolean isWorksheetTool(String toolId) {
        return toolId != null && WORKSHEET_TOOL_IDS.contains(toolId);
    }

    /**
     * 返回四个字帖工具标识。
     *
     * @return 字帖工具标识集合
     */
    public Set<String> worksheetToolIds() {
        return WORKSHEET_TOOL_IDS;
    }
}
