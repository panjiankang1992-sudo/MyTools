package com.yuyutian.mytools.toolbox.service.admin.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yuyutian.mytools.toolbox.model.CurriculumEntry;
import com.yuyutian.mytools.toolbox.model.EntrySourceRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 来源快照构建器：在**内存里**把发布／导入／补录当时的来源事实冻结成一份可核对、可确定性序列化的文档。
 *
 * 为什么需要它：`source_metadata_json` 两列早已存在，却**从来没有任何代码写或读**，
 * 于是"发布时用的是哪份来源"事后无从证明；本地 `sources/*.json` 一旦被改动，
 * 导出侧若重新读盘，就会拿今天的内容冒充当年的来源。
 *
 * 快照分六层，逐层只记录**当时确实存在**的事实：
 * 1. 快照自身：结构版本、服务端采集时刻、采集人、发布／导入操作标识；
 * 2. 对应教材版本：教材标识、内容版本、原正文 SHA-256；
 * 3. 导入目录基线：原 manifest 的 `catalogVersion`／`schemaVersion`、本册声明元数据，
 *    以及原 manifest 的原始 UTF-8 文本与 SHA-256（**绝不用 `contentVersion` 冒充 `catalogVersion`**）；
 * 4. 来源记录：按 `edition.sourceIds` 与条目 `sourceRefs` 解析内容根目录的 `sources/*.json`，
 *    原样冻结命中的完整来源对象；
 * 5. 文件证据：命中来源文件在内容根目录内的**相对**路径、原始 UTF-8 文本与 SHA-256；
 * 6. 条目定位：原样保留每条目的 `{sourceId, locator, page}` 对应关系。
 *
 * 两条硬约束：
 * - **缺失即标记缺失**：没有的字段进入 `missingFields`／顶层 `missing`，绝不由机器编造
 *   `verifiedAt`／`reviewedBy` 之类"已经人工核对"的证据；
 * - **确定性**：同一输入必然得到同一字节与同一 SHA-256（键排序、列表排序、时间由调用方传入）。
 *
 * 快照只装内容根目录内的相对文件名，**不装机器绝对路径、凭据或环境配置**。
 */
@Component
public class SourceSnapshotBuilder {

    /** 当前快照结构版本。 */
    public static final int SNAPSHOT_SCHEMA_VERSION = 1;

    /**
     * 来源记录里"应当有"的字段清单。
     *
     * 只用于**标记缺失**：命中对象里没有的字段会被列进 `missingFields`，
     * 而不是被填上一个编造的值。它不校验、不拒绝任何来源文件。
     */
    private static final List<String> EXPECTED_SOURCE_FIELDS = List.of(
            "sourceId", "kind", "title", "publisher", "isbn", "editionLabel", "url", "locator", "locators",
            "accessBasis", "verifiedAt", "reviewedBy", "reviewNotes", "revisionReviews", "evidence",
            "knownGaps", "purpose", "notes", "contentSha256", "crossChecks", "reconciliation");

    /** 内容根目录内的来源目录名。 */
    private static final String SOURCES_DIR = "sources";

    /** 内容根目录内的清单目录名。 */
    private static final String CATALOG_DIR = "catalog";

    /** 内容根目录内的清单文件名。 */
    private static final String MANIFEST_FILE = "published.json";

    /** 快照内部使用的确定性序列化器：不继承运行环境里的 Jackson 配置，避免输出随配置漂移。 */
    private final ObjectMapper canonical;

