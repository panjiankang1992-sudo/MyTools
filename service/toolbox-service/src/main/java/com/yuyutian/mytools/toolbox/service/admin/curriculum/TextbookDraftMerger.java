package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按稳定身份合并旧基线、最新教材及管理员草稿；纯计算，不访问数据库。 */
public final class TextbookDraftMerger {
    private static final List<String> COLLECTIONS = List.of("units", "lessons", "entries");
    private static final Set<String> DERIVED = Set.of("confirmed", "confirmedDigest", "automaticInitialized",
            "entryVersion", "reviewStatus", "blockingIssues", "order");
    private final ObjectMapper json;

    /** 创建三方合并器。 */
    public TextbookDraftMerger(ObjectMapper json) {
        this.json = json;
    }

    /** 将不可变的旧版和新版与草稿合并；有任何冲突时不返回可写入的草稿。 */
    public MergeResult merge(String editionId, byte[] baseBook, byte[] currentBook, AdminTextbookDraft draft) {
        try {
            validateBook(editionId, baseBook);
            validateBook(editionId, currentBook);
            var converter = new PublishedBookDraftConverter(json);
            var base = json.readValue(converter.convert(editionId, baseBook), AdminTextbookDraft.class);
            var current = json.readValue(converter.convert(editionId, currentBook), AdminTextbookDraft.class);
            return merge(base, current, draft);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("publishedBookUnparsable", failure);
        }
    }

