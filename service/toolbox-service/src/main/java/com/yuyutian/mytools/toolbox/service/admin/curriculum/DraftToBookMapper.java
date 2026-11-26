package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.yuyutian.mytools.toolbox.model.CurriculumEntry;
import com.yuyutian.mytools.toolbox.model.CurriculumLesson;
import com.yuyutian.mytools.toolbox.model.CurriculumUnit;
import com.yuyutian.mytools.toolbox.model.EntryForm;
import com.yuyutian.mytools.toolbox.model.ListType;
import com.yuyutian.mytools.toolbox.model.PinyinSegment;
import com.yuyutian.mytools.toolbox.model.ReviewStatus;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把管理草稿显式映射为公开结构（设计文档 §5.3）。
 *
 * 公开 schema 不接受 `rowId`、校对状态与临时节点 ID，因此必须显式转换，不能直接把管理字段塞进去。
 * 同时完成三件事：
 * 1. 为只有临时标识的节点分配**确定且稳定**的正式 ID；
 * 2. 建立「临时 ID → 正式 ID」映射，供响应回填，使前端不必重取；
 * 3. 语文条目把拼音拆成与汉字一一对应的音节段。
 */
@Component
public class DraftToBookMapper {

    /**
     * 映射结果。
     *
     * @param units          公开单元
     * @param lessons        公开课文
     * @param entries        公开条目
     * @param unitIdMapping  临时单元标识 → 正式单元标识
     * @param lessonIdMapping 临时课文标识 → 正式课文标识
     * @param entryIdMapping 行标识 → 正式条目标识
     * @param persistedMapping 合并后的完整映射，调用方应写回草稿
     */
    public record MappedBook(
            List<CurriculumUnit> units,
            List<CurriculumLesson> lessons,
            List<CurriculumEntry> entries,
            Map<String, String> unitIdMapping,
            Map<String, String> lessonIdMapping,
            Map<String, String> entryIdMapping,
            Map<String, String> persistedMapping) {
    }


    /**
     * 为新节点分配一个**不与任何已分配 ID 冲突**的正式标识。
     *
     * 为什么不能直接用"位置生成的候选值"：
     * 候选值是按**数组下标**算出来的（`-e0001`、`-e0002`…），
     * 而在既有条目**前面插入**一条新行时，新行会拿到与既有条目**相同的候选值**——
     * 于是同一册教材里出现两个 `-e0001`。
     * 审查探针正是这样复现的：`ids=[audit-book-e0001, audit-book-e0001]`。
     *
     * 这里改为：**从候选值开始，跳过一切已被占用的 ID**。
     * 因为 `merged` 会随分配不断累积，同一次映射里先分配的行也会被后续行避开。
     *
     * @param merged     已知的「客户端键 → 正式 ID」映射（同时充当已用 ID 的来源）
     * @param prefix     映射键前缀（`u`／`l`／`e`）
     * @param clientKey  客户端键；为空表示这是没有客户端身份的新行
     * @param editionId  教材标识
     * @param kind       类型段（`u`／`l`／`e`）
     * @param startIndex 起始序号（通常为行下标 + 1）
     * @param width      序号补零宽度
     * @return 可安全使用的正式标识
     */
    private static String allocateUnique(Map<String, String> merged, String prefix, String clientKey,
                                         String editionId, String kind, int startIndex, int width) {
        if (clientKey != null) {
            String existing = merged.get(prefix + ":" + clientKey);
            if (existing != null) {
                // 已有正式 ID 的节点必须沿用，否则改标题／调顺序会让身份漂移。
                return existing;
            }
        }
        java.util.Set<String> used = new java.util.HashSet<>(merged.values());
        int candidate = Math.max(1, startIndex);
        String allocated = editionId + "-" + kind + pad(candidate, width);
        while (used.contains(allocated)) {
            candidate += 1;
            allocated = editionId + "-" + kind + pad(candidate, width);
        }
        return allocated;
    }

