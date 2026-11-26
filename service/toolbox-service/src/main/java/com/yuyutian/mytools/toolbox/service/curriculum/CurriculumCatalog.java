package com.yuyutian.mytools.toolbox.service.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.model.CatalogManifest;
import com.yuyutian.mytools.toolbox.model.CoverageSlot;
import com.yuyutian.mytools.toolbox.model.CoverageStatus;
import com.yuyutian.mytools.toolbox.model.CurriculumEntry;
import com.yuyutian.mytools.toolbox.model.CurriculumLesson;
import com.yuyutian.mytools.toolbox.model.CurriculumUnit;
import com.yuyutian.mytools.toolbox.model.ListType;
import com.yuyutian.mytools.toolbox.model.PublishedBook;
import com.yuyutian.mytools.toolbox.model.RequiredSlot;
import com.yuyutian.mytools.toolbox.model.RequirementsManifest;
import com.yuyutian.mytools.toolbox.model.Semester;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TextbookCatalog;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.model.TextbookEntriesPage;
import com.yuyutian.mytools.toolbox.model.TextbookVersion;
import com.yuyutian.mytools.toolbox.model.VersionCounts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 教材内容目录：启动时校验发布清单与每个已发布正文文件，之后只读内存快照对外提供数据。
 * 缺少目录或清单时降级为空的公开数据并记录警告；清单列出的文件缺失或哈希不一致时启动失败。
 */