    /**
     * 创建构建器。
     */
    public SourceSnapshotBuilder() {
        this.canonical = new ObjectMapper();
        // 映射键排序：来源对象里的键顺序不同不应改变快照字节。
        this.canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 构建快照。
     *
     * @param request 构建输入；内容根目录可缺省，缺省时对应层标记缺失而不抛错
     * @return 快照 JSON、其 SHA-256 与结构化节点
     */
    public SnapshotResult build(SnapshotRequest request) {
        List<String> missing = new ArrayList<>();
        // 第 3 层：导入目录基线。允许调用方显式传入（草稿继承、包内恢复），否则读内容根目录。
        ManifestBaseline baseline = request.manifestBaseline() != null
                ? request.manifestBaseline()
                : readManifestBaseline(request.contentRoot(), request.editionId(), missing);
        if (!baseline.present()) {
            missing.add("manifestBaseline");
        }
        // 第 4 层：来源记录。先把内容根目录的 sources/*.json 建成索引。
        SourceIndex index = indexSources(request.contentRoot(), missing);
        TreeSet<String> referenced = new TreeSet<>();
        if (request.editionSourceIds() != null) {
            for (String sourceId : request.editionSourceIds()) {
                if (sourceId != null && !sourceId.isBlank()) {
                    referenced.add(sourceId);
                }
            }
        } else {
            missing.add("edition.sourceIds");
        }
        List<EntryLocator> entryLocators = request.entryLocators() == null ? List.of() : request.entryLocators();
        for (EntryLocator locator : entryLocators) {
            List<EntrySourceRef> refs = locator.sourceRefs() == null ? List.of() : locator.sourceRefs();
            for (EntrySourceRef ref : refs) {
                if (ref != null && ref.sourceId() != null && !ref.sourceId().isBlank()) {
                    referenced.add(ref.sourceId());
                }
            }
        }
        if (entryLocators.isEmpty()) {
            missing.add("entryLocators");
        }
        if (referenced.isEmpty()) {
            missing.add("referencedSourceIds");
        }

        List<FrozenSource> sources = new ArrayList<>();
        for (String sourceId : referenced) {
            JsonNode found = index.bySourceId().get(sourceId);
            if (found == null) {
                // 引用了但内容根目录里没有：保留缺失说明，不编造记录。
                missing.add("source:" + sourceId);
                sources.add(new FrozenSource(sourceId, true, "declaredButNotFoundInContentRoot",
                        "declaredReference", List.copyOf(EXPECTED_SOURCE_FIELDS), new TreeMap<>()));
                continue;
            }
            Map<String, Object> record = toSortedMap(found);
            sources.add(new FrozenSource(sourceId, false, null, "contentRoot",
                    missingFieldsOf(record), record));
        }
        // 管理员手工录入的来源：只冻结其真实填写过的字段，机器不补审阅证据。
        List<ManualSource> manualSources = request.manualSources() == null ? List.of() : request.manualSources();
        for (ManualSource manual : manualSources) {
            if (manual == null) {
                continue;
            }
            Map<String, Object> record = manualRecordOf(manual);
            if (record.isEmpty()) {
                continue;
            }
            String sourceId = manual.sourceId() == null || manual.sourceId().isBlank()
                    ? "manual-" + sha256Hex(serialize(record)).substring(0, 16)
                    : manual.sourceId();
            record.putIfAbsent("sourceId", sourceId);
            sources.add(new FrozenSource(sourceId, false, null, "manual",
                    missingFieldsOf(record), record));
        }

        // 第 5 层：文件证据。只收**确实被引用到**的来源所在文件，且只记内容根目录内的相对路径。
        List<FileEvidence> files = new ArrayList<>();
        if (request.contentRoot() != null) {
            TreeSet<String> evidenceFiles = new TreeSet<>();
            for (String sourceId : referenced) {
                String relative = index.fileOfSourceId().get(sourceId);
                if (relative != null) {
                    evidenceFiles.add(relative);
                }
            }
            for (String relative : evidenceFiles) {
                Path file = request.contentRoot().resolve(relative);
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    files.add(new FileEvidence(relative, sha256Hex(bytes), bytes.length,
                            new String(bytes, StandardCharsets.UTF_8)));
                } catch (IOException unreadable) {
                    // 读不到就标记缺失：不假装拿到了文件证据。
                    missing.add("file:" + relative);
                }
            }
        }

        String versionId = request.editionId() == null || request.contentVersion() == null
                ? null : request.editionId() + "@" + request.contentVersion();
        SnapshotMeta meta = new SnapshotMeta(request.capture().capturedAt(), request.capture().capturedBy(),
                request.capture().operationId(), request.capture().origin(), request.capture().repairBasis());
        SnapshotDocument document = new SnapshotDocument(SNAPSHOT_SCHEMA_VERSION, meta,
                new VersionIdentity(request.editionId(), request.contentVersion(), versionId, request.contentSha256()),
                baseline, List.copyOf(sources), List.copyOf(files), List.copyOf(entryLocators),
                List.copyOf(new TreeSet<>(missing)));
        byte[] json = serialize(document);
        return new SnapshotResult(new String(json, StandardCharsets.UTF_8), sha256Hex(json),
                readTree(json));
    }

