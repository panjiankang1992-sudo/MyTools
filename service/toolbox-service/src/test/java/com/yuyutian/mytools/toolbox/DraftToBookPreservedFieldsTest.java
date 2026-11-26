package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F03 的**映射侧**：草稿里保留的字段必须写回正文。
 *
 * 上一轮我只验证了"导入时把字段塞进草稿"（书 → 草稿），
 * 但**没有验证"发布时把它们写回"**（草稿 → 书）。
 * 保留了一半等于没保留：管理员改一个字并发布，
 * 第二道门若把字段丢掉，结果与修复前一样。
 *
 * 这条测试**在修复前必然失败**——那时映射器把这几个字段写死为 null／空表。
 */
class DraftToBookPreservedFieldsTest {

    private final DraftToBookMapper mapper = new DraftToBookMapper();

    /** 造一个带保留字段的草稿。 */
    private static AdminTextbookDraft draftWithPreserved(Map<String, Object> preserved) {
        DraftUnit unit = new DraftUnit(null, "cu1", "第一单元", 1);
        DraftEntry entry = new DraftEntry("row-1", "e-old", null, "cu1", null, null,
                "山", "shān", null, List.of("writing"), false, null, true, preserved);
        return new AdminTextbookDraft("book-preserved", "chinese", 1, "first", "人民教育出版社",
                "统编语文", "2024 年修订版", "附录", "partial", List.of(unit), List.of(), List.of(entry));
    }

    @Test
    @DisplayName("草稿保留的词性／义项／上下文／答案／来源必须写回正文")
    void preservedFieldsSurviveMapping() {
        Map<String, Object> preserved = Map.of(
                "partOfSpeech", "名词",
                "senseId", "sense-1",
                "context", "远上寒山石径斜",
                "promptContext", "山 prompts",
                "acceptableAnswers", List.of("山岳"),
                "sourceRefs", List.of(Map.of("sourceId", "src-1", "locator", "p.12", "page", 12)));

        var mapped = mapper.map("book-preserved", draftWithPreserved(preserved), "1.0.0");

        assertThat(mapped.entries()).hasSize(1);
        var entry = mapped.entries().get(0);
        assertThat(entry.partOfSpeech()).as("词性不能被清空").isEqualTo("名词");
        assertThat(entry.senseId()).as("义项标识不能被清空").isEqualTo("sense-1");
        assertThat(entry.context()).as("上下文不能被清空").isEqualTo("远上寒山石径斜");
        assertThat(entry.promptContext()).as("提示语不能被清空").isEqualTo("山 prompts");
        assertThat(entry.acceptableAnswers()).as("可接受答案不能被清空").containsExactly("山岳");
        assertThat(entry.sourceRefs()).as("来源记录不能被清空").hasSize(1);
        assertThat(entry.sourceRefs().get(0).sourceId()).isEqualTo("src-1");
        assertThat(entry.sourceRefs().get(0).page()).isEqualTo(12);
    }

    @Test
    @DisplayName("没有保留字段时不应凭空造出值（空就是空）")
    void absentPreservedStaysEmpty() {
        // 反向断言：修复不能变成"给所有条目塞上假数据"。
        var mapped = mapper.map("book-preserved", draftWithPreserved(Map.of()), "1.0.0");
        var entry = mapped.entries().get(0);
        assertThat(entry.partOfSpeech()).isNull();
        assertThat(entry.context()).isNull();
        assertThat(entry.acceptableAnswers()).isEmpty();
        assertThat(entry.sourceRefs()).isEmpty();
    }
}