public class CurriculumCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(CurriculumCatalog.class);

    /** 目录不可用时的版本占位值，绝不伪造教材内容。 */
    public static final String CATALOG_VERSION_UNAVAILABLE = "none";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final String CATALOG_DIR = "catalog";
    private static final String TEXTBOOKS_DIR = "textbooks";
    private static final String PUBLISHED_FILE = "published.json";
    private static final String REQUIREMENTS_FILE = "requirements.json";
    private static final String BOOK_FILE = "book.json";

    private final Path root;
    private final ObjectMapper objectMapper;

    /**
     * 当前目录快照。
     * 目录版本、槽位、已发布版本与索引必须同代读取：若拆成多个 volatile 字段，
     * 并发发布时可能读到"新目录 + 旧正文"，因此统一放在一个不可变快照里原子替换。
     */
    private final AtomicReference<CatalogSnapshot> snapshot =
            new AtomicReference<>(CatalogSnapshot.empty());

    /**
     * 构造教材目录。
     *
     * @param root         已发布教材根目录
     * @param objectMapper JSON 解析器
     */
    public CurriculumCatalog(Path root, ObjectMapper objectMapper) {
        this.root = root;
        this.objectMapper = objectMapper;
    }

    /**
     * 已加载的教材版本及其正文索引。
     *
     * @param edition     对外元数据
     * @param sha256      正文文件 SHA-256
     * @param units       单元列表
     * @param lessons     课文列表
     * @param entries     已按稳定顺序排序的条目
     * @param listTypes   该版本出现的清单类别
     * @param unitOrder   单元顺序索引
     * @param lessonOrder 课文顺序索引
     */
    record LoadedEdition(
            TextbookEdition edition,
            String sha256,
            List<CurriculumUnit> units,
            List<CurriculumLesson> lessons,
            List<CurriculumEntry> entries,
            List<ListType> listTypes,
            Map<String, Integer> unitOrder,
            Map<String, Integer> lessonOrder) {

        /**
         * 返回版本键。
         *
         * @return editionId@contentVersion
         */
        String versionKey() {
            return edition.editionId() + "@" + edition.contentVersion();
        }
    }

    /**
     * 当前目录快照的代际版本号。
     *
     * @return 每次成功重载后递增的版本号
     */
    public long snapshotVersion() {
        return snapshot.get().generation();
    }

    /**
     * 给词典提供一次读取的已发布内容，避免逐册分页时混入另一代目录。
     *
     * @return 同代且不可变的教材及条目
     */
    public DictionarySnapshot dictionarySnapshot() {
        CatalogSnapshot current = snapshot.get();
        List<DictionaryBook> books = current.editions().stream()
                .map(book -> new DictionaryBook(book.edition(), book.entries())).toList();
        return new DictionarySnapshot(current.generation(), books);
    }

    /**
     * 供词典建立索引的一册已发布教材。
     *
     * @param edition 已发布版本信息
     * @param entries 已发布条目
     */
    public record DictionaryBook(TextbookEdition edition, List<CurriculumEntry> entries) {
    }

    /**
     * 与代际绑定的只读教材视图，不包含任何草稿数据。
     *
     * @param generation 目录代际
     * @param books 教材列表
     */
    public record DictionarySnapshot(long generation, List<DictionaryBook> books) {
    }

    /**
     * 当前目录的业务版本号，仅供诊断；缓存校验由实际响应内容生成。
     *
     * @return 当前目录版本号。
     */
    public String catalogVersion() {
        return snapshot.get().catalogVersion();
    }

    public void load() {
        List<RequiredSlot> slots = loadRequiredSlots();
        List<LoadedEdition> loaded = new ArrayList<>();
        String version = CATALOG_VERSION_UNAVAILABLE;
        Path publishedFile = root.resolve(CATALOG_DIR).resolve(PUBLISHED_FILE);
        if (!Files.isRegularFile(publishedFile)) {
            // 目录尚未填充时不阻断启动，只提供空的公开数据。
            LOG.warn("curriculum catalog not available, path={}", publishedFile.toAbsolutePath());
        } else {
            CatalogManifest manifest = parse(publishedFile, CatalogManifest.class);
            version = manifest.catalogVersion() == null ? CATALOG_VERSION_UNAVAILABLE : manifest.catalogVersion();
            List<TextbookEdition> declared = manifest.editions() == null ? List.of() : manifest.editions();
            List<String> keys = new ArrayList<>();
            for (TextbookEdition edition : declared) {
                LoadedEdition book = loadEdition(edition);
                if (keys.contains(book.versionKey())) {
                    throw new CatalogValidationException("duplicate edition version in manifest: " + book.versionKey());
                }
                keys.add(book.versionKey());
                loaded.add(book);
            }
        }
        installSnapshot(version, slots, loaded);
        int entryTotal = loaded.stream().mapToInt(item -> item.entries().size()).sum();
        // 只输出规模摘要，不打印教材正文或来源细节。
        LOG.info("curriculum catalog loaded, catalogVersion={}, editions={}, entries={}",
                version, loaded.size(), entryTotal);
    }

    /**
     * 由数据库中的已发布版本重建目录快照。
     * 发布事务提交后调用，使新版本无需重启即对公开接口可见。
     * 任何一册校验失败都会抛出异常且**不替换**现有快照，因此不会出现"新目录 + 旧正文"。
     *
     * @param catalogVersion 目录版本号
     * @param versions       已发布版本（声明元数据 + 正文原始字节）
     */
    public void reloadFromVersions(String catalogVersion, List<StoredVersion> versions) {
        List<RequiredSlot> slots = loadRequiredSlots();
        List<LoadedEdition> loaded = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (StoredVersion stored : versions) {
            String actualSha = sha256Hex(stored.bytes());
            if (!actualSha.equalsIgnoreCase(stored.declared().contentSha256())) {
                throw new CatalogValidationException("stored contentSha256 mismatch for "
                        + stored.declared().editionId() + ": declared=" + stored.declared().contentSha256()
                        + ", actual=" + actualSha);
            }
            LoadedEdition book = loadEditionFromBytes(stored.declared(), stored.bytes(), actualSha);
            if (keys.contains(book.versionKey())) {
                throw new CatalogValidationException("duplicate edition version in database: " + book.versionKey());
            }
            keys.add(book.versionKey());
            loaded.add(book);
        }
        installSnapshot(catalogVersion, slots, loaded);
        LOG.info("curriculum catalog reloaded from database, catalogVersion={}, editions={}, entries={}",
                catalogVersion, loaded.size(), loaded.stream().mapToInt(item -> item.entries().size()).sum());
    }

    /**
     * 排序、建索引并原子替换快照。
     * 先完整构造再一次性替换：构造过程中抛异常时，旧快照继续对外服务。
     *
     * @param version 目录版本号
     * @param slots   必需槽位
     * @param loaded  已加载版本
     */
    private synchronized void installSnapshot(String version, List<RequiredSlot> slots, List<LoadedEdition> loaded) {
        loaded.sort(Comparator
                .comparing((LoadedEdition item) -> item.edition().subject().value())
                .thenComparingInt(item -> item.edition().grade())
                .thenComparing(item -> item.edition().semester().value())
                .thenComparing(item -> item.edition().editionId())
                .thenComparing(item -> item.edition().contentVersion()));
        Map<String, LoadedEdition> index = new LinkedHashMap<>();
        for (LoadedEdition item : loaded) {
            index.put(item.versionKey(), item);
        }
        // 内容和代际同时替换，派生词典不能把旧内容标为新一代。
        long generation = snapshot.get().generation() + 1;
        snapshot.set(new CatalogSnapshot(version, List.copyOf(slots), List.copyOf(loaded), Map.copyOf(index), generation));
    }

    /**
     * 数据库中的一册已发布版本。
     *
     * @param declared 声明元数据
     * @param bytes    正文原始字节
     */
    public record StoredVersion(TextbookEdition declared, byte[] bytes) {
    }

    /**
     * 返回已加载的教材版本数量。
     *
     * @return 版本数量
     */
    public int editionCount() {
        return snapshot.get().editions().size();
    }

    /**
     * 返回已加载的条目总数。
     *
     * @return 条目总数
     */
    public int entryCount() {
        return snapshot.get().editions().stream().mapToInt(item -> item.entries().size()).sum();
    }

    /**
     * 返回教材目录视图：不同维度取交集，覆盖槽位按过滤后的版本集合计算。
     *
     * @param query 查询条件
     * @return 目录响应
     */
    /**
     * 教材接口是否就绪（F11）。
     *
     * 发布提交后若本实例**重载失败**，这里会被置为 false——
     * 此时公开读取必须**明确不可用**，而不是继续服务旧快照。
     *
     * 为什么这比"设一个标记"重要：旧快照看起来是好的，用户拿到的是**过期的目录**，
     * 而系统对外宣称发布成功了。**静默地服务旧数据比报错更难发现。**
     */
    private volatile boolean ready = true;

    /**
     * 标记教材接口不可用（重载失败时由发布器调用）。
     */
    public void markNotReady() {
        ready = false;
    }

    /**
     * 标记教材接口恢复可用（重建成功后由发布器调用）。
     */
    public void markReady() {
        ready = true;
    }

    /**
     * 教材接口是否就绪。
     *
     * @return 就绪时为 true
     */
    public boolean isReady() {
        return ready;
    }

    public TextbookCatalog catalog(CatalogQuery query) {
        // 单次请求只取一次快照，目录、正文与覆盖信息必须来自同一代。
        CatalogSnapshot current = snapshot.get();
        List<TextbookEdition> filtered = new ArrayList<>();
        for (LoadedEdition item : current.editions()) {
            if (matches(item.edition(), query)) {
                filtered.add(item.edition());
            }
        }
        List<CoverageSlot> coverage = new ArrayList<>();
        for (RequiredSlot slot : current.requiredSlots()) {
            coverage.add(coverageOf(slot, filtered));
        }
        return new TextbookCatalog(current.catalogVersion(), List.copyOf(filtered), List.copyOf(coverage));
    }

    /**
     * 返回指定不可变版本的完整结构。
     *
     * @param editionId 教材版本标识
     * @param version   内容版本
     * @return 版本视图或空
     */
    public Optional<TextbookVersion> version(String editionId, String version) {
        LoadedEdition book = snapshot.get().byVersionKey().get(editionId + "@" + version);
        if (book == null) {
            return Optional.empty();
        }
        return Optional.of(new TextbookVersion(
                book.edition(),
                book.units(),
                book.lessons(),
                book.listTypes(),
                book.entries().size(),
                new VersionCounts(book.units().size(), book.lessons().size(), book.entries().size()),
                book.sha256()));
    }

    /**
     * 返回条目分页；unitIds 与 lessonIds 必须是该版本的合法标识，否则返回参数错误。
     *
     * @param query 分页与过滤条件
     * @return 分页响应
     */
    public TextbookEntriesPage entries(EntryQuery query) {
        LoadedEdition book = snapshot.get().byVersionKey().get(query.editionId() + "@" + query.version());
        if (book == null) {
            throw new BusinessException(ErrorCode.TOOLBOX_003);
        }
        List<String> unitIds = query.unitIds() == null ? List.of() : query.unitIds();
        List<String> lessonIds = query.lessonIds() == null ? List.of() : query.lessonIds();
        List<ListType> listTypes = query.listTypes() == null ? List.of() : query.listTypes();
        requireKnownIds(unitIds, book.unitOrder().keySet(), "unitIds", "unknownUnitId");
        requireKnownIds(lessonIds, book.lessonOrder().keySet(), "lessonIds", "unknownLessonId");

        List<CurriculumEntry> filtered = new ArrayList<>();
        for (CurriculumEntry entry : book.entries()) {
            if (!unitIds.isEmpty() && !unitIds.contains(entry.unitId())) {
                continue;
            }
            // 不可变集合的 contains(null) 会抛异常，未归属课文的条目在按课文过滤时直接排除。
            if (!lessonIds.isEmpty() && (entry.lessonId() == null || !lessonIds.contains(entry.lessonId()))) {
                continue;
            }
            if (!listTypes.isEmpty() && entry.listTypes().stream().noneMatch(listTypes::contains)) {
                continue;
            }
            filtered.add(entry);
        }
        String versionKey = book.versionKey();
        String filterHash = CursorCodec.filterHash(unitIds, lessonIds, listTypes);
        int offset = 0;
        if (query.cursor() != null && !query.cursor().isBlank()) {
            offset = resolveOffset(query.cursor(), versionKey, filterHash);
        }
        int total = filtered.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + query.limit(), total);
        List<CurriculumEntry> page = List.copyOf(filtered.subList(from, to));
        String nextCursor = to < total ? CursorCodec.encode(versionKey, filterHash, to) : null;
        return new TextbookEntriesPage(page, nextCursor, total, book.edition().contentVersion());
    }

    /**
     * 解析游标并校验其与当前版本、过滤集合绑定。
     *
     * @param cursor     游标字符串
     * @param versionKey 当前版本键
     * @param filterHash 当前过滤哈希
     * @return 偏移量
     */
    private int resolveOffset(String cursor, String versionKey, String filterHash) {
        CursorCodec.CursorPayload payload = CursorCodec.decode(cursor)
                .orElseThrow(() -> BusinessException.invalid("cursor", "invalidCursor"));
        if (!payload.versionKey().equals(versionKey) || !payload.filterHash().equals(filterHash)) {
            // 来自其他版本或不同过滤集合的游标必须拒绝，避免静默返回错误范围。
            throw BusinessException.invalid("cursor", "cursorNotBoundToRequest");
        }
        return payload.offset();
    }

    /**
     * 校验请求中的标识都存在于该版本。
     *
     * @param requested 请求标识
     * @param known     该版本合法标识
     * @param field     字段路径
     * @param reason    机器可读原因
     */
    private void requireKnownIds(List<String> requested, Set<String> known, String field, String reason) {
        for (int index = 0; index < requested.size(); index++) {
            String value = requested.get(index);
            if (value == null || !known.contains(value)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("index", index);
                throw BusinessException.invalid(field, reason, details);
            }
        }
    }

    /**
     * 判断教材版本是否满足过滤条件。
     *
     * @param edition 教材版本
     * @param query   查询条件
     * @return 是否匹配
     */
    private boolean matches(TextbookEdition edition, CatalogQuery query) {
        if (query.subject() != null && edition.subject() != query.subject()) {
            return false;
        }
        if (query.grade() != null && edition.grade() != query.grade()) {
            return false;
        }
        if (query.semester() != null && edition.semester() != query.semester()) {
            return false;
        }
        return query.region() == null
                || (edition.regionCodes() != null && edition.regionCodes().contains(query.region()));
    }

    /**
     * 计算槽位覆盖状态：无版本为 missing，存在完整版本为 complete，否则为 partial。
     *
     * @param slot     必需槽位
     * @param filtered 过滤后的已发布版本
     * @return 覆盖情况
     */
    private CoverageSlot coverageOf(RequiredSlot slot, List<TextbookEdition> filtered) {
        List<String> editionIds = new ArrayList<>();
        boolean complete = false;
        for (TextbookEdition edition : filtered) {
            if (edition.subject() == slot.subject() && edition.grade() == slot.grade()
                    && edition.semester() == slot.semester()) {
                editionIds.add(edition.editionId());
                complete = complete || edition.coverageStatus() == CoverageStatus.COMPLETE;
            }
        }
        CoverageStatus status;
        if (editionIds.isEmpty()) {
            status = CoverageStatus.MISSING;
        } else if (complete) {
            status = CoverageStatus.COMPLETE;
        } else {
            status = CoverageStatus.PARTIAL;
        }
        return new CoverageSlot(slot.subject(), slot.grade(), slot.semester(), status, List.copyOf(editionIds));
    }

    /**
     * 加载必需槽位清单；缺失时使用契约 3.2 固定的 20 个学科年级册次组合。
     *
     * @return 必需槽位列表
     */
    private List<RequiredSlot> loadRequiredSlots() {
        Path file = root.resolve(CATALOG_DIR).resolve(REQUIREMENTS_FILE);
        if (!Files.isRegularFile(file)) {
            LOG.warn("curriculum requirements not available, using canonical slots, path={}", file.toAbsolutePath());
            return canonicalSlots();
        }
        RequirementsManifest manifest = parse(file, RequirementsManifest.class);
        List<RequiredSlot> declared = manifest.slots() == null ? List.of() : manifest.slots();
        if (declared.isEmpty()) {
            LOG.warn("curriculum requirements empty, using canonical slots, path={}", file.toAbsolutePath());
            return canonicalSlots();
        }
        List<RequiredSlot> slots = new ArrayList<>();
        for (RequiredSlot slot : declared) {
            if (slot.subject() == null || slot.semester() == null) {
                throw new CatalogValidationException("requirements slot missing subject or semester: " + slot);
            }
            requireGradeInRange(slot.subject(), slot.grade());
            slots.add(slot);
        }
        slots.sort(Comparator
                .comparing((RequiredSlot item) -> item.subject().value())
                .thenComparingInt(RequiredSlot::grade)
                .thenComparing(item -> item.semester().value()));
        return List.copyOf(new LinkedHashSet<>(slots));
    }

    /**
     * 返回契约固定的 20 个槽位：语文 1-6 年级、英语 3-6 年级，各含上下册。
     *
     * @return 规范槽位列表
     */
    private List<RequiredSlot> canonicalSlots() {
        List<RequiredSlot> slots = new ArrayList<>();
        for (Subject subject : List.of(Subject.CHINESE, Subject.ENGLISH)) {
            int from = subject == Subject.CHINESE ? 1 : 3;
            for (int grade = from; grade <= 6; grade++) {
                for (Semester semester : List.of(Semester.FIRST, Semester.SECOND)) {
                    slots.add(new RequiredSlot(subject, grade, semester));
                }
            }
        }
        return List.copyOf(slots);
    }

    /**
     * 加载并校验单个已发布版本。
     *
     * @param declared 清单声明的元数据
     * @return 已校验的版本
     */
    private LoadedEdition loadEdition(TextbookEdition declared) {
        requireNonBlank(declared.editionId(), "editionId");
        requireNonBlank(declared.contentVersion(), "contentVersion");
        requireNonBlank(declared.contentSha256(), "contentSha256");
        if (declared.subject() == null || declared.semester() == null) {
            throw new CatalogValidationException("edition missing subject or semester: " + declared.editionId());
        }
        requireGradeInRange(declared.subject(), declared.grade());
        if (declared.schemaVersion() != 1) {
            throw new CatalogValidationException("unsupported schemaVersion: " + declared.editionId());
        }
        if (!SHA256_HEX.matcher(declared.contentSha256()).matches()) {
            throw new CatalogValidationException("contentSha256 is not a hex sha256: " + declared.editionId());
        }
        Path bookFile = root.resolve(TEXTBOOKS_DIR)
                .resolve(declared.editionId())
                .resolve(declared.contentVersion())
                .resolve(BOOK_FILE);
        if (!Files.isRegularFile(bookFile)) {
            throw new CatalogValidationException("published book file missing: " + bookFile.toAbsolutePath());
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(bookFile);
        } catch (IOException exception) {
            throw new CatalogValidationException("published book file unreadable: " + bookFile.toAbsolutePath(), exception);
        }
        String actualSha = sha256Hex(bytes);
        if (!actualSha.equalsIgnoreCase(declared.contentSha256())) {
            throw new CatalogValidationException("contentSha256 mismatch for " + declared.editionId()
                    + ": manifest=" + declared.contentSha256() + ", actual=" + actualSha);
        }
        return loadEditionFromBytes(declared, bytes, actualSha);
    }

    /**
     * 由正文原始字节构建已发布版本。
     * 文件仓储与数据库仓储共用这一段：先解析，再按声明元数据校验，最后计算派生信息。
     *
     * @param declared 声明的版本元数据
     * @param bytes    正文原始字节
     * @param actualSha 实际正文哈希
     * @return 已加载版本
     */
    private LoadedEdition loadEditionFromBytes(TextbookEdition declared, byte[] bytes, String actualSha) {
        PublishedBook book;
        try {
            book = objectMapper.readValue(bytes, PublishedBook.class);
        } catch (IOException exception) {
            throw new CatalogValidationException("published book unparsable: " + declared.editionId(), exception);
        }
        return validateBook(declared, book, actualSha);
    }

    /**
     * 校验正文文件内部结构与清单声明一致，并建立稳定排序索引。
     *
     * @param declared 清单声明的元数据
     * @param book     正文文件内容
     * @param sha256   正文文件哈希
     * @return 已校验的版本
     */
    private LoadedEdition validateBook(TextbookEdition declared, PublishedBook book, String sha256) {
        TextbookEdition inner = book.edition();
        if (inner == null) {
            throw new CatalogValidationException("book.json missing edition: " + declared.editionId());
        }
        if (!declared.editionId().equals(inner.editionId())
                || !declared.contentVersion().equals(inner.contentVersion())
                || declared.subject() != inner.subject()
                || declared.grade() != inner.grade()
                || declared.semester() != inner.semester()) {
            throw new CatalogValidationException("book.json edition does not match manifest: " + declared.editionId());
        }
        List<CurriculumUnit> units = book.units() == null ? List.of() : book.units();
        List<CurriculumLesson> lessons = book.lessons() == null ? List.of() : book.lessons();
        List<CurriculumEntry> entries = book.entries() == null ? List.of() : book.entries();
        Map<String, Integer> unitOrder = new LinkedHashMap<>();
        for (CurriculumUnit unit : units) {
            requireNonBlank(unit.unitId(), "unitId");
            if (!declared.editionId().equals(unit.editionId())) {
                throw new CatalogValidationException("unit belongs to another edition: " + unit.unitId());
            }
            if (unitOrder.putIfAbsent(unit.unitId(), unit.order()) != null) {
                throw new CatalogValidationException("duplicate unitId: " + unit.unitId());
            }
        }
        Map<String, Integer> lessonOrder = new LinkedHashMap<>();
        for (CurriculumLesson lesson : lessons) {
            requireNonBlank(lesson.lessonId(), "lessonId");
            if (!unitOrder.containsKey(lesson.unitId())) {
                throw new CatalogValidationException("lesson references unknown unit: " + lesson.lessonId());
            }
            if (lessonOrder.putIfAbsent(lesson.lessonId(), lesson.order()) != null) {
                throw new CatalogValidationException("duplicate lessonId: " + lesson.lessonId());
            }
        }
        List<CurriculumEntry> normalized = new ArrayList<>();
        Set<String> entryIds = new LinkedHashSet<>();
        Set<ListType> listTypes = new LinkedHashSet<>();
        for (CurriculumEntry entry : entries) {
            CurriculumEntry item = normalizeEntry(declared.editionId(), entry, unitOrder, lessonOrder);
            if (!entryIds.add(item.entryId())) {
                throw new CatalogValidationException("duplicate entryId: " + item.entryId());
            }
            listTypes.addAll(item.listTypes());
            normalized.add(item);
        }
        Comparator<CurriculumEntry> comparator = Comparator
                .comparingInt((CurriculumEntry item) -> unitOrder.getOrDefault(item.unitId(), Integer.MAX_VALUE))
                // 未归属课文的条目排在该单元内已归属课文之后。
                .thenComparingInt(item -> item.lessonId() == null
                        ? Integer.MAX_VALUE
                        : lessonOrder.getOrDefault(item.lessonId(), Integer.MAX_VALUE))
                .thenComparingInt(CurriculumEntry::order)
                .thenComparing(CurriculumEntry::entryId);
        normalized.sort(comparator);
        if (declared.availableEntryCount() != null && declared.availableEntryCount() != normalized.size()) {
            throw new CatalogValidationException("availableEntryCount mismatch for " + declared.editionId()
                    + ": manifest=" + declared.availableEntryCount() + ", actual=" + normalized.size());
        }
        List<ListType> orderedTypes = new ArrayList<>();
        for (ListType listType : ListType.values()) {
            if (listTypes.contains(listType)) {
                orderedTypes.add(listType);
            }
        }
        return new LoadedEdition(
                declared.withAvailableEntryCount(normalized.size()),
                sha256,
                List.copyOf(units),
                List.copyOf(lessons),
                List.copyOf(normalized),
                List.copyOf(orderedTypes),
                Map.copyOf(unitOrder),
                Map.copyOf(lessonOrder));
    }

    /**
     * 规范化条目：校验归属关系并把可空集合字段补成空列表。
     *
     * @param editionId   教材版本标识
     * @param entry       原始条目
     * @param unitOrder   单元索引
     * @param lessonOrder 课文索引
     * @return 规范化条目
     */
    private CurriculumEntry normalizeEntry(String editionId, CurriculumEntry entry,
                                           Map<String, Integer> unitOrder, Map<String, Integer> lessonOrder) {
        requireNonBlank(entry.entryId(), "entryId");
        requireNonBlank(entry.entryVersion(), "entryVersion");
        if (!editionId.equals(entry.editionId()) || !unitOrder.containsKey(entry.unitId())) {
            throw new CatalogValidationException("entry references unknown unit: " + entry.entryId());
        }
        if (entry.lessonId() != null && !lessonOrder.containsKey(entry.lessonId())) {
            throw new CatalogValidationException("entry references unknown lesson: " + entry.entryId());
        }
        if (entry.form() == null || entry.reviewStatus() == null) {
            throw new CatalogValidationException("entry missing form or reviewStatus: " + entry.entryId());
        }
        return new CurriculumEntry(
                entry.entryId(),
                entry.entryVersion(),
                entry.editionId(),
                entry.unitId(),
                entry.lessonId(),
                entry.order(),
                entry.form(),
                entry.listTypes() == null ? List.of() : List.copyOf(entry.listTypes()),
                entry.text(),
                entry.pinyinDisplay(),
                entry.pinyinSegments() == null ? List.of() : List.copyOf(entry.pinyinSegments()),
                entry.meaning(),
                entry.partOfSpeech(),
                entry.senseId(),
                entry.context(),
                entry.promptContext(),
                entry.acceptableAnswers() == null ? List.of() : List.copyOf(entry.acceptableAnswers()),
                entry.sourceRefs() == null ? List.of() : List.copyOf(entry.sourceRefs()),
                entry.reviewStatus(),
                entry.blockingIssues() == null ? List.of() : List.copyOf(entry.blockingIssues()));
    }

    /**
     * 校验年级是否在学科允许范围内。
     *
     * @param subject 学科
     * @param grade   年级
     */
    private void requireGradeInRange(Subject subject, int grade) {
        int from = subject == Subject.CHINESE ? 1 : 3;
        if (grade < from || grade > 6) {
            throw new CatalogValidationException("grade out of range for subject " + subject.value() + ": " + grade);
        }
    }

    /**
     * 校验字符串非空白。
     *
     * @param value 取值
     * @param field 字段名
     */
    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new CatalogValidationException("missing required field: " + field);
        }
    }

    /**
     * 解析 JSON 文件，失败时抛出启动失败异常。
     *
     * @param file  文件路径
     * @param type  目标类型
     * @param <T>   目标类型
     * @return 解析结果
     */
    private <T> T parse(Path file, Class<T> type) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), type);
        } catch (IOException exception) {
            throw new CatalogValidationException("invalid curriculum json: " + file.toAbsolutePath(), exception);
        }
    }

    /**
     * 计算字节数组的十六进制 SHA-256。
     *
     * @param bytes 字节数组
     * @return 小写十六进制摘要
     */
    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256Unavailable", exception);
        }
    }
}
