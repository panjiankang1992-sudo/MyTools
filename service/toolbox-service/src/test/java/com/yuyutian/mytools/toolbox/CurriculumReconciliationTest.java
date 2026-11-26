package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookRepositoryAccess.EditionStat;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumReconciliationService;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumReconciliationService.Status;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文件仓储与数据库的双读对账（设计文档 §8 第 5 点）。
 * 重点：任何差异都必须被识别出来，且**不能因为"没有可比内容"就判定为安全**。
 */
class CurriculumReconciliationTest {

    /** 构造统计。 */
    private static EditionStat stat(String version, int count, String sha) {
        return new EditionStat(version, count, sha);
    }

    @Test
    @DisplayName("两侧逐册一致时才判定可以安全切换")
    void identicalMeansSwitchSafe() {
        Map<String, EditionStat> files = new LinkedHashMap<>();
        files.put("cn-ty-1-1", stat("1.0.0", 318, "aaa"));
        Map<String, EditionStat> database = new LinkedHashMap<>();
        database.put("cn-ty-1-1", stat("1.0.0", 318, "aaa"));

        var report = CurriculumReconciliationService.compare(files, database, true);
        assertThat(report.switchSafe()).isTrue();
        assertThat(report.comparisons()).hasSize(1);
        assertThat(report.comparisons().get(0).status()).isEqualTo(Status.IDENTICAL);
    }

    @Test
    @DisplayName("仅文件有的教材判为差异：切换会让公开目录缺少内容")
    void fileOnlyIsUnsafe() {
        // 文件侧有、数据库侧没有 → FILE_ONLY。
        Map<String, EditionStat> files = Map.of("cn-ty-1-1", stat("1.0.0", 318, "aaa"));
        var report = CurriculumReconciliationService.compare(files, Map.of(), true);
        assertThat(report.switchSafe()).isFalse();
        assertThat(report.comparisons().get(0).status()).isEqualTo(Status.FILE_ONLY);
        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("缺少"));
    }

    @Test
    @DisplayName("仅数据库有的教材判为差异：切换会让公开目录多出内容")
    void databaseOnlyIsUnsafe() {
        // 数据库侧有、文件侧没有 → DATABASE_ONLY。
        Map<String, EditionStat> database = Map.of("cn-ty-1-1", stat("1.0.0", 318, "aaa"));
        var report = CurriculumReconciliationService.compare(Map.of(), database, true);
        assertThat(report.switchSafe()).isFalse();
        assertThat(report.comparisons().get(0).status()).isEqualTo(Status.DATABASE_ONLY);
        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("多出"));
    }

    @Test
    @DisplayName("条目数不同或哈希不同都判为差异")
    void countOrHashDifferenceIsUnsafe() {
        Map<String, EditionStat> files = Map.of("book", stat("1.0.0", 318, "aaa"));
        var countDiffers = CurriculumReconciliationService.compare(files,
                Map.of("book", stat("1.0.0", 319, "aaa")), true);
        assertThat(countDiffers.comparisons().get(0).status()).isEqualTo(Status.DIFFERENT);
        assertThat(countDiffers.switchSafe()).isFalse();

        var hashDiffers = CurriculumReconciliationService.compare(files,
                Map.of("book", stat("1.0.0", 318, "bbb")), true);
        assertThat(hashDiffers.comparisons().get(0).status()).isEqualTo(Status.DIFFERENT);
    }

    @Test
    @DisplayName("文件仓储不可读时不能判定为安全，也不能被当成没有差异")
    void unreadableFilesAreNeverSafe() {
        var report = CurriculumReconciliationService.compare(null,
                Map.of("book", stat("1.0.0", 318, "aaa")), false);
        assertThat(report.switchSafe()).isFalse();
        assertThat(report.comparisons().get(0).status()).isEqualTo(Status.FILE_UNREADABLE);
        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("不可读"));
    }

    @Test
    @DisplayName("两侧都为空时不判定为安全（取数出问题不能因无差异而放行）")
    void emptyOnBothSidesIsNotSafe() {
        var report = CurriculumReconciliationService.compare(Map.of(), Map.of(), true);
        assertThat(report.switchSafe()).isFalse();
        assertThat(report.totalCount()).isZero();
    }

    @Test
    @DisplayName("结论按教材标识排序，便于人工逐条核对")
    void comparisonsAreSorted() {
        Map<String, EditionStat> files = new LinkedHashMap<>();
        files.put("z-book", stat("1.0.0", 1, "a"));
        files.put("a-book", stat("1.0.0", 1, "a"));
        files.put("m-book", stat("1.0.0", 1, "a"));
        var report = CurriculumReconciliationService.compare(files, files, true);
        assertThat(report.comparisons()).extracting("editionId")
                .containsExactly("a-book", "m-book", "z-book");
    }
}