    /**
     * 执行映射。
     *
     * @param editionId 教材标识；用于生成正式 ID
     * @param draft     草稿
     * @param entryVersion 条目版本号，与内容版本一致
     * @return 公开结构与 ID 映射
     */
    public MappedBook map(String editionId, AdminTextbookDraft draft, String entryVersion) {
        // 先载入已持久化的映射：已分配过正式 ID 的节点必须沿用，避免改标题/调顺序后身份漂移。
        Map<String, String> persisted = draft.idMapping() == null ? Map.of() : draft.idMapping();
        Map<String, String> unitIds = new HashMap<>();
        Map<String, String> lessonIds = new HashMap<>();
        Map<String, String> entryIds = new HashMap<>();
        Map<String, String> merged = new HashMap<>(persisted);

        List<AdminTextbookDraft.DraftUnit> draftUnits = draft.units() == null ? List.of() : draft.units();
        List<AdminTextbookDraft.DraftLesson> draftLessons = draft.lessons() == null ? List.of() : draft.lessons();
        List<AdminTextbookDraft.DraftEntry> draftEntries = draft.entries() == null ? List.of() : draft.entries();

        // 按出现顺序分配正式 ID：同一草稿同一顺序必然得到相同 ID，便于重复保存后保持稳定。
        List<CurriculumUnit> units = new ArrayList<>();
        for (int index = 0; index < draftUnits.size(); index += 1) {
            AdminTextbookDraft.DraftUnit unit = draftUnits.get(index);
            String official = officialId(unit.unitId(),
                    allocateUnique(merged, "u", unit.clientUnitId(), editionId, "u", index + 1, 2));
            units.add(new CurriculumUnit(official, editionId, unit.order(), unit.title()));
            if (unit.clientUnitId() != null) {
                unitIds.put(unit.clientUnitId(), official);
                merged.put("u:" + unit.clientUnitId(), official);
            } else if (unit.unitId() != null) {
                merged.put("u:" + unit.unitId(), official);
            }
        }

        List<CurriculumLesson> lessons = new ArrayList<>();
        for (int index = 0; index < draftLessons.size(); index += 1) {
            AdminTextbookDraft.DraftLesson lesson = draftLessons.get(index);
            String parent = resolve(lesson.unitId(), lesson.clientUnitId(), unitIds);
            String official = officialId(lesson.lessonId(),
                    allocateUnique(merged, "l", lesson.clientLessonId(), editionId, "l", index + 1, 2));
            lessons.add(new CurriculumLesson(official, parent, lesson.order(), lesson.title()));
            if (lesson.clientLessonId() != null) {
                lessonIds.put(lesson.clientLessonId(), official);
                merged.put("l:" + lesson.clientLessonId(), official);
            } else if (lesson.lessonId() != null) {
                merged.put("l:" + lesson.lessonId(), official);
            }
        }

        List<CurriculumEntry> entries = new ArrayList<>();
        for (int index = 0; index < draftEntries.size(); index += 1) {
            AdminTextbookDraft.DraftEntry entry = draftEntries.get(index);
            String owner = resolve(entry.unitId(), entry.clientUnitId(), unitIds);
            String lessonOwner = entry.lessonId() != null || entry.clientLessonId() != null
                    ? resolve(entry.lessonId(), entry.clientLessonId(), lessonIds)
                    : null;
            String official = officialId(entry.entryId(),
                    allocateUnique(merged, "e", entry.rowId(), editionId, "e", index + 1, 4));
            entries.add(new CurriculumEntry(
                    official,
                    entryVersion,
                    editionId,
                    owner,
                    lessonOwner,
                    // **条目 order 用 0 基**（用户 2026-10-07 拍板）：与生产内容包一致
                    // （只读实测 `cn-ty-3-1-pep-2024` v1.0.0 的 order 为 `0,1,2…`）。
                    // 注意：上面 `allocateUnique(..., index + 1, ...)` 的 `index + 1` 是
                    // **ID 分配种子**、与 order 无关，**不能**一起改（会改变生成的 ID）。
                    index,
                    formOf(entry.text()),
                    listTypesOf(entry.listTypes()),
                    entry.text(),
                    entry.pinyin(),
                    segmentsOf(entry.text(), entry.pinyin()),
                    entry.meaning(),
                    // 以下字段编辑器暂不暴露，但**必须原样写回**。
                    // 此前它们在这里被写死为 null／空表——于是管理员改一个字并发布，
                    // 就把整册书的词性、义项、上下文、答案与来源**静默清空**了。
                    // 草稿把原值放在 `preserved` 里带过来（见 CurriculumFileImporter）。
                    preservedString(entry, "partOfSpeech"),
                    preservedString(entry, "senseId"),
                    preservedString(entry, "context"),
                    preservedString(entry, "promptContext"),
                    preservedStringList(entry, "acceptableAnswers"),
                    entrySourceRefs(entry),
                    // 只有"已人工核对"**且摘要仍然匹配**的条目才标为已核对：
                    // 客户端可以填 confirmed=true，但服务端不据此认定已核对——
                    // 确认之后若内容被改动，摘要不符，这里会退回落回未核对，
                    // 避免用旧确认掩盖新内容（设计文档 B11）。
                    isVerified(entry) ? ReviewStatus.PUBLISHED : ReviewStatus.DRAFT,
                    List.of()));
            // rowId 是**客户端行标识**，用于把服务端分配的正式 ID 回传给前端。
            // 它可能为空（客户端没给身份），此时**没有可映射的东西**——
            // 不能拿 null 当 Map 的键：`Map.copyOf` 会抛 NullPointerException，
            // 整个预览／保存请求会以 503 失败，而错误信息里看不出是哪个字段。
            // （实测：手工构造 rowId 为 null 的草稿即可复现。）
            if (entry.rowId() != null) {
                entryIds.put(entry.rowId(), official);
            }
            merged.put("e:" + entry.rowId(), official);
        }
        return new MappedBook(List.copyOf(units), List.copyOf(lessons), List.copyOf(entries),
                Map.copyOf(unitIds), Map.copyOf(lessonIds), Map.copyOf(entryIds), Map.copyOf(merged));
    }