    /**
     * 读取内容根目录的清单，抽出导入目录基线。
     *
     * 缺失、不可读或结构不符时返回 `present=false` 并记录原因，
     * **不让整个发布失败**——没有清单是"没有基线"，不是"内容非法"。
     *
     * @param contentRoot 内容根目录；可为 null
     * @param editionId   教材标识；用于抽出本册声明元数据
     * @param missing     缺失标记收集器
     * @return 目录基线
     */
    public ManifestBaseline readManifestBaseline(Path contentRoot, String editionId, List<String> missing) {
        if (contentRoot == null) {
            return new ManifestBaseline(false, "contentRootMissing", null, null, null, null, 0, null,
                    new TreeMap<>());
        }
        Path relative = Path.of(CATALOG_DIR, MANIFEST_FILE);
        Path file = contentRoot.resolve(relative);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException unreadable) {
            return new ManifestBaseline(false, "manifestFileMissing", null, null, relativeOf(contentRoot, file),
                    null, 0, null, new TreeMap<>());
        }
        try {
            JsonNode root = canonical.readTree(bytes);
            String catalogVersion = textOrNull(root.path("catalogVersion"));
            Integer schemaVersion = root.path("schemaVersion").isNumber() ? root.path("schemaVersion").asInt() : null;
            if (catalogVersion == null) {
                missing.add("manifestBaseline.catalogVersion");
            }
            Map<String, Object> declared = new TreeMap<>();
            JsonNode editions = root.path("editions");
            if (editions.isArray()) {
                for (JsonNode edition : editions) {
                    if (editionId != null && editionId.equals(textOrNull(edition.path("editionId")))) {
                        declared = toSortedMap(edition);
                        break;
                    }
                }
            }
            if (declared.isEmpty()) {
                missing.add("manifestBaseline.declaredEdition");
            }
            return new ManifestBaseline(true, null, catalogVersion, schemaVersion,
                    relativeOf(contentRoot, file), sha256Hex(bytes), bytes.length,
                    new String(bytes, StandardCharsets.UTF_8), declared);
        } catch (IOException malformed) {
            return new ManifestBaseline(false, "manifestUnparsable", null, null, relativeOf(contentRoot, file),
                    sha256Hex(bytes), bytes.length, new String(bytes, StandardCharsets.UTF_8), new TreeMap<>());
        }
    }

    /**
     * 把"内容根目录内相对路径"算出来；无法相对化时返回文件名，绝不回退成绝对路径。
     *
     * @param contentRoot 内容根目录
     * @param file        文件
     * @return 使用 `/` 分隔的相对路径
     */
    private static String relativeOf(Path contentRoot, Path file) {
        try {
            Path normalizedRoot = contentRoot.toAbsolutePath().normalize();
            Path normalizedFile = file.toAbsolutePath().normalize();
            if (normalizedFile.startsWith(normalizedRoot)) {
                return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignored) {
            // 相对化失败只影响展示，不影响快照可用性。
        }
        return file.getFileName() == null ? MANIFEST_FILE : file.getFileName().toString();
    }

    /** 内容根目录的来源索引：来源标识 → 记录，以及来源标识 → 所在相对文件名。 */
    private record SourceIndex(Map<String, JsonNode> bySourceId, Map<String, String> fileOfSourceId) {
    }

    /**
     * 扫描 `sources/*.json`，建立来源标识索引。
     *
     * 文件按名称排序读取，保证同一内容根目录得到同一索引；重复标识保留**首个**并记录冲突标记。
     *
     * @param contentRoot 内容根目录；可为 null
     * @param missing     缺失标记收集器
     * @return 来源索引
     */
    private SourceIndex indexSources(Path contentRoot, List<String> missing) {
        Map<String, JsonNode> bySourceId = new TreeMap<>();
        Map<String, String> fileOfSourceId = new TreeMap<>();
        if (contentRoot == null) {
            return new SourceIndex(bySourceId, fileOfSourceId);
        }
        Path sourcesDir = contentRoot.resolve(SOURCES_DIR);
        if (!Files.isDirectory(sourcesDir)) {
            missing.add("sourcesDirectory");
            return new SourceIndex(bySourceId, fileOfSourceId);
        }
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(sourcesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(candidates::add);
        } catch (IOException unreadable) {
            missing.add("sourcesDirectoryUnreadable");
            return new SourceIndex(bySourceId, fileOfSourceId);
        }
        candidates.sort((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()));
        for (Path candidate : candidates) {
            JsonNode root;
            try {
                root = canonical.readTree(Files.readAllBytes(candidate));
            } catch (IOException malformed) {
                missing.add("sourceFileUnparsable:" + relativeOf(contentRoot, candidate));
                continue;
            }
            JsonNode records = root.path("sources");
            if (!records.isArray()) {
                continue;
            }
            String relative = relativeOf(contentRoot, candidate);
            for (JsonNode record : records) {
                String sourceId = textOrNull(record.path("sourceId"));
                if (sourceId == null) {
                    continue;
                }
                if (bySourceId.containsKey(sourceId)) {
                    // 同一标识出现在多个文件：保留首个，并明确记录冲突。
                    missing.add("sourceConflict:" + sourceId);
                    continue;
                }
                bySourceId.put(sourceId, record);
                fileOfSourceId.put(sourceId, relative);
            }
        }
        return new SourceIndex(bySourceId, fileOfSourceId);
    }

    /**
     * 按"应当有"的字段清单算出缺失字段。
     *
     * @param record 来源记录
     * @return 缺失字段（按字段清单顺序）
     */
    private static List<String> missingFieldsOf(Map<String, Object> record) {
        List<String> absent = new ArrayList<>();
        for (String field : EXPECTED_SOURCE_FIELDS) {
            Object value = record.get(field);
            if (value == null || (value instanceof String text && text.isBlank())
                    || (value instanceof java.util.Collection<?> collection && collection.isEmpty())) {
                absent.add(field);
            }
        }
        return List.copyOf(absent);
    }

    /**
     * 把管理员手工填写的来源转成显式记录。
     *
     * **只写入管理员确实填过的字段**；`verifiedAt`／`reviewedBy`／`reviewNotes` 等审阅证据
     * 不在这里生成，因此机器补全不会伪造"已经人工核对"。
     *
     * @param manual 手工来源
     * @return 排序后的记录
     */
    private static Map<String, Object> manualRecordOf(ManualSource manual) {
        Map<String, Object> record = new TreeMap<>();
        putIfPresent(record, "sourceId", manual.sourceId());
        putIfPresent(record, "kind", manual.kind());
        putIfPresent(record, "title", manual.title());
        putIfPresent(record, "url", manual.url());
        putIfPresent(record, "publisher", manual.publisher());
        putIfPresent(record, "isbn", manual.isbn());
        putIfPresent(record, "editionLabel", manual.editionLabel());
        putIfPresent(record, "locator", manual.locator());
        if (manual.page() != null) {
            record.put("page", manual.page());
        }
        putIfPresent(record, "notes", manual.note());
        return record;
    }

    /** 非空才写入，避免用空串冒充已填字段。 */
    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * 从已发布正文的**原始字节**解析条目定位。
     *
     * 用原始字节而不是模型反推，是为了保证"快照里的定位"与"当年写进版本的正文"逐字一致。
     *
     * @param bookBytes 正文原始字节；可为 null
     * @return 条目定位；无法解析时返回空表
     */
    public List<EntryLocator> entryLocatorsFromBookBytes(byte[] bookBytes) {
        if (bookBytes == null || bookBytes.length == 0) {
            return List.of();
        }
        try {
            JsonNode root = canonical.readTree(bookBytes);
            List<EntryLocator> out = new ArrayList<>();
            for (JsonNode entry : root.path("entries")) {
                String entryId = textOrNull(entry.path("entryId"));
                List<EntrySourceRef> refs = new ArrayList<>();
                for (JsonNode ref : entry.path("sourceRefs")) {
                    refs.add(new EntrySourceRef(textOrNull(ref.path("sourceId")),
                            textOrNull(ref.path("locator")),
                            ref.path("page").isNumber() ? ref.path("page").asInt() : null));
                }
                out.add(new EntryLocator(entryId, List.copyOf(refs)));
            }
            return List.copyOf(out);
        } catch (IOException malformed) {
            return List.of();
        }
    }

    /**
     * 从已映射的公开条目解析条目定位。
     *
     * @param entries 公开条目；可为 null
     * @return 条目定位
     */
    public List<EntryLocator> entryLocatorsFromEntries(List<CurriculumEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        List<EntryLocator> out = new ArrayList<>();
        for (CurriculumEntry entry : entries) {
            List<EntrySourceRef> refs = entry.sourceRefs() == null ? List.of() : entry.sourceRefs();
            out.add(new EntryLocator(entry.entryId(), List.copyOf(refs)));
        }
        return List.copyOf(out);
    }

    /**
     * 把来源记录转成键排序的映射，保证同一份 JSON 文本得到同一段快照。
     *
     * @param node 来源记录节点
     * @return 排序后的映射
     */
    private Map<String, Object> toSortedMap(JsonNode node) {
        Map<String, Object> converted = canonical.convertValue(node, new TypeReference<TreeMap<String, Object>>() {
        });
        return converted == null ? new TreeMap<>() : converted;
    }

    /** 取文本；缺失或空串返回 null。 */
    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /** 确定性序列化。 */
    private byte[] serialize(Object value) {
        try {
            return canonical.writeValueAsBytes(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("source snapshot serialization failed", impossible);
        }
    }

    /** 解析节点；失败时返回 null 节点，不阻断快照生成。 */
    private JsonNode readTree(byte[] json) {
        try {
            return canonical.readTree(json);
        } catch (IOException impossible) {
            return canonical.nullNode();
        }
    }

    /** 计算字节的 SHA-256 十六进制值。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 快照采集信息；**与教材自身的核对时间严格分开**。
     *
     * @param capturedAt  服务端采集时刻（ISO-8601 字符串，由调用方给出以保证可复现）
     * @param capturedBy  采集人
     * @param operationId 发布／导入／补录操作标识
     * @param origin      来源渠道：`publish`／`import`／`draft`／`repair`
     * @param repairBasis 补录依据；非补录时为 null
     */
    public record SnapshotCapture(String capturedAt, String capturedBy, String operationId, String origin,
                                  String repairBasis) {
    }

    /**
     * 快照构建输入。
     *
     * @param contentRoot     内容根目录；可为 null
     * @param editionId       教材标识
     * @param contentVersion  内容版本号；草稿可为 null
     * @param contentSha256   原正文 SHA-256；草稿可为 null
     * @param editionSourceIds 教材级来源标识；null 表示未声明
     * @param entryLocators   条目定位
     * @param manualSources   管理员手工填写的来源
     * @param capture         采集信息
     * @param manifestBaseline 显式指定的目录基线；null 表示从内容根目录读取
     */
    public record SnapshotRequest(Path contentRoot, String editionId, String contentVersion, String contentSha256,
                                  List<String> editionSourceIds, List<EntryLocator> entryLocators,
                                  List<ManualSource> manualSources, SnapshotCapture capture,
                                  ManifestBaseline manifestBaseline) {
    }

    /**
     * 管理员手工填写的一条来源。
     *
     * @param sourceId     来源标识；可空，空时由记录内容派生稳定标识
     * @param kind         来源类别
     * @param title        标题／说明
     * @param url          链接
     * @param publisher    出版社
     * @param isbn         书号
     * @param editionLabel 版次
     * @param locator      定位描述
     * @param page         页码
     * @param note         备注
     */
    public record ManualSource(String sourceId, String kind, String title, String url, String publisher,
                               String isbn, String editionLabel, String locator, Integer page, String note) {
    }

    /**
     * 快照文档。
     *
     * @param snapshotSchemaVersion 快照结构版本
     * @param snapshot              采集信息
     * @param version               对应教材版本
     * @param manifestBaseline      导入目录基线
     * @param sources               冻结的来源记录
     * @param files                 文件证据
     * @param entryLocators         条目定位
     * @param missing               顶层缺失标记
     */
    public record SnapshotDocument(int snapshotSchemaVersion, SnapshotMeta snapshot, VersionIdentity version,
                                   ManifestBaseline manifestBaseline, List<FrozenSource> sources,
                                   List<FileEvidence> files, List<EntryLocator> entryLocators,
                                   List<String> missing) {
    }

    /**
     * 快照自身。
     *
     * @param capturedAt  服务端采集时刻
     * @param capturedBy  采集人
     * @param operationId 操作标识
     * @param origin      来源渠道
     * @param repairBasis 补录依据；非补录时为 null
     */
    public record SnapshotMeta(String capturedAt, String capturedBy, String operationId, String origin,
                               String repairBasis) {
    }

    /**
     * 对应教材版本。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @param versionId      版本标识
     * @param contentSha256  原正文 SHA-256
     */
    public record VersionIdentity(String editionId, String contentVersion, String versionId, String contentSha256) {
    }

    /**
     * 导入目录基线。
     *
     * @param present          清单是否可读
     * @param missingReason    不可读原因；可读时为 null
     * @param catalogVersion   原 manifest 的目录版本
     * @param schemaVersion    原 manifest 的结构版本
     * @param relativePath     清单在内容根目录内的相对路径
     * @param fileSha256       原 manifest 文件 SHA-256
     * @param byteLength       原 manifest 字节数
     * @param fileUtf8Text     原 manifest 的 UTF-8 文本（可恢复原文件）
     * @param declaredEdition  本册声明元数据
     */
    public record ManifestBaseline(boolean present, String missingReason, String catalogVersion,
                                   Integer schemaVersion, String relativePath, String fileSha256, int byteLength,
                                   String fileUtf8Text, Map<String, Object> declaredEdition) {
    }

    /**
     * 冻结的一条来源记录。
     *
     * @param sourceId      来源标识
     * @param missing       是否缺失
     * @param missingReason 缺失原因；未缺失时为 null
     * @param origin        来源渠道：`contentRoot`／`manual`／`declaredReference`
     * @param missingFields 记录中缺失的字段
     * @param record        原样冻结的来源对象
     */
    public record FrozenSource(String sourceId, boolean missing, String missingReason, String origin,
                               List<String> missingFields, Map<String, Object> record) {
    }

    /**
     * 一条文件证据。
     *
     * @param relativePath 内容根目录内的相对路径
     * @param sha256       文件 SHA-256
     * @param byteLength   字节数
     * @param utf8Text     原始 UTF-8 文本
     */
    public record FileEvidence(String relativePath, String sha256, int byteLength, String utf8Text) {
    }

    /**
     * 一条条目的来源定位。
     *
     * @param entryId    条目标识
     * @param sourceRefs 原样保留的来源引用
     */
    public record EntryLocator(String entryId, List<EntrySourceRef> sourceRefs) {
    }

    /**
     * 构建结果。
     *
     * @param json   确定性快照 JSON
     * @param sha256 快照 JSON 字节的 SHA-256
     * @param node   结构化节点，便于调用方读取单层
     */
    public record SnapshotResult(String json, String sha256, JsonNode node) {
    }
}