    /** 合并已经转换的三份草稿，供独立回归与只读生产快照验算使用。 */
    public MergeResult merge(AdminTextbookDraft base, AdminTextbookDraft current, AdminTextbookDraft local) {
        List<Conflict> conflicts = new ArrayList<>();
        ObjectNode b = canonical(base, conflicts);
        ObjectNode c = canonical(current, conflicts);
        ObjectNode d = canonical(local, conflicts);
        Map<String, String> aligned = alignLegacyIdentities(b, c, d, conflicts);
        ObjectNode result = json.createObjectNode();
        Set<String> fields = fields(b, c, d);
        fields.removeAll(COLLECTIONS);
        fields.remove("idMapping");
        // 教材元数据按字段合并，不把一处来源调整扩大成整册冲突。
        for (String field : fields) {
            result.set(field, mergeValue(b.get(field), c.get(field), d.get(field), field, "", conflicts));
        }
        for (String collection : COLLECTIONS) {
            result.set(collection, mergeCollection(collection, b.path(collection), c.path(collection),
                    d.path(collection), conflicts));
        }
        restoreLocalIdentityAndState(result, local, current, aligned);
        // 原有别名映射继续保留，并为导入的新正式身份建立自映射。
        ObjectNode mapping = json.createObjectNode();
        for (AdminTextbookDraft source : List.of(base, current, local)) {
            if (source.idMapping() != null) {
                source.idMapping().forEach((alias, id) -> mapping.put(alias,
                        alias.startsWith("e:") && source != current ? aligned.getOrDefault(id, id) : id));
            }
        }
        for (String collection : COLLECTIONS) {
            String prefix = prefix(collection);
            for (JsonNode row : result.path(collection)) {
                String id = row.path(idField(collection)).asText();
                if (!id.isBlank()) {
                    mapping.put(prefix + ":" + row.path(clientField(collection)).asText(id), id);
                }
            }
        }
        // 删除不存在节点的历史别名，避免旧编号在重排后指向另一条内容。
        var iterator = mapping.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String collection = entry.getKey().startsWith("u:") ? "units"
                    : entry.getKey().startsWith("l:") ? "lessons" : "entries";
            if (!index(collection, result.path(collection)).containsKey(entry.getValue().asText())) {
                iterator.remove();
            }
        }
        result.set("idMapping", mapping);
        validateReferences(result, conflicts);
        if (!conflicts.isEmpty()) {
            return new MergeResult(null, List.copyOf(conflicts));
        }
        try {
            return new MergeResult(json.treeToValue(result, AdminTextbookDraft.class), List.of());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("mergedDraftUnparsable", invalid);
        }
    }

    private void validateBook(String editionId, byte[] bytes) throws java.io.IOException {
        JsonNode book = json.readTree(bytes);
        if (!book.isObject() || !book.path("edition").isObject()
                || COLLECTIONS.stream().anyMatch(field -> !book.path(field).isArray())) {
            throw new IllegalArgumentException("publishedBookStructureInvalid");
        }
        String declared = book.path("edition").path("editionId").asText(editionId);
        if (!editionId.equals(declared)) {
            throw new IllegalArgumentException("publishedBookIdentityInvalid");
        }
    }

    private ObjectNode canonical(AdminTextbookDraft draft, List<Conflict> conflicts) {
        ObjectNode node = json.valueToTree(draft);
        Map<String, String> aliases = new LinkedHashMap<>();
        if (draft.idMapping() != null) {
            aliases.putAll(draft.idMapping());
        }
        // 先收集单元及课文身份，再归一各条目的所属关系。
        for (String collection : COLLECTIONS) {
            for (JsonNode row : node.path(collection)) {
                String official = row.path(idField(collection)).asText("");
                String client = row.path(clientField(collection)).asText("");
                if (!official.isBlank() && !client.isBlank()) {
                    aliases.put(prefix(collection) + ":" + client, official);
                }
            }
        }
        for (String collection : COLLECTIONS) {
            ArrayNode rows = json.createArrayNode();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode value : node.path(collection)) {
                ObjectNode row = value.deepCopy();
                String official = row.path(idField(collection)).asText("");
                String client = row.path(clientField(collection)).asText("");
                String id = !official.isBlank() ? official : aliases.getOrDefault(prefix(collection) + ":" + client, client);
                if (id.isBlank() || !seen.add(id)) {
                    conflicts.add(new Conflict(collection, id, "identity", "invalidIdentity"));
                }
                row.put(idField(collection), id);
                row.put(clientField(collection), id);
                if (!collection.equals("units")) {
                    canonicalParent(row, "unitId", "clientUnitId", "u", aliases);
                }
                if (collection.equals("entries")) {
                    canonicalParent(row, "lessonId", "clientLessonId", "l", aliases);
                }
                normalize(row);
                rows.add(row);
            }
            node.set(collection, rows);
        }
        // 缺失与空列表是旧版编辑器的等价序列化形式。
        for (String field : List.of("sourceIds", "sources", "regionCodes")) {
            if (!node.path(field).isArray()) {
                node.set(field, json.createArrayNode());
            }
        }
        return node;
    }

    private static void canonicalParent(ObjectNode row, String officialField, String clientField,
                                        String prefix, Map<String, String> aliases) {
        String official = row.path(officialField).asText("");
        String client = row.path(clientField).asText("");
        String id = !official.isBlank() ? aliases.getOrDefault(prefix + ":" + official, official)
                : aliases.getOrDefault(prefix + ":" + client, client);
        if (id.isBlank()) {
            row.putNull(officialField);
            row.putNull(clientField);
        } else {
            row.put(officialField, id);
            row.put(clientField, id);
        }
    }

    private void normalize(ObjectNode row) {
        // 不比较运行时派生的版本号、核对摘要及数组索引，避免重复发布造成伪冲突。
        DERIVED.forEach(row::remove);
        for (String field : List.of("text", "pinyin", "meaning")) {
            if (row.has(field) && row.path(field).asText("").isBlank()) {
                row.putNull(field);
            }
        }
        ObjectNode preserved = row.path("preserved").isObject()
                ? (ObjectNode) row.path("preserved") : json.createObjectNode();
        DERIVED.forEach(preserved::remove);
        // 分段、表类别及来源有具名字段；发布映射从具名字段生成，不比较重复副本。
        if (row.has("sourceRefs") && (!row.path("sourceRefs").isArray() || row.path("sourceRefs").isEmpty())
                && preserved.path("sourceRefs").isArray()) {
            row.set("sourceRefs", preserved.path("sourceRefs").deepCopy());
        }
        preserved.remove(List.of("listTypes", "sourceRefs"));
        if (row.has("preserved")) {
            row.set("preserved", preserved);
        }
        if (row.has("sourceRefs") && !row.path("sourceRefs").isArray()) {
            row.set("sourceRefs", json.createArrayNode());
        }
    }

    private ArrayNode mergeCollection(String collection, JsonNode base, JsonNode current, JsonNode local,
                                      List<Conflict> conflicts) {
        Map<String, JsonNode> b = index(collection, base);
        Map<String, JsonNode> c = index(collection, current);
        Map<String, JsonNode> d = index(collection, local);
        Set<String> ids = new LinkedHashSet<>(b.keySet());
        ids.addAll(c.keySet());
        ids.addAll(d.keySet());
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        for (String id : ids) {
            JsonNode value = mergeValue(b.get(id), c.get(id), d.get(id), collection + "/" + id, id, conflicts);
            if (!value.isNull()) {
                merged.put(id, value);
            }
        }
        // 只比较双方均存在的旧节点相对顺序；插入、删除及编号变化不会误报重排。
        Set<String> common = new LinkedHashSet<>(b.keySet());
        common.retainAll(c.keySet());
        common.retainAll(d.keySet());
        List<String> bo = b.keySet().stream().filter(common::contains).toList();
        List<String> co = c.keySet().stream().filter(common::contains).toList();
        List<String> lo = d.keySet().stream().filter(common::contains).toList();
        boolean localReordered = !bo.equals(lo);
        if (localReordered && !bo.equals(co) && !lo.equals(co)) {
            conflicts.add(new Conflict(collection, "", "order", "concurrentReorder"));
        }
        List<String> order = new ArrayList<>((localReordered ? d : c).keySet());
        order.removeIf(id -> !merged.containsKey(id));
        insertMissing(order, new ArrayList<>((localReordered ? c : d).keySet()), merged.keySet());
        ArrayNode result = json.createArrayNode();
        for (String id : order) {
            ObjectNode row = (ObjectNode) merged.get(id);
            if (!collection.equals("entries")) {
                row.put("order", result.size() + 1);
            } else {
                // 核对状态在恢复身份时按最终正文匹配来源，不能沿用另一份正文的摘要。
                row.put("confirmed", false);
                row.putNull("confirmedDigest");
                row.put("automaticInitialized", true);
            }
            result.add(row);
        }
        return result;
    }

    private Map<String, String> alignLegacyIdentities(ObjectNode base, ObjectNode current, ObjectNode local,
                                                      List<Conflict> conflicts) {
        Map<String, JsonNode> b = index("entries", base.path("entries"));
        Map<String, JsonNode> c = index("entries", current.path("entries"));
        Map<String, JsonNode> d = index("entries", local.path("entries"));
        // 只有出现交叉身份复用才走历史迁移修复；正常稳定 ID 下的改词仍按原身份合并。
        long reassigned = b.entrySet().stream().filter(entry -> c.containsKey(entry.getKey())
                && !sameText(entry.getValue(), c.get(entry.getKey()))
                && candidates(entry.getValue(), c).stream().anyMatch(id -> !id.equals(entry.getKey()))).count();
        if (reassigned == 0) {
            return Map.of();
        }
        Map<String, String> aligned = new LinkedHashMap<>();
        Set<String> consumed = new LinkedHashSet<>();
        for (var entry : b.entrySet()) {
            List<String> matches = candidates(entry.getValue(), c);
            if (matches.isEmpty() && d.containsKey(entry.getKey())) {
                matches = candidates(d.get(entry.getKey()), c).stream()
                        .filter(id -> samePair(d.get(entry.getKey()), c.get(id))).toList();
            }
            if (matches.size() > 1 || (matches.size() == 1 && !consumed.add(matches.getFirst()))) {
                conflicts.add(new Conflict("entries/" + entry.getKey(), entry.getKey(), "identity", "ambiguousIdentity"));
            } else if (matches.size() == 1) {
                aligned.put(entry.getKey(), matches.getFirst());
            } else {
                // 新版已经删除旧词时隔离旧 ID，不能把用户改动误施到复用该编号的另一词。
                aligned.put(entry.getKey(), "legacy-" + entry.getKey());
            }
        }
        for (var entry : d.entrySet()) {
            if (b.containsKey(entry.getKey())) {
                continue;
            }
            List<String> matches = candidates(entry.getValue(), c);
            if (matches.isEmpty()) {
                continue;
            }
            if (matches.size() != 1 || consumed.contains(matches.getFirst())) {
                conflicts.add(new Conflict("entries/" + entry.getKey(), entry.getKey(), "identity", "ambiguousIdentity"));
            } else if (!samePair(entry.getValue(), c.get(matches.getFirst()))) {
                conflicts.add(new Conflict("entries/" + entry.getKey(), entry.getKey(), "meaning", "differentChanges"));
            } else {
                String target = matches.getFirst();
                aligned.put(entry.getKey(), target);
                consumed.add(target);
                // 相同的新增项在最新教材已存在，以已发布节点作为这条新增项的比较基线。
                // 新增草稿没有填写的来源及隐藏属性沿用已发布值；已填写内容仍保持原样。
                ObjectNode published = (ObjectNode) c.get(target);
                ObjectNode input = (ObjectNode) entry.getValue();
                ObjectNode enriched = published.deepCopy();
                input.fields().forEachRemaining(field -> {
                    JsonNode value = field.getValue();
                    if (!value.isNull() && !(value.isContainerNode() && value.isEmpty())) {
                        if (value.isObject() && enriched.path(field.getKey()).isObject()) {
                            overlay((ObjectNode) enriched.path(field.getKey()), (ObjectNode) value);
                        } else {
                            enriched.set(field.getKey(), value.deepCopy());
                        }
                    }
                });
                input.removeAll();
                input.setAll(enriched);
                ObjectNode syntheticBase = published.deepCopy();
                syntheticBase.put("entryId", entry.getKey()).put("rowId", entry.getKey());
                ((ArrayNode) base.path("entries")).add(syntheticBase);
            }
        }
        // 对重编号的两份旧结构同时换算身份，当前教材正式 ID 始终作为输出身份。
        for (ObjectNode draft : List.of(base, local)) {
            for (JsonNode row : draft.path("entries")) {
                String id = row.path("entryId").asText();
                String target = aligned.getOrDefault(id, id);
                ((ObjectNode) row).put("entryId", target).put("rowId", target);
            }
        }
        return aligned;
    }

    private static void overlay(ObjectNode target, ObjectNode input) {
        input.fields().forEachRemaining(field -> {
            if (field.getValue().isObject() && target.path(field.getKey()).isObject()) {
                overlay((ObjectNode) target.path(field.getKey()), (ObjectNode) field.getValue());
            } else {
                target.set(field.getKey(), field.getValue().deepCopy());
            }
        });
    }

    private static List<String> candidates(JsonNode row, Map<String, JsonNode> available) {
        return available.entrySet().stream().filter(entry -> sameText(row, entry.getValue()))
                .map(Map.Entry::getKey).toList();
    }

    private static boolean sameText(JsonNode left, JsonNode right) {
        return Objects.equals(left.path("unitId"), right.path("unitId"))
                && Objects.equals(left.path("text"), right.path("text"))
                && Objects.equals(left.path("lessonId"), right.path("lessonId"));
    }

    private static boolean samePair(JsonNode left, JsonNode right) {
        return sameText(left, right) && Objects.equals(left.path("pinyin"), right.path("pinyin"))
                && comparableMeaning(left.path("meaning").asText("")).equals(
                        comparableMeaning(right.path("meaning").asText("")));
    }

    private static String comparableMeaning(String value) {
        String trimmed = value.trim();
        // 仅中文释义末尾一个句号等价；问号、叹号、英文及词内标点均不改。
        boolean hasChinese = trimmed.codePoints().anyMatch(code -> Character.UnicodeScript.of(code)
                == Character.UnicodeScript.HAN);
        return hasChinese && (trimmed.endsWith("。") || trimmed.endsWith("."))
                ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private void restoreLocalIdentityAndState(ObjectNode merged, AdminTextbookDraft local,
                                               AdminTextbookDraft current, Map<String, String> aligned) {
        Map<String, AdminTextbookDraft.DraftEntry> localRows = new LinkedHashMap<>();
        for (var entry : local.entries() == null ? List.<AdminTextbookDraft.DraftEntry>of() : local.entries()) {
            String id = entry.entryId();
            if (id == null || id.isBlank()) {
                id = local.idMapping() == null ? entry.rowId()
                        : local.idMapping().getOrDefault("e:" + entry.rowId(), entry.rowId());
            }
            localRows.put(aligned.getOrDefault(id, id), entry);
        }
        Map<String, AdminTextbookDraft.DraftEntry> currentRows = new LinkedHashMap<>();
        for (var entry : current.entries() == null ? List.<AdminTextbookDraft.DraftEntry>of() : current.entries()) {
            currentRows.put(entry.entryId(), entry);
        }
        Set<String> reservedRowIds = new LinkedHashSet<>();
        localRows.values().forEach(entry -> reservedRowIds.add(entry.rowId()));
        for (JsonNode value : merged.path("entries")) {
            ObjectNode row = (ObjectNode) value;
            String id = row.path("entryId").asText();
            var localRow = localRows.get(id);
            var currentRow = currentRows.get(id);
            var source = matchesContent(row, localRow) ? localRow : matchesContent(row, currentRow) ? currentRow : null;
            if (localRow != null && localRow.rowId() != null && !localRow.rowId().isBlank()) {
                row.put("rowId", localRow.rowId());
            } else if (reservedRowIds.contains(row.path("rowId").asText())) {
                row.put("rowId", "published-" + id);
            }
            if (localRow != null && (localRow.entryId() == null || localRow.entryId().isBlank())
                    && (local.idMapping() == null || !local.idMapping().containsKey("e:" + localRow.rowId()))
                    && !currentRows.containsKey(id)) {
                row.putNull("entryId");
            }
            if (id.startsWith("legacy-")) {
                row.put("entryId", id.substring("legacy-".length()));
            }
            if (source != null) {
                row.put("confirmed", source.confirmed());
                row.put("automaticInitialized", source.automaticInitialized());
                // 只在正文仍等于已确认来源时重算摘要，不给组合后的新含义伪造确认。
                if (source.confirmed()) {
                    row.put("confirmedDigest", DraftToBookMapper.contentDigest(source));
                }
                var publishedState = currentRow == null ? source : currentRow;
                if (publishedState.preserved() != null) {
                    ObjectNode preserved = (ObjectNode) row.path("preserved");
                    for (String field : List.of("entryVersion", "reviewStatus", "blockingIssues")) {
                        if (publishedState.preserved().containsKey(field)) {
                            preserved.set(field, json.valueToTree(publishedState.preserved().get(field)));
                        }
                    }
                }
            }
        }
        Set<String> newUnits = new LinkedHashSet<>();
        Set<String> newLessons = new LinkedHashSet<>();
        if (local.units() != null) {
            for (var unit : local.units()) {
                if ((unit.unitId() == null || unit.unitId().isBlank())
                        && (local.idMapping() == null || !local.idMapping().containsKey("u:" + unit.clientUnitId()))) {
                    newUnits.add(unit.clientUnitId());
                }
            }
        }
        if (local.lessons() != null) {
            for (var lesson : local.lessons()) {
                if ((lesson.lessonId() == null || lesson.lessonId().isBlank())
                        && (local.idMapping() == null || !local.idMapping().containsKey("l:" + lesson.clientLessonId()))) {
                    newLessons.add(lesson.clientLessonId());
                }
            }
        }
        for (String collection : COLLECTIONS) {
            for (JsonNode value : merged.path(collection)) {
                ObjectNode row = (ObjectNode) value;
                if (newUnits.contains(row.path("unitId").asText())) {
                    row.putNull("unitId");
                }
                if (newLessons.contains(row.path("lessonId").asText())) {
                    row.putNull("lessonId");
                }
            }
        }
    }

    private static boolean matchesContent(JsonNode row, AdminTextbookDraft.DraftEntry entry) {
        return entry != null && row.path("text").asText("").equals(entry.text() == null ? "" : entry.text())
                && row.path("pinyin").asText("").equals(entry.pinyin() == null ? "" : entry.pinyin())
                && row.path("meaning").asText("").equals(entry.meaning() == null ? "" : entry.meaning());
    }

    private static void insertMissing(List<String> order, List<String> source, Set<String> survivors) {
        for (int index = 0; index < source.size(); index++) {
            String id = source.get(index);
            if (!survivors.contains(id) || order.contains(id)) {
                continue;
            }
            int position = -1;
            for (int prior = index - 1; prior >= 0; prior--) {
                int anchor = order.indexOf(source.get(prior));
                if (anchor >= 0) {
                    position = anchor + 1;
                    break;
                }
            }
            if (position < 0) {
                position = 0;
            }
            order.add(position, id);
        }
    }

    private JsonNode mergeValue(JsonNode base, JsonNode current, JsonNode local, String path, String entityId,
                                List<Conflict> conflicts) {
        JsonNode b = nullNode(base), c = nullNode(current), d = nullNode(local);
        if (d.equals(b)) {
            return c.deepCopy();
        }
        if (c.equals(b) || c.equals(d)) {
            return d.deepCopy();
        }
        if (b.isObject() && c.isObject() && d.isObject()) {
            ObjectNode merged = json.createObjectNode();
            for (String field : fields(b, c, d)) {
                merged.set(field, mergeValue(b.get(field), c.get(field), d.get(field),
                        path + "/" + field, entityId, conflicts));
            }
            // 词与释义、汉字与拼音是一个配对，不能把双方分别改过的两栏拼成未被确认的新含义。
            if (b.has("text") && !sameContent(merged, c) && !sameContent(merged, d)) {
                conflicts.add(new Conflict(path, entityId, "text", "pairedContentChanged"));
            }
            return merged;
        }
        conflicts.add(new Conflict(path, entityId, path.substring(path.lastIndexOf('/') + 1),
                c.isNull() || d.isNull() ? "deleteModify" : "differentChanges"));
        return d.deepCopy();
    }

    private static boolean sameContent(JsonNode left, JsonNode right) {
        return List.of("text", "pinyin", "meaning").stream()
                .allMatch(field -> Objects.equals(nullNode(left.get(field)), nullNode(right.get(field))));
    }

    private static JsonNode nullNode(JsonNode value) {
        return value == null ? NullNode.instance : value;
    }

    private static Set<String> fields(JsonNode... nodes) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            node.fieldNames().forEachRemaining(result::add);
        }
        return result;
    }

    private static Map<String, JsonNode> index(String collection, JsonNode rows) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            result.put(row.path(idField(collection)).asText(row.path(clientField(collection)).asText()), row);
        }
        return result;
    }

    private static String prefix(String collection) {
        return collection.equals("units") ? "u" : collection.equals("lessons") ? "l" : "e";
    }

    private static String idField(String collection) {
        return collection.equals("units") ? "unitId" : collection.equals("lessons") ? "lessonId" : "entryId";
    }

    private static String clientField(String collection) {
        return collection.equals("units") ? "clientUnitId" : collection.equals("lessons") ? "clientLessonId" : "rowId";
    }

    private static void validateReferences(ObjectNode draft, List<Conflict> conflicts) {
        var units = index("units", draft.path("units"));
        var lessons = index("lessons", draft.path("lessons"));
        for (String collection : List.of("lessons", "entries")) {
            for (JsonNode row : draft.path(collection)) {
                String id = row.path(idField(collection)).asText();
                String unitId = row.path("unitId").asText(row.path("clientUnitId").asText());
                if (!units.containsKey(unitId)) {
                    conflicts.add(new Conflict(collection + "/" + id, id, "unitId", "missingParent"));
                }
                String lesson = row.path("lessonId").asText(row.path("clientLessonId").asText(""));
                if (!lesson.isBlank() && (!lessons.containsKey(lesson)
                        || !lessons.get(lesson).path("unitId").asText(
                                lessons.get(lesson).path("clientUnitId").asText()).equals(unitId))) {
                    conflicts.add(new Conflict(collection + "/" + id, id, "lessonId", "missingParent"));
                }
            }
        }
    }

    /** 一处无法无损判断的修改；只返回定位，不把用户正文写入错误日志。 */
    public record Conflict(String path, String entityId, String field, String reason) { }

    /** 完整合并结果；存在冲突时 draft 必须为空，调用方不得部分写入。 */
    public record MergeResult(AdminTextbookDraft draft, List<Conflict> conflicts) { }
}
