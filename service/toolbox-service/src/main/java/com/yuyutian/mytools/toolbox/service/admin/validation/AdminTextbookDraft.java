package com.yuyutian.mytools.toolbox.service.admin.validation;

import java.util.List;

/**
 * 管理员编辑草稿（与公开 `PublishedBook` schema 严格分离）。
 * 公开结构不接受 `rowId`、校对状态、临时节点 ID 等管理字段，因此不能直接复用。
 *
 * @param editionId    教材标识；新建草稿时可为 null，由服务端在创建时分配
 * @param subject      学科取值：`chinese` / `english`；未选择时为 null
 * @param grade        年级；未选择时为 0
 * @param semester     册次取值：`first` / `second`；未选择时为 null
 * @param publisher    出版社
 * @param series       教材名称（系列）
 * @param editionLabel 教材版本
 * @param sourceNote   来源说明
 * @param coverageDeclaration 收录范围声明：`partial` / `complete`
 * @param units        单元
 * @param lessons      课文
 * @param entries      内容条目
 * @param idMapping    已持久化的「临时标识 → 正式标识」映射。
 *                     键前缀区分类型：`u:` 单元、`l:` 课文、`e:` 条目（按 rowId）。
 *                     持久化它是为了让**改标题、调顺序、增删相邻节点时不重新分配正式 ID**。
 */
