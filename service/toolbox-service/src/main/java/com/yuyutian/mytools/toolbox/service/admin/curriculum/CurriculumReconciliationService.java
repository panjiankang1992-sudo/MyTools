package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 文件仓储与数据库的双读对账（设计文档 §8 第 5 点）。
 *
 * 用途：在把公开读取来源从文件切到数据库**之前**，证明两者内容一致。
 * 若直接切换而不对账，一旦数据库缺少某册或内容有差异，公开页面会静默少内容或内容变化——
 * 这类问题不会报错，只会让用户看到"变了"。
 *
 * 对账是**只读**的：独立实例加载文件仓储，不触碰当前在用的目录快照。
 */
@Service
public class CurriculumReconciliationService {

    private final ToolboxProperties properties;
    private final ObjectMapper objectMapper;
    private final CurriculumSnapshotLoader loader;
    private final AdminTextbookRepositoryAccess repositoryAccess;

    /**
     * 创建对账服务。
     *
     * @param properties       门面配置，用于取得文件仓储目录
     * @param objectMapper     JSON 解析器
     * @param loader           数据库快照加载器
     * @param repositoryAccess 数据库侧统计访问
     */
    public CurriculumReconciliationService(ToolboxProperties properties, ObjectMapper objectMapper,
                                           CurriculumSnapshotLoader loader,
                                           AdminTextbookRepositoryAccess repositoryAccess) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.loader = loader;
        this.repositoryAccess = repositoryAccess;
    }

    /**
     * 单册对账结果。
     *
     * @param editionId       教材标识
     * @param fileVersion     文件仓储中的版本；缺失为 null
     * @param databaseVersion 数据库中的版本；缺失为 null
     * @param fileEntryCount  文件侧条目数
     * @param databaseEntryCount 数据库侧条目数
     * @param fileSha256      文件侧正文字节哈希
     * @param databaseSha256  数据库侧正文字节哈希
     * @param status          对账结论
     */
    public record EditionComparison(String editionId, String fileVersion, String databaseVersion,
                                    int fileEntryCount, int databaseEntryCount,
                                    String fileSha256, String databaseSha256, String status) {
    }

    /** 对账结论取值。 */
    public static final class Status {
        /** 两侧完全一致。 */
        public static final String IDENTICAL = "identical";
        /** 仅文件仓储有。 */
        public static final String FILE_ONLY = "fileOnly";
        /** 仅数据库有。 */
        public static final String DATABASE_ONLY = "databaseOnly";
        /** 两侧都有但内容不同。 */
        public static final String DIFFERENT = "different";
        /** 文件仓储加载失败（目录缺失或校验不通过）。 */
        public static final String FILE_UNREADABLE = "fileUnreadable";

        private Status() {
        }
    }

    /**
     * 对账结果汇总。
     *
     * @param comparisons    逐册结论
     * @param identicalCount 一致册数
     * @param totalCount     两侧出现过的教材总数
     * @param switchSafe     是否可以直接把公开来源切到数据库
     * @param notes          需要人工查看的说明
     */
    public record ReconciliationReport(List<EditionComparison> comparisons, int identicalCount, int totalCount,
                                       boolean switchSafe, List<String> notes) {
    }

    /**
     * 比较两侧逐册统计，得出对账结论。
     *
     * 抽成静态纯函数：比较规则（什么算一致、什么算差异）是这块逻辑里最容易出错的部分，
     * 而它不依赖任何仓储，可以直接用构造的统计验证。
     *
     * @param fileStats     文件侧统计；文件不可读时传 null
     * @param databaseStats 数据库侧统计
     * @param fileReadable  文件仓储是否可读
     * @return 对账报告
     */
    public static ReconciliationReport compare(Map<String, AdminTextbookRepositoryAccess.EditionStat> fileStats,
                                               Map<String, AdminTextbookRepositoryAccess.EditionStat> databaseStats,
                                               boolean fileReadable) {
        Map<String, AdminTextbookRepositoryAccess.EditionStat> files =
                fileStats == null ? Map.of() : fileStats;
        List<String> notes = new ArrayList<>();
        TreeSet<String> editionIds = new TreeSet<>();
        editionIds.addAll(files.keySet());
        editionIds.addAll(databaseStats.keySet());

        List<EditionComparison> comparisons = new ArrayList<>();
        int identical = 0;
        for (String editionId : editionIds) {
            var file = files.get(editionId);
            var database = databaseStats.get(editionId);
            String status;
            if (!fileReadable) {
                status = Status.FILE_UNREADABLE;
            } else if (file == null) {
                status = Status.DATABASE_ONLY;
            } else if (database == null) {
                status = Status.FILE_ONLY;
            } else if (file.entryCount() == database.entryCount()
                    && java.security.MessageDigest.isEqual(
                            safe(file.sha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            safe(database.sha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                status = Status.IDENTICAL;
                identical += 1;
            } else {
                status = Status.DIFFERENT;
            }
            comparisons.add(new EditionComparison(editionId,
                    file == null ? null : file.version(), database == null ? null : database.version(),
                    file == null ? 0 : file.entryCount(), database == null ? 0 : database.entryCount(),
                    file == null ? null : file.sha256(), database == null ? null : database.sha256(),
                    status));
        }
        comparisons.sort(Comparator.comparing(EditionComparison::editionId));

        if (!fileReadable) {
            notes.add("文件仓储不可读，无法得出可比结论；此时不应切换来源。");
        }
        if (comparisons.stream().anyMatch(item -> Status.DATABASE_ONLY.equals(item.status()))) {
            notes.add("存在仅数据库有的教材：切换后公开目录会多出这些内容。");
        }
        if (comparisons.stream().anyMatch(item -> Status.FILE_ONLY.equals(item.status()))) {
            notes.add("存在仅文件有的教材：切换后公开目录会缺少这些内容。");
        }
        if (comparisons.stream().anyMatch(item -> Status.DIFFERENT.equals(item.status()))) {
            notes.add("存在内容不同的教材：切换后公开内容会发生变化。");
        }

        // 只有"文件可读、两侧都有教材、逐册内容一致"才认为可以安全切换。
        // 空集合不算安全：两侧都没有内容说明取数出了问题，不能因为"没有差异"就放行。
        boolean switchSafe = fileReadable && !comparisons.isEmpty()
                && comparisons.stream().allMatch(item -> Status.IDENTICAL.equals(item.status()));
        return new ReconciliationReport(List.copyOf(comparisons), identical, comparisons.size(), switchSafe,
                List.copyOf(notes));
    }

    /**
     * 执行对账。
     *
     * @return 对账报告
     */
    public ReconciliationReport reconcile() {
        List<String> notes = new ArrayList<>();
        CurriculumCatalog fileCatalog = new CurriculumCatalog(Path.of(properties.catalogPath()), objectMapper);
        boolean fileReadable = true;
        try {
            fileCatalog.load();
        } catch (RuntimeException failure) {
            // 文件仓储不可读本身不是"差异"，但绝不能被当成"没有差异"。
            fileReadable = false;
            notes.add("文件仓储加载失败：" + failure.getMessage());
        }

        if (!fileReadable) {
            notes.add("文件仓储不可读，无法得出可比结论；此时不应切换来源。");
        }
        var report = compare(repositoryAccess.fileStatistics(fileReadable ? fileCatalog : null),
                repositoryAccess.databaseStatistics(loader), fileReadable);
        // 把加载阶段的说明合并进报告，避免调用方丢失"文件不可读"这一前提。
        List<String> merged = new ArrayList<>(notes);
        merged.addAll(report.notes());
        return new ReconciliationReport(report.comparisons(), report.identicalCount(), report.totalCount(),
                report.switchSafe(), List.copyOf(merged));
    }

    /** 空值转空串，避免比较时抛空指针。 */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