    /**
     * 从草稿条目的保留字段里取一个字符串。
     *
     * @param entry 草稿条目
     * @param field 字段名
     * @return 原值；缺失或为空时返回 null
     */
    private static String preservedString(AdminTextbookDraft.DraftEntry entry, String field) {
        Object value = entry.preserved() == null ? null : entry.preserved().get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * 取条目的来源定位。
     *
     * 优先用具名字段 `sourceRefs`（编辑器可以直接增删改），
     * 为空时回落到 `preserved.sourceRefs`——升级前保存的草稿只把来源放在不透明映射里，
     * 这条回落保证它们发布时**不会丢来源**。
     *
     * @param entry 草稿条目
     * @return 来源定位；都为空时返回空表
     */
    private static List<com.yuyutian.mytools.toolbox.model.EntrySourceRef> entrySourceRefs(
            AdminTextbookDraft.DraftEntry entry) {
        List<com.yuyutian.mytools.toolbox.model.EntrySourceRef> named = entry.sourceRefs();
        if (named != null && !named.isEmpty()) {
            return List.copyOf(named);
        }
        return preservedSourceRefs(entry);
    }

    /**
     * 从草稿条目的保留字段里取来源记录。
     *
     * `sourceRefs` 是结构化类型而不是字符串表，因此单独转换。
     * **不做校验**：这些值来自已发布教材，原样写回即可；
     * 在这里"顺手清洗"会变成另一种静默丢数据。
     *
     * @param entry 草稿条目
     * @return 原来源记录；缺失或结构不符时返回空表
     */
    private static List<com.yuyutian.mytools.toolbox.model.EntrySourceRef> preservedSourceRefs(
            AdminTextbookDraft.DraftEntry entry) {
        Object value = entry.preserved() == null ? null : entry.preserved().get("sourceRefs");
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<com.yuyutian.mytools.toolbox.model.EntrySourceRef> out = new java.util.ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof java.util.Map<?, ?> map) {
                Object sourceId = map.get("sourceId");
                Object locator = map.get("locator");
                Object page = map.get("page");
                out.add(new com.yuyutian.mytools.toolbox.model.EntrySourceRef(
                        sourceId == null ? null : String.valueOf(sourceId),
                        locator == null ? null : String.valueOf(locator),
                        page instanceof Number number ? number.intValue() : null));
            }
        }
        return List.copyOf(out);
    }

    /**
     * 从草稿条目的保留字段里取一个字符串列表。
     *
     * @param entry 草稿条目
     * @param field 字段名
     * @return 原列表；缺失或为空时返回空表
     */
    private static List<String> preservedStringList(AdminTextbookDraft.DraftEntry entry, String field) {
        Object value = entry.preserved() == null ? null : entry.preserved().get(field);
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof String text) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 判断条目的"已人工核对"是否可信。
     *
     * 要求：`confirmed` 为真**且**客户端给出的摘要与服务端按收到的内容重算的摘要一致。
     * 缺摘要一律视为未核对——**保守取值**，不把"没给证据"当成"已确认"。
     *
     * @param entry 草稿条目
     * @return 可信时为真
     */
    public static boolean isVerified(AdminTextbookDraft.DraftEntry entry) {
        if (!entry.confirmed()) {
            return false;
        }
        String provided = entry.confirmedDigest();
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                provided.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                contentDigest(entry).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 按收到的内容重算摘要。
     *
     * 取正文、拼音与释义三个**影响含义**的字段；类别与排序不影响含义，不参与摘要。
     *
     * @param entry 草稿条目
     * @return SHA-256 十六进制摘要
     */
    public static String contentDigest(AdminTextbookDraft.DraftEntry entry) {
        String payload = String.join("\u0000",
                entry.text() == null ? "" : entry.text(),
                entry.pinyin() == null ? "" : entry.pinyin(),
                entry.meaning() == null ? "" : entry.meaning());
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception failure) {
            throw new com.yuyutian.mytools.toolbox.service.curriculum.CatalogValidationException(
                    "content digest failed", failure);
        }
    }

    /** 优先沿用正式 ID，否则使用服务端生成值。 */
    private static String officialId(String existing, String generated) {
        return existing == null || existing.isBlank() ? generated : existing;
    }

    /** 解析引用：正式优先，临时兜底。 */
    private static String resolve(String official, String client, Map<String, String> mapping) {
        if (official != null && !official.isBlank()) {
            return official;
        }
        return client == null ? null : mapping.getOrDefault(client, client);
    }

    /**
     * 按字数与标点推断条目形态：单字为 character，含空白为 phrase，其余为 word。
     *
     * **单个 ASCII 字母不算"单字"**：这条规则此前只看文本、不看学科，
     * 于是英语册里的 `"I"` 与 `"a"` 被推成 {@link EntryForm#CHARACTER} ✗ ——
     * 那是**单词被标成单字**的用户可见错值
     * （2026-10-07 在真实 140 条英语册上复现，生产同册的对应条目是 `word`）。
     * 汉字单个码点仍然判为单字，不受此改动影响。
     *
     * @param text 条目正文
     * @return 条目形态
     */
    private static EntryForm formOf(String text) {
        if (text == null || text.isBlank()) {
            return EntryForm.WORD;
        }
        String trimmed = text.trim();
        if (trimmed.codePointCount(0, trimmed.length()) == 1) {
            int codePoint = trimmed.codePointAt(0);
            boolean asciiLetter = (codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= 'a' && codePoint <= 'z');
            return asciiLetter ? EntryForm.WORD : EntryForm.CHARACTER;
        }
        return trimmed.contains(" ") ? EntryForm.PHRASE : EntryForm.WORD;
    }

    /** 把字符串类别转换为枚举，未知值忽略而不猜测。 */
    private static List<ListType> listTypesOf(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<ListType> result = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (ListType type : ListType.values()) {
                if (type.value().equalsIgnoreCase(value.trim())) {
                    result.add(type);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 把拼音拆成与汉字一一对应的音节段。
     * 音节数与字素数不一致时返回空列表——宁可留空由人工补，也不硬拆错配。
     */
    private static List<PinyinSegment> segmentsOf(String text, String pinyin) {
        if (text == null || pinyin == null || pinyin.isBlank()) {
            return List.of();
        }
        List<String> graphemes = new ArrayList<>();
        text.codePoints().forEach(codePoint -> graphemes.add(new String(Character.toChars(codePoint))));
        String[] syllables = pinyin.trim().split("\\s+");
        if (syllables.length != graphemes.size()) {
            return List.of();
        }
        List<PinyinSegment> segments = new ArrayList<>();
        for (int index = 0; index < graphemes.size(); index += 1) {
            segments.add(new PinyinSegment(graphemes.get(index), syllables[index]));
        }
        return List.copyOf(segments);
    }

    /** 左侧补零。 */
    private static String pad(int value, int width) {
        return String.format("%0" + width + "d", value);
    }
}