public record AdminTextbookDraft(
        String editionId,
        String subject,
        int grade,
        String semester,
        String publisher,
        String series,
        String editionLabel,
        String sourceNote,
        String coverageDeclaration,
        List<DraftUnit> units,
        List<DraftLesson> lessons,
        List<DraftEntry> entries,
        java.util.Map<String, String> idMapping,
        /**
         * 适用地区代码（设计 104 行：默认江苏，可改全国或多选；**存代码、显示名称**）。
         */
        List<String> regionCodes,
        /**
         * 课标／修订年份（设计 105 行：**可选**，放"更多信息"）。
         */
        Integer curriculumYear,
        /**
         * ISBN（设计 105 行：**可选**，按真实封面／版权页填写，**不推断**）。
         */
        String isbn,
        /**
         * 教材级来源标识（设计 297 行：编辑器可给**当前范围**设置默认来源）。
         *
         * 这些标识指向内容根目录的 `sources/*.json`；发布时按它们冻结完整来源对象。
         * 未声明时为空表——**空不是错误**，只是"没有声明来源"。
         */
        List<String> sourceIds,
        /**
         * 管理员**手工填写**的来源记录（设计 297 行：可给当前范围设置默认来源和页码）。
         *
         * 与 `sourceIds` 的区别：`sourceIds` 引用已有来源文件，这里保存的是界面上直接录入的
         * 标题／链接／出版社／页码等。机器**不会**为它们补 `verifiedAt`／`reviewedBy`，
         * 因此不会伪造"已经人工核对"的证据。
         */
        List<DraftSource> sources) {

    /**
     * 兼容构造：未提供映射时按空映射处理（首次保存前的草稿）。
     *
     * @param editionId 教材标识
     * @param subject 学科
     * @param grade 年级
     * @param semester 册次
     * @param publisher 出版社
     * @param series 教材名称
     * @param editionLabel 教材版本
     * @param sourceNote 来源说明
     * @param coverageDeclaration 收录范围声明
     * @param units 单元
     * @param lessons 课文
     * @param entries 条目
     */
    /**
     * 兼容构造：不带地区／年份／ISBN（升级前保存的草稿）。
     *
     * 加这三个字段时用它**避免改动既有的 15 个构造点**——
     * 与第 65 轮 `refreshRevision`、更早的 `csrfTokenSealed` 是同一个手法。
     *
     * @param editionId 教材标识
     * @param subject 学科
     * @param grade 年级
     * @param semester 册次
     * @param publisher 出版社
     * @param series 教材名称
     * @param editionLabel 教材版本
     * @param sourceNote 来源说明
     * @param coverageDeclaration 收录范围声明
     * @param units 单元
     * @param lessons 课文
     * @param entries 条目
     * @param idMapping 正式 ID 映射
     */
    public AdminTextbookDraft(String editionId, String subject, int grade, String semester, String publisher,
                              String series, String editionLabel, String sourceNote, String coverageDeclaration,
                              List<DraftUnit> units, List<DraftLesson> lessons, List<DraftEntry> entries,
                              java.util.Map<String, String> idMapping) {
        this(editionId, subject, grade, semester, publisher, series, editionLabel, sourceNote,
                coverageDeclaration, units, lessons, entries, idMapping, List.of(), null, null,
                List.of(), List.of());
    }

    /**
     * 兼容构造：不带来源字段（升级前保存的草稿）。
     *
     * @param editionId 教材标识
     * @param subject 学科
     * @param grade 年级
     * @param semester 册次
     * @param publisher 出版社
     * @param series 教材名称
     * @param editionLabel 教材版本
     * @param sourceNote 来源说明
     * @param coverageDeclaration 收录范围声明
     * @param units 单元
     * @param lessons 课文
     * @param entries 条目
     * @param idMapping 正式 ID 映射
     * @param regionCodes 适用地区代码
     * @param curriculumYear 课标年份
     * @param isbn 书号
     */
    public AdminTextbookDraft(String editionId, String subject, int grade, String semester, String publisher,
                              String series, String editionLabel, String sourceNote, String coverageDeclaration,
                              List<DraftUnit> units, List<DraftLesson> lessons, List<DraftEntry> entries,
                              java.util.Map<String, String> idMapping, List<String> regionCodes,
                              Integer curriculumYear, String isbn) {
        this(editionId, subject, grade, semester, publisher, series, editionLabel, sourceNote,
                coverageDeclaration, units, lessons, entries, idMapping, regionCodes, curriculumYear, isbn,
                List.of(), List.of());
    }

    public AdminTextbookDraft(String editionId, String subject, int grade, String semester, String publisher,
                              String series, String editionLabel, String sourceNote, String coverageDeclaration,
                              List<DraftUnit> units, List<DraftLesson> lessons, List<DraftEntry> entries) {
        this(editionId, subject, grade, semester, publisher, series, editionLabel, sourceNote, coverageDeclaration,
                units, lessons, entries, java.util.Map.of());
    }

    /**
     * 返回带新映射的副本。
     *
     * 用**规范构造器**而不是某个兼容构造器：来源标识、手工来源、适用地区、课标年份与 ISBN
     * 都必须随副本一起留存——发布时会把映射写回草稿，若在这里丢字段，
     * 管理员刚录入的来源就会在下一次发布时被静默清空。
     *
     * @param mapping 新的映射
     * @return 副本
     */
    public AdminTextbookDraft withIdMapping(java.util.Map<String, String> mapping) {
        return new AdminTextbookDraft(editionId, subject, grade, semester, publisher, series, editionLabel,
                sourceNote, coverageDeclaration, units, lessons, entries, mapping, regionCodes, curriculumYear,
                isbn, sourceIds, sources);
    }

    /**
     * 草稿里手工填写的一条来源。
     *
     * 字段全部可选：只填写过的字段会进入快照，其余按缺失标记，**不编造**。
     *
     * @param sourceId     来源标识；可空，空时由记录内容派生稳定标识
     * @param kind         来源类别
     * @param title        标题／来源说明
     * @param url          链接
     * @param publisher    出版社
     * @param isbn         书号
     * @param editionLabel 版次
     * @param locator      定位描述
     * @param page         页码
     * @param note         备注
     */
    public record DraftSource(String sourceId, String kind, String title, String url, String publisher,
                              String isbn, String editionLabel, String locator, Integer page, String note) {

        /**
         * 兼容构造：只填标题与链接（最常见的录入形态）。
         *
         * @param title 标题／来源说明
         * @param url   链接
         */
        public DraftSource(String title, String url) {
            this(null, null, title, url, null, null, null, null, null, null);
        }
    }

    /**
     * 草稿单元。
     *
     * @param unitId  正式标识；新建时为 null
     * @param clientUnitId 客户端临时标识；与 unitId 互斥
     * @param title   单元名称
     * @param order   同父顺序
     */
    public record DraftUnit(String unitId, String clientUnitId, String title, int order) {
    }

    /**
     * 草稿课文。
     *
     * @param lessonId 正式标识；新建时为 null
     * @param clientLessonId 客户端临时标识；与 lessonId 互斥
     * @param unitId   所属单元正式标识
     * @param clientUnitId 所属单元临时标识
     * @param title    课文标题
     * @param order    同父顺序
     */
    public record DraftLesson(String lessonId, String clientLessonId, String unitId, String clientUnitId,
                              String title, int order) {
    }

    /**
     * 草稿条目。
     *
     * @param rowId      行稳定标识，草稿生命周期内不变
     * @param entryId    正式条目标识；未发布时为 null
     * @param unitId     所属单元正式标识
     * @param clientUnitId 所属单元临时标识
     * @param lessonId   所属课文正式标识；无课文时为 null
     * @param clientLessonId 所属课文临时标识；无课文时为 null
     * @param text       语文为汉字、英语为英文
     * @param pinyin     逐字拼音（空格分隔）；英语为 null
     * @param meaning    中文释义；语文为 null
     * @param listTypes  教材表类别
     * @param confirmed  是否已人工核对
     * @param confirmedDigest 核对时内容的摘要（由客户端在用户确认的那一刻计算）。
     *                 服务端据此判断"确认之后内容有没有被改过"，避免用旧确认掩盖新内容。
     */
    public record DraftEntry(String rowId, String entryId, String unitId, String clientUnitId,
                             String lessonId, String clientLessonId, String text, String pinyin,
                             String meaning, List<String> listTypes, boolean confirmed,
                             String confirmedDigest,
                             /**
                              * 该条是否**已经过自动补全**。
                              *
                              * 必须随草稿往返：用户要求「一条内容只自动补全一次」，
                              * 前端若能在重新打开草稿后二次补全，会覆盖用户保存前的手工清空。
                              * 服务端只需原样存取，不参与判定。
                              */
                             boolean automaticInitialized,
                             /**
                              * **暂不由编辑器暴露、但必须原样往返的字段**。
                              *
                              * 教材正文里还有一批字段（`pinyinSegments`、`partOfSpeech`、`senseId`、
                              * `context`、`promptContext`、`acceptableAnswers`、`sourceRefs`、
                              * `form`、`order` 等）当前界面上没有编辑入口。
                              * 它们的正确语义是"**原样保留**"，而不是"因为没人编辑就丢掉"——
                              * 丢掉它们会让管理员改一个字就把整册书的义项、上下文和来源清空。
                              *
                              * 用不透明映射而不是逐个加字段，是因为这些字段**目前没有编辑语义**：
                              * 逐个加会引入 9 个永远为默认值的参数，并牵动 27 处构造点。
                              * 将来若某个字段有了编辑入口，再把它提升为具名字段。
                              */
                             java.util.Map<String, Object> preserved,
                             /**
                              * **条目级来源定位**（设计 297 行：对少数条目调整来源和页码）。
                              *
                              * 具名字段而不是继续躺在 `preserved` 里：编辑器要对它做增删改，
                              * 发布时也必须原样展开为正文里的 `{sourceId, locator, page}`。
                              * `preserved.sourceRefs` 仍然兼容读取（升级前的草稿）。
                              */
                             List<com.yuyutian.mytools.toolbox.model.EntrySourceRef> sourceRefs) {

        /**
         * 兼容构造：未提供摘要时按"未确认"处理（保守取值，不把缺少摘要当成已确认）。
         *
         * @param rowId 行标识
         * @param entryId 条目标识
         * @param unitId 单元标识
         * @param clientUnitId 临时单元标识
         * @param lessonId 课文标识
         * @param clientLessonId 临时课文标识
         * @param text 正文
         * @param pinyin 拼音
         * @param meaning 释义
         * @param listTypes 类别
         * @param confirmed 是否已核对
         */
        public DraftEntry(String rowId, String entryId, String unitId, String clientUnitId,
                          String lessonId, String clientLessonId, String text, String pinyin,
                          String meaning, List<String> listTypes, boolean confirmed) {
            this(rowId, entryId, unitId, clientUnitId, lessonId, clientLessonId, text, pinyin,
                    meaning, listTypes, confirmed, null, false, java.util.Map.of(), List.of());
        }

        /**
         * 兼容构造：不带条目级来源定位（升级前保存的草稿）。
         *
         * @param rowId 行标识
         * @param entryId 条目标识
         * @param unitId 单元标识
         * @param clientUnitId 临时单元标识
         * @param lessonId 课文标识
         * @param clientLessonId 临时课文标识
         * @param text 正文
         * @param pinyin 拼音
         * @param meaning 释义
         * @param listTypes 类别
         * @param confirmed 是否已核对
         * @param confirmedDigest 核对摘要
         * @param automaticInitialized 是否已自动补全
         * @param preserved 原样保留字段
         */
        public DraftEntry(String rowId, String entryId, String unitId, String clientUnitId,
                          String lessonId, String clientLessonId, String text, String pinyin,
                          String meaning, List<String> listTypes, boolean confirmed,
                          String confirmedDigest, boolean automaticInitialized,
                          java.util.Map<String, Object> preserved) {
            this(rowId, entryId, unitId, clientUnitId, lessonId, clientLessonId, text, pinyin, meaning,
                    listTypes, confirmed, confirmedDigest, automaticInitialized, preserved, List.of());
        }

        /**
         * 兼容构造：未提供"已自动补全"标记时按未补全处理。
         *
         * @param rowId 行标识
         * @param entryId 条目标识
         * @param unitId 单元标识
         * @param clientUnitId 临时单元标识
         * @param lessonId 课文标识
         * @param clientLessonId 临时课文标识
         * @param text 正文
         * @param pinyin 拼音
         * @param meaning 释义
         * @param listTypes 类别
         * @param confirmed 是否已核对
         * @param confirmedDigest 核对摘要
         */
        public DraftEntry(String rowId, String entryId, String unitId, String clientUnitId,
                          String lessonId, String clientLessonId, String text, String pinyin,
                          String meaning, List<String> listTypes, boolean confirmed,
                          String confirmedDigest) {
            this(rowId, entryId, unitId, clientUnitId, lessonId, clientLessonId, text, pinyin,
                    meaning, listTypes, confirmed, confirmedDigest, false, java.util.Map.of(), List.of());
        }
    }
}
